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
    public void profileShiftsAndScalesTheSourceInsideTheCanonicalCrop() {
        Bitmap source = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(source);
            canvas.drawColor(Color.GREEN);
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            canvas.drawRect(0, 0, 100, 60, paint);
            paint.setColor(Color.BLUE);
            canvas.drawRect(200, 120, 300, 180, paint);

            Bitmap xLeft = decode(HudMapImage.profileCropPng(source,
                    HudMapProfile.defaults(HudMapProfile.Source.WAZE).withX(-10)));
            try {
                assertEquals(Color.TRANSPARENT, xLeft.getPixel(0, 90));
                assertEquals(Color.RED, xLeft.getPixel(30, 20));
            } finally {
                xLeft.recycle();
            }

            Bitmap xRight = decode(HudMapImage.profileCropPng(source,
                    HudMapProfile.defaults(HudMapProfile.Source.WAZE).withX(10)));
            try {
                assertEquals(Color.RED, xRight.getPixel(0, 20));
                assertEquals(Color.TRANSPARENT, xRight.getPixel(270, 90));
            } finally {
                xRight.recycle();
            }

            Bitmap yUp = decode(HudMapImage.profileCropPng(source,
                    HudMapProfile.defaults(HudMapProfile.Source.WAZE).withY(-10)));
            try {
                assertEquals(Color.RED, yUp.getPixel(50, 0));
                assertEquals(Color.TRANSPARENT, yUp.getPixel(50, 162));
            } finally {
                yUp.recycle();
            }

            Bitmap zoomedOut = decode(HudMapImage.profileCropPng(source,
                    HudMapProfile.defaults(HudMapProfile.Source.WAZE).withScale(50)));
            try {
                assertEquals(Color.TRANSPARENT, zoomedOut.getPixel(0, 0));
                assertEquals(Color.RED, zoomedOut.getPixel(77, 47));
                assertEquals(Color.GREEN, zoomedOut.getPixel(150, 90));
            } finally {
                zoomedOut.recycle();
            }
        } finally {
            source.recycle();
        }
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
