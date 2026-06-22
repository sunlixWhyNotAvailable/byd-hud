package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class NavCaptureStore {
    private static final String TAG = "BydHudNavCapture";
    private static final String DIR = "nav-capture";
    private static final String RAW_EVENTS_FILE = "raw_nav_events.jsonl";
    private static final String SNAPSHOTS_FILE = "nav_snapshots.jsonl";
    static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;

    private NavCaptureStore() {
    }

    static File logDir(Context context) {
        File dir = new File(NavigationLogStorage.navCaptureDir(context), todayDir());
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "mkdir failed: " + dir.getAbsolutePath());
        }
        return dir;
    }

    static String todayDir() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    static void rawEvent(Context context, String channel, String packageName, String payload) {
        append(context, RAW_EVENTS_FILE, "{"
                + "\"t\":" + SystemClock.elapsedRealtime()
                + ",\"channel\":\"" + esc(channel) + "\""
                + ",\"package\":\"" + esc(packageName) + "\""
                + ",\"payload\":\"" + esc(payload) + "\""
                + "}");
    }

    static void snapshot(Context context, NavSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        append(context, SNAPSHOTS_FILE, "{"
                + "\"t\":" + snapshot.elapsedRealtimeMs
                + ",\"source\":\"" + snapshot.sourceApp + "\""
                + ",\"package\":\"" + esc(snapshot.packageName) + "\""
                + ",\"maneuver\":\"" + snapshot.maneuver + "\""
                + ",\"distanceMeters\":" + snapshot.distanceMeters
                + ",\"street\":\"" + esc(snapshot.streetName) + "\""
                + ",\"roundaboutExit\":" + snapshot.roundaboutExitNumber
                + ",\"lanes\":\"" + esc(snapshot.laneString) + "\""
                + ",\"confidence\":" + snapshot.confidence
                + ",\"reason\":\"" + esc(snapshot.rawReason) + "\""
                + "}");
    }

    private static synchronized void append(Context context, String fileName, String line) {
        File file = new File(logDir(context), fileName);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException e) {
            Log.e(TAG, "append failed " + file.getAbsolutePath(), e);
            return;
        }
        if (rotateIfNeeded(file)) {
            NavigationLogStorage.enforceNavCaptureRetention(context);
        }
    }

    private static boolean rotateIfNeeded(File file) {
        if (!shouldRotate(file.length())) {
            return false;
        }
        File rotated = new File(file.getParentFile(), file.getName() + ".1");
        if (rotated.exists() && !rotated.delete()) {
            Log.w(TAG, "delete rotated failed: " + rotated.getAbsolutePath());
        }
        if (file.renameTo(rotated)) {
            try {
                if (!file.createNewFile() && !file.exists()) {
                    Log.w(TAG, "create active log failed: " + file.getAbsolutePath());
                }
            } catch (IOException e) {
                Log.e(TAG, "create active log failed " + file.getAbsolutePath(), e);
            }
            return true;
        }
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write("");
        } catch (IOException e) {
            Log.e(TAG, "truncate failed " + file.getAbsolutePath(), e);
            return false;
        }
        return true;
    }

    static boolean shouldRotate(long byteCount) {
        return byteCount > MAX_LOG_BYTES;
    }

    static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        appendHexControl(builder, c);
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        return builder.toString();
    }

    private static void appendHexControl(StringBuilder builder, char c) {
        final String hex = "0123456789ABCDEF";
        builder.append("\\u00");
        builder.append(hex.charAt((c >> 4) & 0x0F));
        builder.append(hex.charAt(c & 0x0F));
    }
}
