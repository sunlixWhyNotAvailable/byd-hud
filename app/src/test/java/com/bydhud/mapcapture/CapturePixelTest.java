package com.bydhud.mapcapture;
import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
public class CapturePixelTest {
    @Test public void missingMapsCallbackIsQuarantinedUntilCallbackWithoutOverlappingNativeRequests() throws Exception {
        Object controller = Class.forName("SnapshotControllerFixture").getConstructor().newInstance();
        java.lang.reflect.Method capture = CaptureBridge.class.getDeclaredMethod("captureMaps", android.os.Bundle.class);
        capture.setAccessible(true);
        org.robolectric.util.ReflectionHelpers.setStaticField(CaptureBridge.class, "worker", new android.os.Handler(android.os.Looper.getMainLooper()));
        org.robolectric.util.ReflectionHelpers.setStaticField(CaptureBridge.class, "app", org.robolectric.RuntimeEnvironment.getApplication());
        CaptureBridge.trackMaps(controller);
        android.os.Bundle request = new android.os.Bundle(); request.putLong("frameTimeoutMs", 100);
        capture.invoke(null, request);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(101));
        java.util.concurrent.atomic.AtomicBoolean busy = org.robolectric.util.ReflectionHelpers.getStaticField(CaptureBridge.class, "BUSY");
        assertFalse(busy.get());
        capture.invoke(null, new android.os.Bundle());
        assertEquals(1, controller.getClass().getField("calls").getInt(controller));
        controller.getClass().getMethod("respond").invoke(controller);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        capture.invoke(null, new android.os.Bundle());
        assertEquals(2, controller.getClass().getField("calls").getInt(controller));
        controller.getClass().getMethod("respond").invoke(controller);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    @Test public void bulkAndAlphaFallbackPreserveColorsAndVerticalFlip() {
        for (int edge : new int[]{320,1920}) for (int alpha : new int[]{255,128}) {
            ByteBuffer bytes = ByteBuffer.allocateDirect(16);
            bytes.put(new byte[]{(byte)255,0,0,(byte)alpha, 0,(byte)255,0,(byte)255,
                    0,0,(byte)255,(byte)255, (byte)255,(byte)255,0,(byte)255});
            Bitmap bitmap = CaptureBridge.rgbaBitmap(bytes,2,2,edge);
            assertEquals(0xff0000ff, bitmap.getPixel(0,0));
            assertEquals(0xffffff00, bitmap.getPixel(1,0));
            assertEquals((alpha<<24)|0xff0000, bitmap.getPixel(0,1));
            assertEquals(0xff00ff00, bitmap.getPixel(1,1));
            bitmap.recycle();
        }
    }
}
