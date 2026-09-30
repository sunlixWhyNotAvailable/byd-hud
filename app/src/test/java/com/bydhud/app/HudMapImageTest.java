package com.bydhud.app;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class HudMapImageTest {
    @Test
    public void centerCropProducesCanonicalAspectWithoutRecyclingSource() {
        Bitmap wide = Bitmap.createBitmap(360, 180, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(wide);
            canvas.drawColor(Color.GREEN);
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            canvas.drawRect(0, 0, 25, 180, paint);
            paint.setColor(Color.BLUE);
            canvas.drawRect(335, 0, 360, 180, paint);

            byte[] png = HudMapImage.centerCropPng(wide);
            Bitmap cropped = BitmapFactory.decodeByteArray(png, 0, png.length);
            assertNotNull(cropped);
            try {
                assertEquals(300, cropped.getWidth());
                assertEquals(180, cropped.getHeight());
                assertEquals(Color.GREEN, cropped.getPixel(0, 90));
                assertEquals(Color.GREEN, cropped.getPixel(299, 90));
                assertFalse(wide.isRecycled());
            } finally {
                cropped.recycle();
            }
        } finally {
            wide.recycle();
        }
    }

    @Test
    public void noProfileKeepsFullCentralCropForLargeSource() {
        Bitmap source = Bitmap.createBitmap(1500, 900, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(source);
            canvas.drawColor(Color.GREEN);
            Paint paint = new Paint();
            paint.setColor(Color.RED); canvas.drawRect(0, 0, 300, 900, paint);
            paint.setColor(Color.BLUE); canvas.drawRect(1200, 0, 1500, 900, paint);
            Bitmap centered = decode(HudMapImage.centerCropPng(source));
            Bitmap withoutProfile = decode(HudMapImage.profileCropPng(source, null));
            Bitmap explicitProfile = decode(HudMapImage.profileCropPng(source,
                    HudMapProfile.defaults(HudMapProfile.Source.WAZE)));
            try {
                for (Bitmap result : new Bitmap[]{centered, withoutProfile}) {
                    assertEquals(Color.RED, result.getPixel(20, 90));
                    assertEquals(Color.BLUE, result.getPixel(280, 90));
                }
                assertEquals(Color.GREEN, explicitProfile.getPixel(20, 90));
                assertEquals(Color.GREEN, explicitProfile.getPixel(280, 90));
            } finally {
                centered.recycle(); withoutProfile.recycle(); explicitProfile.recycle();
            }
        } finally { source.recycle(); }
    }

    @Test
    public void profilesSelectOriginalMapAreaWithoutTransparentMargins() {
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
                Bitmap result = decode(HudMapImage.profileCropPng(source,
                        new HudMapProfile(profile.source, offsets[i][0], offsets[i][1], 100)));
                try { assertEquals(colors[i], result.getPixel(150, 90)); }
                finally { result.recycle(); }
            }
            for (float scale : new float[]{20, 50, 92, 100, 125.5f, 300}) {
                Bitmap result = decode(HudMapImage.profileCropPng(source, profile.withScale(scale)));
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

    @Test
    public void nativePayloadIsLengthDelimitedBase64AndEmptyPayloadClears() {
        byte[] png = new byte[]{0, 1, 2};
        assertArrayEquals(new byte[]{0x0a, 0x04, 'A', 'A', 'E', 'C'},
                HudMapImage.nativePayload(png));
        assertArrayEquals(new byte[]{0x0a, 0x00}, HudMapImage.nativePayload(null));
        assertArrayEquals(new byte[]{0x0a, 0x00}, HudMapImage.nativePayload(new byte[0]));

        byte[] largePayload = HudMapImage.nativePayload(new byte[256]);
        assertEquals(0x0a, largePayload[0] & 0xff);
        int encodedLength = (largePayload[1] & 0x7f) | ((largePayload[2] & 0x7f) << 7);
        assertEquals(Base64.getEncoder().encodeToString(new byte[256]),
                new String(largePayload, 3, encodedLength, StandardCharsets.US_ASCII));
    }

    private static Bitmap decode(byte[] png) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull(bitmap);
        return bitmap;
    }
}
