package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public final class HudPrefsSpeedLimitModeTest {
    private Context context;

    @After
    public void clearSpeedLimitPreferences() {
        if (context != null) {
            context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit()
                    .remove("speed_limit_native_delay_enabled")
                    .remove("speed_limit_mode")
                    .apply();
        }
    }

    @Test
    public void nativeModesNormalizeToTheirSupportedRanges() {
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_OFF,
                HudPrefs.normalizeNativeSpeedLimitFallbackMode(-1));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_COMPOSITE,
                HudPrefs.normalizeNativeSpeedLimitFallbackMode(5));
    }

    @Test
    public void configuredFallbackOnlySelectsBitmapModeForNativePrimary() {
        int[] fallbacks = {
                HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_OFF,
                HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_MANEUVER,
                HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_LANES,
                HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_FREE,
                HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_COMPOSITE
        };
        for (int fallback : fallbacks) {
            assertEquals(fallback, HudPrefs.effectiveSpeedLimitBitmapMode(
                    HudPrefs.SPEED_LIMIT_NATIVE, fallback));
        }

        int[] primaryBitmapModes = {
                HudPrefs.SPEED_LIMIT_OFF,
                HudPrefs.SPEED_LIMIT_MANEUVER,
                HudPrefs.SPEED_LIMIT_LANES,
                HudPrefs.SPEED_LIMIT_FREE,
                HudPrefs.SPEED_LIMIT_COMPOSITE
        };
        for (int primaryMode : primaryBitmapModes) {
            assertEquals(primaryMode, HudPrefs.effectiveSpeedLimitBitmapMode(
                    primaryMode, HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_COMPOSITE));
        }
    }

    @Test
    public void nativeDelayDefaultsOnPersistsAndNotClearedByModeChanges() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit()
                .clear().apply();
        assertTrue(HudPrefs.isNativeSpeedLimitDelayEnabled(context));

        int beforeDisable = HudPrefs.outputOptionsRevision();
        HudPrefs.setNativeSpeedLimitDelayEnabled(context, false);
        assertFalse(HudPrefs.isNativeSpeedLimitDelayEnabled(context));
        assertTrue(HudPrefs.outputOptionsRevision() > beforeDisable);

        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_OFF);
        assertFalse(HudPrefs.isNativeSpeedLimitDelayEnabled(context));

        int beforeEnable = HudPrefs.outputOptionsRevision();
        HudPrefs.setNativeSpeedLimitDelayEnabled(context, true);
        assertTrue(HudPrefs.isNativeSpeedLimitDelayEnabled(context));
        assertTrue(HudPrefs.outputOptionsRevision() > beforeEnable);
    }
}
