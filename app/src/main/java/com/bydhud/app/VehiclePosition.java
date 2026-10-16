package com.bydhud.app;

/** Immutable latest vehicle location supplied to route matching. */
public final class VehiclePosition {
    public final double latitude;
    public final double longitude;
    public final long elapsedRealtimeMs;
    public final float accuracyMeters;
    public final Float bearingDegrees;
    public final boolean mock;

    VehiclePosition(double latitude, double longitude, long elapsedRealtimeMs,
            float accuracyMeters, Float bearingDegrees, boolean mock) {
        if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d
                || !Double.isFinite(longitude) || longitude < -180d || longitude > 180d
                || elapsedRealtimeMs < 0L
                || (!Float.isNaN(accuracyMeters)
                        && (!Float.isFinite(accuracyMeters) || accuracyMeters < 0f))
                || (bearingDegrees != null
                        && (!Float.isFinite(bearingDegrees)
                                || bearingDegrees < 0f || bearingDegrees >= 360f))) {
            throw new IllegalArgumentException("invalid vehicle position");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.accuracyMeters = accuracyMeters;
        this.bearingDegrees = bearingDegrees;
        this.mock = mock;
    }
}
