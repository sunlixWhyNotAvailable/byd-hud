package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GMapsDirectManeuverMapTest {
    @Test
    public void departAlwaysUsesBlankFallbackAndNative() {
        GMapsDirectManeuverMap.Result withDistance =
                new GMapsDirectManeuverMap().map(1, true);
        GMapsDirectManeuverMap.Result withoutDistance =
                new GMapsDirectManeuverMap().map(1, false);

        assertEquals("DEPART", withDistance.maneuverName);
        assertEquals(72, withDistance.fallbackSource);
        assertEquals(99, withDistance.nativeManeuver);
        assertEquals(72, withoutDistance.fallbackSource);
        assertEquals(99, withoutDistance.nativeManeuver);
    }
}
