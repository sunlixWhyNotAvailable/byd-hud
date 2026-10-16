package com.bydhud.app;

import java.util.concurrent.atomic.AtomicLong;

/** Builds one coherent real vehicle pose and route line from a live fix and navigator route. */
final class HudSpatialGeometry {
    private static final double MAX_ACCURACY_METERS = 50.0;
    private static final double MIN_SPEED_FOR_BEARING_MPS = 5.0 / 3.6;
    private static final AtomicLong NEXT_IDENTITY = new AtomicLong(1L);

    enum Mode { OFF, REAL, SCHEMATIC }
    enum HeadingOrigin { MEASURED, ROUTE_TANGENT, CACHED }

    static final class Result {
        private static final Result OFF = new Result(Mode.OFF, null, -1, "off",
                NavigatorManeuverAnchor.Origin.UNAVAILABLE, null, null, null, null,
                null, null, null, -1, 0);

        final Mode mode;
        final NavigatorRouteData.Header header;
        final long stepSerial;
        final String reason;
        final NavigatorManeuverAnchor.Origin origin;
        final Double anchorLatitude, anchorLongitude;
        final Double latitude, longitude, headingDegrees;
        final HeadingOrigin headingOrigin;
        final String line;
        final long validUntilElapsedMs;
        final long identity;

        private Result(Mode mode, NavigatorRouteData.Header header, long stepSerial,
                String reason, NavigatorManeuverAnchor.Origin origin,
                Double anchorLatitude, Double anchorLongitude, Double latitude,
                Double longitude, Double headingDegrees, HeadingOrigin headingOrigin,
                String line, long validUntilElapsedMs, long identity) {
            this.mode = mode;
            this.header = header;
            this.stepSerial = stepSerial;
            this.reason = reason;
            this.origin = origin;
            this.anchorLatitude = anchorLatitude;
            this.anchorLongitude = anchorLongitude;
            this.latitude = latitude;
            this.longitude = longitude;
            this.headingDegrees = headingDegrees;
            this.headingOrigin = headingOrigin;
            this.line = line;
            this.validUntilElapsedMs = validUntilElapsedMs;
            this.identity = identity;
        }

        boolean usableAt(long nowMs) {
            return mode == Mode.REAL && nowMs >= 0 && nowMs < validUntilElapsedMs;
        }

        private Result withIdentity(long nextIdentity) {
            return new Result(mode, header, stepSerial, reason, origin, anchorLatitude,
                    anchorLongitude, latitude, longitude, headingDegrees, headingOrigin,
                    line, validUntilElapsedMs, nextIdentity);
        }

        static Result off() {
            return OFF;
        }

        static Result fallback(NavigatorRouteData.Header header, long stepSerial, String reason) {
            return new Result(Mode.SCHEMATIC, header, stepSerial,
                    reason == null || reason.isEmpty() ? "unavailable" : reason,
                    NavigatorManeuverAnchor.Origin.UNAVAILABLE, null, null, null, null,
                    null, null, null, -1, 0);
        }
    }

    private final NavigatorRouteData route;
    private final NavigatorManeuverAnchor anchor;
    private NavigatorRouteData.Header cachedHeader;
    private int cachedSegmentIndex = -1;
    private double cachedHeadingDegrees;
    private long cachedHeadingElapsedMs = -1;
    private Result previous;

    HudSpatialGeometry(NavigatorRouteData route) {
        if (route == null) throw new IllegalArgumentException("route is required");
        this.route = route;
        anchor = new NavigatorManeuverAnchor(route);
    }

    synchronized Result resolve(NavigatorRouteStore.CurrentStep step, VehiclePosition position,
            DirectTbtFrame.SourceDistance distance, long nowMs) {
        long stepSerial = step == null ? -1 : step.stepSerial;
        String stepReason = anchor.stepUnavailableReason(step);
        if (stepReason != null) return publish(fallback(stepSerial, stepReason, null));
        if (position == null || !NavigatorManeuverAnchor.fresh(position.elapsedRealtimeMs, nowMs))
            return publish(fallback(stepSerial, "position_stale", null));
        if (!NavigatorManeuverAnchor.coordinate(position.latitude, position.longitude))
            return publish(fallback(stepSerial, "position_invalid", null));
        if (!Float.isFinite(position.accuracyMeters)
                || position.accuracyMeters < 0f || position.accuracyMeters > MAX_ACCURACY_METERS)
            return publish(fallback(stepSerial, "position_accuracy_unavailable", null));

        NavigatorManeuverAnchor.PositionMatch vehicle = anchor.matchPosition(position, nowMs);
        if (!vehicle.available()) return publish(fallback(stepSerial, vehicle.reason, null));
        NavigatorManeuverAnchor.Result target = anchor.resolve(step, position, distance, nowMs, vehicle);
        if (target.origin == NavigatorManeuverAnchor.Origin.UNAVAILABLE)
            return publish(fallback(stepSerial, target.reason, target));

        double targetProgress;
        if (target.origin == NavigatorManeuverAnchor.Origin.NATIVE) {
            NavigatorManeuverAnchor.PositionMatch nativeProjection = anchor.projectCoordinate(
                    target.latitude, target.longitude, MAX_ACCURACY_METERS);
            if (!nativeProjection.available())
                return publish(fallback(stepSerial, "native_anchor_off_route", target));
            targetProgress = nativeProjection.progressMeters;
            if (targetProgress + 0.05 < vehicle.progressMeters)
                return publish(fallback(stepSerial, "anchor_behind_vehicle", target));
        } else {
            if (distance == null) return publish(fallback(stepSerial, "distance_stale", target));
            targetProgress = vehicle.progressMeters + distance.meters;
        }

        double tangent = anchor.forwardTangentDegrees(vehicle);
        boolean measuredBearing = position.bearingDegrees != null
                && position.speedMetersPerSecond != null
                && position.speedMetersPerSecond >= MIN_SPEED_FOR_BEARING_MPS
                && position.bearingAccuracyDegrees != null
                && position.bearingAccuracyDegrees <= 30f;
        double heading;
        HeadingOrigin headingOrigin;
        long headingElapsedMs;
        if (measuredBearing) {
            if (Double.isFinite(tangent) && angularDifference(position.bearingDegrees, tangent) > 90.0)
                return publish(fallback(stepSerial, "bearing_conflicts_with_route", target));
            heading = NavigatorManeuverAnchor.normalizeDegrees(position.bearingDegrees);
            headingOrigin = HeadingOrigin.MEASURED;
            headingElapsedMs = position.elapsedRealtimeMs;
            rememberHeading(vehicle.segmentIndex, heading, headingElapsedMs);
        } else if (Double.isFinite(tangent)) {
            heading = tangent;
            headingOrigin = HeadingOrigin.ROUTE_TANGENT;
            headingElapsedMs = position.elapsedRealtimeMs;
            rememberHeading(vehicle.segmentIndex, heading, headingElapsedMs);
        } else if (cachedHeadingUsable(vehicle.segmentIndex, nowMs)) {
            heading = cachedHeadingDegrees;
            headingOrigin = HeadingOrigin.CACHED;
            headingElapsedMs = cachedHeadingElapsedMs;
        } else {
            return publish(fallback(stepSerial, "heading_unavailable", target));
        }

        String line = anchor.maneuverLine(vehicle, targetProgress,
                target.latitude, target.longitude);
        if (line == null) return publish(fallback(stepSerial, "route_line_unavailable", target));
        long validUntil = addFreshnessDeadline(position.elapsedRealtimeMs);
        if (target.origin == NavigatorManeuverAnchor.Origin.ROUTE_DERIVED)
            validUntil = Math.min(validUntil, addFreshnessDeadline(distance.elapsedMs));
        if (headingOrigin == HeadingOrigin.CACHED)
            validUntil = Math.min(validUntil, addFreshnessDeadline(headingElapsedMs));
        return publish(new Result(Mode.REAL, route.header(), stepSerial, "real_route_geometry",
                target.origin, target.latitude, target.longitude, position.latitude,
                position.longitude, heading, headingOrigin, line, validUntil, 0));
    }

    private Result fallback(long stepSerial, String reason, NavigatorManeuverAnchor.Result target) {
        return new Result(Mode.SCHEMATIC, route.header(), stepSerial, reason,
                target == null ? NavigatorManeuverAnchor.Origin.UNAVAILABLE : target.origin,
                target == null ? null : target.latitude, target == null ? null : target.longitude,
                null, null, null, null, null, -1, 0);
    }

    private boolean cachedHeadingUsable(int segmentIndex, long nowMs) {
        return cachedHeader != null && route.header().sameSnapshot(cachedHeader)
                && cachedSegmentIndex == segmentIndex
                && NavigatorManeuverAnchor.fresh(cachedHeadingElapsedMs, nowMs);
    }

    private void rememberHeading(int segmentIndex, double degrees, long elapsedMs) {
        cachedHeader = route.header();
        cachedSegmentIndex = segmentIndex;
        cachedHeadingDegrees = degrees;
        cachedHeadingElapsedMs = elapsedMs;
    }

    private Result publish(Result next) {
        if (previous != null && sameContent(previous, next)) {
            if (previous.validUntilElapsedMs == next.validUntilElapsedMs) return previous;
            Result refreshed = next.withIdentity(previous.identity);
            previous = refreshed;
            return refreshed;
        }
        Result identified = next.withIdentity(NEXT_IDENTITY.getAndIncrement());
        previous = identified;
        return identified;
    }

    private static boolean sameContent(Result first, Result second) {
        return first.mode == second.mode && sameHeader(first.header, second.header)
                && first.stepSerial == second.stepSerial && equal(first.reason, second.reason)
                && first.origin == second.origin
                && equal(first.anchorLatitude, second.anchorLatitude)
                && equal(first.anchorLongitude, second.anchorLongitude)
                && equal(first.latitude, second.latitude) && equal(first.longitude, second.longitude)
                && equal(first.headingDegrees, second.headingDegrees)
                && first.headingOrigin == second.headingOrigin && equal(first.line, second.line);
    }

    private static boolean sameHeader(NavigatorRouteData.Header first, NavigatorRouteData.Header second) {
        return first == null ? second == null : first.sameSnapshot(second);
    }

    private static boolean equal(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }

    private static double angularDifference(double first, double second) {
        return Math.abs(((first - second + 540.0) % 360.0) - 180.0);
    }

    private static long addFreshnessDeadline(long timestamp) {
        long delta = NavigatorManeuverAnchor.MAX_AGE_MS + 1L;
        return timestamp > Long.MAX_VALUE - delta ? Long.MAX_VALUE : timestamp + delta;
    }
}
