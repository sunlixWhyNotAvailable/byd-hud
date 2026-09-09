package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Executable contract for the persisted multi-profile payload and uniqueness key. */
public final class SteeringTransferPreferencesTest {
    @Test
    public void migrationPreservesTheOldGestureAndSkipsUnconfiguredBindings() {
        assertEquals(SteeringTransferPreferences.PRESS_HOLD,
                SteeringTransferPreferences.migrateLegacy(306, "com.waze", "full").get(0).pressMode);
        assertEquals(304,
                SteeringTransferPreferences.migrateLegacy(312, "com.waze", "partial").get(0).keyCode);
        assertEquals(88,
                SteeringTransferPreferences.migrateLegacy(303, "com.waze", "partial").get(0).keyCode);
        assertEquals(SteeringTransferPreferences.PRESS_HOLD,
                SteeringTransferPreferences.migrateLegacy(302, "com.waze", "full").get(0).pressMode);
        assertEquals(SteeringTransferPreferences.PROFILE_FULL,
                SteeringTransferPreferences.migrateLegacy(305, "com.waze", "full").get(0).windowProfile);
        assertEquals(0, SteeringTransferPreferences.migrateLegacy(-1, "com.waze", "full").size());
        assertEquals(0, SteeringTransferPreferences.migrateLegacy(305, "", "full").size());
    }

    @Test
    public void jsonRoundTripPreservesOrderAndNormalizesValues() {
        List<SteeringTransferProfile> parsed = SteeringTransferPreferences.parseProfiles(
                SteeringTransferPreferences.serializeProfiles(Arrays.asList(
                        new SteeringTransferProfile("first", 306, "HOLD",
                                " COM.WAZE ", "FULL"),
                        new SteeringTransferProfile("second", 310, "double",
                                "App.Revanced.Android.Apps.Maps", "partial"))));

        assertEquals(2, parsed.size());
        assertEquals("first", parsed.get(0).id);
        assertEquals(305, parsed.get(0).keyCode);
        assertEquals(SteeringTransferPreferences.PRESS_HOLD, parsed.get(0).pressMode);
        assertEquals("com.waze", parsed.get(0).packageName);
        assertEquals(SteeringTransferPreferences.PROFILE_FULL, parsed.get(0).windowProfile);
        assertEquals("second", parsed.get(1).id);
    }

    @Test
    public void duplicateMeansSameCanonicalButtonAndPressModeOnly() {
        SteeringTransferProfile single = new SteeringTransferProfile(
                "single", 305, "single", "com.waze", "selected");
        SteeringTransferProfile aliasSingle = new SteeringTransferProfile(
                "alias", 306, "single", "com.example", "full");
        SteeringTransferProfile hold = new SteeringTransferProfile(
                "hold", 306, "hold", "com.example", "full");

        assertSame(single, SteeringTransferPreferences.findConflict(
                Arrays.asList(single), aliasSingle, ""));
        assertNull(SteeringTransferPreferences.findConflict(
                Arrays.asList(single), hold, ""));
        assertNull(SteeringTransferPreferences.findConflict(
                Arrays.asList(single), aliasSingle, "single"));

        SteeringTransferProfile previous = new SteeringTransferProfile(
                "previous", 88, "hold", "com.waze", "selected");
        SteeringTransferProfile previousAlias = new SteeringTransferProfile(
                "previous-alias", 303, "hold", "com.example", "full");
        assertSame(previous, SteeringTransferPreferences.findConflict(
                Arrays.asList(previous), previousAlias, ""));
    }

    @Test
    public void mediaAliasJsonKeepsIdsAndPressModesWhileCanonicalizingKeys() {
        List<SteeringTransferProfile> parsed = SteeringTransferPreferences.parseProfiles(
                "[{\"id\":\"previous-single\",\"keyCode\":303,\"pressMode\":\"single\","
                        + "\"packageName\":\"com.waze\"},"
                        + "{\"id\":\"next-double\",\"keyCode\":302,\"pressMode\":\"double\","
                        + "\"packageName\":\"com.example\"}]");

        assertEquals(2, parsed.size());
        assertEquals("previous-single", parsed.get(0).id);
        assertEquals(88, parsed.get(0).keyCode);
        assertEquals(SteeringTransferPreferences.PRESS_SINGLE, parsed.get(0).pressMode);
        assertEquals("next-double", parsed.get(1).id);
        assertEquals(87, parsed.get(1).keyCode);
        assertEquals(SteeringTransferPreferences.PRESS_DOUBLE, parsed.get(1).pressMode);
    }

    @Test
    public void malformedAndDuplicateJsonCannotCreateInvalidProfiles() {
        assertEquals(0, SteeringTransferPreferences.parseProfiles("not-json").size());
        List<SteeringTransferProfile> parsed = SteeringTransferPreferences.parseProfiles(
                "[{\"id\":\"a\",\"keyCode\":305,\"pressMode\":\"single\","
                        + "\"packageName\":\"com.waze\"},"
                        + "{\"id\":\"b\",\"keyCode\":306,\"pressMode\":\"single\","
                        + "\"packageName\":\"com.example\"},"
                        + "{\"id\":\"bad\",\"keyCode\":-1,\"packageName\":\"\"}]");
        assertEquals(1, parsed.size());
        assertEquals("a", parsed.get(0).id);
    }
}
