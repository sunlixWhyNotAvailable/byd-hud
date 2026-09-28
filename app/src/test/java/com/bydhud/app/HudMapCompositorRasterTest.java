package com.bydhud.app;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class HudMapCompositorRasterTest {
    @Test
    public void calibrationIsExplicitExperimentalOnlyAndNavigationRendersAboveIt() throws Exception {
        byte[] maneuver = solidPng(100, 10, Color.BLUE);
        byte[] lanes = solidPng(100, 10, Color.GREEN);
        HudMapSettings experimental = HudMapSettings.defaults()
                .withMode(HudMapSettings.EXPERIMENTAL);
        HudExperimentalCompositor compositor = new HudExperimentalCompositor();

        HudExperimentalCompositor.Result mapOnly = compositor.compose(input(
                null, null, experimental, true));
        Bitmap mapUpper = decode(mapOnly.f8Png());
        Bitmap mapLower = decode(mapOnly.f7Png());
        assertEquals(720, mapUpper.getWidth());
        assertEquals(48, mapUpper.getHeight());
        assertEquals(960, mapLower.getWidth());
        assertEquals(48, mapLower.getHeight());
        assertTrue(countColor(mapUpper, Color.RED) > 0);
        assertTrue(countColor(mapLower, Color.RED) > 0);
        assertTrue(countColor(mapUpper, Color.YELLOW) > 0);
        assertTrue(countColor(mapLower, Color.YELLOW) > 0);

        HudExperimentalCompositor.Result calibrated = compositor.compose(input(
                maneuver, lanes, experimental, true));
        Bitmap upper = decode(calibrated.f8Png());
        Bitmap lower = decode(calibrated.f7Png());
        assertEquals(Color.BLUE, upper.getPixel(300, 20));
        assertEquals(Color.GREEN, lower.getPixel(480, 24));

        HudExperimentalCompositor.Result ordinaryRoute = compositor.compose(input(
                maneuver, lanes, experimental, false));
        Bitmap routeUpper = decode(ordinaryRoute.f8Png());
        Bitmap routeLower = decode(ordinaryRoute.f7Png());
        assertTrue(countColor(routeUpper, Color.BLUE) > 0);
        assertTrue(countColor(routeLower, Color.GREEN) > 0);
        assertEquals(0, countColor(routeUpper, Color.YELLOW));
        assertEquals(0, countColor(routeLower, Color.YELLOW));

        for (int mode : new int[]{HudMapSettings.OFF, HudMapSettings.NATIVE}) {
            HudExperimentalCompositor.Result disabled = new HudExperimentalCompositor().compose(
                    input(null, null, experimental.withMode(mode), true));
            assertTrue(disabled.isEmpty());
        }
        assertFalse(HudExperimentalCompositor.hasMeaningfulF8Content(input(
                null, null, experimental, false)));
    }

    private static HudExperimentalCompositor.Inputs input(byte[] maneuver, byte[] lanes,
                                                           HudMapSettings settings,
                                                           boolean calibration) {
        return new HudExperimentalCompositor.Inputs("", "", "", maneuver, lanes,
                null, "", HudExperimentalCompositor.Colors.defaults(), settings, calibration);
    }

    private static byte[] solidPng(int width, int height, int color) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint();
            paint.setColor(color);
            canvas.drawColor(Color.TRANSPARENT);
            canvas.drawRect(0, 0, width, height, paint);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap decode(byte[] png) {
        return BitmapFactory.decodeByteArray(png, 0, png.length);
    }

    private static int countColor(Bitmap bitmap, int color) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == color) count++;
            }
        }
        return count;
    }
}
