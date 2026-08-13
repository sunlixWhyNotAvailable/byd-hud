package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class HudOutputPreferenceSnapshotTest {
    @Test
    public void compactSnapshotIncludesEveryOutputControlInStableOrder() {
        DirectTbtPayload.Options options = options();

        HudOutputPreferenceSnapshot snapshot =
                HudOutputPreferenceSnapshot.from(options, true);

        assertEquals(
                "png=1 native=0 lanes=1 distance=0 street=1 textDirection=0"
                        + " clampSmallDistance=1 wazeAlerts=1 routeMetrics=2 eta=1"
                        + " remainingTime=0 remainingDistance=1 speedLimitMode=4"
                        + " speedFreeFallback=2 speedOverlaySeconds=7 speedPlacement=3"
                        + " speedManeuverSize=80 speedLaneSize=30",
                snapshot.compact());
    }

    @Test
    public void equalityChangesWhenAnUncachedAlertPreferenceChanges() {
        HudOutputPreferenceSnapshot enabled =
                HudOutputPreferenceSnapshot.from(options(), true);
        HudOutputPreferenceSnapshot enabledAgain =
                HudOutputPreferenceSnapshot.from(options(), true);
        HudOutputPreferenceSnapshot disabled =
                HudOutputPreferenceSnapshot.from(options(), false);

        assertEquals(enabled, enabledAgain);
        assertEquals(enabled.hashCode(), enabledAgain.hashCode());
        assertNotEquals(enabled, disabled);
    }

    private static DirectTbtPayload.Options options() {
        return new DirectTbtPayload.Options(
                true, false, true, false, true, false, true,
                HudPrefs.ROUTE_METRICS_WHOLE_ROUTE,
                true, false, true,
                HudPrefs.SPEED_LIMIT_COMPOSITE,
                HudPrefs.SPEED_LIMIT_FALLBACK_LANES,
                7,
                HudPrefs.SPEED_LIMIT_COMPOSITE_FREE_OR_LANES,
                80,
                30,
                new byte[0]);
    }
}
