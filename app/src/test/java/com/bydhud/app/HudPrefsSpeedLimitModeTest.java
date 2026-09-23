package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class HudPrefsSpeedLimitModeTest {
    @Test
    public void nativeModesNormalizeToTheirSupportedRanges() {
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER,
                HudPrefs.normalizeNativeSpeedLimitClearMode(-1));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_AT_NAVIGATION_END,
                HudPrefs.normalizeNativeSpeedLimitClearMode(3));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER,
                HudPrefs.normalizeNativeSpeedLimitClearMode(4));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_OFF,
                HudPrefs.normalizeNativeSpeedLimitFallbackMode(-1));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_COMPOSITE,
                HudPrefs.normalizeNativeSpeedLimitFallbackMode(5));
    }

    @Test
    public void clearingRetainsStableIdsAndNormalizesPerPrimaryMode() {
        assertEquals(0, HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER);
        assertEquals(1, HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NO_NAV_DATA);
        assertEquals(2, HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_ALWAYS);
        assertEquals(3, HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_AT_NAVIGATION_END);

        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_AT_NAVIGATION_END,
                HudPrefs.normalizeNativeSpeedLimitClearMode(
                        HudPrefs.SPEED_LIMIT_NATIVE,
                        HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_AT_NAVIGATION_END));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER,
                HudPrefs.normalizeNativeSpeedLimitClearMode(
                        HudPrefs.SPEED_LIMIT_NATIVE,
                        HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_ALWAYS));
        // Legacy No nav. data and newly invalid choices safely migrate to Never.
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER,
                HudPrefs.normalizeNativeSpeedLimitClearMode(
                        HudPrefs.SPEED_LIMIT_OFF,
                        HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NO_NAV_DATA));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER,
                HudPrefs.normalizeNativeSpeedLimitClearMode(
                        HudPrefs.SPEED_LIMIT_OFF,
                        HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_AT_NAVIGATION_END));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_ALWAYS,
                HudPrefs.normalizeNativeSpeedLimitClearMode(
                        HudPrefs.SPEED_LIMIT_OFF,
                        HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_ALWAYS));
    }

    @Test
    public void clearingUiOrderAndAvailabilityMapToStableIds() {
        int[] modesInUiOrder = {
                HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER,
                HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_AT_NAVIGATION_END,
                HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NO_NAV_DATA,
                HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_ALWAYS
        };
        for (int index = 0; index < modesInUiOrder.length; index++) {
            assertEquals(index, HudPrefs.nativeSpeedLimitClearModeUiIndex(modesInUiOrder[index]));
        }

        int[] nativeChoices = {0, 1, 2, 3};
        boolean[] nativeEnabled = {true, true, false, false};
        int[] otherChoices = {0, 1, 2, 3};
        boolean[] otherEnabled = {true, false, false, true};
        for (int index = 0; index < nativeChoices.length; index++) {
            assertEquals(nativeEnabled[index], HudPrefs.isNativeSpeedLimitClearModeUiOptionEnabled(
                    HudPrefs.SPEED_LIMIT_NATIVE, nativeChoices[index]));
            assertEquals(otherEnabled[index], HudPrefs.isNativeSpeedLimitClearModeUiOptionEnabled(
                    HudPrefs.SPEED_LIMIT_OFF, otherChoices[index]));
        }
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_AT_NAVIGATION_END,
                HudPrefs.nativeSpeedLimitClearModeFromUiIndex(HudPrefs.SPEED_LIMIT_NATIVE, 1));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_ALWAYS,
                HudPrefs.nativeSpeedLimitClearModeFromUiIndex(HudPrefs.SPEED_LIMIT_OFF, 3));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER,
                HudPrefs.nativeSpeedLimitClearModeFromUiIndex(HudPrefs.SPEED_LIMIT_NATIVE, 3));
        assertEquals(HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER,
                HudPrefs.nativeSpeedLimitClearModeFromUiIndex(HudPrefs.SPEED_LIMIT_OFF, 1));
    }

    @Test
    public void nativeAndOtherClearingChoicesUseSeparateStoredKeys() {
        String nativeKey = HudPrefs.nativeSpeedLimitClearPreferenceKey(HudPrefs.SPEED_LIMIT_NATIVE);
        assertNotEquals(nativeKey,
                HudPrefs.nativeSpeedLimitClearPreferenceKey(HudPrefs.SPEED_LIMIT_OFF));
        assertEquals(HudPrefs.nativeSpeedLimitClearPreferenceKey(HudPrefs.SPEED_LIMIT_OFF),
                HudPrefs.nativeSpeedLimitClearPreferenceKey(HudPrefs.SPEED_LIMIT_MANEUVER));
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
