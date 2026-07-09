package com.bydhud.app;

//guards Waze parser freshness by moving debug disk writes off the capture/parser thread.

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

final class WazeCaptureDebugWriter {
    private static final String TAG = "BydHudWazeDebugWriter";
    private static final int MAX_PENDING_TASKS = 128;
    private static final int MAX_PENDING_BITMAPS = 4;
    private static final String SESSION_LOG = "session.jsonl";
    private static final String BUCKET_MISSING_LANES = "missing-lanes";
    private static final Object INSTANCE_LOCK = new Object();

    private static WazeCaptureDebugWriter instance;

    private final HandlerThread thread;
    private final Handler handler;
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicInteger pendingBitmaps = new AtomicInteger();
    private final WazeCaptureDebugArtifacts artifacts = new WazeCaptureDebugArtifacts();

    private WazeCaptureDebugWriter() {
        thread = new HandlerThread("BydHudWazeDebugWriter", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    static WazeCaptureDebugWriter get() {
        synchronized (INSTANCE_LOCK) {
            if (instance == null) {
                instance = new WazeCaptureDebugWriter();
            }
            return instance;
        }
    }

    int pendingTasks() {
        return pendingTasks.get();
    }

    int pendingBitmaps() {
        return pendingBitmaps.get();
    }

    boolean appendSessionLine(File dir, String line) {
        if (dir == null || line == null) {
            return false;
        }
        return post("session_jsonl", () -> appendLine(dir, SESSION_LOG, line));
    }

    boolean appendCaptureEvent(File dir, String line) {
        if (dir == null || line == null) {
            return false;
        }
        return post("capture_events", () -> artifacts.appendEvent(dir, line));
    }

    boolean rawEvent(Context context, String channel, String packageName, String payload) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            return false;
        }
        return post("raw_nav_event", () -> NavCaptureStore.rawEvent(app, channel, packageName, payload));
    }

    boolean frameArtifacts(
            File dir,
            String sourceFrameName,
            Bitmap frame,
            String missingBucket,
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis) {
        if (dir == null || sourceFrameName == null || sourceFrameName.isEmpty() || frame == null) {
            return false;
        }
        if (!tryReserveBitmap()) {
            Log.w(TAG, "debug_writer_drop type=frame reason=bitmap_queue_full file=" + sourceFrameName);
            return false;
        }
        Bitmap copy;
        try {
            copy = frame.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException e) {
            pendingBitmaps.decrementAndGet();
            Log.w(TAG, "debug_writer_drop type=frame reason=copy_failed file=" + sourceFrameName, e);
            return false;
        }
        boolean posted = post("frame_artifacts", () -> {
            try {
                writeFrameArtifacts(dir, sourceFrameName, copy, missingBucket, laneAnalysis);
            } finally {
                copy.recycle();
                pendingBitmaps.decrementAndGet();
            }
        });
        if (!posted) {
            copy.recycle();
            pendingBitmaps.decrementAndGet();
        }
        return posted;
    }

    private boolean tryReserveBitmap() {
        while (true) {
            int current = pendingBitmaps.get();
            if (current >= MAX_PENDING_BITMAPS) {
                return false;
            }
            if (pendingBitmaps.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private boolean post(String type, Runnable work) {
        while (true) {
            int current = pendingTasks.get();
            if (current >= MAX_PENDING_TASKS) {
                Log.w(TAG, "debug_writer_drop type=" + type + " reason=task_queue_full");
                return false;
            }
            if (pendingTasks.compareAndSet(current, current + 1)) {
                break;
            }
        }
        handler.post(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "debug_writer_failed type=" + type, e);
            } finally {
                pendingTasks.decrementAndGet();
            }
        });
        return true;
    }

    private void writeFrameArtifacts(
            File dir,
            String sourceFrameName,
            Bitmap bitmap,
            String missingBucket,
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis) {
        artifacts.saveBitmap(dir, sourceFrameName, bitmap);
        if (missingBucket != null && !missingBucket.isEmpty()) {
            artifacts.saveBitmap(new File(dir, missingBucket), sourceFrameName, bitmap);
        }
        if (BUCKET_MISSING_LANES.equals(missingBucket)) {
            exportMissingLaneCells(dir, sourceFrameName, bitmap, laneAnalysis);
        }
    }

    private static void appendLine(File dir, String fileName, String line) {
        if (dir == null || fileName == null || line == null) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File file = new File(dir, fileName);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException ignored) {
            //debug evidence must never block live navigation.
        }
    }

    private void exportMissingLaneCells(
            File dir,
            String sourceFrameName,
            Bitmap source,
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis) {
        if (dir == null || source == null || laneAnalysis == null || laneAnalysis.cells.isEmpty()) {
            return;
        }
        File targetDir = new File(dir, BUCKET_MISSING_LANES);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return;
        }
        for (WazeLaneCell cell : laneAnalysis.cells) {
            if (shouldExportMissingLaneCell(cell)) {
                exportLaneCell(source, targetDir, sourceFrameName, cell);
            }
        }
    }

    private static boolean shouldExportMissingLaneCell(WazeLaneCell cell) {
        if (cell == null) {
            return false;
        }
        String reason = cell.failureReason == null ? "" : cell.failureReason.trim();
        return !"NONE".equals(reason);
    }

    private static void exportLaneCell(
            Bitmap source,
            File targetDir,
            String fileName,
            WazeLaneCell cell) {
        int x1 = clamp(cell.x1, 0, source.getWidth() - 1);
        int y1 = clamp(cell.y1, 0, source.getHeight() - 1);
        int x2 = clamp(cell.x2, x1, source.getWidth() - 1);
        int y2 = clamp(cell.y2, y1, source.getHeight() - 1);
        int width = x2 - x1 + 1;
        int height = y2 - y1 + 1;
        if (width <= 0 || height <= 0) {
            return;
        }
        String baseName = stripPngSuffix(fileName)
                + ".cell_" + String.format(Locale.US, "%02d", cell.index);
        Bitmap raw = Bitmap.createBitmap(source, x1, y1, width, height);
        try {
            if (!writePng(raw, new File(targetDir, baseName + ".raw.png"))) {
                return;
            }
            Bitmap normalized = normalizedCell(raw);
            try {
                writePng(normalized, new File(targetDir, baseName + ".norm.png"));
            } finally {
                normalized.recycle();
            }
            writeCellMeta(new File(targetDir, baseName + ".meta.json"), fileName, cell, x1, y1, x2, y2);
        } finally {
            raw.recycle();
        }
    }

    private static Bitmap normalizedCell(Bitmap raw) {
        final int targetWidth = 64;
        final int targetHeight = 96;
        Bitmap out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF000000);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        float scale = Math.min(
                targetWidth / Math.max(1.0f, raw.getWidth()),
                targetHeight / Math.max(1.0f, raw.getHeight()));
        int drawWidth = Math.max(1, Math.round(raw.getWidth() * scale));
        int drawHeight = Math.max(1, Math.round(raw.getHeight() * scale));
        int left = (targetWidth - drawWidth) / 2;
        int top = (targetHeight - drawHeight) / 2;
        Rect dst = new Rect(left, top, left + drawWidth, top + drawHeight);
        canvas.drawBitmap(raw, null, dst, paint);
        return out;
    }

    private static boolean writePng(Bitmap bitmap, File file) {
        if (bitmap == null || file == null) {
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeCellMeta(
            File file,
            String sourceScreen,
            WazeLaneCell cell,
            int x1,
            int y1,
            int x2,
            int y2) {
        if (file == null || cell == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write("{"
                    + "\"sourceScreen\":\"" + NavCaptureStore.esc(sourceScreen) + "\""
                    + ",\"cellIndex\":" + cell.index
                    + ",\"bbox\":{\"x1\":" + x1
                    + ",\"y1\":" + y1
                    + ",\"x2\":" + x2
                    + ",\"y2\":" + y2 + "}"
                    + ",\"geometryGuess\":\"" + NavCaptureStore.esc(cell.geometryGuess) + "\""
                    + ",\"failureReason\":\"" + NavCaptureStore.esc(cell.failureReason) + "\""
                    + ",\"layout\":\"LANES\""
                    + "}");
        } catch (IOException ignored) {
            //debug evidence must remain best-effort.
        }
    }

    private static String stripPngSuffix(String fileName) {
        String safeName = fileName == null ? "screen" : fileName.trim();
        if (safeName.toLowerCase(Locale.US).endsWith(".png")) {
            return safeName.substring(0, safeName.length() - 4);
        }
        return safeName;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
