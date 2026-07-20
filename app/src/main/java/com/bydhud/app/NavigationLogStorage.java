package com.bydhud.app;

//rotates navigation logs so long trips and idle runtime do not exhaust device storage.

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

//defines the NavigationLogStorage module boundary so related behavior stays readable inside one unit.
final class NavigationLogStorage {
    static final String ROOT_DIR = "BYD-HUD";
    static final String LOGS_DIR = "logs";
    static final String LOGCAT_DIR = "logcat";
    static final String WAZE_CROP_DIR = "waze-crop";
    static final String WAZE_DIRECT_DIR = "waze-direct";

    private static final String TAG = "BydHudLogStorage";
    private static final String PUBLIC_ROOT = "/sdcard/Documents/" + ROOT_DIR;
    private static final String WRITE_PROBE = ".write_probe";
    private static final Object PUBLIC_DIR_CACHE_LOCK = new Object();
    private static final Map<String, CachedDir> PUBLIC_DIR_CACHE = new HashMap<>();
    private static final Object RETENTION_THROTTLE_LOCK = new Object();
    private static final Object RETENTION_WORKER_LOCK = new Object();
    private static final Object TOMBSTONE_CLEANUP_LOCK = new Object();
    private static final ReentrantReadWriteLock TOPOLOGY_GATE =
            new ReentrantReadWriteLock(true);
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final long RETENTION_MIN_INTERVAL_MS = 30000L;
    private static final long RETENTION_BATCH_FILE_LIMIT = 64L;
    private static final long RETENTION_BATCH_TIME_MS = 150L;
    private static final long RETENTION_BATCH_PAUSE_MS = 20L;
    private static final long PUBLIC_DIR_CACHE_TTL_MS = 30000L;
    private static final String SCREEN_PREFIX = "screen_";
    private static final String PNG_SUFFIX = ".png";
    private static final String MISSING_MANEUVERS_DIR = "missing-maneuvers";
    private static final String MISSING_LANES_DIR = "missing-lanes";
    private static boolean retentionWorkerRunning;
    private static RetentionRequest pendingRetentionRequest;
    private static boolean tombstoneCleanupRunning;

    //stores resolved public/fallback roots briefly so hot paths avoid repeated storage probing.
    private static final class CachedDir {
        final File dir;
        final boolean publicStorage;
        final boolean publicPermission;
        final long checkedMs;

        CachedDir(
                File dir,
                boolean publicStorage,
                boolean publicPermission,
                long checkedMs) {
            this.dir = dir;
            this.publicStorage = publicStorage;
            this.publicPermission = publicPermission;
            this.checkedMs = checkedMs;
        }
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavigationLogStorage() {
    }

    interface LockedSupplier<T> {
        T get();
    }

    static <T> T withReadLock(LockedSupplier<T> work) {
        TOPOLOGY_GATE.readLock().lock();
        try {
            return work.get();
        } finally {
            TOPOLOGY_GATE.readLock().unlock();
        }
    }

    static void withReadLock(Runnable work) {
        withReadLock(() -> {
            work.run();
            return null;
        });
    }

    static <T> T withWriteLock(LockedSupplier<T> work) {
        TOPOLOGY_GATE.writeLock().lock();
        try {
            return work.get();
        } finally {
            TOPOLOGY_GATE.writeLock().unlock();
        }
    }

    static void withWriteLock(Runnable work) {
        withWriteLock(() -> {
            work.run();
            return null;
        });
    }

    static void lockTopologyRead() {
        TOPOLOGY_GATE.readLock().lock();
    }

    static void unlockTopologyRead() {
        TOPOLOGY_GATE.readLock().unlock();
    }

    static void lockTopologyWrite() {
        TOPOLOGY_GATE.writeLock().lock();
    }

    static void unlockTopologyWrite() {
        TOPOLOGY_GATE.writeLock().unlock();
    }

    static boolean holdsTopologyRead() {
        return TOPOLOGY_GATE.getReadHoldCount() > 0;
    }

    static final class StorageRoot {
        final File dir;
        final boolean publicStorage;
        final String archivePrefix;

        StorageRoot(File dir, boolean publicStorage, String archivePrefix) {
            this.dir = dir;
            this.publicStorage = publicStorage;
            this.archivePrefix = archivePrefix;
        }
    }

    static final class StorageDay {
        final String name;
        final long lastModified;
        final int cropSessions;
        final long bytes;
        final boolean active;
        final boolean hasPublicStorage;
        final boolean hasPrivateStorage;

        StorageDay(
                String name,
                long lastModified,
                int cropSessions,
                long bytes,
                boolean active,
                boolean hasPublicStorage,
                boolean hasPrivateStorage) {
            this.name = name;
            this.lastModified = lastModified;
            this.cropSessions = cropSessions;
            this.bytes = bytes;
            this.active = active;
            this.hasPublicStorage = hasPublicStorage;
            this.hasPrivateStorage = hasPrivateStorage;
        }
    }

    static final class StorageSnapshot {
        final List<StorageRoot> roots;
        final List<StorageDay> days;
        final long totalBytes;
        final int totalSessions;

        StorageSnapshot(
                List<StorageRoot> roots,
                List<StorageDay> days,
                long totalBytes,
                int totalSessions) {
            this.roots = roots;
            this.days = days;
            this.totalBytes = totalBytes;
            this.totalSessions = totalSessions;
        }
    }

    static final class DayRetirement {
        final boolean ok;
        final String day;
        final boolean active;
        final List<File> tombstones;
        final String detail;

        DayRetirement(
                boolean ok,
                String day,
                boolean active,
                List<File> tombstones,
                String detail) {
            this.ok = ok;
            this.day = day == null ? "" : day;
            this.active = active;
            this.tombstones = Collections.unmodifiableList(new ArrayList<>(tombstones));
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final class MutableStorageDay {
        final String name;
        long lastModified;
        int cropSessions;
        long bytes;
        boolean hasPublicStorage;
        boolean hasPrivateStorage;

        MutableStorageDay(String name) {
            this.name = name;
        }
    }

    private static final class RenamedFragment {
        final File source;
        final File tombstone;

        RenamedFragment(File source, File tombstone) {
            this.source = source;
            this.tombstone = tombstone;
        }
    }

    //defines the SessionPath module boundary so related behavior stays readable inside one unit.
    static final class SessionPath {
        final File localDir;
        final String shellDir;
        final boolean shellWritable;

        SessionPath(File localDir, String shellDir, boolean shellWritable) {
            this.localDir = localDir;
            this.shellDir = shellDir == null ? "" : shellDir;
            this.shellWritable = shellWritable;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static File storageRootDir(Context context) {
        return withReadLock(() -> publicFirstDir(context, ""));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static File navCaptureDir(Context context) {
        return storageRootDir(context);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static File dayDir(Context context, String day) {
        return withReadLock(() -> {
            String safeDay = day != null && day.matches("\\d{8}") ? day : activeNavCaptureDay();
            File dir = new File(publicFirstDir(context, ""), safeDay);
            ensureDir(dir);
            return dir;
        });
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static File logsDir(Context context) {
        return withReadLock(() -> {
            File day = new File(publicFirstDir(context, ""), activeNavCaptureDay());
            File dir = new File(day, LOGS_DIR);
            ensureDir(dir);
            return dir;
        });
    }

    static File directCaptureDir(Context context) {
        return withReadLock(() -> {
            File day = new File(publicFirstDir(context, ""), activeNavCaptureDay());
            File dir = new File(day, WAZE_DIRECT_DIR);
            ensureDir(dir);
            return dir;
        });
    }

    //creates the logcat child only when LogcatRecorder explicitly starts recording.
    static File logcatDir(Context context, String startDay) {
        return withReadLock(() -> {
            String safeDay = startDay != null && startDay.matches("\\d{8}")
                    ? startDay
                    : activeNavCaptureDay();
            File dir = new File(publicFirstDir(context, ""),
                    safeDay + "/" + LOGS_DIR + "/" + LOGCAT_DIR);
            ensureDir(dir);
            return dir;
        });
    }

    //Returns roots visible to storage UI/share without creating either root or dated folders.
    static List<StorageRoot> accessibleRoots(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        return withWriteLock(() -> Collections.unmodifiableList(
                new ArrayList<>(accessibleRootsLocked(context.getApplicationContext()))));
    }

    //Enumerates one consistent logical-day view; same-name fragments are merged across roots.
    static StorageSnapshot snapshotAccessibleStorage(Context context) {
        if (context == null) {
            return new StorageSnapshot(
                    Collections.emptyList(), Collections.emptyList(), 0L, 0);
        }
        String activeDay = activeNavCaptureDay();
        String activeLogcatDay = LogcatRecorder.activeStartDay();
        return withWriteLock(() -> snapshotAccessibleStorageLocked(
                context.getApplicationContext(), activeDay, activeLogcatDay));
    }

    //Caller stops active Waze/logcat first; Waze restart must replace its cached sessionDir.
    //Atomically disconnects every accessible fragment before any recursive deletion begins.
    static DayRetirement retireStorageDay(Context context, String day) {
        String safeDay = day == null ? "" : day.trim();
        if (context == null || !safeDay.matches("\\d{8}")) {
            return new DayRetirement(false, safeDay, false,
                    Collections.emptyList(), "invalid day");
        }
        Context app = context.getApplicationContext();
        boolean active = isActiveNavCaptureDay(safeDay);
        withWriteLock(() -> { });
        if (!WazeCaptureDebugWriter.get().awaitIdle()) {
            return new DayRetirement(false, safeDay, active,
                    Collections.emptyList(), "capture writer interrupted");
        }
        return withWriteLock(() -> retireStorageDayLocked(app, safeDay, active));
    }

    //Deletes only hidden tombstones produced by a successful retirement.
    static boolean deleteRetiredStorageDayAsync(
            DayRetirement retirement,
            Runnable onComplete) {
        if (retirement == null || !retirement.ok || retirement.tombstones.isEmpty()) {
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                for (File tombstone : retirement.tombstones) {
                    if (isSafeTombstone(tombstone)) {
                        deleteRecursively(tombstone, tombstone.getParentFile(), null);
                    }
                }
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, "BydHudDayDelete");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return true;
    }

    //Resumes deletions that were atomically retired before a previous process was killed.
    static void cleanupRetiredStorageDaysAsync(Context context) {
        if (context == null) {
            return;
        }
        synchronized (TOMBSTONE_CLEANUP_LOCK) {
            if (tombstoneCleanupRunning) {
                return;
            }
            tombstoneCleanupRunning = true;
        }
        Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            try {
                withWriteLock(() -> {
                    for (File root : rootFiles(accessibleRootsLocked(app))) {
                        File[] children = root == null ? null : root.listFiles();
                        if (children == null) {
                            continue;
                        }
                        for (File child : children) {
                            if (isSafeTombstone(child)) {
                                deleteRecursively(child, root, null);
                            }
                        }
                    }
                });
            } finally {
                synchronized (TOMBSTONE_CLEANUP_LOCK) {
                    tombstoneCleanupRunning = false;
                }
            }
        }, "BydHudTombstoneCleanup");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String publicRootPath() {
        return PUBLIC_ROOT;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String publicLogsPath() {
        return PUBLIC_ROOT + "/<yyyymmdd>/" + LOGS_DIR;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String publicLogcatPath() {
        return publicLogsPath() + "/" + LOGCAT_DIR;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String publicNavCapturePath() {
        return publicLogsPath();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String publicWazeCropPath() {
        return PUBLIC_ROOT + "/<yyyymmdd>/" + WAZE_CROP_DIR
                + "/<session>/screen_*.png";
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String activeNavCaptureDay() {
        return NavCaptureStore.todayDir();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isActiveNavCaptureDay(String day) {
        return day != null && (activeNavCaptureDay().equals(day)
                || LogcatRecorder.activeStartDay().equals(day));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void enforceNavCaptureRetention(Context context) {
        if (context == null) {
            return;
        }
        if (!shouldRunRetentionNow()) {
            return;
        }
        scheduleNavCaptureRetention(new RetentionRequest(
                context.getApplicationContext(),
                activeNavCaptureDay(),
                LogcatRecorder.activeStartDay(),
                "",
                "",
                "",
                retentionLimitBytes(context),
                "general",
                null));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void enforceNavCaptureRetention(
            Context context,
            String activeSessionDir,
            String activeSessionName,
            String preserveScreenshotName) {
        if (context == null) {
            return;
        }
        if (!shouldRunRetentionNow()) {
            return;
        }
        scheduleNavCaptureRetention(new RetentionRequest(
                context.getApplicationContext(),
                activeNavCaptureDay(),
                LogcatRecorder.activeStartDay(),
                activeSessionDir,
                activeSessionName,
                preserveScreenshotName,
                retentionLimitBytes(context),
                "active-session",
                null));
    }

    //runs an explicit user-requested retention pass without blocking the UI caller.
    static void forceNavCaptureRetention(Context context, String reason, Runnable onComplete) {
        if (context == null) {
            return;
        }
        scheduleNavCaptureRetention(new RetentionRequest(
                context.getApplicationContext(),
                activeNavCaptureDay(),
                LogcatRecorder.activeStartDay(),
                "",
                "",
                "",
                retentionLimitBytes(context),
                safePathSegment(reason, "manual"),
                onComplete));
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static void enforceNavCaptureRetentionForTest(
            File root,
            String activeDay,
            String activeSessionDir,
            String activeSessionName,
            String preserveScreenshotName,
            long maxBytes) {
        runNavCaptureRetention(
                root,
                activeDay,
                "",
                activeSessionDir,
                activeSessionName,
                preserveScreenshotName,
                maxBytes,
                null);
    }

    //exposes active-logcat protection to host-side retention probes.
    static void enforceNavCaptureRetentionForTest(
            File root,
            String activeDay,
            String activeLogcatDay,
            String activeSessionDir,
            String activeSessionName,
            String preserveScreenshotName,
            long maxBytes) {
        runNavCaptureRetention(
                root,
                activeDay,
                activeLogcatDay,
                activeSessionDir,
                activeSessionName,
                preserveScreenshotName,
                maxBytes,
                null);
    }

    //marks active frame work so retention can avoid deleting during parser-critical sections.
    static void beginCaptureFrame() {
        lockTopologyRead();
    }

    //captures one coalesced retention request so worker threads do not touch UI or capture state.
    private static final class RetentionRequest {
        final Context context;
        final String activeDay;
        final String activeLogcatDay;
        final String activeSessionDir;
        final String activeSessionName;
        final String preserveScreenshotName;
        final long maxBytes;
        final String reason;
        final Runnable onComplete;

        RetentionRequest(
                Context context,
                String activeDay,
                String activeLogcatDay,
                String activeSessionDir,
                String activeSessionName,
                String preserveScreenshotName,
                long maxBytes,
                String reason,
                Runnable onComplete) {
            this.context = context;
            this.activeDay = activeDay;
            this.activeLogcatDay = activeLogcatDay;
            this.activeSessionDir = activeSessionDir;
            this.activeSessionName = activeSessionName;
            this.preserveScreenshotName = preserveScreenshotName;
            this.maxBytes = maxBytes;
            this.reason = safePathSegment(reason, "unknown");
            this.onComplete = onComplete;
        }
    }

    //records coarse retention progress without per-file verbose logging.
    private static final class RetentionStats {
        final Context context;
        final long beforeBytes;
        final long maxBytes;
        final long startMs = SystemClock.elapsedRealtime();
        long deletedFiles;
        long deletedBytes;
        private long batchFiles;
        private long batchStartMs = startMs;

        RetentionStats(Context context, long beforeBytes, long maxBytes) {
            this.context = context;
            this.beforeBytes = beforeBytes;
            this.maxBytes = maxBytes;
        }

        void recordDelete(long bytes) {
            deletedFiles++;
            deletedBytes += Math.max(0L, bytes);
            batchFiles++;
            long now = SystemClock.elapsedRealtime();
            if (batchFiles >= RETENTION_BATCH_FILE_LIMIT
                    || now - batchStartMs >= RETENTION_BATCH_TIME_MS) {
                logInfo("retention_progress files=" + deletedFiles
                        + " bytesDeleted=" + deletedBytes
                        + " beforeBytes=" + beforeBytes
                        + " maxBytes=" + maxBytes
                        + " elapsedMs=" + elapsedMs());
                batchFiles = 0L;
                batchStartMs = now;
                sleepQuietly(RETENTION_BATCH_PAUSE_MS);
            }
        }

        long elapsedMs() {
            return Math.max(0L, SystemClock.elapsedRealtime() - startMs);
        }
    }

    private static final class RetentionOutcome {
        final long beforeBytes;
        final long afterBytes;
        final RetentionStats stats;

        RetentionOutcome(long beforeBytes, long afterBytes, RetentionStats stats) {
            this.beforeBytes = beforeBytes;
            this.afterBytes = afterBytes;
            this.stats = stats;
        }
    }

    //marks frame completion so deferred retention can run during quieter periods.
    static void endCaptureFrame() {
        unlockTopologyRead();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static SessionPath navCaptureSessionDir(Context context, String sessionDir, String sessionName) {
        return withReadLock(() -> {
            String dayDir = activeNavCaptureDay();
            String safeSessionDir = safePathSegment(sessionDir, "session");
            String safeSessionName = safePathSegment(sessionName, "unknown");
            File dir = new File(publicFirstDir(context, ""),
                    dayDir + "/" + safeSessionDir + "/" + safeSessionName);
            ensureDir(dir);
            if (isUnderPublicRoot(dir)) {
                return new SessionPath(
                        dir,
                        publicRootPath() + "/" + dayDir + "/"
                                + safeSessionDir + "/" + safeSessionName,
                        true);
            }
            String shellPath = shellPathForFallback(context, dir);
            return new SessionPath(dir, shellPath, !shellPath.isEmpty());
        });
    }

    //schedules retention off the capture path so parser timing is not blocked by recursive deletion.
    private static void scheduleNavCaptureRetention(RetentionRequest request) {
        if (request == null) {
            return;
        }
        synchronized (RETENTION_WORKER_LOCK) {
            if (retentionWorkerRunning) {
                if (pendingRetentionRequest != null
                        && pendingRetentionRequest.onComplete != null
                        && request.onComplete == null) {
                    logRetention(request.context, "retention_deferred reason=force-request-pending");
                    return;
                }
                pendingRetentionRequest = request;
                logRetention(request.context, "retention_deferred reason=worker-running");
                return;
            }
            pendingRetentionRequest = request;
            retentionWorkerRunning = true;
        }
        Thread worker = new Thread(NavigationLogStorage::drainRetentionRequests,
                "BydHudNavRetention");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    //drains the latest pending retention request and coalesces bursts from capture/log writers.
    private static void drainRetentionRequests() {
        while (true) {
            RetentionRequest request;
            synchronized (RETENTION_WORKER_LOCK) {
                request = pendingRetentionRequest;
                pendingRetentionRequest = null;
                if (request == null) {
                    retentionWorkerRunning = false;
                    return;
                }
            }
            try {
                runScheduledRetention(request);
            } finally {
                runRetentionCompletion(request);
            }
        }
    }

    //returns UI cache refresh to the caller after worker retention finishes or defers.
    private static void runRetentionCompletion(RetentionRequest request) {
        if (request == null || request.onComplete == null) {
            return;
        }
        try {
            request.onComplete.run();
        } catch (RuntimeException e) {
            logWarn("retention completion failed: " + e.getClass().getSimpleName());
        }
    }

    //runs one retention pass only when storage is over limit and parser-critical work is quiet.
    private static void runScheduledRetention(RetentionRequest request) {
        if (request.context == null || request.maxBytes <= 0L) {
            return;
        }
        RetentionOutcome outcome = withWriteLock(() -> {
            List<File> roots = rootFiles(accessibleRootsLocked(request.context));
            long beforeBytes = datedFolderSizeBytes(roots);
            if (beforeBytes <= request.maxBytes) {
                return null;
            }
            RetentionStats stats = new RetentionStats(
                    request.context, beforeBytes, request.maxBytes);
            WazeCropCapture.RetentionState liveCrop =
                    WazeCropCapture.currentRetentionState();
            String activeDay = activeNavCaptureDay();
            String activeLogcatDay = LogcatRecorder.retentionActiveStartDay();
            String activeSessionDay = liveCrop.active
                    ? liveCrop.day
                    : request.activeDay;
            String activeSessionDir = liveCrop.active
                    ? WAZE_CROP_DIR
                    : request.activeSessionDir;
            String activeSessionName = liveCrop.active
                    ? liveCrop.sessionName
                    : request.activeSessionName;
            String preserveScreenshotName = liveCrop.active
                    ? liveCrop.preserveScreenshotName
                    : request.preserveScreenshotName;
            runNavCaptureRetention(
                    roots,
                    activeDay,
                    activeLogcatDay,
                    activeSessionDay,
                    activeSessionDir,
                    activeSessionName,
                    preserveScreenshotName,
                    request.maxBytes,
                    stats);
            return new RetentionOutcome(
                    beforeBytes, datedFolderSizeBytes(roots), stats);
        });
        if (outcome == null) {
            return;
        }
        logRetention(request.context, "retention_start reason=" + request.reason
                + " bytes=" + outcome.beforeBytes
                + " maxBytes=" + request.maxBytes);
        logRetention(request.context, "retention_end reason=" + request.reason
                + " files=" + outcome.stats.deletedFiles
                + " bytesDeleted=" + outcome.stats.deletedBytes
                + " beforeBytes=" + outcome.beforeBytes
                + " afterBytes=" + outcome.afterBytes
                + " elapsedMs=" + outcome.stats.elapsedMs());
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void runNavCaptureRetention(
            File root,
            String activeDay,
            String activeLogcatDay,
            String activeSessionDir,
            String activeSessionName,
            String preserveScreenshotName,
            long maxBytes,
            RetentionStats stats) {
        if (root == null) {
            return;
        }
        List<File> roots = new ArrayList<>();
        roots.add(root);
        runNavCaptureRetention(
                roots,
                activeDay,
                activeLogcatDay,
                activeDay,
                activeSessionDir,
                activeSessionName,
                preserveScreenshotName,
                maxBytes,
                stats);
    }

    private static void runNavCaptureRetention(
            List<File> roots,
            String activeDay,
            String activeLogcatDay,
            String activeSessionDay,
            String activeSessionDir,
            String activeSessionName,
            String preserveScreenshotName,
            long maxBytes,
            RetentionStats stats) {
        if (roots == null || roots.isEmpty() || maxBytes <= 0L) {
            return;
        }
        if (!isOverLimit(roots, maxBytes)) {
            return;
        }
        String safeActiveDay = safePathSegment(activeDay, "");
        String safeActiveLogcatDay = safePathSegment(activeLogcatDay, "");
        String safeActiveSessionDay = safePathSegment(activeSessionDay, "");
        String safeSessionDir = safePathSegment(activeSessionDir, "");
        String safeSessionName = safePathSegment(activeSessionName, "");
        deleteOldNavCaptureDays(
                roots,
                safeActiveDay,
                safeActiveLogcatDay,
                safeActiveSessionDay,
                maxBytes,
                stats);
        if (!isOverLimit(roots, maxBytes) || safeActiveSessionDay.isEmpty()
                || !WAZE_CROP_DIR.equals(safeSessionDir) || safeSessionName.isEmpty()) {
            return;
        }
        deleteOldNavCaptureSessions(
                roots, safeActiveSessionDay, safeSessionName, maxBytes, stats);
        if (!isOverLimit(roots, maxBytes)) {
            return;
        }
        deleteOldSessionScreenshots(
                roots,
                safeActiveSessionDay,
                safeSessionName,
                safePathSegment(preserveScreenshotName, ""),
                maxBytes,
                stats);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void deleteOldNavCaptureDays(
            List<File> roots,
            String activeDay,
            String activeLogcatDay,
            String activeSessionDay,
            long maxBytes,
            RetentionStats stats) {
        List<File> days = new ArrayList<>();
        for (File root : roots) {
            File[] children = root == null ? null : root.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (child != null
                        && child.isDirectory()
                        && isDayFolder(child)
                        && !child.getName().equals(activeDay)
                        && !child.getName().equals(activeLogcatDay)
                        && !child.getName().equals(activeSessionDay)
                        && isCanonicalDescendant(root, child, true)) {
                    days.add(child);
                }
            }
        }
        sortOldestFirst(days);
        for (File day : days) {
            if (!isOverLimit(roots, maxBytes)) {
                return;
            }
            if (deleteRecursively(day, day.getParentFile(), stats)) {
                logInfo("retention deleted day=" + day.getName());
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void deleteOldNavCaptureSessions(
            List<File> roots,
            String activeDay,
            String activeSessionName,
            long maxBytes,
            RetentionStats stats) {
        List<File> sessions = new ArrayList<>();
        for (File root : roots) {
            File sessionParent = new File(new File(root, activeDay), WAZE_CROP_DIR);
            File[] children = sessionParent.listFiles();
            if (children == null) {
                continue;
            }
            for (File session : children) {
                if (session != null
                        && session.isDirectory()
                        && !session.getName().equals(activeSessionName)
                        && isCanonicalDescendant(sessionParent, session, true)) {
                    sessions.add(session);
                }
            }
        }
        sortOldestFirst(sessions);
        for (File session : sessions) {
            if (!isOverLimit(roots, maxBytes)) {
                return;
            }
            if (deleteRecursively(session, session.getParentFile(), stats)) {
                logInfo("retention deleted session=" + session.getAbsolutePath());
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void deleteOldSessionScreenshots(
            List<File> roots,
            String activeDay,
            String activeSessionName,
            String preserveScreenshotName,
            long maxBytes,
            RetentionStats stats) {
        List<File> screenshots = new ArrayList<>();
        for (File root : roots) {
            File activeSession = new File(
                    new File(new File(root, activeDay), WAZE_CROP_DIR),
                    activeSessionName);
            File[] files = activeSession.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file != null
                        && file.isFile()
                        && isCaptureFramePng(file.getName())
                        && !file.getName().equals(preserveScreenshotName)
                        && isCanonicalDescendant(activeSession, file, true)) {
                    screenshots.add(file);
                }
            }
        }
        sortOldestFirst(screenshots);
        for (File screenshot : screenshots) {
            if (!isOverLimit(roots, maxBytes)) {
                return;
            }
            File activeSession = screenshot.getParentFile();
            String screenshotName = screenshot.getName();
            removeScreenshotArtifacts(activeSession, screenshotName, stats);
            long length = Math.max(0L, screenshot.length());
            if (screenshot.delete()) {
                if (stats != null) {
                    stats.recordDelete(length);
                }
                logInfo("retention deleted screenshot=" + screenshot.getAbsolutePath());
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void removeScreenshotArtifacts(
            File activeSession,
            String screenshotName,
            RetentionStats stats) {
        removeBucketScreenshotArtifacts(new File(activeSession, MISSING_MANEUVERS_DIR), screenshotName, stats);
        removeBucketScreenshotArtifacts(new File(activeSession, MISSING_LANES_DIR), screenshotName, stats);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void removeBucketScreenshotArtifacts(
            File bucketDir,
            String screenshotName,
            RetentionStats stats) {
        if (bucketDir == null || !bucketDir.isDirectory()) {
            return;
        }
        File copiedScreenshot = new File(bucketDir, screenshotName);
        if (copiedScreenshot.isFile()) {
            long length = Math.max(0L, copiedScreenshot.length());
            if (copiedScreenshot.delete()) {
                if (stats != null) {
                    stats.recordDelete(length);
                }
            } else {
                logWarn("retention delete artifact failed: " + copiedScreenshot.getAbsolutePath());
            }
        }
        String base = stripPngSuffix(screenshotName) + ".cell_";
        File[] files = bucketDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && file.isFile() && file.getName().startsWith(base)) {
                long length = Math.max(0L, file.length());
                if (file.delete()) {
                    if (stats != null) {
                        stats.recordDelete(length);
                    }
                } else {
                    logWarn("retention delete cell artifact failed: " + file.getAbsolutePath());
                }
            }
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isOverLimit(List<File> roots, long maxBytes) {
        return datedFolderSizeBytes(roots) > maxBytes;
    }

    private static long lastRetentionElapsedMs;

    //guard recursive folder scans so capture frames do not pay retention cost every time.
    private static boolean shouldRunRetentionNow() {
        long now = SystemClock.elapsedRealtime();
        synchronized (RETENTION_THROTTLE_LOCK) {
            if (lastRetentionElapsedMs > 0L
                    && now - lastRetentionElapsedMs < RETENTION_MIN_INTERVAL_MS) {
                return false;
            }
            lastRetentionElapsedMs = now;
            return true;
        }
    }

    private static long folderSizeBytes(File file, File allowedRoot) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (!isCanonicalDescendant(allowedRoot, file, false)) {
            return 0L;
        }
        if (file.isFile()) {
            return Math.max(0L, file.length());
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children == null) {
            return 0L;
        }
        for (File child : children) {
            total += folderSizeBytes(child, allowedRoot);
            if (total < 0L) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    //excludes untouched legacy siblings from both retention accounting and deletion.
    private static long datedFolderSizeBytes(File root) {
        if (root == null || !root.isDirectory()) {
            return 0L;
        }
        long total = 0L;
        File[] children = root.listFiles();
        if (children == null) {
            return 0L;
        }
        for (File child : children) {
            if (child != null
                    && child.isDirectory()
                    && (isDayFolder(child) || isSafeTombstone(child))
                    && isCanonicalDescendant(root, child, true)) {
                total += folderSizeBytes(child, root);
                if (total < 0L) {
                    return Long.MAX_VALUE;
                }
            }
        }
        return total;
    }

    private static long datedFolderSizeBytes(List<File> roots) {
        long total = 0L;
        for (File root : roots) {
            total += datedFolderSizeBytes(root);
            if (total < 0L) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static boolean deleteRecursively(
            File file,
            File allowedRoot,
            RetentionStats stats) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (!isCanonicalDescendant(allowedRoot, file, false)) {
            logWarn("refusing recursive delete outside root: " + file.getAbsolutePath());
            return false;
        }
        boolean wasFile = file.isFile();
        long length = wasFile ? Math.max(0L, file.length()) : 0L;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child, allowedRoot, stats);
                }
            }
        }
        boolean deleted = file.delete();
        if (deleted && wasFile && stats != null) {
            stats.recordDelete(length);
        }
        return deleted;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void sortOldestFirst(List<File> files) {
        files.sort(Comparator
                .comparingLong(File::lastModified)
                .thenComparing(File::getAbsolutePath));
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isDayFolder(File file) {
        return file != null && file.getName().matches("\\d{8}");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isCaptureFramePng(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        return lower.endsWith(PNG_SUFFIX)
                && (name.startsWith(SCREEN_PREFIX) || name.startsWith("source_frame_"));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String stripPngSuffix(String fileName) {
        if (fileName != null && fileName.toLowerCase(Locale.US).endsWith(PNG_SUFFIX)) {
            return fileName.substring(0, fileName.length() - PNG_SUFFIX.length());
        }
        return fileName == null ? "" : fileName;
    }

    private static List<StorageRoot> accessibleRootsLocked(Context context) {
        File publicRoot = publicRootDir();
        File privateRoot = privateRootDir(context);
        List<StorageRoot> roots = new ArrayList<>();
        if (!hasPublicWritePermission(context) || !probeExistingWritable(publicRoot)) {
            invalidatePublicRootCache();
            roots.add(new StorageRoot(privateRoot, false, "private"));
            return roots;
        }
        cachePublicFirstDir("", publicRoot, true, true, SystemClock.elapsedRealtime());
        roots.add(new StorageRoot(publicRoot, true, "public"));
        if (hasDatedSessions(privateRoot)) {
            roots.add(new StorageRoot(privateRoot, false, "private"));
        }
        return roots;
    }

    private static StorageSnapshot snapshotAccessibleStorageLocked(
            Context context,
            String activeDay,
            String activeLogcatDay) {
        List<StorageRoot> roots = accessibleRootsLocked(context);
        Map<String, MutableStorageDay> merged = new LinkedHashMap<>();
        long totalBytes = 0L;
        int totalSessions = 0;
        for (StorageRoot root : roots) {
            File[] children = root.dir == null ? null : root.dir.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (child == null
                        || !child.isDirectory()
                        || !isDayFolder(child)
                        || !isCanonicalDescendant(root.dir, child, true)) {
                    continue;
                }
                long bytes = folderSizeBytes(child, root.dir);
                int sessions = countCropSessions(child);
                MutableStorageDay day = merged.get(child.getName());
                if (day == null) {
                    day = new MutableStorageDay(child.getName());
                    merged.put(child.getName(), day);
                }
                day.lastModified = Math.max(day.lastModified, child.lastModified());
                day.cropSessions += sessions;
                day.bytes = saturatedAdd(day.bytes, bytes);
                day.hasPublicStorage |= root.publicStorage;
                day.hasPrivateStorage |= !root.publicStorage;
                totalBytes = saturatedAdd(totalBytes, bytes);
                totalSessions += sessions;
            }
        }
        List<StorageDay> days = new ArrayList<>();
        for (MutableStorageDay day : merged.values()) {
            days.add(new StorageDay(
                    day.name,
                    day.lastModified,
                    day.cropSessions,
                    day.bytes,
                    day.name.equals(activeDay) || day.name.equals(activeLogcatDay),
                    day.hasPublicStorage,
                    day.hasPrivateStorage));
        }
        days.sort((left, right) -> right.name.compareTo(left.name));
        return new StorageSnapshot(
                Collections.unmodifiableList(new ArrayList<>(roots)),
                Collections.unmodifiableList(days),
                totalBytes,
                totalSessions);
    }

    private static int countCropSessions(File day) {
        File parent = new File(day, WAZE_CROP_DIR);
        File[] sessions = parent.listFiles();
        if (sessions == null) {
            return 0;
        }
        int count = 0;
        for (File session : sessions) {
            if (session != null
                    && session.isDirectory()
                    && isCanonicalDescendant(parent, session, true)) {
                count++;
            }
        }
        return count;
    }

    private static DayRetirement retireStorageDayLocked(
            Context context,
            String day,
            boolean active) {
        List<File> sources = new ArrayList<>();
        for (StorageRoot root : accessibleRootsLocked(context)) {
            File source = new File(root.dir, day);
            if (!source.exists()) {
                continue;
            }
            if (!source.isDirectory() || !isCanonicalDescendant(root.dir, source, true)) {
                return new DayRetirement(false, day, active,
                        Collections.emptyList(), "unsafe day fragment");
            }
            sources.add(source);
        }
        if (sources.isEmpty()) {
            return new DayRetirement(false, day, active,
                    Collections.emptyList(), "day not found");
        }

        List<RenamedFragment> renamed = new ArrayList<>();
        long stamp = System.currentTimeMillis();
        for (int i = 0; i < sources.size(); i++) {
            File source = sources.get(i);
            File tombstone = uniqueTombstone(source.getParentFile(), day, stamp, i);
            if (!source.renameTo(tombstone)) {
                boolean rolledBack = rollbackRenamedFragments(renamed);
                return new DayRetirement(false, day, active,
                        Collections.emptyList(), rolledBack ? "rename failed" : "rollback failed");
            }
            renamed.add(new RenamedFragment(source, tombstone));
        }

        File writeRoot = publicFirstDir(context, "");
        String liveDayName = activeNavCaptureDay();
        File liveDay = new File(writeRoot, liveDayName);
        File liveLogs = new File(liveDay, LOGS_DIR);
        boolean dayExisted = liveDay.exists();
        boolean logsExisted = liveLogs.exists();
        if (!ensureDir(liveLogs)) {
            if (!logsExisted) {
                liveLogs.delete();
            }
            if (!dayExisted) {
                liveDay.delete();
            }
            boolean rolledBack = rollbackRenamedFragments(renamed);
            return new DayRetirement(false, day, active,
                    Collections.emptyList(), rolledBack
                            ? "live logs recreate failed"
                            : "live logs recreate and rollback failed");
        }

        List<File> tombstones = new ArrayList<>();
        for (RenamedFragment fragment : renamed) {
            tombstones.add(fragment.tombstone);
        }
        return new DayRetirement(true, day, active, tombstones,
                "retired fragments=" + tombstones.size());
    }

    private static File uniqueTombstone(File root, String day, long stamp, int index) {
        String base = ".delete-" + day + "-" + stamp + "-" + index;
        File tombstone = new File(root, base);
        int suffix = 1;
        while (tombstone.exists()) {
            tombstone = new File(root, base + "-" + suffix++);
        }
        return tombstone;
    }

    private static boolean rollbackRenamedFragments(List<RenamedFragment> renamed) {
        boolean ok = true;
        for (int i = renamed.size() - 1; i >= 0; i--) {
            RenamedFragment fragment = renamed.get(i);
            if (fragment.tombstone.exists()
                    && (fragment.source.exists()
                    || !fragment.tombstone.renameTo(fragment.source))) {
                ok = false;
            }
        }
        return ok;
    }

    private static boolean isSafeTombstone(File file) {
        return file != null
                && file.isDirectory()
                && file.getName().matches("\\.delete-\\d{8}-\\d+-\\d+(?:-\\d+)?")
                && file.getParentFile() != null
                && isCanonicalDescendant(file.getParentFile(), file, true);
    }

    private static List<File> rootFiles(List<StorageRoot> roots) {
        List<File> files = new ArrayList<>();
        for (StorageRoot root : roots) {
            if (root != null && root.dir != null) {
                files.add(root.dir);
            }
        }
        return files;
    }

    private static boolean hasDatedSessions(File root) {
        File[] children = root == null ? null : root.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child != null
                    && child.isDirectory()
                    && isDayFolder(child)
                    && isCanonicalDescendant(root, child, true)) {
                return true;
            }
        }
        return false;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static boolean isCanonicalDescendant(File root, File candidate, boolean direct) {
        if (root == null || candidate == null) {
            return false;
        }
        try {
            File absoluteRoot = root.getAbsoluteFile();
            File absoluteCandidate = candidate.getAbsoluteFile();
            String rootPath = absoluteRoot.getPath();
            String candidatePath = absoluteCandidate.getPath();
            String prefix = rootPath.endsWith(File.separator)
                    ? rootPath
                    : rootPath + File.separator;
            String relative;
            if (candidatePath.equals(rootPath)) {
                relative = "";
            } else if (candidatePath.startsWith(prefix)) {
                relative = candidatePath.substring(prefix.length());
            } else {
                return false;
            }
            if (direct && (relative.isEmpty() || relative.contains(File.separator))) {
                return false;
            }
            File canonicalRoot = absoluteRoot.getCanonicalFile();
            File expected = relative.isEmpty()
                    ? canonicalRoot
                    : new File(canonicalRoot, relative).getAbsoluteFile();
            return absoluteCandidate.getCanonicalFile().equals(expected);
        } catch (IOException e) {
            return false;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static long retentionLimitBytes(Context context) {
        return HudPrefs.storageLimitGb(context) * BYTES_PER_GB;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void logInfo(String message) {
        try {
            Log.i(TAG, message);
        } catch (RuntimeException | NoClassDefFoundError ignored) {
            //keeps host-side probes quiet because java-only tests do not provide android logging.
        }
    }

    //writes retention telemetry to the same field log stream used by runtime/capture diagnostics.
    private static void logRetention(Context context, String message) {
        logInfo(message);
        if (context == null) {
            return;
        }
        try {
            AppEventLogger.event(context, "nav_capture " + message);
        } catch (RuntimeException ignored) {
            //retention must stay best-effort because logging can itself depend on external storage.
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void logWarn(String message) {
        try {
            Log.w(TAG, message);
        } catch (RuntimeException | NoClassDefFoundError ignored) {
            //keeps host-side probes quiet because java-only tests do not provide android logging.
        }
    }

    //pauses retention batches briefly so UI/capture threads can regain CPU while deleting many files.
    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static File publicFirstDir(Context context, String childDir) {
        long now = SystemClock.elapsedRealtime();
        boolean publicPermission = hasPublicWritePermission(context);
        synchronized (PUBLIC_DIR_CACHE_LOCK) {
            CachedDir cached = PUBLIC_DIR_CACHE.get(childDir);
            if (cached != null
                    && cached.dir != null
                    && cached.dir.isDirectory()
                    && cached.publicPermission == publicPermission
                    && (!cached.publicStorage || cached.dir.canWrite())
                    && now - cached.checkedMs < PUBLIC_DIR_CACHE_TTL_MS) {
                return cached.dir;
            }
        }
        File publicRoot = publicRootDir();
        File publicDir = childDir.isEmpty() ? publicRoot : new File(publicRoot, childDir);
        if (publicPermission && ensureWritable(publicDir)) {
            cachePublicFirstDir(childDir, publicDir, true, publicPermission, now);
            return publicDir;
        }

        File fallbackRoot = privateRootDir(context);
        File fallback = childDir.isEmpty() ? fallbackRoot : new File(fallbackRoot, childDir);
        ensureDir(fallback);
        Log.w(TAG, "public storage unavailable; fallback=" + fallback.getAbsolutePath());
        cachePublicFirstDir(childDir, fallback, false, publicPermission, now);
        return fallback;
    }

    //logs only cache misses or root changes so storage evidence does not spam frame logs.
    private static void cachePublicFirstDir(
            String childDir,
            File dir,
            boolean publicStorage,
            boolean publicPermission,
            long checkedMs) {
        synchronized (PUBLIC_DIR_CACHE_LOCK) {
            CachedDir previous = PUBLIC_DIR_CACHE.put(
                    childDir,
                    new CachedDir(dir, publicStorage, publicPermission, checkedMs));
            String previousPath = previous == null || previous.dir == null
                    ? ""
                    : previous.dir.getAbsolutePath();
            String nextPath = dir == null ? "" : dir.getAbsolutePath();
            if (previous == null || !previousPath.equals(nextPath)) {
                logInfo("storage_root_cache hit=false child=" + safePathSegment(childDir, "unknown")
                        + " public=" + publicStorage
                        + " root=" + nextPath);
            }
        }
    }

    private static File publicRootDir() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), ROOT_DIR);
    }

    private static File privateRootDir(Context context) {
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles != null) {
            return new File(externalFiles, ROOT_DIR);
        }
        return new File(context.getFilesDir(), ROOT_DIR);
    }

    private static boolean hasPublicWritePermission(Context context) {
        try {
            if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                    || !Environment.isExternalStorageLegacy()) {
                return false;
            }
            AppOpsManager manager = context.getSystemService(AppOpsManager.class);
            if (manager == null) {
                return false;
            }
            int mode = manager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_WRITE_EXTERNAL_STORAGE,
                    context.getApplicationInfo().uid,
                    context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void invalidatePublicRootCache() {
        synchronized (PUBLIC_DIR_CACHE_LOCK) {
            PUBLIC_DIR_CACHE.clear();
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isUnderPublicRoot(File dir) {
        return isCanonicalDescendant(publicRootDir(), dir, false);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String shellPathForFallback(Context context, File dir) {
        if (dir == null) {
            return "";
        }
        String path = dir.getAbsolutePath().replace('\\', '/');
        String externalFiles = "";
        File externalRoot = context.getExternalFilesDir(null);
        if (externalRoot != null) {
            externalFiles = externalRoot.getAbsolutePath().replace('\\', '/');
        }
        if (!externalFiles.isEmpty() && path.startsWith(externalFiles)) {
            String relative = path.substring(externalFiles.length());
            return "/sdcard/Android/data/" + context.getPackageName() + "/files" + relative;
        }
        return "";
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safePathSegment(String value, String fallback) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) {
            safe = fallback;
        }
        return safe.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private static boolean ensureWritable(File dir) {
        if (!ensureDir(dir)) {
            return false;
        }
        return probeWritable(dir);
    }

    private static boolean probeExistingWritable(File dir) {
        return dir != null && dir.isDirectory() && probeWritable(dir);
    }

    private static boolean probeWritable(File dir) {
        synchronized (PUBLIC_DIR_CACHE_LOCK) {
            File probe = new File(dir, WRITE_PROBE);
            try (FileWriter writer = new FileWriter(probe, false)) {
                writer.write("ok");
            } catch (IOException e) {
                Log.w(TAG, "storage write probe failed: " + dir.getAbsolutePath(), e);
                return false;
            }
            if (!readProbe(probe)) {
                Log.w(TAG, "storage read probe failed: " + dir.getAbsolutePath());
                cleanupProbe(probe);
                return false;
            }
            return cleanupProbe(probe);
        }
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static boolean cleanupProbe(File probe) {
        if (probe == null || !probe.exists()) {
            return true;
        }
        if (!probe.delete() && probe.exists()) {
            Log.w(TAG, "public storage probe cleanup failed: " + probe.getAbsolutePath());
            return false;
        }
        return true;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static boolean readProbe(File probe) {
        if (probe == null || !probe.exists()) {
            return false;
        }
        byte[] buffer = new byte[2];
        try (FileInputStream in = new FileInputStream(probe)) {
            int read = in.read(buffer);
            return read == 2 && buffer[0] == 'o' && buffer[1] == 'k';
        } catch (IOException e) {
            Log.w(TAG, "public storage probe read failed: " + probe.getAbsolutePath(), e);
            return false;
        }
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private static boolean ensureDir(File dir) {
        return dir.exists() || dir.mkdirs();
    }
}
