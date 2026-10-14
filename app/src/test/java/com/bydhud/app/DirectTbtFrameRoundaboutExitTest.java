package com.bydhud.app;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;

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
        DirectTbtFrame frame = base.withVehicleTbt(18, 2, exit);
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
        assertEquals("MAIN_SURFACE", copy.getRoundaboutExit().getSourceMode());
        assertSame(RoundaboutExit.unknown(), DirectTbtFrame.empty().getRoundaboutExit());
    }
}
