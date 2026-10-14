package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bydhud.gmapswire.GmapsWireParser;
import org.junit.Test;

public final class GMapsDirectManeuverMapRoundaboutTest {
    @Test
    public void exactDirectionIsSeparateFromFallbackAndExpiresWithCircleContext() {
        GMapsDirectManeuverMap map = new GMapsDirectManeuverMap();

        GMapsDirectManeuverMap.Result exact = map.map(37, 10);
        assertEquals("ROUNDABOUT_ENTER_AND_EXIT_CW_SLIGHT_LEFT",
                GmapsWireParser.maneuverName(exact.wireValue));
        assertEquals(27, exact.fallbackSource);
        assertEquals(RoundaboutExit.Direction.LEFT, exact.roundaboutExit.getDirection());
        assertEquals(RoundaboutExit.Source.GMAPS_EXACT, exact.roundaboutExit.getSource());
        assertEquals(37, exact.roundaboutExit.getRawType());
        assertTrue(Double.isNaN(exact.roundaboutExit.getRawAngleDegrees()));

        GMapsDirectManeuverMap.Result exit = map.map(66, 11);
        assertEquals(RoundaboutExit.Direction.LEFT, exit.roundaboutExit.getDirection());
        assertEquals(RoundaboutExit.Source.GMAPS_CIRCLE_CONTEXT,
                exit.roundaboutExit.getSource());
        assertEquals(37, exit.roundaboutExit.getRawType());

        assertFalse(map.map(68, 11).roundaboutExit.hasDirection());
        assertFalse(map.map(66, 11).roundaboutExit.hasDirection());

        map.map(37, 20);
        map.map(65, 21);
        GMapsDirectManeuverMap.Result newCircleExit = map.map(66, 22);
        assertFalse(newCircleExit.roundaboutExit.hasDirection());
        assertEquals(72, newCircleExit.fallbackSource);

        map.map(41, 30);
        GMapsDirectManeuverMap.Result counterClockwise = map.map(68, 31);
        assertEquals(RoundaboutExit.Direction.RIGHT,
                counterClockwise.roundaboutExit.getDirection());
        assertEquals(RoundaboutExit.Source.GMAPS_CIRCLE_CONTEXT,
                counterClockwise.roundaboutExit.getSource());
        assertEquals(41, counterClockwise.roundaboutExit.getRawType());

        map.reset();
        assertFalse(map.map(68, 31).roundaboutExit.hasDirection());
    }

    @Test
    public void inheritedDirectionRequiresSameOrImmediatelyNextObservedStep() {
        GMapsDirectManeuverMap map = new GMapsDirectManeuverMap();

        assertTrue(map.map(37, 4).roundaboutExit.hasDirection());
        assertEquals(RoundaboutExit.Direction.LEFT,
                map.map(66, 4).roundaboutExit.getDirection());

        map.map(37, 10);
        assertEquals(RoundaboutExit.Direction.LEFT,
                map.map(66, 11).roundaboutExit.getDirection());

        map.map(37, 20);
        assertFalse(map.map(66, 22).roundaboutExit.hasDirection());
        map.map(37, 20);
        assertFalse(map.map(66, 19).roundaboutExit.hasDirection());

        assertEquals(RoundaboutExit.Direction.LEFT, map.map(37, -1)
                .roundaboutExit.getDirection());
        assertFalse(map.map(66, -1).roundaboutExit.hasDirection());
    }

    @Test
    public void unusableWireInstructionClearsCircleContext() {
        GMapsDirectManeuverMap map = new GMapsDirectManeuverMap();
        map.map(37, 8);

        assertFalse(map.map(999, 9).roundaboutExit.hasDirection());
        assertFalse(map.map(66, 10).roundaboutExit.hasDirection());
    }
}
