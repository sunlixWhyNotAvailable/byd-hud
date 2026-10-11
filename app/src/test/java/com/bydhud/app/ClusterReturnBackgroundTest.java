package com.bydhud.app;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.Application;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class,
        shadows = {NativeSpeedLimitTestSupport.EventsLog.class, ClusterReturnBackgroundTest.RawEvents.class})
public final class ClusterReturnBackgroundTest {
    private ClusterProjectionService service;
    private VirtualDisplay display;
    private Surface surface;
    private SurfaceTexture texture;
    private FrameLayout root;
    private View black;

    @Before public void setup() {
        NativeSpeedLimitTestSupport.reset();
        service = Robolectric.buildService(ClusterProjectionService.class).get();
        service.getSharedPreferences("byd_hud_prefs", 0).edit().clear().commit();
        WindowManager manager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        root = new FrameLayout(service);
        manager.addView(root, params());
        texture = new SurfaceTexture(0);
        surface = new Surface(texture);
        display = ((DisplayManager) service.getSystemService(Context.DISPLAY_SERVICE))
                .createVirtualDisplay("test-projection", 640, 240, 160, surface, 0);
        black = new View(service);
        manager.addView(black, params());
        black.setVisibility(View.GONE);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("overlay attached", root.isAttachedToWindow());
        assertTrue("projection surface valid", surface.isValid());
        assertTrue("virtual display valid", display.getDisplay().isValid());
        set("overlayRoot", root);
        set("overlayWindowManager", manager);
        set("blackWindowView", black);
        set("blackWindowManager", manager);
        set("blackWindowDisplayGeneration", 1);
        set("projectionSurface", surface);
        set("virtualDisplay", display);
        set("virtualDisplayGeneration", 1);
        set("projectionBufferWidth", 640);
        set("projectionBufferHeight", 240);
        own(10, 7L);
    }

    @After public void cleanup() {
        ReflectionHelpers.callInstanceMethod(service, "releaseProjection", from(String.class, "test-end"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        surface.release();
        texture.release();
    }

    @Test public void defaultOffHidesIdleWithoutReleasingAndStaleMoveCannotRevealIt() {
        assertFalse(HudPrefs.isDashboardKeepBlackBackgroundEnabled(service));
        assertEquals("", idle(10, 7L));
        assertEquals(0f, ((WindowManager.LayoutParams) root.getLayoutParams()).alpha, 0f);
        assertSame(display, ReflectionHelpers.getField(service, "virtualDisplay"));
        assertTrue(display.getDisplay().isValid());
        assertTrue(surface.isValid());
        own(12, 8L);
        assertFalse(prepare(10, 7L).isEmpty());
        assertEquals(0f, ((WindowManager.LayoutParams) root.getLayoutParams()).alpha, 0f);
        assertEquals("", prepare(12, 8L));
        assertEquals(1f, ((WindowManager.LayoutParams) root.getLayoutParams()).alpha, 0f);
        assertSame(display, ReflectionHelpers.getField(service, "virtualDisplay"));
    }

    @Test public void enabledRetainsBlackAndTheNextMoveRemovesItWithTheSameDisplay() throws Exception {
        HudPrefs.setDashboardKeepBlackBackgroundEnabled(service, true);
        assertTrue(VehicleConfigurationDiagnostics.options(service)
                .getBoolean("dashboardKeepBlackBackground"));
        assertFalse(idle(9, 7L).isEmpty());
        assertEquals(View.GONE, black.getVisibility());
        assertEquals("", idle(10, 7L));
        assertEquals(View.VISIBLE, black.getVisibility());
        assertEquals(1f, ((WindowManager.LayoutParams) root.getLayoutParams()).alpha, 0f);
        own(12, 8L);
        assertEquals("", prepare(12, 8L));
        assertEquals(View.GONE, black.getVisibility());
        assertSame(display, ReflectionHelpers.getField(service, "virtualDisplay"));
    }

    @Test public void failedBlackWindowHidesAndReleasesOutputInsteadOfKeepingAnOldFrame() {
        HudPrefs.setDashboardKeepBlackBackgroundEnabled(service, true);
        WindowManager manager = ReflectionHelpers.getField(service, "blackWindowManager");
        WindowManager failingManager = (WindowManager) Proxy.newProxyInstance(
                WindowManager.class.getClassLoader(), new Class<?>[]{WindowManager.class},
                (proxy, method, args) -> {
                    if ("removeViewImmediate".equals(method.getName())) {
                        throw new IllegalStateException("test black-window removal failure");
                    }
                    return method.invoke(manager, args);
                });
        set("blackWindowManager", failingManager);
        set("blackWindowDisplayGeneration", 0);
        try {
            assertTrue(idle(10, 7L).contains("black idle remove failed"));
            assertEquals(0f, ((WindowManager.LayoutParams) root.getLayoutParams()).alpha, 0f);
            assertNull(ReflectionHelpers.getField(service, "virtualDisplay"));
            assertFalse(display.getDisplay().isValid());
        } finally {
            manager.removeViewImmediate(black);
        }
    }

    private void own(int generation, long token) {
        set("projectionRequested", true);
        set("projectedPackage", "com.waze");
        set("projectionGeneration", generation);
        set("projectionOwnerToken", token);
        set("projectionPlacementReady", true);
    }

    private String idle(int generation, long token) {
        return ReflectionHelpers.callInstanceMethod(service, "retainProjectionIdle",
                from(String.class, "com.waze"), from(int.class, generation),
                from(long.class, token), from(String.class, "test-return"));
    }

    private String prepare(int generation, long token) {
        return ReflectionHelpers.callInstanceMethod(service, "hideBlackWindowForMoveOnMain",
                from(String.class, "com.waze"), from(int.class, 1), from(int.class, 0),
                from(int.class, display.getDisplay().getDisplayId()), from(int.class, 1),
                from(int.class, generation), from(long.class, token),
                from(BooleanSupplier.class, () -> true));
    }

    private void set(String name, Object value) { ReflectionHelpers.setField(service, name, value); }

    private static WindowManager.LayoutParams params() {
        return new WindowManager.LayoutParams(640, 240,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE);
    }

    @Implements(NavCaptureStore.class)
    public static final class RawEvents {
        @Implementation protected static void rawEvent(
                Context context, String channel, String packageName, String payload) { }
    }
}
