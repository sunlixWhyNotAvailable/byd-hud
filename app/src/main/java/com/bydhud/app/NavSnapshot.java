package com.bydhud.app;

//keeps current navigation state immutable so UI and sender can reason about the same route frame.

public final class NavSnapshot {
    //defines the SourceApp module boundary so related behavior stays readable inside one unit.
    public enum SourceApp {
        GOOGLE_MAPS,
        WAZE,
        UNKNOWN
    }

    //defines the Maneuver module boundary so related behavior stays readable inside one unit.
    public enum Maneuver {
        UNKNOWN,
        STRAIGHT,
        LEFT_45,
        RIGHT_45,
        LEFT_90,
        RIGHT_90,
        LEFT_150,
        RIGHT_150,
        UTURN_LEFT,
        UTURN_RIGHT,
        RAMP_LEFT,
        RAMP_RIGHT,
        ROUNDABOUT_RIGHT_EXIT,
        ROUNDABOUT_LEFT_EXIT,
        ARRIVE,
        HIDE
    }

    public final long elapsedRealtimeMs;
    public final SourceApp sourceApp;
    public final String packageName;
    public final Maneuver maneuver;
    public final int distanceMeters;
    public final String streetName;
    public final int roundaboutExitNumber;
    public final String laneString;
    public final int confidence;
    public final String rawReason;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    public NavSnapshot(long elapsedRealtimeMs, SourceApp sourceApp, String packageName,
            Maneuver maneuver, int distanceMeters, String streetName,
            int roundaboutExitNumber, String laneString, int confidence, String rawReason) {
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.sourceApp = sourceApp == null ? SourceApp.UNKNOWN : sourceApp;
        this.packageName = packageName == null ? "" : packageName;
        this.maneuver = maneuver == null ? Maneuver.UNKNOWN : maneuver;
        this.distanceMeters = distanceMeters;
        this.streetName = streetName == null ? "" : streetName;
        this.roundaboutExitNumber = roundaboutExitNumber;
        this.laneString = laneString == null ? "" : laneString;
        this.confidence = clampConfidence(confidence);
        this.rawReason = rawReason == null ? "" : rawReason;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    public boolean isUsableForHud() {
        return confidence >= 70 && maneuver != Maneuver.UNKNOWN;
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static int clampConfidence(int confidence) {
        if (confidence < 0) {
            return 0;
        }
        if (confidence > 100) {
            return 100;
        }
        return confidence;
    }
}
