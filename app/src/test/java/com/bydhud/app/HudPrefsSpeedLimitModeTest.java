package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HudPrefsSpeedLimitModeTest {
    @Test
    public void nativeModesNormalizeToTheirSupportedRanges() {
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER,
                HudPrefs.normalizeNativeSpeedLimitClearMode(-1));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_ALWAYS,
                HudPrefs.normalizeNativeSpeedLimitClearMode(3));
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
}
