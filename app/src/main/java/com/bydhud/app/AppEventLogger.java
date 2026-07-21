package com.bydhud.app;

//keeps diagnostic events centralized so runtime, parser, and capture logs remain comparable across field tests.

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

//defines the AppEventLogger module boundary so related behavior stays readable inside one unit.
final class AppEventLogger {
    private static final String TAG = "BydHudEventLog";
    private static final String EVENTS_FILE = "events.log";
    private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    private static final Object WRITE_LOCK = new Object();
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private AppEventLogger() {
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void event(Context context, String line) {
        Log.i(TAG, line);
        WazeCaptureDebugWriter.get().appEvent(context, line);
    }

    static void writeEvent(
            Context context,
            String line,
            long wallClockMs,
            String targetDay) {
        append(context, targetDay, EVENTS_FILE,
                timestamp(wallClockMs) + " " + line + "\n", false);
        NavigationLogStorage.enforceNavCaptureRetention(context);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static File logDir(Context context) {
        return NavigationLogStorage.logsDir(context);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static File file(Context context, String targetDay, String name) {
        return new File(NavigationLogStorage.logsDir(context, targetDay), name);
    }

    //guard event log rotation and append because runtime callbacks write from multiple threads.
    private static void append(
            Context context,
            String targetDay,
            String name,
            String text,
            boolean ignored) {
        synchronized (WRITE_LOCK) {
            File file = NavigationLogStorage.withReadLock(() -> {
                File target = file(context, targetDay, name);
                try (FileWriter writer = new FileWriter(target, true)) {
                    writer.write(text);
                    return target;
                } catch (IOException e) {
                    Log.e(TAG, "write failed: " + target.getAbsolutePath(), e);
                    return null;
                }
            });
            if (file != null
                    && file.length() > MAX_FILE_BYTES
                    && !NavigationLogStorage.holdsTopologyRead()) {
                if (HudPrefs.isDetailedDebugArtifactsEnabled(context)) {
                    NavCaptureStore.rotate(file);
                } else {
                    NavigationLogStorage.withWriteLock(() -> rotate(file, name));
                }
            }
        }
    }

    private static void rotate(File file, String name) {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) {
            return;
        }
        File rotated = new File(file.getParentFile(), name + ".1");
        if (rotated.exists() && !rotated.delete()) {
            Log.w(TAG, "delete rotated failed: " + rotated.getAbsolutePath());
        }
        if (!file.renameTo(rotated)) {
            Log.w(TAG, "rotate failed: " + file.getAbsolutePath());
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String timestamp(long wallClockMs) {
        synchronized (FORMAT) {
            return FORMAT.format(new Date(wallClockMs));
        }
    }

}
