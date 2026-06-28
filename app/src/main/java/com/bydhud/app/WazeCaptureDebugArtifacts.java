package com.bydhud.app;

//writes beta capture evidence without changing parser or HUD decisions.

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

//keeps debug artifact naming in one place so field tests can compare frame sources reliably.
final class WazeCaptureDebugArtifacts {
    private static final String EVENTS_FILE = "capture_events.jsonl";

    private long lastCompareElapsedMs;

    //persists the full frame that the parser actually consumed.
    String saveSourceFrame(File dir, int frameId, Bitmap frame) {
        return saveBitmap(dir, frameName("source_frame_", frameId), frame);
    }

    //persists the maneuver crop used for visual verification.
    String saveArrowInput(File dir, int frameId, Bitmap frame, Rect bounds) {
        return saveCrop(dir, frameName("arrow_input_", frameId), frame, bounds);
    }

    //persists the lane crop used for visual verification.
    String saveLanesInput(File dir, int frameId, Bitmap frame, Rect bounds) {
        return saveCrop(dir, frameName("lanes_input_", frameId), frame, bounds);
    }

    //paces ADB screencap comparison evidence so beta logs remain useful but bounded.
    boolean shouldWriteCompare(long nowMs) {
        if (lastCompareElapsedMs <= 0L
                || nowMs - lastCompareElapsedMs >= BuildConfig.WAZE_COMPARE_SCREENSHOT_INTERVAL_MS) {
            lastCompareElapsedMs = nowMs;
            return true;
        }
        return false;
    }

    //records per-frame metadata alongside the PNG evidence.
    void appendEvent(File dir, String line) {
        if (dir == null || line == null) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File file = new File(dir, EVENTS_FILE);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException ignored) {
            //keeps debug evidence best-effort because capture must not block HUD output.
        }
    }

    //uses stable zero-padded names so screenshot timelines sort naturally.
    static String frameName(String prefix, int frameId) {
        return prefix + String.format(Locale.US, "%04d", frameId) + ".png";
    }

    //names old-path comparison screenshots for projection-vs-screencap audits.
    static String screencapCompareName(int frameId) {
        return frameName("screencap_compare_", frameId);
    }

    //writes a bitmap as PNG and returns the relative session file name.
    private static String saveBitmap(File dir, String name, Bitmap bitmap) {
        if (dir == null || bitmap == null || name == null || name.isEmpty()) {
            return "";
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return "";
        }
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) ? name : "";
        } catch (IOException e) {
            return "";
        }
    }

    //clips parser geometry to the current frame before writing the crop.
    private static String saveCrop(File dir, String name, Bitmap frame, Rect bounds) {
        if (frame == null || bounds == null) {
            return "";
        }
        Rect clipped = new Rect(
                clamp(bounds.left, 0, frame.getWidth()),
                clamp(bounds.top, 0, frame.getHeight()),
                clamp(bounds.right, 0, frame.getWidth()),
                clamp(bounds.bottom, 0, frame.getHeight()));
        if (clipped.width() <= 0 || clipped.height() <= 0) {
            return "";
        }
        Bitmap crop = Bitmap.createBitmap(frame, clipped.left, clipped.top,
                clipped.width(), clipped.height());
        try {
            return saveBitmap(dir, name, crop);
        } finally {
            crop.recycle();
        }
    }

    //keeps crop coordinates inside bitmap bounds.
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
