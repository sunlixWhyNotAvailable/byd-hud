package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;

public final class DirectTbtPayloadTest {
    @Test
    public void activeAlertKeepsRouteNativeAndLanes() {
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(11, 9, DirectTbtFrame.AlertOverlay.active(
                        7, 25, "Camera", new byte[]{8, 9})),
                DirectTbtPayload.Options.ALL);

        assertEquals(9, prepared.nativeManeuver());
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
