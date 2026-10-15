package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ManeuverCompatibilityProfileTest {
    @Test public void baiduProfileMapsConfirmedAmapSemanticsToMatchingIds() {
        int[][] cases = {
                {2, 1007, 7}, {3, 1003, 3}, {4, 1008, 8}, {5, 1002, 2},
                {6, 1006, 6}, {7, 1004, 4}, {8, 1005, 5}, {9, 1001, 1},
                {11, 1009, 9}, {17, 1009, 9}, {12, 1010, 10}, {18, 1010, 10},
                {14, 1031, 31}, {15, 1024, 24}, {19, 1111, 1111},
                {21, 1075, 96}, {25, 1075, 96}, {22, 1071, 92}, {26, 1071, 92},
                {23, 1069, 90}, {27, 1069, 90}, {24, 1073, 94}, {28, 1073, 94}
        };

        for (int[] value : cases) {
            ManeuverCompatibilityProfile.Mapping mapping = ManeuverCompatibilityProfile.map(
                    ManeuverCompatibilityProfile.BAIDU, value[0], 3, 49);
            assertEquals("Instrument icon for AMap " + value[0], value[1],
                    mapping.instrumentIcon);
            assertEquals("broadcast icon for AMap " + value[0], value[2],
                    mapping.broadcastIcon);
            assertEquals("roundabout ordinal is not forwarded", 0,
                    mapping.broadcastRoundaboutExit);
            assertTrue("supported AMap " + value[0], mapping.supported);
            assertTrue("accepted Instrument icon " + mapping.instrumentIcon,
                    ManeuverCompatibilityProfile.isBaiduInstrumentIcon(
                            mapping.instrumentIcon));
        }
    }

    @Test public void amapProfilePreservesLegacyInstrumentAndBroadcastValues() {
        ManeuverCompatibilityProfile.Mapping mapping = ManeuverCompatibilityProfile.map(
                ManeuverCompatibilityProfile.AMAP, 5, 2, 4);

        assertEquals(4, mapping.instrumentIcon);
        assertEquals(5, mapping.broadcastIcon);
        assertEquals(2, mapping.broadcastRoundaboutExit);
        assertTrue(mapping.supported);
    }

    @Test public void explicitBlankAndUnknownManeuversClearOnlyTheGlyph() {
        ManeuverCompatibilityProfile.Mapping blank = ManeuverCompatibilityProfile.map(
                ManeuverCompatibilityProfile.BAIDU, 0, 0, 0);
        assertEquals(0, blank.instrumentIcon);
        assertEquals(0, blank.broadcastIcon);
        assertTrue(blank.supported);

        ManeuverCompatibilityProfile.Mapping unknown = ManeuverCompatibilityProfile.map(
                ManeuverCompatibilityProfile.BAIDU, 20, 0, 49);
        assertEquals(0, unknown.instrumentIcon);
        assertEquals(0, unknown.broadcastIcon);
        assertFalse(unknown.supported);
    }

    @Test public void extendedIconWhitelistExcludesLegacyBlankAndUnknownCodes() {
        assertTrue(ManeuverCompatibilityProfile.isBaiduInstrumentIcon(1001));
        assertTrue(ManeuverCompatibilityProfile.isBaiduInstrumentIcon(1010));
        assertTrue(ManeuverCompatibilityProfile.isBaiduInstrumentIcon(1111));
        assertFalse(ManeuverCompatibilityProfile.isBaiduInstrumentIcon(0));
        assertFalse(ManeuverCompatibilityProfile.isBaiduInstrumentIcon(49));
        assertFalse(ManeuverCompatibilityProfile.isBaiduInstrumentIcon(1011));
    }

    @Test public void ipcGuidanceValidationAcceptsOnlyLegacyOrWhitelistedIcons() {
        assertTrue(InstrumentProxyContract.validGuidance(0, -1, ""));
        assertTrue(InstrumentProxyContract.validGuidance(49, 2_000_000, "road"));
        assertTrue(InstrumentProxyContract.validGuidance(1111, 0, "road"));
        assertFalse(InstrumentProxyContract.validGuidance(1000, 0, "road"));
        assertFalse(InstrumentProxyContract.validGuidance(1011, 0, "road"));
        assertFalse(InstrumentProxyContract.validGuidance(-1, 0, "road"));
    }

    @Test public void ipcGuidanceValidationKeepsDistanceTextAndLaneBoundaries() {
        assertTrue(InstrumentProxyContract.validGuidance(1001, -1,
                repeat('r', 512), new int[] {254}, new int[] {255}));
        assertFalse(InstrumentProxyContract.validGuidance(1001, -2,
                "road", new int[0], new int[0]));
        assertFalse(InstrumentProxyContract.validGuidance(1001, 2_000_001,
                "road", new int[0], new int[0]));
        assertFalse(InstrumentProxyContract.validGuidance(1001, 0,
                repeat('r', 513), new int[0], new int[0]));
        assertFalse(InstrumentProxyContract.validGuidance(1001, 0,
                "road", new int[] {255}, new int[] {0}));
        assertFalse(InstrumentProxyContract.validGuidance(1001, 0,
                "road", new int[] {0}, new int[] {-1}));
        assertFalse(InstrumentProxyContract.validGuidance(1001, 0,
                "road", new int[] {0}, new int[0]));
    }

    @Test public void ipcGuidanceRequiresAnExactCompatibilityProfile() {
        assertTrue(InstrumentProxyContract.validCompatibilityProfile(
                ManeuverCompatibilityProfile.AMAP));
        assertTrue(InstrumentProxyContract.validCompatibilityProfile(
                ManeuverCompatibilityProfile.BAIDU));
        assertFalse(InstrumentProxyContract.validCompatibilityProfile(2));
        assertFalse(InstrumentProxyContract.validCompatibilityProfile(-1));
        assertTrue(InstrumentProxyContract.validGuidance(
                ManeuverCompatibilityProfile.BAIDU, 1001, 0, "road",
                new int[] {0}, new int[] {255}));
        assertFalse(InstrumentProxyContract.validGuidance(
                2, 1001, 0, "road", new int[0], new int[0]));
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        java.util.Arrays.fill(characters, value);
        return new String(characters);
    }
}
