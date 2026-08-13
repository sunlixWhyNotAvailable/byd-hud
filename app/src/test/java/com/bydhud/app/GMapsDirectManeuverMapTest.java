package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.util.Collections;

public final class GMapsDirectManeuverMapTest {
    @Test
    public void departAlwaysUsesBlankFallbackAndNative() {
        GMapsDirectManeuverMap.Result withDistance =
                new GMapsDirectManeuverMap().map(1, true);
        GMapsDirectManeuverMap.Result withoutDistance =
                new GMapsDirectManeuverMap().map(1, false);

        assertEquals("DEPART", withDistance.maneuverName);
        assertEquals(20, withDistance.intermediate);
        assertEquals(20, withDistance.amapBroadcastManeuver);
        assertEquals(72, withDistance.fallbackSource);
        assertEquals(99, withDistance.nativeManeuver);
        assertEquals(72, withoutDistance.fallbackSource);
        assertEquals(99, withoutDistance.nativeManeuver);
    }

    @Test
    public void exactRoundaboutGeometryUsesVerifiedAmapBroadcastIcons() {
        GMapsDirectManeuverMap map = new GMapsDirectManeuverMap();

        assertEquals(26, map.map(31, true).amapBroadcastManeuver);
        assertEquals(27, map.map(34, true).amapBroadcastManeuver);
        assertEquals(25, map.map(35, true).amapBroadcastManeuver);
        assertEquals(28, map.map(38, true).amapBroadcastManeuver);
        assertEquals(22, map.map(39, true).amapBroadcastManeuver);
        assertEquals(23, map.map(42, true).amapBroadcastManeuver);
        assertEquals(21, map.map(43, true).amapBroadcastManeuver);
        assertEquals(24, map.map(46, true).amapBroadcastManeuver);
    }

    @Test
    public void departDashboardOverrideDoesNotChangeRoadInfoNativeOrBitmap() {
        GMapsDirectManeuverMap.Result mapping = new GMapsDirectManeuverMap().map(1, true);
        byte[] bitmap = new byte[]{7, 2};
        DirectTbtFrame frame = new DirectTbtFrame(
                1, mapping.intermediate, mapping.nativeManeuver, 0,
                "", "", "", bitmap, null, Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive())
                .withVehicleTbt(mapping.amapBroadcastManeuver, mapping.roundaboutExitNumber);

        assertEquals(20, frame.getAmapBroadcastManeuver());
        assertEquals(99, frame.getBydManeuver());
        assertArrayEquals(bitmap, frame.getManeuverPng());
        assertEquals(99, DirectTbtPayload.prepare(
                frame, DirectTbtPayload.Options.ALL).nativeManeuver());
    }

    @Test
    public void genericRoundaboutEventsUseEnterAndExitBroadcastIcons() {
        GMapsDirectManeuverMap map = new GMapsDirectManeuverMap();

        assertEquals(17, map.map(55, true).amapBroadcastManeuver);
        assertEquals(11, map.map(56, true).amapBroadcastManeuver);
        assertEquals(17, map.map(65, true).amapBroadcastManeuver);
        assertEquals(18, map.map(66, true).amapBroadcastManeuver);
        assertEquals(11, map.map(67, true).amapBroadcastManeuver);
        assertEquals(12, map.map(68, true).amapBroadcastManeuver);
    }
}
