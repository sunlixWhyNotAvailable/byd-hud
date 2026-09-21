package com.bydhud.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The retained 1 Hz WGS84 Shanghai fixture with only its initial hold shortened. */
final class ShanghaiRoute {
    static final int PREPARATION_SECONDS = 15;
    static final int DURATION_SECONDS = 295;
    static final float ACCURACY_METERS = 3f;
    private static final int REMOVED_INITIAL_HOLD_FIXES = 15;
    private static final int SOURCE_POINT_COUNT = 311;
    private static final String ASSET = "shanghai/shanghai_east_city_drive.gpx";
    private static final Pattern TRACK_POINT = Pattern.compile(
            "<trkpt\\s+lat=\\\"([^\\\"]+)\\\"\\s+lon=\\\"([^\\\"]+)\\\"\\s*/?>");

    static final class Point {
        final double latitude;
        final double longitude;
        final float speedMetersPerSecond;
        final float bearingDegrees;
        final float accuracyMeters;

        Point(double latitude, double longitude, float speedMetersPerSecond,
                float bearingDegrees, float accuracyMeters) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.speedMetersPerSecond = speedMetersPerSecond;
            this.bearingDegrees = bearingDegrees;
            this.accuracyMeters = accuracyMeters;
        }
    }

    private final List<Point> points;

    private ShanghaiRoute(List<Point> points) {
        this.points = points;
    }

    static ShanghaiRoute load(Context context) throws IOException {
        try (InputStream input = context.getApplicationContext().getAssets().open(ASSET)) {
            return parse(input);
        }
    }

    static ShanghaiRoute parse(InputStream input) throws IOException {
        String xml = readUtf8(input);
        Matcher matcher = TRACK_POINT.matcher(xml);
        List<double[]> source = new ArrayList<>(SOURCE_POINT_COUNT);
        while (matcher.find()) {
            source.add(new double[] {
                    Double.parseDouble(matcher.group(1)),
                    Double.parseDouble(matcher.group(2))
            });
        }
        if (source.size() != SOURCE_POINT_COUNT) {
            throw new IOException("Shanghai route point count mismatch: " + source.size());
        }

        List<Point> route = new ArrayList<>(DURATION_SECONDS + 1);
        for (int second = 0; second <= DURATION_SECONDS; second++) {
            int sourceIndex = second <= PREPARATION_SECONDS
                    ? 0 : second + REMOVED_INITIAL_HOLD_FIXES;
            double[] current = source.get(sourceIndex);
            int adjacentIndex = sourceIndex < source.size() - 1 ? sourceIndex + 1 : sourceIndex - 1;
            double[] adjacent = source.get(adjacentIndex);
            float speed = (float) Math.min(70d, distanceMeters(current, adjacent));
            float bearing = bearingDegrees(current, adjacent);
            if (current[0] == adjacent[0] && current[1] == adjacent[1]) {
                speed = 0f;
                bearing = 0f;
            }
            route.add(new Point(current[0], current[1], speed, bearing, ACCURACY_METERS));
        }
        return new ShanghaiRoute(Collections.unmodifiableList(route));
    }

    Point pointAt(int elapsedSeconds) {
        if (elapsedSeconds < 0 || elapsedSeconds > DURATION_SECONDS) {
            throw new IllegalArgumentException("elapsedSeconds must be 0.." + DURATION_SECONDS);
        }
        return points.get(elapsedSeconds);
    }

    int pointCount() {
        return points.size();
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static double distanceMeters(double[] from, double[] to) {
        double lat1 = Math.toRadians(from[0]);
        double lat2 = Math.toRadians(to[0]);
        double deltaLat = Math.toRadians(to[0] - from[0]);
        double deltaLon = Math.toRadians(to[1] - from[1]);
        double sinLat = Math.sin(deltaLat / 2d);
        double sinLon = Math.sin(deltaLon / 2d);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 6_371_000d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    private static float bearingDegrees(double[] from, double[] to) {
        double lat1 = Math.toRadians(from[0]);
        double lat2 = Math.toRadians(to[0]);
        double deltaLon = Math.toRadians(to[1] - from[1]);
        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360d) % 360d);
    }
}
