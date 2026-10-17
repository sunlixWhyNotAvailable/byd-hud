package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class GMapsDirectChannelGuidanceTest {
    @SuppressWarnings("removal")
    @Test
    public void routePayloadFenceAllowsSameStepRecurrenceAndKeepsCrossStepHistory() {
        GMapsDirectChannel.RoutePayloadResurrectionFence fence =
                new GMapsDirectChannel.RoutePayloadResurrectionFence();
        Integer step1000First = new Integer(1000);
        Integer step1000Second = new Integer(1000);
        Integer step1000Third = new Integer(1000);
        assertNotSame(step1000First, step1000Second);
        assertNotSame(step1000Second, step1000Third);

        assertTrue(fence.accepts(step1000First, true, "A"));
        assertTrue(fence.accepts(step1000Second, true, "B"));
        assertTrue(fence.accepts(step1000Third, true, "A"));
        assertTrue(fence.accepts(step1000Third, true, "A"));
        assertTrue(fence.accepts(1001, true, "C"));
        assertFalse(fence.accepts(step1000First, true, "A"));
        assertTrue(fence.accepts(4, true, "D"));
        assertEquals(4, fence.lastAcceptedCurrentStepIndex());

        fence.reset();
        assertEquals(0, fence.size());
        assertTrue(fence.accepts(9, true, "A"));
        assertEquals(9, fence.lastAcceptedCurrentStepIndex());

        fence.reset();
        assertTrue(fence.accepts(0, false, "unknown-A"));
        assertTrue(fence.accepts(0, false, "unknown-B"));
        assertFalse(fence.accepts(0, false, "unknown-A"));

        fence.reset();
        assertTrue(fence.accepts(0, true, "explicit-A"));
        assertTrue(fence.accepts(0, true, "explicit-B"));
        assertTrue(fence.accepts(0, true, "explicit-A"));
    }

    @Test
    public void laneSetIsClearedForDisabledOrIncompletePhysicalLaneData() {
        Map<String, Object> left = lane(Collections.singletonList(arrow(5, 1, true)));
        Map<String, Object> right = lane(Collections.singletonList(arrow(5, 2, false)));
        List<Map<String, Object>> valid = Arrays.asList(left, right);

        List<DirectTbtFrame.Lane> mapped = GMapsDirectChannel.mapLanes(valid, true);
        assertEquals(2, mapped.size());
        assertEquals(2, mapped.get(0).getDirection());
        assertEquals(3, mapped.get(1).getDirection());
        assertTrue(GMapsDirectChannel.mapLanes(valid, false).isEmpty());
        assertTrue(GMapsDirectChannel.mapLanes(Arrays.asList(
                lane(Collections.emptyList()), right), true).isEmpty());
        assertTrue(GMapsDirectChannel.mapLanes(Arrays.asList(
                lane(Collections.singletonList(arrow(0, 0, false))), right), true).isEmpty());
    }

    @Test
    public void bitmapRequiresSourceSequenceInsideCurrentInstruction() {
        byte[] fallback = new byte[0];
        GMapsDirectChannel.ManeuverBitmap current = bitmap(10L);
        GMapsDirectChannel.ManeuverBitmap oldInstruction = bitmap(9L);
        GMapsDirectChannel.ManeuverBitmap future = bitmap(12L);
        GMapsDirectChannel.ManeuverBitmap uncorrelated = bitmap(-1L);

        assertEquals("google", select(current, fallback, 11L, 10L).selected);
        assertEquals("fallback", select(oldInstruction, fallback, 11L, 10L).selected);
        assertEquals("fallback", select(future, fallback, 11L, 10L).selected);
        assertEquals("fallback", select(uncorrelated, fallback, 11L, 10L).selected);
    }

    @Test
    public void missingMetricScopeDoesNotEraseLastValidEta() {
        DirectTbtFrame.TravelMetrics previousNext = new DirectTbtFrame.TravelMetrics(
                -1L, DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                90L, 1500L, 10000L);
        DirectTbtFrame.TravelMetrics previousWholeRoute = new DirectTbtFrame.TravelMetrics(
                -1L, DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                240L, 5000L, 10000L);
        DirectTbtFrame.TripMetrics previous = new DirectTbtFrame.TripMetrics(
                previousNext, previousWholeRoute);
        DirectTbtFrame.TripMetrics update = new DirectTbtFrame.TripMetrics(
                new DirectTbtFrame.TravelMetrics(-1L,
                        DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                        45L, -1L, 20000L),
                new DirectTbtFrame.TravelMetrics(-1L,
                        DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                        -1L, 6000L, 20000L));

        DirectTbtFrame.TripMetrics merged = GMapsDirectChannel.mergeTripMetrics(
                previous, update);

        assertEquals(45L, merged.getNextStop().getRemainingTimeSeconds());
        assertEquals(-1L, merged.getNextStop().getRemainingDistanceMeters());
        assertEquals(240L, merged.getWholeRoute().getRemainingTimeSeconds());
        assertEquals(6000L, merged.getWholeRoute().getRemainingDistanceMeters());
    }

    private static GMapsDirectChannel.BitmapSelection select(
            GMapsDirectChannel.ManeuverBitmap bitmap, byte[] fallback,
            long sequence, long instructionStartSequence) {
        return GMapsDirectChannel.BitmapSelection.select(
                sequence, "TURN_NORMAL_LEFT", bitmap, fallback,
                100L, "matched", instructionStartSequence);
    }

    private static GMapsDirectChannel.ManeuverBitmap bitmap(long sourceSequence) {
        return new GMapsDirectChannel.ManeuverBitmap(
                "TURN_NORMAL_LEFT", "", new byte[]{1}, 1, 1, 100L,
                1L, 1L, 1L, sourceSequence);
    }

    private static Map<String, Object> lane(List<Map<String, Object>> arrows) {
        Map<String, Object> lane = new HashMap<>();
        lane.put("arrows", new ArrayList<>(arrows));
        return lane;
    }

    private static Map<String, Object> arrow(int shape, int side, boolean recommended) {
        Map<String, Object> arrow = new HashMap<>();
        arrow.put("shapeEnum", shape);
        arrow.put("shape", "NORMAL");
        arrow.put("sideEnum", side);
        arrow.put("side", side == 1 ? "LEFT" : side == 2 ? "RIGHT" : "UNSPECIFIED");
        arrow.put("recommended", recommended);
        return arrow;
    }
}
