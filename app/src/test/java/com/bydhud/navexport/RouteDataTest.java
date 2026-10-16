package com.bydhud.navexport;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;

public class RouteDataTest {
    @Test public void fullRouteSurvivesEncodingWithExactManeuverAssociation() throws Exception {
        double[] points = new double[40_000];
        Random random = new Random(2516);
        for (int i = 0; i < points.length; i += 2) {
            points[i] = 49 + random.nextDouble();
            points[i + 1] = 30 + random.nextDouble();
        }
        RouteData.Maneuver turn = new RouteData.Maneuver();
        turn.id = "turn-19999";
        turn.ordinal = 7;
        turn.latitude = points[points.length - 2];
        turn.longitude = points[points.length - 1];
        turn.vertexIndex = 19999;
        turn.instructionSegmentIndex = 42;
        turn.navigationSegmentIndex = 85;
        turn.type = "LEFT";
        turn.turnSide = "LEFT_SIDE";
        turn.incomingBearing = 12.5;
        turn.outgoingBearing = 280;
        turn.sourceRotation = 93;
        byte[] encoded = new RouteData(points, new RouteData.Maneuver[] {turn}, true).encode();
        assertTrue("exercise a route larger than one transport chunk", encoded.length > 24 * 1024);
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(encoded)))) {
            assertEquals(0x42595231, in.readInt());
            assertEquals(points.length / 2, in.readInt());
            for (double value : points) assertEquals(value, in.readDouble(), 0);
            assertTrue(in.readBoolean());
            assertEquals(1, in.readInt());
            assertEquals(turn.id, in.readUTF());
            assertEquals(7, in.readInt());
            assertEquals(turn.latitude, in.readDouble(), 0);
            assertEquals(turn.longitude, in.readDouble(), 0);
            assertEquals(19999, in.readInt());
            assertEquals(42, in.readInt());
            assertEquals(85, in.readInt());
            assertEquals("LEFT", in.readUTF());
            assertEquals("LEFT_SIDE", in.readUTF());
            assertEquals(12.5, in.readDouble(), 0);
            assertEquals(280, in.readDouble(), 0);
            assertEquals(93, in.readInt());
            assertFalse(in.readBoolean());
            assertEquals(-1, in.read());
        }
    }

    @Test public void malformedCoordinatesRejectTheSnapshotInsteadOfPublishingAPartialRoute() throws Exception {
        for (double[] points : new double[][] {{10}, {Double.NaN, 10}, {91, 10}, {10, 181}}) {
            try {
                new RouteData(points, new RouteData.Maneuver[0], false).encode();
                fail("Malformed source route accepted");
            } catch (IOException expected) { }
        }
    }
}
