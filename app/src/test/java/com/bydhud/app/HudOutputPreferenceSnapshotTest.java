package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class HudOutputPreferenceSnapshotTest {
    @Test
    public void compactSnapshotIncludesEveryOutputControlInStableOrder() {
        DirectTbtPayload.Options options = options();

        HudOutputPreferenceSnapshot snapshot =
                HudOutputPreferenceSnapshot.from(
                        options, true, HudPrefs.TRANSLITERATION_UNIVERSAL);

        assertEquals(
                "png=1 native=0 lanes=1 distance=0 street=1 textDirection=0"
                        + " textTransliteration=2"
                        + " clampSmallDistance=1 wazeAlerts=1 routeMetrics=2 eta=1"
                        + " remainingTime=0 remainingDistance=1 speedLimitMode=4"
                        + " nativeSpeedFallback=0"
                        + " speedFreeFallback=2 speedOverlaySeconds=7 speedPlacement=3"
                        + " speedManeuverSize=80 speedLaneSize=30"
                        + " etaField=0 warningField=0 etaStreetFormat=0 etaLanguage=en"
                        + " etaWaitForFullText=1"
                        + " etaColors=FFFFFFFF/FFFFFFFF/FFFFFFFF warningColor=FFFFFF00",
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
        HudOutputPreferenceSnapshot ukrainian =
                HudOutputPreferenceSnapshot.from(
                        options(), true, HudPrefs.TRANSLITERATION_UKRAINIAN);

        assertEquals(enabled, enabledAgain);
        assertEquals(enabled.hashCode(), enabledAgain.hashCode());
        assertNotEquals(enabled, disabled);
        assertNotEquals(enabled, ukrainian);
    }

    @Test
    public void equalityChangesWithWaitForFullTextRuntimeOption() {
        DirectTbtPayload.Options enabled = options().withPresentation(
                new DirectTbtPayload.Presentation(0, 0, 0, false,
                        -1, -1, -1, 0xffffff00, true));
        DirectTbtPayload.Options disabled = options().withPresentation(
                new DirectTbtPayload.Presentation(0, 0, 0, false,
                        -1, -1, -1, 0xffffff00, false));

        assertNotEquals(HudOutputPreferenceSnapshot.from(enabled, true),
                HudOutputPreferenceSnapshot.from(disabled, true));
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
