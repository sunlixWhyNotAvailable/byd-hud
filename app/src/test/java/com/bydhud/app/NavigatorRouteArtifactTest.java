package com.bydhud.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NavigatorRouteArtifactTest {
    @Test
    public void writesCompleteGzipJsonOnceToSafeRevisionPath() throws Exception {
        File logs = Files.createTempDirectory("navigator-route-artifact-").toFile();
        try {
            NavigatorRouteData route = route("gmaps-26.30", "route/../\"id");
            assertTrue(NavigatorRouteArtifact.write(logs, route));

            File artifact = new File(logs,
                    "route-geometry/gmaps-26.30/42-7.json.gz");
            assertTrue(artifact.isFile());
            JSONObject json = new JSONObject(readGzip(artifact));
            assertEquals("gmaps-26.30", json.getString("source"));
            assertEquals(42L, json.getLong("producerEpoch"));
            assertEquals(7L, json.getLong("routeRevision"));
            assertEquals("route/../\"id", json.getString("routeId"));
            assertEquals(123456L, json.getLong("sourceElapsedMs"));
            assertEquals("navigator_source_degrees", json.getString("coordinateFrame"));
            assertTrue(json.getBoolean("geometryAvailable"));
            assertTrue(json.getBoolean("maneuversAvailable"));
            assertEquals(2, json.getInt("pointCount"));
            assertEquals(2, json.getInt("maneuverCount"));
            assertEquals(2, json.getInt("maneuverCoordinateCount"));
            assertEquals(2, json.getInt("maneuverCoordinateAvailableCount"));

            JSONArray points = json.getJSONArray("points");
            assertEquals(0, points.getJSONObject(0).getInt("index"));
            assertEquals(31.123456789012345,
                    points.getJSONObject(0).getDouble("latitude"), 0d);
            assertEquals(121.98765432109876,
                    points.getJSONObject(0).getDouble("longitude"), 0d);
            assertEquals(1, points.getJSONObject(1).getInt("index"));
            assertEquals(32.12345678901234,
                    points.getJSONObject(1).getDouble("latitude"), 0d);
            assertEquals(122.9876543210987,
                    points.getJSONObject(1).getDouble("longitude"), 0d);

            JSONArray maneuvers = json.getJSONArray("maneuvers");
            JSONObject first = maneuvers.getJSONObject(0);
            assertTrue(first.getBoolean("coordinateAvailable"));
            assertEquals("m1", first.getString("id"));
            assertEquals(0, first.getInt("ordinal"));
            assertEquals(31.2, first.getDouble("latitude"), 0d);
            assertEquals(121.3, first.getDouble("longitude"), 0d);
            assertEquals(3, first.getInt("vertexIndex"));
            assertEquals(4, first.getInt("instructionSegmentIndex"));
            assertEquals(5, first.getInt("navigationSegmentIndex"));
            assertEquals("turn_left", first.getString("type"));
            assertEquals("left", first.getString("turnSide"));
            assertTrue(first.has("incomingBearing"));
            assertTrue(first.isNull("incomingBearing"));
            assertEquals(22.123456789, first.getDouble("outgoingBearing"), 0d);
            assertTrue(first.has("sourceRotation"));
            assertTrue(first.isNull("sourceRotation"));
            assertFalse(first.getBoolean("syntheticPolyline"));

            JSONObject second = maneuvers.getJSONObject(1);
            assertTrue(second.getBoolean("coordinateAvailable"));
            assertEquals("m\"2", second.getString("id"));
            assertEquals(1, second.getInt("ordinal"));
            assertEquals(32.4, second.getDouble("latitude"), 0d);
            assertEquals(122.5, second.getDouble("longitude"), 0d);
            assertEquals(8, second.getInt("vertexIndex"));
            assertEquals(9, second.getInt("instructionSegmentIndex"));
            assertEquals(10, second.getInt("navigationSegmentIndex"));
            assertEquals("roundabout", second.getString("type"));
            assertEquals("right", second.getString("turnSide"));
            assertEquals(44.987654321, second.getDouble("incomingBearing"), 0d);
            assertTrue(second.has("outgoingBearing"));
            assertTrue(second.isNull("outgoingBearing"));
            assertEquals(270, second.getInt("sourceRotation"));
            assertTrue(second.getBoolean("syntheticPolyline"));

            byte[] written = Files.readAllBytes(artifact.toPath());
            assertTrue(NavigatorRouteArtifact.write(logs, route));
            assertArrayEquals(written, Files.readAllBytes(artifact.toPath()));
            assertFalse(new File(artifact.getPath() + ".tmp").exists());

            assertFalse(NavigatorRouteArtifact.write(logs,
                    route("../../outside", "route/../id")));
            assertEquals(1, new File(logs, "route-geometry").list().length);
        } finally {
            deleteTree(logs.toPath());
        }
    }

    @Test
    public void treatsOnlyWazeZeroPairAsMissingAndPreservesRawCoordinates() throws Exception {
        File logs = Files.createTempDirectory("navigator-route-waze-coordinates-").toFile();
        try {
            NavigatorRouteData route = new NavigatorRouteData(
                    new NavigatorRouteData.Header(
                            "waze-5.25.0.1", 43L, 8L, "waze-coordinate-test", 123457L),
                    false, true, new double[0], new NavigatorRouteData.Maneuver[] {
                    new NavigatorRouteData.Maneuver("missing", 0, 0d, 0d,
                            -1, -1, -1, null, null, Double.NaN, Double.NaN,
                            Integer.MIN_VALUE, false),
                    new NavigatorRouteData.Maneuver("valid", 1, 0d, -122.4d,
                            -1, -1, -1, null, null, Double.NaN, Double.NaN,
                            Integer.MIN_VALUE, false)
            });

            assertTrue(NavigatorRouteArtifact.write(logs, route));
            File artifact = new File(logs,
                    "route-geometry/waze-5.25.0.1/43-8.json.gz");
            JSONObject json = new JSONObject(readGzip(artifact));
            assertEquals(2, json.getInt("maneuverCoordinateCount"));
            assertEquals(1, json.getInt("maneuverCoordinateAvailableCount"));
            JSONArray maneuvers = json.getJSONArray("maneuvers");
            JSONObject missing = maneuvers.getJSONObject(0);
            assertFalse(missing.getBoolean("coordinateAvailable"));
            assertEquals(0d, missing.getDouble("latitude"), 0d);
            assertEquals(0d, missing.getDouble("longitude"), 0d);
            assertTrue(maneuvers.getJSONObject(1).getBoolean("coordinateAvailable"));
        } finally {
            deleteTree(logs.toPath());
        }
    }

    private static NavigatorRouteData route(String source, String routeId) {
        NavigatorRouteData.Header header = new NavigatorRouteData.Header(
                source, 42L, 7L, routeId, 123456L);
        NavigatorRouteData.Maneuver first = new NavigatorRouteData.Maneuver(
                "m1", 0, 31.2, 121.3, 3, 4, 5, "turn_left", "left",
                Double.NaN, 22.123456789, Integer.MIN_VALUE, false);
        NavigatorRouteData.Maneuver second = new NavigatorRouteData.Maneuver(
                "m\"2", 1, 32.4, 122.5, 8, 9, 10, "roundabout", "right",
                44.987654321, Double.NaN, 270, true);
        return new NavigatorRouteData(header, true, true,
                new double[] {31.123456789012345, 121.98765432109876,
                        32.12345678901234, 122.9876543210987},
                new NavigatorRouteData.Maneuver[] {first, second});
    }

    private static String readGzip(File file) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPInputStream input = new GZIPInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
