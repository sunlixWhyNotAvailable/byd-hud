package com.bydhud.app;

//writes navigation evidence to dated folders so parser regressions can be reproduced from field data.

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

//defines the NavCaptureStore module boundary so related behavior stays readable inside one unit.
final class NavCaptureStore {
    private static final String TAG = "BydHudNavCapture";
    private static final String RAW_EVENTS_FILE = "raw_nav_events.jsonl";
    private static final String SNAPSHOTS_FILE = "nav_snapshots.jsonl";
    private static final SimpleDateFormat LOCAL_TS_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
    static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavCaptureStore() {
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static File logDir(Context context) {
        return NavigationLogStorage.logsDir(context);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String todayDir() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void rawEvent(Context context, String channel, String packageName, String payload) {
        append(context, RAW_EVENTS_FILE, "{"
                + timeFields(SystemClock.elapsedRealtime())
                + ",\"channel\":\"" + esc(channel) + "\""
                + ",\"package\":\"" + esc(packageName) + "\""
                + ",\"payload\":\"" + esc(payload) + "\""
                + "}");
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void snapshot(Context context, NavSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        append(context, SNAPSHOTS_FILE, "{"
                + timeFields(snapshot.elapsedRealtimeMs)
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

    //keeps elapsed and wall-clock times together so pulled raw logs can be correlated without anchor math.
    private static String timeFields(long elapsedRealtimeMs) {
        long wallMs = System.currentTimeMillis();
        return "\"t\":" + Math.max(0L, elapsedRealtimeMs)
                + ",\"ts\":" + wallMs
                + ",\"localTs\":\"" + esc(localTimestamp(wallMs)) + "\"";
    }

    //keeps SimpleDateFormat behind one lock because navigation events can arrive from multiple services.
    private static String localTimestamp(long wallMs) {
        synchronized (LOCAL_TS_FORMAT) {
            return LOCAL_TS_FORMAT.format(new Date(wallMs));
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static synchronized void append(Context context, String fileName, String line) {
        File file = NavigationLogStorage.withReadLock(() -> {
            File target = new File(logDir(context), fileName);
            try (FileWriter writer = new FileWriter(target, true)) {
                writer.write(line);
                writer.write('\n');
                return target;
            } catch (IOException e) {
                Log.e(TAG, "append failed " + target.getAbsolutePath(), e);
                return null;
            }
        });
        if (file == null) {
            return;
        }
        if (rotateIfNeeded(file)) {
            NavigationLogStorage.enforceNavCaptureRetention(context);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static boolean rotateIfNeeded(File file) {
        if (!shouldRotate(file.length()) || NavigationLogStorage.holdsTopologyRead()) {
            return false;
        }
        return rotate(file);
    }

    static synchronized String saveDirectArtifact(
            Context context, String kind, byte[] bytes) {
        String fileName = directArtifactFileName(kind, bytes);
        if (fileName.isEmpty()) {
            return "";
        }
        return NavigationLogStorage.withReadLock(() -> {
            File dir = NavigationLogStorage.directCaptureDir(context);
            File file = new File(dir, fileName);
            if (file.isFile()) {
                return NavigationLogStorage.WAZE_DIRECT_DIR + "/" + fileName;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
                return NavigationLogStorage.WAZE_DIRECT_DIR + "/" + fileName;
            } catch (IOException e) {
                Log.e(TAG, "direct artifact failed " + file.getAbsolutePath(), e);
                return "";
            }
        });
    }

    static String directArtifactFileName(String kind, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String hash = sha256(bytes);
        if (hash.isEmpty()) {
            return "";
        }
        String safeKind = kind == null ? "artifact"
                : kind.replaceAll("[^A-Za-z0-9_-]", "_");
        return safeKind + "-" + hash + ".png";
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    //preserves every same-day shard; the dated parent directory resets the sequence naturally.
    static boolean rotate(File file) {
        return NavigationLogStorage.withWriteLock(() -> rotateLocked(file));
    }

    private static boolean rotateLocked(File file) {
        if (file == null || !file.exists() || file.getParentFile() == null) {
            return false;
        }
        int highestSuffix = highestRotatedSuffix(file);
        if (highestSuffix == Integer.MAX_VALUE) {
            Log.w(TAG, "rotate suffix exhausted: " + file.getAbsolutePath());
            return false;
        }
        for (int suffix = highestSuffix; suffix >= 1; suffix--) {
            File source = rotatedFile(file, suffix);
            if (!source.isFile()) {
                continue;
            }
            File target = rotatedFile(file, suffix + 1);
            if (target.exists() || !source.renameTo(target)) {
                Log.w(TAG, "rotate shard failed: " + source.getAbsolutePath()
                        + " -> " + target.getAbsolutePath());
                return false;
            }
        }

        File rotated = rotatedFile(file, 1);
        if (!file.renameTo(rotated)) {
            Log.w(TAG, "rotate active failed: " + file.getAbsolutePath()
                    + " -> " + rotated.getAbsolutePath());
            return false;
        }
        try {
            if (!file.createNewFile() && !file.exists()) {
                Log.w(TAG, "create active log failed: " + file.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "create active log failed " + file.getAbsolutePath(), e);
        }
        return true;
    }

    private static int highestRotatedSuffix(File file) {
        String prefix = file.getName() + ".";
        File[] siblings = file.getParentFile().listFiles();
        int highest = 0;
        if (siblings == null) {
            return highest;
        }
        for (File sibling : siblings) {
            if (sibling == null || !sibling.isFile()) {
                continue;
            }
            String name = sibling.getName();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String suffix = name.substring(prefix.length());
            try {
                int value = Integer.parseInt(suffix);
                if (value > highest) {
                    highest = value;
                }
            } catch (NumberFormatException ignored) {
                // Ignore non-numeric artifacts such as manual backups.
            }
        }
        return highest;
    }

    private static File rotatedFile(File file, int suffix) {
        return new File(file.getParentFile(), file.getName() + "." + suffix);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldRotate(long byteCount) {
        return byteCount > MAX_LOG_BYTES;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void appendHexControl(StringBuilder builder, char c) {
        final String hex = "0123456789ABCDEF";
        builder.append("\\u00");
        builder.append(hex.charAt((c >> 4) & 0x0F));
        builder.append(hex.charAt(c & 0x0F));
    }
}
