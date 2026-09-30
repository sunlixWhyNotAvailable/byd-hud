package com.bydhud.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Small image conversions shared by live-map output paths. */
public final class HudMapImage {
    private static final int WIDTH = HudMapGeometry.SOURCE_WIDTH;
    private static final int HEIGHT = HudMapGeometry.SOURCE_HEIGHT;

    private HudMapImage() {
    }

    /** Center-crops and scales a source image into the compositor's 300x180 PNG. */
    public static byte[] centerCropPng(Bitmap source) {
        return framedPng(source, 0, 0, 100);
    }

    /** Applies source framing before producing the same 300x180 image used by HUD output. */
    public static byte[] profileCropPng(Bitmap source, HudMapProfile profile) {
        if (profile == null || (profile.x == 0 && profile.y == 0 && profile.scale == 100)) {
            return centerCropPng(source);
        }
        return framedPng(source, profile.x, profile.y, profile.scale);
    }

    private static byte[] framedPng(Bitmap source, int x, int y, int scalePercent) {
        if (source == null || source.isRecycled()) return new byte[0];
        Bitmap output = null;
        try {
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) return new byte[0];
            output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            output.eraseColor(Color.TRANSPARENT);
            float baseScale = Math.max(WIDTH / (float) sourceWidth,
                    HEIGHT / (float) sourceHeight);
            float drawWidth = sourceWidth * baseScale * scalePercent / 100f;
            float drawHeight = sourceHeight * baseScale * scalePercent / 100f;
            float shiftX = -x * WIDTH / 100f;
            float shiftY = y * HEIGHT / 100f;
            RectF destination = new RectF(
                    (WIDTH - drawWidth) / 2f + shiftX,
                    (HEIGHT - drawHeight) / 2f + shiftY,
                    (WIDTH + drawWidth) / 2f + shiftX,
                    (HEIGHT + drawHeight) / 2f + shiftY);
            Canvas canvas = new Canvas(output);
            canvas.clipRect(0, 0, WIDTH, HEIGHT);
            canvas.drawBitmap(source, null, destination,
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            return output.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                    ? bytes.toByteArray() : new byte[0];
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return new byte[0];
        } finally {
            if (output != null && !output.isRecycled()) output.recycle();
        }
    }

    /** Builds the native 0x8003 field-1 Base64 payload; empty input clears it. */
    public static byte[] nativePayload(byte[] canonicalPng) {
        if (canonicalPng == null || canonicalPng.length == 0) {
            return new byte[]{0x0a, 0x00};
        }
        byte[] encoded = Base64.getEncoder().encodeToString(canonicalPng)
                .getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(encoded.length + 6);
        payload.write(0x0a);
        writeVarint(payload, encoded.length);
        payload.write(encoded, 0, encoded.length);
        return payload.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            out.write((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }
}
