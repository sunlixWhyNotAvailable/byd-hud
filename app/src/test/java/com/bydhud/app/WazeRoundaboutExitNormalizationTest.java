package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.car.app.navigation.model.Maneuver;
import org.junit.Test;

public final class WazeRoundaboutExitNormalizationTest {
    @Test
    public void angleTypeAndCirculationDetermineDirectionButOrdinalDoesNot() {
        RoundaboutExit clockwise = exit(
                Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 90);
        assertEquals(RoundaboutExit.Direction.LEFT, clockwise.getDirection());
        assertEquals(RoundaboutExit.Source.WAZE_WITH_ANGLE, clockwise.getSource());
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
                clockwise.getRawType());
        assertEquals(90.0, clockwise.getRawAngleDegrees(), 0.0);

        assertEquals(RoundaboutExit.Direction.STRAIGHT,
                exit(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, 180)
                        .getDirection());
        assertEquals(RoundaboutExit.Direction.RIGHT,
                exit(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 270)
                        .getDirection());
        assertEquals(RoundaboutExit.Direction.RIGHT,
                exit(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, 90)
                        .getDirection());
        assertEquals(RoundaboutExit.Direction.LEFT,
                exit(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, 270)
                        .getDirection());
        assertEquals(RoundaboutExit.Direction.U_TURN,
                exit(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 360)
                        .getDirection());
        assertEquals(RoundaboutExit.Direction.LEFT,
                exit(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 140)
                        .getDirection());
        assertEquals(RoundaboutExit.Direction.RIGHT,
                exit(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 320)
                        .getDirection());
        assertEquals(RoundaboutExit.Direction.RIGHT,
                exit(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, 140)
                        .getDirection());
        assertEquals(RoundaboutExit.Direction.LEFT,
                exit(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, 320)
                        .getDirection());
        RoundaboutExit mainSurface = exit(
                Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 140)
                .withSourceMode("MAIN_SURFACE");
        assertEquals(140.0, mainSurface.getRawAngleDegrees(), 0.0);
        assertTrue(mainSurface.diagnostics().contains("exitRawAngleDegrees=140.0"));
        assertTrue(mainSurface.diagnostics().contains("mode=MAIN_SURFACE"));

        Maneuver ordinalOnly = new Maneuver.Builder(
                Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW)
                .setRoundaboutExitNumber(2)
                .build();
        RoundaboutExit unknown = WazeDirectChannel.roundaboutExitDirection(ordinalOnly);
        assertFalse(unknown.hasDirection());
        assertEquals(RoundaboutExit.Source.NONE, unknown.getSource());
        assertTrue(Double.isNaN(unknown.getRawAngleDegrees()));
        RoundaboutExit cluster = unknown.withSourceMode("CLUSTER");
        assertSame(cluster, RoundaboutExit.unknown().withSourceMode("CLUSTER"));
        assertEquals("CLUSTER", cluster.getSourceMode());
        assertTrue(cluster.diagnostics().contains("mode=CLUSTER"));
        assertTrue(cluster.diagnostics().contains("exitRawType=-1"));
        assertSame(unknown, cluster.withSourceMode("unknown"));
    }

    private static RoundaboutExit exit(int type, int angle) {
        Maneuver maneuver = new Maneuver.Builder(type)
                .setRoundaboutExitNumber(2)
                .setRoundaboutExitAngle(angle)
                .build();
        return WazeDirectChannel.roundaboutExitDirection(maneuver);
    }

    @Test public void iconReuseRequiresTheSameAngleAndExitNumber() {
        byte[] pixels = {1, 2, 3};
        WazeDirectChannel.CachedManeuverIcon cached =
                new WazeDirectChannel.CachedManeuverIcon(2, 90, pixels);
        assertArrayEquals(pixels, cached.bytesFor(2, 90));
        assertEquals(0, cached.bytesFor(2, 270).length);
        assertEquals(0, cached.bytesFor(3, 90).length);
        assertEquals(0, cached.bytesFor(2, Double.NaN).length);
        WazeDirectChannel.CachedManeuverIcon ordinary =
                new WazeDirectChannel.CachedManeuverIcon(0, Double.NaN, pixels);
        assertArrayEquals(pixels, ordinary.bytesFor(0, Double.NaN));
    }
}
