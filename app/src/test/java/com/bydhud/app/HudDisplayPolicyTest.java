package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HudDisplayPolicyTest {
    @Test
    public void clampsOnlyZeroThroughTen() {
        assertEquals(-1, HudDisplayPolicy.displayDistanceMeters(-1, true));
        assertEquals(11, HudDisplayPolicy.displayDistanceMeters(0, true));
        assertEquals(11, HudDisplayPolicy.displayDistanceMeters(10, true));
        assertEquals(11, HudDisplayPolicy.displayDistanceMeters(11, true));
        assertEquals(12, HudDisplayPolicy.displayDistanceMeters(12, true));
        assertEquals(5, HudDisplayPolicy.displayDistanceMeters(5, false));
    }

    @Test
    public void clearStateKeepsZeroDistance() {
        HudState clear = new HudState().copyForClear();
        assertEquals(0, HudDisplayPolicy.apply(clear, true).distanceToIntersection);
    }
}
