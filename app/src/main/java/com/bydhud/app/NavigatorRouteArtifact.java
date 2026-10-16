package com.bydhud.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

final class NavigatorRouteArtifact {
    private static final String DIRECTORY = "route-geometry";
    private static final Object WRITE_LOCK = new Object();

    private NavigatorRouteArtifact() {
    }

    static String safeSource(String navigator) {
        if ("gmaps-26.30".equals(navigator)) return "gmaps-26.30";
        if ("waze-5.25.0.1".equals(navigator)) return "waze-5.25.0.1";
        return null;
    }

    static boolean write(File logsDir, NavigatorRouteData route) {
        if (logsDir == null || route == null) return false;
        NavigatorRouteData.Header header = route.header();
        String source = safeSource(header.navigator);
        if (source == null || header.producerEpoch <= 0 || header.routeRevision <= 0) {
            return false;
        }

        synchronized (WRITE_LOCK) {
            File routeDir = new File(new File(logsDir, DIRECTORY), source);
            if (!routeDir.isDirectory() && !routeDir.mkdirs()) return false;
            String fileName = header.producerEpoch + "-" + header.routeRevision + ".json.gz";
            File target = new File(routeDir, fileName);
            File temporary = new File(routeDir, fileName + ".tmp");
            if (target.isFile()) return !temporary.exists() || temporary.delete();
            if (temporary.exists() && !temporary.delete()) return false;

            try {
                writeJsonGzip(temporary, route);
                if (target.exists()) {
                    temporary.delete();
                    return target.isFile();
                }
                if (!temporary.renameTo(target)) {
                    temporary.delete();
                    return target.isFile();
                }
                return true;
            } catch (IOException | JSONException | RuntimeException error) {
                temporary.delete();
                return false;
            }
        }
    }

    private static void writeJsonGzip(File file, NavigatorRouteData route)
            throws IOException, JSONException {
        try (FileOutputStream fileOut = new FileOutputStream(file)) {
            GZIPOutputStream gzip = new GZIPOutputStream(fileOut);
            Writer text = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8));
            try {
                writeJson(text, route);
                text.flush();
                gzip.finish();
                fileOut.getFD().sync();
            } finally {
                text.close();
            }
        }
    }

    private static void writeJson(Writer json, NavigatorRouteData route)
            throws IOException, JSONException {
        NavigatorRouteData.Header header = route.header();
        JSONObject metadata = new JSONObject()
                .put("schemaVersion", 1)
                .put("source", header.navigator)
                .put("producerEpoch", header.producerEpoch)
                .put("routeRevision", header.routeRevision)
                .put("routeId", header.routeId)
                .put("sourceElapsedMs", header.sourceElapsedMs)
                .put("coordinateFrame", "navigator_source_degrees")
                .put("geometryAvailable", route.geometryAvailable())
                .put("maneuversAvailable", route.maneuversAvailable())
                .put("pointCount", route.pointCount())
                .put("maneuverCount", route.maneuverCount())
                .put("maneuverCoordinateCount", route.maneuverCount())
                .put("maneuverCoordinateAvailableCount",
                        route.availableManeuverCoordinateCount());
        String metadataJson = metadata.toString();
        json.write(metadataJson, 0, metadataJson.length() - 1);
        json.write(",\"points\":[");
        for (int i = 0; i < route.pointCount(); i++) {
            if (i != 0) json.write(',');
            json.write("{\"index\":");
            json.write(Integer.toString(i));
            json.write(",\"latitude\":");
            writeFiniteNumber(json, route.latitude(i));
            json.write(",\"longitude\":");
            writeFiniteNumber(json, route.longitude(i));
            json.write('}');
        }
        json.write("],\"maneuvers\":[");
        for (int i = 0; i < route.maneuverCount(); i++) {
            if (i != 0) json.write(',');
            NavigatorRouteData.Maneuver maneuver = route.maneuver(i);
            json.write(new JSONObject()
                    .put("id", maneuver.id == null ? JSONObject.NULL : maneuver.id)
                    .put("ordinal", maneuver.ordinal)
                    .put("latitude", finiteOrNull(maneuver.latitude))
                    .put("longitude", finiteOrNull(maneuver.longitude))
                    .put("coordinateAvailable", route.hasManeuverCoordinate(i))
                    .put("vertexIndex", maneuver.vertexIndex)
                    .put("instructionSegmentIndex", maneuver.instructionSegmentIndex)
                    .put("navigationSegmentIndex", maneuver.navigationSegmentIndex)
                    .put("type", maneuver.type == null ? JSONObject.NULL : maneuver.type)
                    .put("turnSide", maneuver.turnSide == null
                            ? JSONObject.NULL : maneuver.turnSide)
                    .put("incomingBearing", finiteOrNull(maneuver.incomingBearing))
                    .put("outgoingBearing", finiteOrNull(maneuver.outgoingBearing))
                    .put("sourceRotation", maneuver.sourceRotation == Integer.MIN_VALUE
                            ? JSONObject.NULL : maneuver.sourceRotation)
                    .put("syntheticPolyline", maneuver.syntheticPolyline)
                    .toString());
        }
        json.write("]}");
    }

    private static Object finiteOrNull(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? JSONObject.NULL : value;
    }

    private static void writeFiniteNumber(Writer json, double value) throws IOException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            json.write("null");
        } else {
            json.write(Double.toString(value));
        }
    }
}
