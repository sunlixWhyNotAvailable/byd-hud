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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class HudMapCompositorRasterTest {

    @Test
    public void changedPlanesShareOneRenderAndUnchangedPlaneStaysCached() {
        int[] renders = {0};
        boolean[] failNextRender = {false};
        byte[] upperPng = {1};
        byte[] lowerPng = {2};
        HudExperimentalCompositor compositor = new HudExperimentalCompositor(input -> {
            renders[0]++;
            assertTrue(input.maneuverPng.length > 0);
            assertTrue(input.lanePng.length > 0);
            if (failNextRender[0]) {
                failNextRender[0] = false;
                return HudExperimentalCompositor.Result.empty();
            }
            return new HudExperimentalCompositor.Result(input.maneuverPng.clone(), input.lanePng.clone());
        });
        HudMapSettings settings = HudMapSettings.defaults();

        HudExperimentalCompositor.Inputs initial = input(new byte[]{1}, new byte[]{2},
                settings, false, null);
        HudExperimentalCompositor.Result first = compositor.compose(initial);
        assertEquals(1, renders[0]);
        assertArrayEquals(upperPng, first.f8Png());
        assertArrayEquals(lowerPng, first.f7Png());
        compositor.compose(initial);
        assertEquals(1, renders[0]);

        HudExperimentalCompositor.Result upperChanged = compositor.compose(input(
                new byte[]{3}, new byte[]{2}, settings, false, null));
        assertEquals(2, renders[0]);
        assertArrayEquals(new byte[]{3}, upperChanged.f8Png());
        assertArrayEquals(lowerPng, upperChanged.f7Png());
        HudExperimentalCompositor.Result lowerChanged = compositor.compose(input(
                new byte[]{3}, new byte[]{4}, settings, false, null));
        assertEquals(3, renders[0]);
        assertArrayEquals(new byte[]{3}, lowerChanged.f8Png());
        assertArrayEquals(new byte[]{4}, lowerChanged.f7Png());

        HudExperimentalCompositor.Inputs nextLower = input(
                new byte[]{3}, new byte[]{5}, settings, false, null);
        failNextRender[0] = true;
        HudExperimentalCompositor.Result failed = compositor.compose(nextLower);
        assertEquals(4, renders[0]);
        assertArrayEquals(new byte[]{3}, failed.f8Png());
        assertNull(failed.f7Png());
        HudExperimentalCompositor.Result recovered = compositor.compose(nextLower);
        assertEquals(5, renders[0]);
        assertArrayEquals(new byte[]{3}, recovered.f8Png());
        assertArrayEquals(new byte[]{5}, recovered.f7Png());
        compositor.compose(nextLower);
        assertEquals(5, renders[0]);
    }

    @Test
    public void liveMapUsesSharedSeamAndLaneArtStaysAboveIt() throws Exception {
        HudMapSettings experimental = HudMapSettings.defaults()
                .withMode(HudMapSettings.EXPERIMENTAL);
        int split = new HudMapGeometry(experimental.geometry()).splitRow();
        byte[] mapPng = twoToneMap(split);
        HudExperimentalCompositor compositor = new HudExperimentalCompositor();

        HudExperimentalCompositor.Result mapOnly = compositor.compose(input(
                null, null, experimental, false, mapPng));
        Bitmap upper = decode(mapOnly.f8Png());
        Bitmap lower = decode(mapOnly.f7Png());
        assertTrue(countColor(upper, Color.RED) > 0);
        assertEquals(0, countColor(upper, Color.BLUE));
        assertTrue(countColor(lower, Color.BLUE) > 0);
        assertEquals(0, countColor(lower, Color.RED));

        HudMapSettings laneOverMap = experimental.withValue(
                HudMapSettings.CONTROL_LANE_Y, -50);
        HudExperimentalCompositor.Result layered = new HudExperimentalCompositor().compose(input(
                solidPng(100, 10, Color.GREEN), solidPng(100, 10, Color.GREEN),
                laneOverMap, false, mapPng));
        Bitmap layeredLower = decode(layered.f7Png());
        assertEquals(Color.GREEN, layeredLower.getPixel(480, 15));

        HudExperimentalCompositor.Result calibration = new HudExperimentalCompositor().compose(input(
                null, null, experimental, true, solidPng(300, 180, Color.GREEN)));
        assertEquals(0, countColor(decode(calibration.f8Png()), Color.GREEN));
        assertEquals(0, countColor(decode(calibration.f7Png()), Color.GREEN));
    }

    private static HudExperimentalCompositor.Inputs input(byte[] maneuver, byte[] lanes,
                                                           HudMapSettings settings,
                                                           boolean calibration) {
        return input(maneuver, lanes, settings, calibration, null);
    }

    private static HudExperimentalCompositor.Inputs input(byte[] maneuver, byte[] lanes,
                                                           HudMapSettings settings,
                                                           boolean calibration, byte[] mapPng) {
        return new HudExperimentalCompositor.Inputs("", "", "", maneuver, lanes,
                null, "", HudExperimentalCompositor.Colors.defaults(), settings, calibration,
                mapPng);
    }

    private static byte[] twoToneMap(int split) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            canvas.drawRect(0, 0, 300, split - 2, paint);
            paint.setColor(Color.BLUE);
            canvas.drawRect(0, split + 2, 300, 180, paint);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
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
