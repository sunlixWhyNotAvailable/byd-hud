package com.bydhud.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class NavigationLogStorage {
    static final String ROOT_DIR = "BYD-HUD";
    static final String LOGCAT_DIR = "logcat";
    static final String NAV_CAPTURE_DIR = "nav-capture";

    private static final String TAG = "BydHudLogStorage";
    private static final String PUBLIC_ROOT = "/sdcard/Documents/" + ROOT_DIR;
    private static final String WRITE_PROBE = ".write_probe";
    private static final Object WRITABLE_DIR_LOCK = new Object();
    private static final Set<String> WRITABLE_DIR_CACHE = new HashSet<>();
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final String SCREEN_PREFIX = "screen_";
    private static final String PNG_SUFFIX = ".png";
    private static final String MISSING_MANEUVERS_DIR = "missing-maneuvers";
    private static final String MISSING_LANES_DIR = "missing-lanes";

    private NavigationLogStorage() {
    }

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

    static File logcatDir(Context context) {
        return publicFirstDir(context, LOGCAT_DIR);
    }

    static File navCaptureDir(Context context) {
        return publicFirstDir(context, NAV_CAPTURE_DIR);
    }

    static String publicLogcatPath() {
        return PUBLIC_ROOT + "/" + LOGCAT_DIR;
    }

    static String publicNavCapturePath() {
        return PUBLIC_ROOT + "/" + NAV_CAPTURE_DIR;
    }

    static String publicWazeCropPath() {
        return publicNavCapturePath() + "/<yyyymmdd>/waze-crop/<session>/screen_*.png";
    }

    static String activeNavCaptureDay() {
        return NavCaptureStore.todayDir();
    }

    static boolean isActiveNavCaptureDay(String day) {
        return day != null && activeNavCaptureDay().equals(day);
    }

    static void enforceNavCaptureRetention(Context context) {
        if (context == null) {
            return;
        }
        enforceNavCaptureRetention(
                navCaptureDir(context),
                activeNavCaptureDay(),
                "",
                "",
                "",
                retentionLimitBytes(context));
    }

    static void enforceNavCaptureRetention(
            Context context,
            String activeSessionDir,
            String activeSessionName,
            String preserveScreenshotName) {
        if (context == null) {
            return;
        }
        enforceNavCaptureRetention(
                navCaptureDir(context),
                activeNavCaptureDay(),
                activeSessionDir,
                activeSessionName,
                preserveScreenshotName,
                retentionLimitBytes(context));
    }

    static void enforceNavCaptureRetentionForTest(
            File root,
            String activeDay,
            String activeSessionDir,
            String activeSessionName,
            String preserveScreenshotName,
            long maxBytes) {
        enforceNavCaptureRetention(
                root,
                activeDay,
                activeSessionDir,
                activeSessionName,
                preserveScreenshotName,
                maxBytes);
    }

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

    private static void enforceNavCaptureRetention(
            File root,
            String activeDay,
            String activeSessionDir,
            String activeSessionName,
            String preserveScreenshotName,
            long maxBytes) {
        if (root == null || maxBytes <= 0L || !root.exists() || !root.isDirectory()) {
            return;
        }
        if (!isOverLimit(root, maxBytes)) {
            return;
        }
        String safeActiveDay = safePathSegment(activeDay, "");
        String safeSessionDir = safePathSegment(activeSessionDir, "");
        String safeSessionName = safePathSegment(activeSessionName, "");
        deleteOldNavCaptureDays(root, safeActiveDay, maxBytes);
        if (!isOverLimit(root, maxBytes) || safeActiveDay.isEmpty()
                || safeSessionDir.isEmpty() || safeSessionName.isEmpty()) {
            return;
        }
        deleteOldNavCaptureSessions(root, safeActiveDay, safeSessionDir, safeSessionName, maxBytes);
        if (!isOverLimit(root, maxBytes)) {
            return;
        }
        File activeSession = new File(new File(new File(root, safeActiveDay), safeSessionDir), safeSessionName);
        deleteOldSessionScreenshots(activeSession, safePathSegment(preserveScreenshotName, ""), root, maxBytes);
    }

    private static void deleteOldNavCaptureDays(File root, String activeDay, long maxBytes) {
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
            if (deleteRecursively(day)) {
                logInfo("retention deleted day=" + day.getName());
            }
        }
    }

    private static void deleteOldNavCaptureSessions(
            File root,
            String activeDay,
            String activeSessionDir,
            String activeSessionName,
            long maxBytes) {
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
            if (deleteRecursively(session)) {
                logInfo("retention deleted session=" + session.getAbsolutePath());
            }
        }
    }

    private static void deleteOldSessionScreenshots(
            File activeSession,
            String preserveScreenshotName,
            File root,
            long maxBytes) {
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
                    && isScreenPng(file.getName())
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
            removeScreenshotArtifacts(activeSession, screenshotName);
            if (screenshot.delete()) {
                logInfo("retention deleted screenshot=" + screenshot.getAbsolutePath());
            }
        }
    }

    private static void removeScreenshotArtifacts(File activeSession, String screenshotName) {
        removeBucketScreenshotArtifacts(new File(activeSession, MISSING_MANEUVERS_DIR), screenshotName);
        removeBucketScreenshotArtifacts(new File(activeSession, MISSING_LANES_DIR), screenshotName);
    }

    private static void removeBucketScreenshotArtifacts(File bucketDir, String screenshotName) {
        if (bucketDir == null || !bucketDir.isDirectory()) {
            return;
        }
        File copiedScreenshot = new File(bucketDir, screenshotName);
        if (copiedScreenshot.isFile() && !copiedScreenshot.delete()) {
            logWarn("retention delete artifact failed: " + copiedScreenshot.getAbsolutePath());
        }
        String base = stripPngSuffix(screenshotName) + ".cell_";
        File[] files = bucketDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && file.isFile() && file.getName().startsWith(base)
                    && !file.delete()) {
                logWarn("retention delete cell artifact failed: " + file.getAbsolutePath());
            }
        }
    }

    private static boolean isOverLimit(File root, long maxBytes) {
        return folderSizeBytes(root) > maxBytes;
    }

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

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    private static void sortOldestFirst(List<File> files) {
        files.sort(Comparator
                .comparingLong(File::lastModified)
                .thenComparing(File::getAbsolutePath));
    }

    private static boolean isDayFolder(File file) {
        return file != null && file.getName().matches("\\d{8}");
    }

    private static boolean isScreenPng(String name) {
        return name != null
                && name.startsWith(SCREEN_PREFIX)
                && name.toLowerCase(Locale.US).endsWith(PNG_SUFFIX);
    }

    private static String stripPngSuffix(String fileName) {
        if (fileName != null && fileName.toLowerCase(Locale.US).endsWith(PNG_SUFFIX)) {
            return fileName.substring(0, fileName.length() - PNG_SUFFIX.length());
        }
        return fileName == null ? "" : fileName;
    }

    private static long retentionLimitBytes(Context context) {
        return HudPrefs.storageLimitGb(context) * BYTES_PER_GB;
    }

    private static void logInfo(String message) {
        try {
            Log.i(TAG, message);
        } catch (RuntimeException | NoClassDefFoundError ignored) {
            // Host-side Java probes do not provide Android logging.
        }
    }

    private static void logWarn(String message) {
        try {
            Log.w(TAG, message);
        } catch (RuntimeException | NoClassDefFoundError ignored) {
            // Host-side Java probes do not provide Android logging.
        }
    }

    private static File publicFirstDir(Context context, String childDir) {
        File publicDir = new File(
                new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOCUMENTS), ROOT_DIR),
                childDir);
        if (ensureWritable(publicDir)) {
            return publicDir;
        }

        File fallback = context.getExternalFilesDir(childDir);
        if (fallback == null) {
            fallback = new File(context.getFilesDir(), childDir);
        }
        ensureDir(fallback);
        Log.w(TAG, "public storage unavailable; fallback=" + fallback.getAbsolutePath());
        return fallback;
    }

    private static boolean isUnderPublicRoot(File dir) {
        if (dir == null) {
            return false;
        }
        String path = dir.getAbsolutePath().replace('\\', '/');
        return path.startsWith("/storage/emulated/0/Documents/" + ROOT_DIR + "/")
                || path.startsWith("/sdcard/Documents/" + ROOT_DIR + "/");
    }

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

    private static String safePathSegment(String value, String fallback) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) {
            safe = fallback;
        }
        return safe.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

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

    private static String writableDirCacheKey(File dir) {
        try {
            return dir.getCanonicalPath();
        } catch (IOException e) {
            return dir.getAbsolutePath();
        }
    }

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

    private static boolean ensureDir(File dir) {
        return dir.exists() || dir.mkdirs();
    }
}
