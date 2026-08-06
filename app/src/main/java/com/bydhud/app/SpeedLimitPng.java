package com.bydhud.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.util.SparseArray;

import java.io.ByteArrayOutputStream;

final class SpeedLimitPng {
    private static final SparseArray<byte[]> CACHE = new SparseArray<>();

    private SpeedLimitPng() {
    }

    static synchronized byte[] get(int speedLimit) {
        if (speedLimit <= 0 || speedLimit > 300) return new byte[0];
        byte[] cached = CACHE.get(speedLimit);
        if (cached != null) return cached;

        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
            red.setColor(Color.rgb(208, 0, 0));
            canvas.drawCircle(48f, 48f, 46.079998f, red);
            Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(Color.WHITE);
            canvas.drawCircle(48f, 48f, 32.64f, white);
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.BLACK);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            text.setTextSize(speedLimit >= 100 ? 34.56f : 42.239998f);
            Paint.FontMetrics metrics = text.getFontMetrics();
            canvas.drawText(String.valueOf(speedLimit), 48f,
                    48f - (metrics.ascent + metrics.descent) / 2f, text);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return new byte[0];
            byte[] png = output.toByteArray();
            CACHE.put(speedLimit, png);
            return png;
        } finally {
            bitmap.recycle();
        }
    }
}
