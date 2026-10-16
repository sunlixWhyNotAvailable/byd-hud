package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bydhud.gmapswire.GmapsWireParser;
import java.util.Map;
import org.junit.Test;

/** Verifies field-3 presence on real nested route-state protobuf input. */
public final class GmapsWireParserStepIndexPresenceTest {
    private static final byte[] ROUNDABOUT_ENTRY_EXPLICIT_ZERO_INDEX = {
            0x1a, 0x1b, 0x12, 0x19, 0x1a, 0x17,
            0x08, 0x07,
            0x12, 0x13,
            0x12, 0x0b, 0x12, 0x02, 0x08, 0x25,
            0x1a, 0x02, 0x08, 0x1e, 0x42, 0x01, 0x78,
            0x18, 0x00, 0x22, 0x02, 0x08, 0x05
    };

    private static final byte[] ROUNDABOUT_EXIT_EXPLICIT_ZERO_INDEX = {
            0x1a, 0x1b, 0x12, 0x19, 0x1a, 0x17,
            0x08, 0x07,
            0x12, 0x13,
            0x12, 0x0b, 0x12, 0x02, 0x08, 0x42,
            0x1a, 0x02, 0x08, 0x1e, 0x42, 0x01, 0x78,
            0x18, 0x00, 0x22, 0x02, 0x08, 0x05
    };

    private static final byte[] ROUNDABOUT_EXIT_ABSENT_INDEX = {
            0x1a, 0x19, 0x12, 0x17, 0x1a, 0x15,
            0x08, 0x07,
            0x12, 0x11,
            0x12, 0x0b, 0x12, 0x02, 0x08, 0x42,
            0x1a, 0x02, 0x08, 0x1e, 0x42, 0x01, 0x78,
            0x22, 0x02, 0x08, 0x05
    };

    private static final byte[] PRESENT_EMPTY_DISTANCE = {
            0x1a, 0x19, 0x12, 0x17, 0x1a, 0x15, 0x08, 0x07, 0x12, 0x11,
            0x12, 0x09, 0x12, 0x02, 0x08, 0x08, 0x1a, 0x00, 0x42, 0x01, 0x78,
            0x18, 0x00, 0x22, 0x02, 0x08, 0x05
    };

    private static final byte[] ABSENT_DISTANCE = {
            0x1a, 0x17, 0x12, 0x15, 0x1a, 0x13, 0x08, 0x07, 0x12, 0x0f,
            0x12, 0x07, 0x12, 0x02, 0x08, 0x08, 0x42, 0x01, 0x78,
            0x18, 0x00, 0x22, 0x02, 0x08, 0x05
    };

    private static final byte[] COMPLETE_ROUTE_DISTANCES = {
            0x1a, 0x22, 0x12, 0x20, 0x1a, 0x1e, 0x08, 0x07, 0x12, 0x1a,
            0x12, 0x08, 0x12, 0x02, 0x08, 0x08, 0x1a, 0x02, 0x08, 0x0a,
            0x12, 0x08, 0x12, 0x02, 0x08, 0x03, 0x1a, 0x02, 0x08, 0x14,
            0x18, 0x00, 0x22, 0x02, 0x08, 0x05
    };

    private static final byte[] INCOMPLETE_ROUTE_DISTANCES = {
            0x1a, 0x1e, 0x12, 0x1c, 0x1a, 0x1a, 0x08, 0x07, 0x12, 0x16,
            0x12, 0x08, 0x12, 0x02, 0x08, 0x08, 0x1a, 0x02, 0x08, 0x0a,
            0x12, 0x04, 0x12, 0x02, 0x08, 0x03, 0x18, 0x00, 0x22, 0x02,
            0x08, 0x05
    };

    private static final byte[] ROUTE_METRICS_WITHOUT_CURRENT_STEP = {
            0x1a, 0x1e, 0x12, 0x1c, 0x1a, 0x1a, 0x08, 0x07, 0x12, 0x16,
            0x12, 0x08, 0x12, 0x02, 0x08, 0x08, 0x1a, 0x02, 0x08, 0x0a,
            0x12, 0x04, 0x12, 0x02, 0x08, 0x03, 0x18, 0x02, 0x22, 0x02,
            0x08, 0x05
    };

    @Test
    public void distinguishesSynthesizedZeroFromExplicitZeroWithoutChangingIndex() throws Exception {
        Map<String, Object> absent = GmapsWireParser.summarize(ROUNDABOUT_EXIT_ABSENT_INDEX);
        Map<String, Object> explicitZero = GmapsWireParser.summarize(
                ROUNDABOUT_ENTRY_EXPLICIT_ZERO_INDEX);

        assertEquals(Integer.valueOf(0), absent.get("currentStepIndex"));
        assertEquals(Integer.valueOf(0), explicitZero.get("currentStepIndex"));
        assertEquals(Boolean.FALSE, absent.get("currentStepIndexPresent"));
        assertEquals(Boolean.TRUE, explicitZero.get("currentStepIndexPresent"));
        assertEquals(Boolean.TRUE, absent.get("currentStepAvailable"));
        assertEquals(Integer.valueOf(1), absent.get("stepCount"));
    }

    @Test
    public void mapperRequiresObservedIndexOnlyForInheritedCircleContext() throws Exception {
        Map<String, Object> entry = GmapsWireParser.summarize(
                ROUNDABOUT_ENTRY_EXPLICIT_ZERO_INDEX);
        Map<String, Object> exitWithoutIndex = GmapsWireParser.summarize(
                ROUNDABOUT_EXIT_ABSENT_INDEX);
        Map<String, Object> exitWithIndex = GmapsWireParser.summarize(
                ROUNDABOUT_EXIT_EXPLICIT_ZERO_INDEX);

        GMapsDirectManeuverMap absentIndexMap = new GMapsDirectManeuverMap();
        absentIndexMap.map((Integer) entry.get("maneuverEnum"),
                (Integer) entry.get("currentStepIndex"),
                Boolean.TRUE.equals(entry.get("currentStepIndexPresent")));
        GMapsDirectManeuverMap.Result unknownExit = absentIndexMap.map((Integer) exitWithoutIndex.get("maneuverEnum"),
                (Integer) exitWithoutIndex.get("currentStepIndex"),
                Boolean.TRUE.equals(exitWithoutIndex.get("currentStepIndexPresent")));
        assertFalse(unknownExit.roundaboutExit.hasDirection());
        assertEquals(72, unknownExit.fallbackSource);

        GMapsDirectManeuverMap explicitZeroMap = new GMapsDirectManeuverMap();
        explicitZeroMap.map((Integer) entry.get("maneuverEnum"),
                (Integer) entry.get("currentStepIndex"),
                Boolean.TRUE.equals(entry.get("currentStepIndexPresent")));
        assertEquals(RoundaboutExit.Direction.LEFT,
                explicitZeroMap.map((Integer) exitWithIndex.get("maneuverEnum"),
                        (Integer) exitWithIndex.get("currentStepIndex"),
                        Boolean.TRUE.equals(exitWithIndex.get("currentStepIndexPresent")))
                        .roundaboutExit.getDirection());
    }

    @Test
    public void distinguishesPresentZeroDistanceFromMissingDistance() throws Exception {
        Map<String, Object> presentZero = GmapsWireParser.summarize(PRESENT_EMPTY_DISTANCE);
        Map<String, Object> absent = GmapsWireParser.summarize(ABSENT_DISTANCE);

        assertEquals(Long.valueOf(0L), presentZero.get("distanceMeters"));
        assertEquals(Long.valueOf(0L), presentZero.get("nextStopRemainingDistanceMeters"));
        assertEquals(Long.valueOf(0L), presentZero.get("wholeRouteRemainingDistanceMeters"));
        assertFalse(absent.containsKey("distanceMeters"));
        assertFalse(absent.containsKey("nextStopRemainingDistanceMeters"));
        assertFalse(absent.containsKey("wholeRouteRemainingDistanceMeters"));
        assertTrue(absent.containsKey("nextStopRemainingSeconds"));
        assertEquals("x", absent.get("contentDescription"));
    }

    @Test
    public void emitsRouteDistanceOnlyWhenEveryRemainingStepHasOne() throws Exception {
        Map<String, Object> complete = GmapsWireParser.summarize(COMPLETE_ROUTE_DISTANCES);
        Map<String, Object> incomplete = GmapsWireParser.summarize(INCOMPLETE_ROUTE_DISTANCES);

        assertEquals(Long.valueOf(30L), complete.get("nextStopRemainingDistanceMeters"));
        assertEquals(Long.valueOf(30L), complete.get("wholeRouteRemainingDistanceMeters"));
        assertFalse(incomplete.containsKey("nextStopRemainingDistanceMeters"));
        assertFalse(incomplete.containsKey("wholeRouteRemainingDistanceMeters"));
        assertEquals(Long.valueOf(5L), incomplete.get("nextStopRemainingSeconds"));
        assertEquals(Long.valueOf(5L), incomplete.get("wholeRouteRemainingSeconds"));
    }

    @Test
    public void keepsRouteMetricsWhenCurrentStepIndexHasNoStep() throws Exception {
        Map<String, Object> summary = GmapsWireParser.summarize(
                ROUTE_METRICS_WITHOUT_CURRENT_STEP);

        assertEquals(Boolean.FALSE, summary.get("currentStepAvailable"));
        assertEquals(Long.valueOf(5L), summary.get("nextStopRemainingSeconds"));
        assertEquals(Long.valueOf(5L), summary.get("wholeRouteRemainingSeconds"));
        assertFalse(summary.containsKey("nextStopRemainingDistanceMeters"));
        assertFalse(summary.containsKey("wholeRouteRemainingDistanceMeters"));
    }
}
