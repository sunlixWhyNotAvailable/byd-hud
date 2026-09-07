package com.bydhud.app;

import org.junit.Test;
import java.util.Collections;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class HudEtaTextTest {
    @Test public void everyMetricMaskBothLocalesAndBothStreetFormats() {
        DirectTbtFrame frame = frame(new DirectTbtFrame.TravelMetrics(
                3_600_000L, 7200, 1500, 8400));
        for (boolean ua : new boolean[]{false, true}) {
            for (int mask = 0; mask < 8; mask++) {
                HudEtaText text = HudEtaText.from(frame, options(mask, ua, 1));
                assertEquals((mask & 1) == 0 ? "" : "03:00", text.arrival);
                assertEquals((mask & 2) == 0 ? "" : ua ? "25 хв" : "25 min", text.duration);
                assertEquals((mask & 4) == 0 ? "" : ua ? "8,4 км" : "8.4 km", text.remainingDistance);
                assertEquals(text.joined(), text.street("Road", true));
                assertEquals(mask == 0 ? "Road" : "[" + text.joined() + "] Road",
                        text.street("Road", false));
                assertFalse(text.joined().contains("ETA"));
                assertFalse(text.joined().startsWith(" | "));
                assertFalse(text.joined().endsWith(" | "));
            }
        }
    }

    @Test public void unavailableMetricsAreOmittedRatherThanZeroAndOffRestoresRoad() {
        DirectTbtFrame frame = frame(new DirectTbtFrame.TravelMetrics(-1, -1, -1));
        assertEquals("", HudEtaText.from(frame, options(7, false, 1)).joined());
        assertEquals("", DirectTbtPayload.describe(frame, options(7, false, 1)
                .withPresentation(presentation(false, 1))).displayText());
        assertEquals("Road", DirectTbtPayload.describe(frame, options(7, false, 0)
                .withPresentation(presentation(false, 1))).displayText());
    }

    @Test public void arrivalWithoutOffsetUsesCurrentDeviceZone() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:00"));
            assertEquals("06:00", HudEtaText.from(frame(
                    new DirectTbtFrame.TravelMetrics(3_600_000L, -1, -1)),
                    options(1, false, 1)).arrival);
        } finally {
            TimeZone.setDefault(before);
        }
    }

    @Test public void durationRoundingAndHoursNeverOverflow() {
        assertEquals("", HudEtaText.duration(-1, false));
        assertEquals("0 min", HudEtaText.duration(0, false));
        assertEquals("1 min", HudEtaText.duration(1, false));
        assertEquals("1 h 0 min", HudEtaText.duration(3599, false));
        assertEquals("1 г 25 хв", HudEtaText.duration(5100, true));
        assertFalse(HudEtaText.duration(Long.MAX_VALUE, false).startsWith("-"));
    }

    @Test public void distancesAndMissingPartsKeepStableSeparators() {
        assertEquals("", HudEtaText.distance(-1, false));
        assertEquals("0 м", HudEtaText.distance(0, true));
        assertEquals("999 m", HudEtaText.distance(999, false));
        assertEquals("1 км", HudEtaText.distance(1000, true));
        assertEquals("8.4 km", HudEtaText.distance(8400, false));
        assertEquals("18:45 | 8.4 km", new HudEtaText("18:45", "", "8.4 km").joined());
    }

    static DirectTbtFrame frame(DirectTbtFrame.TravelMetrics metrics) {
        return new DirectTbtFrame(1, 2, 13, 30, "Road", "Cue", "Road",
                new byte[]{1}, new byte[]{2}, Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive(), DirectTbtFrame.TripMetrics.nextStopOnly(metrics));
    }

    static DirectTbtPayload.Options options(int mask, boolean ua, int mode) {
        return new DirectTbtPayload.Options(true, true, true, true, true, true, false,
                mode, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0,
                0, 0, 5, null).withPresentation(presentation(ua, 0));
    }

    static DirectTbtPayload.Presentation presentation(boolean ua, int format) {
        return new DirectTbtPayload.Presentation(0, 0, format, ua, -1, -1, -1, 0xffffff00);
    }
}
