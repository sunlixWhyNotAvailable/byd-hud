package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;

public final class DirectTbtPayloadTest {
    @Test
    public void activeAlertUsesBlankNativeAndKeepsLanes() {
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(11, 9, DirectTbtFrame.AlertOverlay.active(
                        7, 25, "Camera", new byte[]{8, 9})),
                DirectTbtPayload.Options.ALL);

        assertEquals(99, prepared.nativeManeuver());
        assertEquals(1, prepared.laneCount());
        assertEquals(3, prepared.lanePngBytes());
        assertEquals(2, prepared.maneuverPngBytes());
        assertEquals("alert", prepared.maneuverMode());
    }

    @Test
    public void activeAlertWithoutRawManeuverUsesBlankNative() {
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(-1, 9, DirectTbtFrame.AlertOverlay.active(
                        7, 25, "Camera", new byte[]{8, 9})),
                DirectTbtPayload.Options.ALL);

        assertEquals(99, prepared.nativeManeuver());
    }

    @Test
    public void inactiveAlertUsesRouteManeuver() {
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(11, 9, DirectTbtFrame.AlertOverlay.inactive()),
                DirectTbtPayload.Options.ALL);

        assertEquals(9, prepared.nativeManeuver());
        assertEquals(3, prepared.maneuverPngBytes());
        assertEquals("current", prepared.maneuverMode());
    }

    @Test
    public void laneOnlyFrameUsesBlankPngAndNative99() {
        DirectTbtFrame frame = new DirectTbtFrame(
                -1, 0, 14, 120, "Road", "Continue", "Road",
                new byte[0], new byte[]{4, 5, 6},
                Collections.singletonList(new DirectTbtFrame.Lane(2, true, "R")),
                DirectTbtFrame.AlertOverlay.inactive());
        DirectTbtPayload.Options options = new DirectTbtPayload.Options(
                true, true, true, true, true, true, false, new byte[]{7, 2});

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(frame, options);

        assertEquals(99, prepared.nativeManeuver());
        assertEquals(2, prepared.maneuverPngBytes());
        assertEquals("blank_s72", prepared.maneuverMode());
    }

    @Test
    public void enabledSmallDistanceClampAppliesToDirectPayload() {
        DirectTbtFrame frame = new DirectTbtFrame(
                11, 3, 9, 10, "Road", "Turn right", "Road",
                new byte[]{1}, new byte[0], Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive());
        DirectTbtPayload.Options options = new DirectTbtPayload.Options(
                true, true, true, true, true, true, true);

        assertEquals(11, DirectTbtPayload.prepare(frame, options).distanceMeters());
    }

    private static DirectTbtFrame frame(
            int rawManeuver,
            int bydManeuver,
            DirectTbtFrame.AlertOverlay alert) {
        return new DirectTbtFrame(
                rawManeuver,
                3,
                bydManeuver,
                120,
                "Road",
                "Turn right",
                "Road",
                new byte[]{1, 2, 3},
                new byte[]{4, 5, 6},
                Collections.singletonList(new DirectTbtFrame.Lane(2, true, "R")),
                alert);
    }
}
