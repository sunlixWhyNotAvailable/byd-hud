package com.bydhud.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Matrix;
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
        if (source == null || source.isRecycled()) return new byte[0];
        float fitPercent = 100f * Math.max(WIDTH / (float) source.getWidth(),
                HEIGHT / (float) source.getHeight());
        return encodeAndRecycle(framedBitmap(source, 0, 0, fitPercent));
    }

    /** Applies source framing before producing the same 300x180 image used by HUD output. */
    public static byte[] profileCropPng(Bitmap source, HudMapProfile profile) {
        if (profile == null) {
            return centerCropPng(source);
        }
        return encodeAndRecycle(profileCrop(source, profile));
    }

    static Bitmap profileCrop(Bitmap source, HudMapProfile profile) {
        if (source == null || source.isRecycled()) return null;
        if (profile != null) return framedBitmap(source, profile.x, profile.y, profile.scale);
        float fit = 100f * Math.max(WIDTH / (float) source.getWidth(), HEIGHT / (float) source.getHeight());
        return framedBitmap(source, 0, 0, fit);
    }

    static byte[] encodePng(Bitmap bitmap) {
        if (bitmap == null) return new byte[0];
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes) ? bytes.toByteArray() : new byte[0];
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return new byte[0];
        }
    }

    private static byte[] encodeAndRecycle(Bitmap bitmap) {
        try { return encodePng(bitmap); }
        finally { if (bitmap != null) bitmap.recycle(); }
    }

    private static Bitmap framedBitmap(Bitmap source, float x, float y, float scalePercent) {
        if (source == null || source.isRecycled()) return null;
        Bitmap output = null;
        try {
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) return null;
            output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            output.eraseColor(Color.TRANSPARENT);
            // 100% means one source pixel per HUD pixel; 20% selects five times
            // as much map. Bound the source window instead of moving its outer edge.
            float cropWidth = WIDTH * 100f / scalePercent;
            float cropHeight = HEIGHT * 100f / scalePercent;
            float fit = Math.min(1f, Math.min(sourceWidth / cropWidth, sourceHeight / cropHeight));
            cropWidth *= fit;
            cropHeight *= fit;
            float left = (sourceWidth - cropWidth) * (1f + x / 100f) / 2f;
            float top = (sourceHeight - cropHeight) * (1f - y / 100f) / 2f;
            RectF window = new RectF(left, top, left + cropWidth, top + cropHeight);
            Matrix transform = new Matrix();
            transform.setRectToRect(window, new RectF(0, 0, WIDTH, HEIGHT), Matrix.ScaleToFit.FILL);
            Canvas canvas = new Canvas(output);
            canvas.clipRect(0, 0, WIDTH, HEIGHT);
            canvas.drawBitmap(source, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
            return output;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            if (output != null && !output.isRecycled()) output.recycle();
            return null;
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
