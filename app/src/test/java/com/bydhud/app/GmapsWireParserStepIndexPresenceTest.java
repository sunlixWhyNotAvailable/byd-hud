package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
}
