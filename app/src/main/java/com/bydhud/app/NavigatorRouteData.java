package com.bydhud.app;

import java.util.Objects;

/** Immutable navigator-supplied route geometry, kept separate from live TBT frames. */
public final class NavigatorRouteData {
    public static final class Header {
        public final String navigator;
        public final long producerEpoch;
        public final long routeRevision;
        public final String routeId;
        public final long sourceElapsedMs;

        public Header(String navigator, long producerEpoch, long routeRevision,
                String routeId, long sourceElapsedMs) {
            if (navigator == null || navigator.isEmpty() || navigator.length() > 64) {
                throw new IllegalArgumentException("invalid navigator");
            }
            if (producerEpoch < 0 || routeRevision < 0 || sourceElapsedMs < 0) {
                throw new IllegalArgumentException("invalid route identity or source time");
            }
            if (routeId != null && routeId.length() > 512) {
                throw new IllegalArgumentException("route id is too long");
            }
            this.navigator = navigator;
            this.producerEpoch = producerEpoch;
            this.routeRevision = routeRevision;
            this.routeId = routeId == null ? "" : routeId;
            this.sourceElapsedMs = sourceElapsedMs;
        }

        boolean sameRevision(Header other) {
            return other != null && navigator.equals(other.navigator)
                    && producerEpoch == other.producerEpoch
                    && routeRevision == other.routeRevision;
        }

        boolean sameSnapshot(Header other) {
            return sameRevision(other) && routeId.equals(other.routeId)
                    && sourceElapsedMs == other.sourceElapsedMs;
        }
    }

    public static final class Maneuver {
        public final String id;
        public final int ordinal;
        public final double latitude;
        public final double longitude;
        public final int vertexIndex;
        public final int instructionSegmentIndex;
        public final int navigationSegmentIndex;
        public final String type;
        public final String turnSide;
        public final double incomingBearing;
        public final double outgoingBearing;
        public final int sourceRotation;
        public final boolean syntheticPolyline;

        Maneuver(String id, int ordinal, double latitude, double longitude,
                int vertexIndex, int instructionSegmentIndex, int navigationSegmentIndex,
                String type, String turnSide, double incomingBearing, double outgoingBearing,
                int sourceRotation, boolean syntheticPolyline) {
            this.id = id;
            this.ordinal = ordinal;
            this.latitude = latitude;
            this.longitude = longitude;
            this.vertexIndex = vertexIndex;
            this.instructionSegmentIndex = instructionSegmentIndex;
            this.navigationSegmentIndex = navigationSegmentIndex;
            this.type = type;
            this.turnSide = turnSide;
            this.incomingBearing = incomingBearing;
            this.outgoingBearing = outgoingBearing;
            this.sourceRotation = sourceRotation;
            this.syntheticPolyline = syntheticPolyline;
        }
    }

    private final Header header;
    private final boolean geometryAvailable;
    private final boolean maneuversAvailable;
    private final double[] coordinates;
    private final Maneuver[] maneuvers;

    NavigatorRouteData(Header header, boolean geometryAvailable, boolean maneuversAvailable,
            double[] ownedCoordinates, Maneuver[] ownedManeuvers) {
        this.header = Objects.requireNonNull(header, "header");
        this.geometryAvailable = geometryAvailable;
        this.maneuversAvailable = maneuversAvailable;
        this.coordinates = ownedCoordinates;
        this.maneuvers = ownedManeuvers;
    }

    public Header header() {
        return header;
    }

    public boolean geometryAvailable() {
        return geometryAvailable;
    }

    public boolean maneuversAvailable() {
        return maneuversAvailable;
    }

    public int pointCount() {
        return coordinates.length / 2;
    }

    public double latitude(int index) {
        return coordinates[index * 2];
    }

    public double longitude(int index) {
        return coordinates[index * 2 + 1];
    }

    public int maneuverCount() {
        return maneuvers.length;
    }

    public Maneuver maneuver(int index) {
        return maneuvers[index];
    }
}
