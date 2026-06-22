package com.bydhud.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class AppEventLogger {
    private static final String TAG = "BydHudEventLog";
    private static final String EVENTS_FILE = "events.log";
    private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private AppEventLogger() {
    }

    static void event(Context context, String line) {
        Log.i(TAG, line);
        append(context, EVENTS_FILE, timestamp() + " " + line + "\n", false);
    }

    static File logDir(Context context) {
        return NavigationLogStorage.logcatDir(context);
    }

    private static File file(Context context, String name) {
        File file = new File(logDir(context), name);
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            File rotated = new File(logDir(context), name + ".1");
            if (rotated.exists() && !rotated.delete()) {
                Log.w(TAG, "delete rotated failed: " + rotated.getAbsolutePath());
            }
            if (!file.renameTo(rotated)) {
                Log.w(TAG, "rotate failed: " + file.getAbsolutePath());
            }
        }
        return file;
    }

    private static void append(Context context, String name, String text, boolean ignored) {
        File file = file(context, name);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(text);
        } catch (IOException e) {
            Log.e(TAG, "write failed: " + file.getAbsolutePath(), e);
        }
    }

    private static String timestamp() {
        synchronized (FORMAT) {
            return FORMAT.format(new Date());
        }
    }

}
