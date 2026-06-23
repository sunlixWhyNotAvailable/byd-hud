package com.bydhud.app;

//scores maneuver evidence so native, PNG, accessibility, and notification signals can be compared safely.

final class NavManeuverEvidence {
    //defines the Source module boundary so related behavior stays readable inside one unit.
    enum Source {
        NONE,
        LARGE_ICON,
        TEXT
    }

    static final NavManeuverEvidence NONE =
            new NavManeuverEvidence(Source.NONE, NavSnapshot.Maneuver.UNKNOWN,
                    HudState.TURN_BITMAP_BLANK_SOURCE_ID, 0, 0L, "none");

    final Source source;
    final NavSnapshot.Maneuver maneuver;
    final int sourceManeuver;
    final int confidence;
    final long freshUntilElapsedMs;
    final String reason;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavManeuverEvidence(Source source, NavSnapshot.Maneuver maneuver,
            int sourceManeuver, int confidence, long freshUntilElapsedMs, String reason) {
        this.source = source == null ? Source.NONE : source;
        this.maneuver = maneuver == null ? NavSnapshot.Maneuver.UNKNOWN : maneuver;
        this.sourceManeuver = Math.max(0, Math.min(99, sourceManeuver));
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.freshUntilElapsedMs = Math.max(0L, freshUntilElapsedMs);
        this.reason = reason == null ? "" : reason;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static NavManeuverEvidence icon(NavSnapshot.Maneuver maneuver, int sourceManeuver,
            int confidence, long freshUntilElapsedMs, String reason) {
        return new NavManeuverEvidence(Source.LARGE_ICON, maneuver, sourceManeuver,
                confidence, freshUntilElapsedMs, reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static NavManeuverEvidence text(NavSnapshot.Maneuver maneuver, int sourceManeuver,
            int confidence, long freshUntilElapsedMs, String reason) {
        return new NavManeuverEvidence(Source.TEXT, maneuver, sourceManeuver,
                confidence, freshUntilElapsedMs, reason);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isFreshAt(long nowElapsedMs) {
        return confidence >= 70
                && maneuver != NavSnapshot.Maneuver.UNKNOWN
                && nowElapsedMs <= freshUntilElapsedMs;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    String summary() {
        return "source=" + source
                + " maneuver=" + maneuver
                + " sourceManeuver=" + sourceManeuver
                + " confidence=" + confidence
                + " freshUntil=" + freshUntilElapsedMs
                + " reason=" + reason;
    }
}
