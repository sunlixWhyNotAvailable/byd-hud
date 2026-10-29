package com.bydhud.app;

//keeps diagnostic events centralized so runtime, parser, and capture logs remain comparable across field tests.

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

//defines the AppEventLogger module boundary so related behavior stays readable inside one unit.
final class AppEventLogger {
    private static final String TAG = "BydHudEventLog";
    static final String EVENTS_FILE = "events.log";
    static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
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

    static void retentionEvent(Context context, String line) {
        Log.i(TAG, line);
        WazeCaptureDebugWriter.get().retentionEvent(context, line);
    }

    static boolean writeEvent(
            Context context,
            String line,
            long wallClockMs,
            String targetDay) {
        return WazeCaptureDebugWriter.get().writeEventSynchronously(
                context, line, wallClockMs, targetDay);
    }

    static boolean writeEventNow(
            Context context,
            String line,
            long wallClockMs,
            String targetDay) {
        return writeBatch(context, targetDay,
                java.util.Collections.singletonList(queuedEvent(line, wallClockMs)), true, true);
    }

    static String queuedEvent(String line, long wallClockMs) {
        return timestamp(wallClockMs) + " " + line + "\n";
    }

    static boolean writeBatch(Context context, String targetDay, List<String> lines) {
        return writeBatch(context, targetDay, lines, true);
    }

    static boolean writeBatch(
            Context context, String targetDay, List<String> lines, boolean requestRetention) {
        return writeBatch(context, targetDay, lines, requestRetention, false);
    }

    static boolean writeBatch(Context context, String targetDay, List<String> lines,
            boolean requestRetention, boolean durable) {
        if (context == null || targetDay == null || lines == null || lines.isEmpty()) return false;
        synchronized (WRITE_LOCK) {
            int index = 0;
            boolean allWritten = true;
            boolean anyWritten = false;
            while (index < lines.size()) {
                final int start = index;
                BatchAppend append = NavigationLogStorage.withReadLock(() -> {
                    int next = start;
                    File destination = null;
                    try {
                        destination = file(context, targetDay, EVENTS_FILE);
                        long projectedLength = destination.isFile() ? destination.length() : 0L;
                        List<String> chunk = new ArrayList<>();
                        while (next < lines.size()) {
                            String line = lines.get(next++);
                            chunk.add(line);
                            projectedLength += JournalTextBatch.byteCount(line);
                            if (projectedLength > MAX_FILE_BYTES) break;
                        }
                        JournalTextBatch.append(destination, chunk, durable);
                        return new BatchAppend(destination, next, true);
                    } catch (IOException | RuntimeException error) {
                        WazeCaptureDebugWriter.recordWriteFailure("events append: " + error);
                        Log.e(TAG, "write failed: "
                                + (destination == null ? EVENTS_FILE : destination.getAbsolutePath()), error);
                        return new BatchAppend(destination, Math.max(start + 1, next), false);
                    }
                });
                if (!append.written) {
                    allWritten = false;
                } else {
                    anyWritten = true;
                    WazeCaptureDebugWriter.recordWriteSuccess();
                }
                index = append.nextIndex;
                if (append.written && append.file != null
                        && append.file.length() > MAX_FILE_BYTES
                        && !NavigationLogStorage.holdsTopologyRead()) {
                    NavCaptureStore.rotate(append.file);
                }
            }
            if (requestRetention && anyWritten) {
                NavigationLogStorage.enforceNavCaptureRetention(context);
            }
            return allWritten;
        }
    }

    private static final class BatchAppend {
        final File file;
        final int nextIndex;
        final boolean written;

        BatchAppend(File file, int nextIndex, boolean written) {
            this.file = file;
            this.nextIndex = nextIndex;
            this.written = written;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static File logDir(Context context) {
        return NavigationLogStorage.logsDir(context);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static File file(Context context, String targetDay, String name) {
        return new File(NavigationLogStorage.logsDir(context, targetDay), name);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String timestamp(long wallClockMs) {
        synchronized (FORMAT) {
            return FORMAT.format(new Date(wallClockMs));
        }
    }

}
