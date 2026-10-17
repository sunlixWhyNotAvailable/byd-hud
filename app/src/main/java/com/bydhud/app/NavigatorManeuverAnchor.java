package com.bydhud.app;

import java.util.Arrays;

/** Resolves a current target without altering the navigator's source geometry or maneuvers. */
final class NavigatorManeuverAnchor {
    static final long MAX_AGE_MS = 10_000L;
    private static final double EARTH_METERS = 6_371_008.8;
    private static final double SAME_POINT_METERS = 0.05;
    private static final int MAX_LINE_BYTES = 64 * 1024;
    private static final double MIN_DISTINCT_ROUTE_GAP_METERS = 5.0;
    private static final double MAX_SINGLE_TURN_PATH_TO_CHORD_RATIO = 2.0;
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

    /** Shared route projection used by the legacy anchor and live geometry. */
    static final class PositionMatch {
        final VehiclePosition sourcePosition;
        final String reason;
        final double progressMeters;
        final double latitude, longitude;
        final int segmentIndex;

        private PositionMatch(VehiclePosition sourcePosition, String reason,
                double progressMeters, double latitude, double longitude,
                int segmentIndex) {
            this.sourcePosition = sourcePosition;
            this.reason = reason;
            this.progressMeters = progressMeters;
            this.latitude = latitude;
            this.longitude = longitude;
            this.segmentIndex = segmentIndex;
        }

        boolean available() { return reason == null; }

        private static PositionMatch missing(VehiclePosition sourcePosition, String reason) {
            return new PositionMatch(sourcePosition, reason, Double.NaN, Double.NaN,
                    Double.NaN, -1);
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
        return resolve(step, position, distance, nowMs, null);
    }

    Result resolve(NavigatorRouteStore.CurrentStep step, VehiclePosition position,
            DirectTbtFrame.SourceDistance distance, long nowMs, PositionMatch matchedPosition) {
        String stepReason = stepUnavailableReason(step);
        if (stepReason != null) return missing(step, stepReason);
        if (isNativeStep(step)) {
            return new Result(route.header(), step.stepSerial, Origin.NATIVE, "source_coordinate",
                    step.latitude, step.longitude, Double.NaN);
        }
        if (position == null || !fresh(position.elapsedRealtimeMs, nowMs)) return missing(step, "position_stale");
        if (!coordinate(position.latitude, position.longitude)) return missing(step, "position_invalid");
        if (distance == null || !fresh(distance.elapsedMs, nowMs)
                || distance.elapsedMs < route.header().sourceElapsedMs
                || distance.elapsedMs < step.stepElapsedMs) return missing(step, "distance_stale");
        if (!route.geometryAvailable() || cumulative.length < 2) return missing(step, "geometry_unavailable");

        PositionMatch projected = matchedPosition != null
                && matchedPosition.sourcePosition == position
                ? matchedPosition : matchPosition(position, nowMs);
        if (!projected.available()) return missing(step, projected.reason);
        double target = projected.progressMeters + distance.meters;
        if (target > cumulative[cumulative.length - 1]) return missing(step, "beyond_route_end");
        int at = Arrays.binarySearch(cumulative, target);
        if (at >= 0) return derived(step, route.latitude(at), route.longitude(at), projected.progressMeters);
        int right = -at - 1;
        if (right <= 0 || right >= cumulative.length) return missing(step, "geometry_unavailable");
        double fraction = (target - cumulative[right - 1]) / (cumulative[right] - cumulative[right - 1]);
        double latitude = route.latitude(right - 1) + fraction * (route.latitude(right) - route.latitude(right - 1));
        double longitude = longitudeDelta(route.longitude(right - 1)
                + fraction * longitudeDelta(route.longitude(right) - route.longitude(right - 1)));
        return derived(step, latitude, longitude, projected.progressMeters);
    }

    String stepUnavailableReason(NavigatorRouteStore.CurrentStep step) {
        if (step == null || !route.header().sameSnapshot(step.header)) return "step_unavailable";
        if (Boolean.FALSE.equals(step.onRoute)) return "off_route";
        if (Boolean.FALSE.equals(step.available)) return "step_unavailable";
        if ("waze-5.25.0.1".equals(route.header().navigator)
                && step.currentSegmentIndex < 0 && step.nextSegmentIndex < 0
                && (step.currentInstruction == null || step.currentInstruction < 0)
                && (step.nextInstruction == null || step.nextInstruction < 0))
            return "step_unavailable";
        return null;
    }

    PositionMatch matchPosition(VehiclePosition position, long nowMs) {
        if (position == null || !fresh(position.elapsedRealtimeMs, nowMs))
            return PositionMatch.missing(position, "position_stale");
        if (!coordinate(position.latitude, position.longitude))
            return PositionMatch.missing(position, "position_invalid");
        double tolerance = Float.isFinite(position.accuracyMeters) && position.accuracyMeters >= 0
                ? Math.max(30.0, position.accuracyMeters) : 30.0;
        return project(position.latitude, position.longitude, tolerance, position);
    }

    PositionMatch projectCoordinate(double latitude, double longitude, double toleranceMeters) {
        if (!coordinate(latitude, longitude) || !Double.isFinite(toleranceMeters) || toleranceMeters < 0)
            return PositionMatch.missing(null, "position_invalid");
        return project(latitude, longitude, toleranceMeters, null);
    }

    double forwardTangentDegrees(PositionMatch position) {
        if (position == null || !position.available()) return Double.NaN;
        for (int i = position.segmentIndex + 1; i < route.pointCount(); i++) {
            double nextLatitude = route.latitude(i), nextLongitude = route.longitude(i);
            if (length(position.latitude, position.longitude, nextLatitude, nextLongitude) > SAME_POINT_METERS)
                return bearing(position.latitude, position.longitude, nextLatitude, nextLongitude);
        }
        return Double.NaN;
    }

    String maneuverLine(PositionMatch start, double anchorProgressMeters,
            double anchorLatitude, double anchorLongitude) {
        if (start == null || !start.available() || !Double.isFinite(anchorProgressMeters)
                || !coordinate(anchorLatitude, anchorLongitude)
                || anchorProgressMeters + SAME_POINT_METERS < start.progressMeters
                || anchorProgressMeters > cumulative[cumulative.length - 1]) return null;
        StringBuilder line = new StringBuilder("[");
        if (!appendPoint(line, start.longitude, start.latitude)) return null;
        int pointCount = 1;
        double lastLatitude = start.latitude, lastLongitude = start.longitude;
        for (int i = 0; i < cumulative.length; i++) {
            if (cumulative[i] <= start.progressMeters || cumulative[i] >= anchorProgressMeters) continue;
            double latitude = route.latitude(i), longitude = route.longitude(i);
            if (length(lastLatitude, lastLongitude, latitude, longitude) <= SAME_POINT_METERS) continue;
            if (!appendPoint(line, longitude, latitude)) return null;
            pointCount++;
            lastLatitude = latitude;
            lastLongitude = longitude;
        }
        if (length(lastLatitude, lastLongitude, anchorLatitude, anchorLongitude) > SAME_POINT_METERS) {
            if (!appendPoint(line, anchorLongitude, anchorLatitude)) return null;
            pointCount++;
            lastLatitude = anchorLatitude;
            lastLongitude = anchorLongitude;
        }
        boolean departureFound = false;
        for (int i = 0; i < cumulative.length; i++) {
            if (cumulative[i] <= anchorProgressMeters) continue;
            double latitude = route.latitude(i), longitude = route.longitude(i);
            if (length(anchorLatitude, anchorLongitude, latitude, longitude) <= SAME_POINT_METERS
                    || length(lastLatitude, lastLongitude, latitude, longitude) <= SAME_POINT_METERS) continue;
            if (!appendPoint(line, longitude, latitude)) return null;
            departureFound = true;
            break;
        }
        // The final approach ends at the destination; there is no departure vertex beyond it.
        int last = cumulative.length - 1;
        if (!departureFound && (pointCount < 2
                || cumulative[last] - anchorProgressMeters > SAME_POINT_METERS
                || length(anchorLatitude, anchorLongitude, route.latitude(last),
                        route.longitude(last)) > SAME_POINT_METERS)) return null;
        if (line.length() + 1 > MAX_LINE_BYTES) return null;
        return line.append(']').toString();
    }

    private PositionMatch project(double latitude, double longitude, double tolerance,
            VehiclePosition sourcePosition) {
        if (!route.geometryAvailable() || cumulative.length < 2)
            return PositionMatch.missing(sourcePosition, "geometry_unavailable");
        double cosine = Math.cos(Math.toRadians(latitude));
        double bestDistance = Double.POSITIVE_INFINITY, progress = 0, fractionAtBest = 0;
        double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY;
        int segmentAtBest = -1;
        boolean hasProjection = false, distinctPassAmbiguous = false;
        double firstProjectionProgress = 0, firstProjectionLatitude = 0, firstProjectionLongitude = 0;
        double lastProjectionProgress = 0, lastProjectionLatitude = 0, lastProjectionLongitude = 0;
        for (int i = 1; i < cumulative.length; i++) {
            double segmentLength = cumulative[i] - cumulative[i - 1];
            if (segmentLength <= 0) continue;
            double ax = Math.toRadians(longitudeDelta(route.longitude(i - 1) - longitude)) * EARTH_METERS * cosine;
            double ay = Math.toRadians(route.latitude(i - 1) - latitude) * EARTH_METERS;
            double bx = ax + Math.toRadians(longitudeDelta(route.longitude(i) - route.longitude(i - 1))) * EARTH_METERS * cosine;
            double by = Math.toRadians(route.latitude(i) - latitude) * EARTH_METERS;
            double dx = bx - ax, dy = by - ay;
            double norm = dx * dx + dy * dy;
            if (norm == 0) continue;
            double fraction = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / norm));
            double offset = Math.hypot(ax + fraction * dx, ay + fraction * dy);
            if (offset > tolerance) continue;
            double candidate = cumulative[i - 1] + fraction * segmentLength;
            double projectedLatitude = route.latitude(i - 1)
                    + fraction * (route.latitude(i) - route.latitude(i - 1));
            double projectedLongitude = longitudeDelta(route.longitude(i - 1)
                    + fraction * longitudeDelta(route.longitude(i) - route.longitude(i - 1)));
            if (hasProjection) {
                distinctPassAmbiguous |= isDistinctRoutePass(firstProjectionProgress,
                        firstProjectionLatitude, firstProjectionLongitude, candidate,
                        projectedLatitude, projectedLongitude, cosine)
                        || isDistinctRoutePass(lastProjectionProgress, lastProjectionLatitude,
                        lastProjectionLongitude, candidate, projectedLatitude, projectedLongitude, cosine);
            } else {
                firstProjectionProgress = candidate;
                firstProjectionLatitude = projectedLatitude;
                firstProjectionLongitude = projectedLongitude;
                hasProjection = true;
            }
            lastProjectionProgress = candidate;
            lastProjectionLatitude = projectedLatitude;
            lastProjectionLongitude = projectedLongitude;
            first = Math.min(first, candidate);
            last = Math.max(last, candidate);
            if (offset < bestDistance) {
                bestDistance = offset;
                progress = candidate;
                fractionAtBest = fraction;
                segmentAtBest = i - 1;
            }
        }
        if (!Double.isFinite(bestDistance)) return PositionMatch.missing(sourcePosition, "outside_route");
        if (distinctPassAmbiguous || last - first > tolerance * 2.0 + 0.01)
            return PositionMatch.missing(sourcePosition, "ambiguous_progress");
        double projectedLatitude = route.latitude(segmentAtBest)
                + fractionAtBest * (route.latitude(segmentAtBest + 1) - route.latitude(segmentAtBest));
        double projectedLongitude = longitudeDelta(route.longitude(segmentAtBest)
                + fractionAtBest * longitudeDelta(route.longitude(segmentAtBest + 1)
                - route.longitude(segmentAtBest)));
        return new PositionMatch(sourcePosition, null, progress, projectedLatitude,
                projectedLongitude, segmentAtBest);
    }

    private static boolean isDistinctRoutePass(double firstProgress, double firstLatitude,
            double firstLongitude, double secondProgress, double secondLatitude,
            double secondLongitude, double cosineLatitude) {
        double routeGap = Math.abs(firstProgress - secondProgress);
        if (routeGap <= MIN_DISTINCT_ROUTE_GAP_METERS) return false;
        double east = Math.toRadians(longitudeDelta(secondLongitude - firstLongitude))
                * EARTH_METERS * cosineLatitude;
        double north = Math.toRadians(secondLatitude - firstLatitude) * EARTH_METERS;
        double chord = Math.hypot(east, north);
        // A right-angle turn tops out at sqrt(2):1; a nearby return/crossing reaches 2:1.
        return routeGap + 0.01 >= MAX_SINGLE_TURN_PATH_TO_CHORD_RATIO * chord;
    }

    private boolean isNativeStep(NavigatorRouteStore.CurrentStep step) {
        return "gmaps-26.30".equals(route.header().navigator)
                && Boolean.TRUE.equals(step.available)
                && step.latitude != null && step.longitude != null;
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

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double fromLat = Math.toRadians(lat1), toLat = Math.toRadians(lat2);
        double deltaLon = Math.toRadians(longitudeDelta(lon2 - lon1));
        double y = Math.sin(deltaLon) * Math.cos(toLat);
        double x = Math.cos(fromLat) * Math.sin(toLat)
                - Math.sin(fromLat) * Math.cos(toLat) * Math.cos(deltaLon);
        return normalizeDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    static double normalizeDegrees(double degrees) {
        return ((degrees % 360.0) + 360.0) % 360.0;
    }

    private static boolean appendPoint(StringBuilder line, double longitude, double latitude) {
        if (!coordinate(latitude, longitude)) return false;
        String point = "[" + Double.toString(longitude) + "," + Double.toString(latitude) + ",0]";
        int comma = line.length() > 1 ? 1 : 0;
        if (line.length() + comma + point.length() + 1 > MAX_LINE_BYTES) return false;
        if (comma != 0) line.append(',');
        line.append(point);
        return true;
    }
}
