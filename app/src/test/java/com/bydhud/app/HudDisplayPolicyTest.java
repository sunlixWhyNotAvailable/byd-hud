package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;

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
    public void activeDirectFrameUsesSharedDistanceBoundaries() {
        int[] source = {0, 10, 11, 12};
        int[] clamped = {11, 11, 11, 12};
        for (int index = 0; index < source.length; index++) {
            DirectTbtFrame frame = frame(source[index]);
            assertEquals(clamped[index],
                    HudDisplayPolicy.applyActiveFrame(frame, true).getDistanceMeters());
            assertEquals(source[index],
                    HudDisplayPolicy.applyActiveFrame(frame, false).getDistanceMeters());
        }
    }

    @Test
    public void clearStateKeepsZeroDistance() {
        HudState clear = new HudState().copyForClear();
        assertEquals(0, HudDisplayPolicy.apply(clear, true).distanceToIntersection);
    }

    private static DirectTbtFrame frame(int distanceMeters) {
        return new DirectTbtFrame(
                11, 3, 9, distanceMeters, "Road", "Turn right", "Road",
                new byte[]{1}, new byte[0], Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive());
    }
}
