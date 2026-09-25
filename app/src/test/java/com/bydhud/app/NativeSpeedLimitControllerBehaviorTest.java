package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        NativeSpeedLimitTestSupport.InstrumentProxy.class,
        NativeSpeedLimitTestSupport.SomeIpClient.class,
        NativeSpeedLimitTestSupport.TxLog.class,
        NativeSpeedLimitTestSupport.EventsLog.class,
        NativeSpeedLimitTestSupport.DebugWriter.class
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeSpeedLimitControllerBehaviorTest {
    private static final String WAZE = "com.waze";

    private Context context;
    private NativeSpeedLimitController controller;
    private final List<String> bitmapChanges = new ArrayList<>();

    @Before public void setUp() {
        NativeSpeedLimitTestSupport.reset();
        context = RuntimeEnvironment.getApplication();
        NativeSpeedLimitTestSupport.resetPreferences(context);
        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_NATIVE);
        HudPrefs.setNativeSpeedLimitFallbackMode(context,
                HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_LANES);
        DirectSpeedLimitStore.update(WAZE, 60, 60, "km/h", 1L);
        controller = new NativeSpeedLimitController(context,
                new Handler(Looper.getMainLooper()), () -> true, bitmapChanges::add);
    }

    @After public void tearDown() {
        controller.stop("test-cleanup");
        NativeSpeedLimitTestSupport.idleMainLooper();
        DirectSpeedLimitStore.clear(WAZE);
    }

    @Test public void failedNativeWritePublishesConfiguredBitmapAndReadbackRemovesIt() {
        NativeSpeedLimitTestSupport.raw = 12;
        NativeSpeedLimitTestSupport.failOperation = NativeSpeedLimitEngine.ROAD;

        controller.refresh(WAZE, 1L, 60);
        NativeSpeedLimitTestSupport.idleMainLooper();

        assertEquals(HudPrefs.SPEED_LIMIT_LANES,
                NativeSpeedLimitController.outputOptions(context, WAZE).speedLimitMode);
        assertTrue(NativeSpeedLimitTestSupport.EVENTS.contains("native:1=7"));

        NativeSpeedLimitTestSupport.raw = 13;
        NativeSpeedLimitTestSupport.failOperation = -1;
        NativeSpeedLimitTestSupport.idleMainLooperFor(NativeSpeedLimitEngine.READ_MS);

        assertEquals(HudPrefs.SPEED_LIMIT_OFF,
                NativeSpeedLimitController.outputOptions(context, WAZE).speedLimitMode);
        assertTrue(bitmapChanges.contains(WAZE));
    }

    @Test public void ownerLimitChangeBeforeNextWriteFencesTheOldNativeTarget() {
        NativeSpeedLimitTestSupport.raw = 12;
        NativeSpeedLimitTestSupport.deferNextOperation = NativeSpeedLimitEngine.ROAD;
        controller.refresh(WAZE, 1L, 60);
        NativeSpeedLimitTestSupport.idleMainLooper();

        NativeSpeedLimitTestSupport.Pending stale = NativeSpeedLimitTestSupport.pending;
        assertNotNull(stale);
        assertEquals(NativeSpeedLimitEngine.ROAD, stale.operation);

        DirectSpeedLimitStore.update(WAZE, 55, 55, "km/h", 2L);
        assertFalse(stale.current.getAsBoolean());
        NativeSpeedLimitTestSupport.releasePending(true);
        NativeSpeedLimitTestSupport.idleMainLooperFor(NativeSpeedLimitEngine.ROAD_GAP_MS);

        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=60"));
        assertEquals(HudPrefs.SPEED_LIMIT_OFF,
                NativeSpeedLimitController.outputOptions(context, WAZE).speedLimitMode);
    }

    @Test public void ownerSessionReplacementCannotResumeTheOldNativeTarget() {
        NativeSpeedLimitTestSupport.raw = 12;
        NativeSpeedLimitTestSupport.deferNextOperation = NativeSpeedLimitEngine.ROAD;
        controller.refresh(WAZE, 1L, 60);
        NativeSpeedLimitTestSupport.idleMainLooper();

        NativeSpeedLimitTestSupport.Pending stale = NativeSpeedLimitTestSupport.pending;
        assertNotNull(stale);
        assertEquals(NativeSpeedLimitEngine.ROAD, stale.operation);

        NativeSpeedLimitTestSupport.raw = 13;
        controller.refresh("manual", 2L, 55);
        NativeSpeedLimitTestSupport.idleMainLooper();
        assertFalse(stale.current.getAsBoolean());
        NativeSpeedLimitTestSupport.releasePending(true);
        NativeSpeedLimitTestSupport.idleMainLooperFor(300L);

        assertTrue(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=55"));
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=60"));
    }

    @Test public void changedOutputModeFencesTheOldNativeTarget() {
        NativeSpeedLimitTestSupport.raw = 12;
        NativeSpeedLimitTestSupport.deferNextOperation = NativeSpeedLimitEngine.ROAD;
        controller.refresh(WAZE, 1L, 60);
        NativeSpeedLimitTestSupport.idleMainLooper();

        NativeSpeedLimitTestSupport.Pending stale = NativeSpeedLimitTestSupport.pending;
        assertNotNull(stale);
        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_OFF);
        NativeSpeedLimitTestSupport.releasePending(true);
        NativeSpeedLimitTestSupport.idleMainLooperFor(NativeSpeedLimitEngine.ROAD_GAP_MS);

        assertFalse(stale.current.getAsBoolean());
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=60"));
        assertEquals(HudPrefs.SPEED_LIMIT_OFF,
                NativeSpeedLimitController.outputOptions(context, WAZE).speedLimitMode);
    }
    @Test public void restoredLegacyClearSelectionsAreIgnoredInEveryOutputMode() {
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                "byd_hud_prefs", Context.MODE_PRIVATE);
        for (int mode = 0; mode <= 5; mode++) {
            for (int legacy = 1; legacy <= 3; legacy++) {
                controller.stop("next-case");
                NativeSpeedLimitTestSupport.EVENTS.clear();
                NativeSpeedLimitTestSupport.raw = 12;
                // Simulate existing or restored preferences, including invalid old types.
                prefs.edit().putInt("speed_limit_native_clear_mode", legacy)
                        .putString("speed_limit_native_clear_mode_native", "legacy:" + legacy).commit();
                HudPrefs.setSpeedLimitMode(context, mode);
                controller.refresh(WAZE, legacy, 60);
                NativeSpeedLimitTestSupport.idleMainLooperFor(500L);
                if (mode == HudPrefs.SPEED_LIMIT_NATIVE) {
                    assertTrue(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=60"));
                } else {
                    assertFalse(NativeSpeedLimitTestSupport.EVENTS.stream()
                            .anyMatch(event -> event.startsWith("native:")));
                }
                int ioBeforeLoss = (int) NativeSpeedLimitTestSupport.EVENTS.stream()
                        .filter(event -> event.startsWith("native:")).count();
                controller.refresh(WAZE, legacy, 0);
                NativeSpeedLimitTestSupport.idleMainLooperFor(20_000L);
                controller.stop("route-end");
                assertEquals(ioBeforeLoss, NativeSpeedLimitTestSupport.EVENTS.stream()
                        .filter(event -> event.startsWith("native:")).count());
                assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=1"));
                assertEquals(mode, HudPrefs.speedLimitMode(context));
                assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_LANES,
                        HudPrefs.getNativeSpeedLimitFallbackMode(context));
                assertEquals(legacy, prefs.getInt("speed_limit_native_clear_mode", -1));
                assertEquals("legacy:" + legacy, prefs.getString("speed_limit_native_clear_mode_native", ""));
                assertFalse(HudOutputPreferenceSnapshot.capture(context).compact().contains("nativeSpeedClear"));
            }
        }
    }

}
