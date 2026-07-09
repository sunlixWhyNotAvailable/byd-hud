package com.bydhud.app;

//rotates navigation logs so long trips and idle runtime do not exhaust device storage.

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

//defines the NavigationLogStorage module boundary so related behavior stays readable inside one unit.
final class NavigationLogStorage {
    static final String ROOT_DIR = "BYD-HUD";
    static final String LOGCAT_DIR = "logcat";
    static final String NAV_CAPTURE_DIR = "nav-capture";

    private static final String TAG = "BydHudLogStorage";
    private static final String PUBLIC_ROOT = "/sdcard/Documents/" + ROOT_DIR;
    private static final String WRITE_PROBE = ".write_probe";
    private static final Object PUBLIC_DIR_CACHE_LOCK = new Object();
    private static final Object WRITABLE_DIR_LOCK = new Object();
    private static final Map<String, CachedDir> PUBLIC_DIR_CACHE = new HashMap<>();
    private static final Set<String> WRITABLE_DIR_CACHE = new HashSet<>();
    private static final Object RETENTION_THROTTLE_LOCK = new Object();
    private static final Object RETENTION_WORKER_LOCK = new Object();
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final long RETENTION_MIN_INTERVAL_MS = 30000L;
    private static final long RETENTION_CRITICAL_OVERAGE_BYTES = 256L * 1024L * 1024L;
    private static final long RETENTION_BATCH_FILE_LIMIT = 64L;
    private static final long RETENTION_BATCH_TIME_MS = 150L;
    private static final long RETENTION_BATCH_PAUSE_MS = 20L;
    private static final long PUBLIC_DIR_CACHE_TTL_MS = 30000L;
    private static final String SCREEN_PREFIX = "screen_";
    private static final String PNG_SUFFIX = ".png";
    private static final String MISSING_MANEUVERS_DIR = "missing-maneuvers";
    private static final String MISSING_LANES_DIR = "missing-lanes";
    private static int activeCaptureFrames;
    private static boolean retentionWorkerRunning;
    private static RetentionRequest pendingRetentionRequest;

    //stores resolved public/fallback roots briefly so hot paths avoid repeated storage probing.
    private static final class CachedDir {
        final File dir;
        final boolean publicStorage;
        final long checkedMs;

        CachedDir(File dir, boolean publicStorage, long checkedMs) {
            this.dir = dir;
            this.publicStorage = publicStorage;
            this.checkedMs = checkedMs;
        }
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavigationLogStorage() {
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
    static File logcatDir(Context context) {
        return publicFirstDir(context, LOGCAT_DIR);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static File navCaptureDir(Context context) {
        return publicFirstDir(context, NAV_CAPTURE_DIR);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String publicLogcatPath() {
        return PUBLIC_ROOT + "/" + LOGCAT_DIR;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String publicNavCapturePath() {
        return PUBLIC_ROOT + "/" + NAV_CAPTURE_DIR;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String publicWazeCropPath() {
        return publicNavCapturePath() + "/<yyyymmdd>/waze-crop/<session>/screen_*.png";
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String activeNavCaptureDay() {
        return NavCaptureStore.todayDir();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isActiveNavCaptureDay(String day) {
        return day != null && activeNavCaptureDay().equals(day);
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
                navCaptureDir(context),
                activeNavCaptureDay(),
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
                navCaptureDir(context),
                activeNavCaptureDay(),
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
                navCaptureDir(context),
                activeNavCaptureDay(),
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
                activeSessionDir,
                activeSessionName,
                preserveScreenshotName,
                maxBytes,
                null);
    }

    //marks active frame work so retention can avoid deleting during parser-critical sections.
    static void beginCaptureFrame() {
        synchronized (RETENTION_WORKER_LOCK) {
            activeCaptureFrames++;
        }
    }

    //captures one coalesced retention request so worker threads do not touch UI or capture state.
    private static final class RetentionRequest {
        final Context context;
        final File root;
        final String activeDay;
        final String activeSessionDir;
        final String activeSessionName;
        final String preserveScreenshotName;
        final long maxBytes;
        final String reason;
        final Runnable onComplete;

        RetentionRequest(
                Context context,
                File root,
                String activeDay,
                String activeSessionDir,
                String activeSessionName,
                String preserveScreenshotName,
                long maxBytes,
                String reason,
                Runnable onComplete) {
            this.context = context;
            this.root = root;
            this.activeDay = activeDay;
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
                logRetention(context, "retention_progress files=" + deletedFiles
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

    //marks frame completion so deferred retention can run during quieter periods.
    static void endCaptureFrame() {
        synchronized (RETENTION_WORKER_LOCK) {
            if (activeCaptureFrames > 0) {
                activeCaptureFrames--;
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static SessionPath navCaptureSessionDir(Context context, String sessionDir, String sessionName) {
        String dayDir = activeNavCaptureDay();
        String safeSessionDir = safePathSegment(sessionDir, "session");
        String safeSessionName = safePathSegment(sessionName, "unknown");
        File publicDir = new File(navCaptureDir(context), dayDir + "/" + safeSessionDir + "/" + safeSessionName);
        if (isUnderPublicRoot(publicDir)) {
            ensureDir(publicDir);
            return new SessionPath(
                    publicDir,
                    publicNavCapturePath() + "/" + dayDir + "/" + safeSessionDir + "/" + safeSessionName,
                    true);
        }

        File fallback = new File(publicDir, "");
        ensureDir(fallback);
        String shellPath = shellPathForFallback(context, fallback);
        return new SessionPath(fallback, shellPath, !shellPath.isEmpty());
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
        if (request.root == null
                || request.maxBytes <= 0L
                || !request.root.exists()
                || !request.root.isDirectory()) {
            return;
        }
        long beforeBytes = folderSizeBytes(request.root);
        if (beforeBytes <= request.maxBytes) {
            return;
        }
        int activeFrames;
        synchronized (RETENTION_WORKER_LOCK) {
            activeFrames = activeCaptureFrames;
        }
        boolean criticalOverLimit = beforeBytes - request.maxBytes >= RETENTION_CRITICAL_OVERAGE_BYTES;
        if (activeFrames > 0 && !criticalOverLimit) {
            logRetention(request.context, "retention_deferred reason=active_capture"
                    + " activeFrames=" + activeFrames
                    + " bytes=" + beforeBytes
                    + " maxBytes=" + request.maxBytes);
            return;
        }
        RetentionStats stats = new RetentionStats(request.context, beforeBytes, request.maxBytes);
        logRetention(request.context, "retention_start reason=" + request.reason
                + " bytes=" + beforeBytes
                + " maxBytes=" + request.maxBytes
                + " activeFrames=" + activeFrames);
        runNavCaptureRetention(
                request.root,
                request.activeDay,
                request.activeSessionDir,
                request.activeSessionName,
                request.preserveScreenshotName,
                request.maxBytes,
                stats);
        long afterBytes = folderSizeBytes(request.root);
        logRetention(request.context, "retention_end reason=" + request.reason
                + " files=" + stats.deletedFiles
                + " bytesDeleted=" + stats.deletedBytes
                + " beforeBytes=" + beforeBytes
                + " afterBytes=" + afterBytes
                + " elapsedMs=" + stats.elapsedMs());
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void runNavCaptureRetention(
            File root,
            String activeDay,
            String activeSessionDir,
            String activeSessionName,
            String preserveScreenshotName,
            long maxBytes,
            RetentionStats stats) {
        if (root == null || maxBytes <= 0L || !root.exists() || !root.isDirectory()) {
            return;
        }
        if (!isOverLimit(root, maxBytes)) {
            return;
        }
        String safeActiveDay = safePathSegment(activeDay, "");
        String safeSessionDir = safePathSegment(activeSessionDir, "");
        String safeSessionName = safePathSegment(activeSessionName, "");
        deleteOldNavCaptureDays(root, safeActiveDay, maxBytes, stats);
        if (!isOverLimit(root, maxBytes) || safeActiveDay.isEmpty()
                || safeSessionDir.isEmpty() || safeSessionName.isEmpty()) {
            return;
        }
        deleteOldNavCaptureSessions(root, safeActiveDay, safeSessionDir, safeSessionName, maxBytes, stats);
        if (!isOverLimit(root, maxBytes)) {
            return;
        }
        File activeSession = new File(new File(new File(root, safeActiveDay), safeSessionDir), safeSessionName);
        deleteOldSessionScreenshots(activeSession, safePathSegment(preserveScreenshotName, ""), root, maxBytes, stats);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void deleteOldNavCaptureDays(
            File root,
            String activeDay,
            long maxBytes,
            RetentionStats stats) {
        List<File> days = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child != null
                    && child.isDirectory()
                    && isDayFolder(child)
                    && !child.getName().equals(activeDay)) {
                days.add(child);
            }
        }
        sortOldestFirst(days);
        for (File day : days) {
            if (!isOverLimit(root, maxBytes)) {
                return;
            }
            if (deleteRecursively(day, stats)) {
                logInfo("retention deleted day=" + day.getName());
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void deleteOldNavCaptureSessions(
            File root,
            String activeDay,
            String activeSessionDir,
            String activeSessionName,
            long maxBytes,
            RetentionStats stats) {
        File dayDir = new File(root, activeDay);
        File[] sessionParents = dayDir.listFiles();
        if (sessionParents == null) {
            return;
        }
        List<File> sessions = new ArrayList<>();
        for (File sessionParent : sessionParents) {
            if (sessionParent == null || !sessionParent.isDirectory()) {
                continue;
            }
            File[] children = sessionParent.listFiles();
            if (children == null) {
                continue;
            }
            for (File session : children) {
                if (session == null || !session.isDirectory()) {
                    continue;
                }
                boolean activeSession = sessionParent.getName().equals(activeSessionDir)
                        && session.getName().equals(activeSessionName);
                if (!activeSession) {
                    sessions.add(session);
                }
            }
        }
        sortOldestFirst(sessions);
        for (File session : sessions) {
            if (!isOverLimit(root, maxBytes)) {
                return;
            }
            if (deleteRecursively(session, stats)) {
                logInfo("retention deleted session=" + session.getAbsolutePath());
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void deleteOldSessionScreenshots(
            File activeSession,
            String preserveScreenshotName,
            File root,
            long maxBytes,
            RetentionStats stats) {
        if (activeSession == null || !activeSession.isDirectory()) {
            return;
        }
        File[] files = activeSession.listFiles();
        if (files == null) {
            return;
        }
        List<File> screenshots = new ArrayList<>();
        for (File file : files) {
            if (file != null
                    && file.isFile()
                    && isCaptureFramePng(file.getName())
                    && !file.getName().equals(preserveScreenshotName)) {
                screenshots.add(file);
            }
        }
        sortOldestFirst(screenshots);
        for (File screenshot : screenshots) {
            if (!isOverLimit(root, maxBytes)) {
                return;
            }
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
    private static boolean isOverLimit(File root, long maxBytes) {
        return folderSizeBytes(root) > maxBytes;
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static long folderSizeBytes(File file) {
        if (file == null || !file.exists()) {
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
            total += folderSizeBytes(child);
            if (total < 0L) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static boolean deleteRecursively(File file, RetentionStats stats) {
        if (file == null || !file.exists()) {
            return false;
        }
        boolean wasFile = file.isFile();
        long length = wasFile ? Math.max(0L, file.length()) : 0L;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child, stats);
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
        synchronized (PUBLIC_DIR_CACHE_LOCK) {
            CachedDir cached = PUBLIC_DIR_CACHE.get(childDir);
            if (cached != null
                    && cached.dir != null
                    && now - cached.checkedMs < PUBLIC_DIR_CACHE_TTL_MS) {
                return cached.dir;
            }
        }
        File publicDir = new File(
                new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOCUMENTS), ROOT_DIR),
                childDir);
        if (ensureWritable(publicDir)) {
            cachePublicFirstDir(childDir, publicDir, true, now);
            return publicDir;
        }

        File fallback = context.getExternalFilesDir(childDir);
        if (fallback == null) {
            fallback = new File(context.getFilesDir(), childDir);
        }
        ensureDir(fallback);
        Log.w(TAG, "public storage unavailable; fallback=" + fallback.getAbsolutePath());
        cachePublicFirstDir(childDir, fallback, false, now);
        return fallback;
    }

    //logs only cache misses or root changes so storage evidence does not spam frame logs.
    private static void cachePublicFirstDir(
            String childDir,
            File dir,
            boolean publicStorage,
            long checkedMs) {
        synchronized (PUBLIC_DIR_CACHE_LOCK) {
            CachedDir previous = PUBLIC_DIR_CACHE.put(
                    childDir,
                    new CachedDir(dir, publicStorage, checkedMs));
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

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isUnderPublicRoot(File dir) {
        if (dir == null) {
            return false;
        }
        String path = dir.getAbsolutePath().replace('\\', '/');
        return path.startsWith("/storage/emulated/0/Documents/" + ROOT_DIR + "/")
                || path.startsWith("/sdcard/Documents/" + ROOT_DIR + "/");
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
        String cacheKey = writableDirCacheKey(dir);
        synchronized (WRITABLE_DIR_LOCK) {
            if (WRITABLE_DIR_CACHE.contains(cacheKey)) {
                return true;
            }
        }
        File probe = new File(dir, WRITE_PROBE);
        try (FileWriter writer = new FileWriter(probe, false)) {
            writer.write("ok");
        } catch (IOException e) {
            Log.w(TAG, "public storage write probe failed: " + dir.getAbsolutePath(), e);
            return false;
        }
        if (!readProbe(probe)) {
            Log.w(TAG, "public storage read probe failed: " + dir.getAbsolutePath());
            cleanupProbe(probe);
            return false;
        }
        if (!cleanupProbe(probe)) {
            return false;
        }
        synchronized (WRITABLE_DIR_LOCK) {
            WRITABLE_DIR_CACHE.add(cacheKey);
        }
        return true;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String writableDirCacheKey(File dir) {
        try {
            return dir.getCanonicalPath();
        } catch (IOException e) {
            return dir.getAbsolutePath();
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
