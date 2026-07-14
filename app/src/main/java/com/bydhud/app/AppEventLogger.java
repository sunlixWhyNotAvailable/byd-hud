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
        append(context, EVENTS_FILE, timestamp() + " " + line + "\n", false);
        NavigationLogStorage.enforceNavCaptureRetention(context);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static File logDir(Context context) {
        return NavigationLogStorage.logsDir(context);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static File file(Context context, String name) {
        File file = new File(logDir(context), name);
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            if (HudPrefs.isDetailedDebugArtifactsEnabled(context)) {
                NavCaptureStore.rotate(file);
                return file;
            }
            File rotated = new File(file.getParentFile(), name + ".1");
            if (rotated.exists() && !rotated.delete()) {
                Log.w(TAG, "delete rotated failed: " + rotated.getAbsolutePath());
            }
            if (!file.renameTo(rotated)) {
                Log.w(TAG, "rotate failed: " + file.getAbsolutePath());
            }
        }
        return file;
    }

    //guard event log rotation and append because runtime callbacks write from multiple threads.
    private static void append(Context context, String name, String text, boolean ignored) {
        synchronized (WRITE_LOCK) {
            File file = file(context, name);
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(text);
            } catch (IOException e) {
                Log.e(TAG, "write failed: " + file.getAbsolutePath(), e);
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String timestamp() {
        synchronized (FORMAT) {
            return FORMAT.format(new Date());
        }
    }

}
