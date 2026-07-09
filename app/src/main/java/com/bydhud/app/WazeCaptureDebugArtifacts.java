package com.bydhud.app;

//writes beta capture evidence without changing parser or HUD decisions.

import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

//keeps debug artifact naming in one place so field tests can compare frame sources reliably.
final class WazeCaptureDebugArtifacts {
    private static final String EVENTS_FILE = "capture_events.jsonl";

    //persists the full frame that the parser actually consumed.
    String saveSourceFrame(File dir, int frameId, Bitmap frame) {
        String name = frameName("source_frame_", frameId);
        return saveBitmap(dir, name, frame) ? name : "";
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

    //writes a bitmap as PNG and returns the relative session file name.
    //writes a bitmap as PNG without forcing capture/parser callers to touch file APIs directly.
    boolean saveBitmap(File dir, String name, Bitmap bitmap) {
        if (dir == null || bitmap == null || name == null || name.isEmpty()) {
            return false;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            return false;
        }
    }

}
