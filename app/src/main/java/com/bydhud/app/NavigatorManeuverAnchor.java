package com.bydhud.app;

import java.util.Arrays;

/** Resolves a current target without altering the navigator's source geometry or maneuvers. */
final class NavigatorManeuverAnchor {
    static final long MAX_AGE_MS = 10_000L;
    private static final double EARTH_METERS = 6_371_008.8;
    enum Origin { NATIVE, ROUTE_DERIVED, UNAVAILABLE }

    static final class Result {
        final NavigatorRouteData.Header header;
        final long stepSerial;
        final Origin origin;
        final String reason;
        final Double latitude, longitude;
        final double progressMeters;
        Result(NavigatorRouteData.Header header, long stepSerial, Origin origin,
                String reason, Double latitude, Double longitude, double progressMeters) {
            this.header = header;
            this.stepSerial = stepSerial;
            this.origin = origin;
            this.reason = reason;
            this.latitude = latitude;
            this.longitude = longitude;
            this.progressMeters = progressMeters;
        }
    }

    private final NavigatorRouteData route;
    private final double[] cumulative;

    NavigatorManeuverAnchor(NavigatorRouteData route) {
        this.route = route;
        cumulative = new double[route.pointCount()];
        for (int i = 1; i < cumulative.length; i++) {
            cumulative[i] = cumulative[i - 1] + length(route.latitude(i - 1),
                    route.longitude(i - 1), route.latitude(i), route.longitude(i));
        }
    }

    Result resolve(NavigatorRouteStore.CurrentStep step, VehiclePosition position,
            DirectTbtFrame.SourceDistance distance, long nowMs) {
        if (step == null || !route.header().sameSnapshot(step.header)) return missing(step, "step_unavailable");
        if (Boolean.FALSE.equals(step.onRoute)) return missing(step, "off_route");
        if (Boolean.FALSE.equals(step.available)) return missing(step, "step_unavailable");
        if ("waze-5.25.0.1".equals(route.header().navigator)
                && step.currentSegmentIndex < 0 && step.nextSegmentIndex < 0
                && (step.currentInstruction == null || step.currentInstruction < 0)
                && (step.nextInstruction == null || step.nextInstruction < 0))
            return missing(step, "step_unavailable");
        // Only Maps currently supplies an authoritative current-item association.
        if ("gmaps-26.30".equals(route.header().navigator)
                && Boolean.TRUE.equals(step.available)
                && step.latitude != null && step.longitude != null) {
            return new Result(route.header(), step.stepSerial, Origin.NATIVE, "source_coordinate",
                    step.latitude, step.longitude, Double.NaN);
        }
        if (position == null || !fresh(position.elapsedRealtimeMs, nowMs)) return missing(step, "position_stale");
        if (!coordinate(position.latitude, position.longitude)) return missing(step, "position_invalid");
        if (distance == null || !fresh(distance.elapsedMs, nowMs)
                || distance.elapsedMs < route.header().sourceElapsedMs
                || distance.elapsedMs < step.stepElapsedMs) return missing(step, "distance_stale");
        if (!route.geometryAvailable() || cumulative.length < 2) return missing(step, "geometry_unavailable");

        double tolerance = Float.isFinite(position.accuracyMeters)
                && position.accuracyMeters >= 0 ? Math.max(30.0, position.accuracyMeters) : 30.0;
        double cosine = Math.cos(Math.toRadians(position.latitude));
        double bestDistance = Double.POSITIVE_INFINITY, progress = 0;
        double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < cumulative.length; i++) {
            double length = cumulative[i] - cumulative[i - 1];
            if (length <= 0) continue;
            double ax = Math.toRadians(longitudeDelta(route.longitude(i - 1) - position.longitude)) * EARTH_METERS * cosine;
            double ay = Math.toRadians(route.latitude(i - 1) - position.latitude) * EARTH_METERS;
            double bx = ax + Math.toRadians(longitudeDelta(route.longitude(i) - route.longitude(i - 1))) * EARTH_METERS * cosine;
            double by = Math.toRadians(route.latitude(i) - position.latitude) * EARTH_METERS;
            double dx = bx - ax, dy = by - ay;
            double norm = dx * dx + dy * dy;
            if (norm == 0) continue;
            double fraction = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / norm));
            double offset = Math.hypot(ax + fraction * dx, ay + fraction * dy);
            if (offset > tolerance) continue;
            double candidate = cumulative[i - 1] + fraction * length;
            first = Math.min(first, candidate);
            last = Math.max(last, candidate);
            if (offset < bestDistance) {
                bestDistance = offset;
                progress = candidate;
            }
        }
        if (!Double.isFinite(bestDistance)) return missing(step, "outside_route");
        // Nearby adjacent segments form one uncertainty interval; distant route passes do not.
        if (last - first > tolerance * 2.0) return missing(step, "ambiguous_progress");
        double target = progress + distance.meters;
        if (target > cumulative[cumulative.length - 1]) return missing(step, "beyond_route_end");
        int at = Arrays.binarySearch(cumulative, target);
        if (at >= 0) return derived(step, route.latitude(at), route.longitude(at), progress);
        int right = -at - 1;
        if (right <= 0 || right >= cumulative.length) return missing(step, "geometry_unavailable");
        double fraction = (target - cumulative[right - 1]) / (cumulative[right] - cumulative[right - 1]);
        double latitude = route.latitude(right - 1) + fraction * (route.latitude(right) - route.latitude(right - 1));
        double longitude = longitudeDelta(route.longitude(right - 1)
                + fraction * longitudeDelta(route.longitude(right) - route.longitude(right - 1)));
        return derived(step, latitude, longitude, progress);
    }

    private Result derived(NavigatorRouteStore.CurrentStep step, double lat, double lon, double progress) {
        return new Result(route.header(), step.stepSerial, Origin.ROUTE_DERIVED,
                "polyline_distance", lat, lon, progress);
    }

    private Result missing(NavigatorRouteStore.CurrentStep step, String reason) {
        return new Result(route.header(), step == null ? -1 : step.stepSerial,
                Origin.UNAVAILABLE, reason, null, null, Double.NaN);
    }

    static boolean fresh(long timestamp, long now) {
        return timestamp >= 0 && now >= timestamp && now - timestamp <= MAX_AGE_MS;
    }

    static boolean coordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    private static double longitudeDelta(double degrees) {
        return ((degrees + 540) % 360) - 180;
    }

    private static double length(double lat1, double lon1, double lat2, double lon2) {
        double sinLat = Math.sin(Math.toRadians(lat2 - lat1) / 2);
        double sinLon = Math.sin(Math.toRadians(longitudeDelta(lon2 - lon1)) / 2);
        double haversine = sinLat * sinLat + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
        return 2 * EARTH_METERS * Math.asin(Math.sqrt(Math.min(1, Math.max(0, haversine))));
    }
}
