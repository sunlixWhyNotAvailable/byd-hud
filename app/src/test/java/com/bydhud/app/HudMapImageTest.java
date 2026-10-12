package com.bydhud.app;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class HudMapImageTest {

    @Test
    public void profilesSelectOriginalMapAreaWithoutTransparentMargins() {
        assertArrayEquals(new byte[]{0x0a, 0x00}, HudMapImage.nativePayload(null));
        Bitmap source = Bitmap.createBitmap(1500, 900, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(source);
            canvas.drawColor(Color.GREEN);
            Paint paint = new Paint();
            paint.setColor(Color.RED); canvas.drawRect(0, 0, 300, 900, paint);
            paint.setColor(Color.BLUE); canvas.drawRect(1200, 0, 1500, 900, paint);
            paint.setColor(Color.YELLOW); canvas.drawRect(300, 0, 1200, 180, paint);
            paint.setColor(Color.CYAN); canvas.drawRect(300, 720, 1200, 900, paint);
            HudMapProfile profile = HudMapProfile.defaults(HudMapProfile.Source.WAZE);
            int[] colors = {Color.RED, Color.BLUE, Color.YELLOW, Color.CYAN};
            float[][] offsets = {{-100,0},{100,0},{0,100},{0,-100}};
            for (int i = 0; i < offsets.length; i++) {
                Bitmap result = HudMapImage.profileCrop(source,
                        new HudMapProfile(profile.source, offsets[i][0], offsets[i][1], 100));
                assertNotNull(result);
                try {
                    assertEquals(colors[i], result.getPixel(150, 90));
                    java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
                    assertTrue(result.compress(Bitmap.CompressFormat.PNG, 100, encoded));
                    byte[] wire = HudMapImage.nativePayload(encoded.toByteArray());
                    java.nio.ByteBuffer packet = java.nio.ByteBuffer.wrap(wire);
                    assertEquals(10, packet.get() & 255);
                    int length = 0, shift = 0, next;
                    do { next = packet.get() & 255; length |= (next & 127) << shift; shift += 7; }
                    while ((next & 128) != 0);
                    assertEquals(length, packet.remaining());
                    byte[] payload = new byte[length]; packet.get(payload);
                    assertArrayEquals(encoded.toByteArray(), android.util.Base64.decode(payload, android.util.Base64.DEFAULT));
                }
                finally { result.recycle(); }
            }
            for (float scale : new float[]{20, 50, 92, 100, 125.5f, 300}) {
                Bitmap result = HudMapImage.profileCrop(source, profile.withScale(scale));
                assertNotNull(result);
                try {
                    for (int x : new int[]{0, 150, 299}) for (int y : new int[]{0, 90, 179})
                        assertEquals(255, Color.alpha(result.getPixel(x, y)));
                    if (scale == 20) {
                        assertEquals(Color.RED, result.getPixel(20, 90));
                        assertEquals(Color.BLUE, result.getPixel(280, 90));
                    }
                } finally { result.recycle(); }
            }
            assertFalse(source.isRecycled());
        } finally { source.recycle(); }
    }

}
