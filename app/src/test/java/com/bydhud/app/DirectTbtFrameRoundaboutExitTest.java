package com.bydhud.app;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public final class DirectTbtFrameRoundaboutExitTest {
    @Test
    public void allPartialCopiesPreserveRoundaboutDirectionAndDefaultIsSharedUnknown() {
        RoundaboutExit exit = RoundaboutExit.of(
                RoundaboutExit.Direction.RIGHT, RoundaboutExit.Source.WAZE_WITH_ANGLE,
                33, 90).withSourceMode("MAIN_SURFACE");
        DirectTbtFrame base = new DirectTbtFrame(
                33, 22, 99, 120, "road", "cue", "road", null, null,
                Collections.emptyList(), DirectTbtFrame.AlertOverlay.inactive());
        DirectTbtFrame.SourceDistance distance = new DirectTbtFrame.SourceDistance(120, 1_234);
        DirectTbtFrame frame = base.withVehicleTbt(18, 2, exit).withSourceDistance(distance);
        assertSame(exit, base.withRoundaboutExit(exit).getRoundaboutExit());
        DirectTbtFrame laneSource = new DirectTbtFrame(
                0, 9, 11, 100, "", "", "", null, new byte[]{1},
                Collections.emptyList(), DirectTbtFrame.AlertOverlay.inactive());

        DirectTbtFrame copy = frame.withDistanceMeters(60)
                .withNavigationText("new road", "new cue")
                .withManeuverPng(new byte[]{2})
                .withLanesFrom(laneSource)
                .withTripMetrics(DirectTbtFrame.TripMetrics.empty())
                .withSpeedLimit(DirectTbtFrame.SpeedLimit.inactive())
                .withAlertOverlay(DirectTbtFrame.AlertOverlay.inactive())
                .withVehicleTbt(18, 3);

        assertSame(exit, copy.getRoundaboutExit());
        assertSame(distance, copy.getSourceDistance());
        assertEquals(120, copy.getSourceDistance().meters);
        assertEquals(1_234, copy.getSourceDistance().elapsedMs);
        assertNull(DirectTbtFrame.empty().getSourceDistance());
        assertEquals("MAIN_SURFACE", copy.getRoundaboutExit().getSourceMode());
        assertSame(RoundaboutExit.unknown(), DirectTbtFrame.empty().getRoundaboutExit());
    }

    @Test
    public void clearingUnknownManeuverEvidenceKeepsIndependentDataAndRendersWarning() {
        DirectTbtFrame.TripMetrics metrics = DirectTbtFrame.TripMetrics.nextStopOnly(
                new DirectTbtFrame.TravelMetrics(1_800_000L, 600L, 8_000L));
        DirectTbtFrame.SpeedLimit speed = new DirectTbtFrame.SpeedLimit(60, 60, "km/h", 100L);
        DirectTbtFrame.AlertOverlay warning = DirectTbtFrame.AlertOverlay.active(
                7, 35, "speed camera", null).withRouteFrame(true);
        DirectTbtFrame unknownManeuver = new DirectTbtFrame(
                -1, 0, 0, 0, "current road", "", "", null, null,
                Collections.singletonList(new DirectTbtFrame.Lane(1, true, "left")), warning)
                .withTripMetrics(metrics)
                .withSpeedLimit(speed)
                .withSourceDistance(new DirectTbtFrame.SourceDistance(0, 90L));

        assertTrue(unknownManeuver.hasGuidance());
        DirectTbtFrame cleared = unknownManeuver.withoutGuidance();

        assertFalse(cleared.hasGuidance());
        assertEquals(-1, cleared.getRawManeuverType());
        assertEquals(0, cleared.getDistanceMeters());
        assertEquals("", cleared.getRoadText());
        assertEquals("", cleared.getCueText());
        assertEquals("", cleared.getDisplayText());
        assertEquals(0, cleared.getManeuverPng().length);
        assertEquals(0, cleared.getLanePng().length);
        assertTrue(cleared.getLanes().isEmpty());
        assertNull(cleared.getSourceDistance());
        assertSame(RoundaboutExit.unknown(), cleared.getRoundaboutExit());
        assertSame(metrics, cleared.getTripMetrics());
        assertSame(speed, cleared.getSpeedLimit());
        assertTrue(cleared.getAlertOverlay().isActive());
        assertFalse(cleared.getAlertOverlay().useRouteFrame());

        DirectTbtFrame metricCopy = cleared.withTripMetrics(metrics)
                .withSpeedLimit(speed)
                .withAlertOverlay(cleared.getAlertOverlay());
        assertFalse(metricCopy.hasGuidance());
        DirectTbtFrame displayCopy = HudDisplayPolicy.applyActiveFrame(cleared, true);
        assertFalse(displayCopy.hasGuidance());
        assertEquals(0, displayCopy.getDistanceMeters());

        DirectTbtPayload.Prepared warningPayload = DirectTbtPayload.prepare(
                cleared, DirectTbtPayload.Options.ALL);
        assertEquals("alert", warningPayload.maneuverMode());
        assertEquals(35, warningPayload.distanceMeters());
        assertEquals("speed camera", warningPayload.displayText());
    }
}
