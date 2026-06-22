package com.bydhud.app;

final class NavManeuverEvidence {
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

    private NavManeuverEvidence(Source source, NavSnapshot.Maneuver maneuver,
            int sourceManeuver, int confidence, long freshUntilElapsedMs, String reason) {
        this.source = source == null ? Source.NONE : source;
        this.maneuver = maneuver == null ? NavSnapshot.Maneuver.UNKNOWN : maneuver;
        this.sourceManeuver = Math.max(0, Math.min(99, sourceManeuver));
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.freshUntilElapsedMs = Math.max(0L, freshUntilElapsedMs);
        this.reason = reason == null ? "" : reason;
    }

    static NavManeuverEvidence icon(NavSnapshot.Maneuver maneuver, int sourceManeuver,
            int confidence, long freshUntilElapsedMs, String reason) {
        return new NavManeuverEvidence(Source.LARGE_ICON, maneuver, sourceManeuver,
                confidence, freshUntilElapsedMs, reason);
    }

    static NavManeuverEvidence text(NavSnapshot.Maneuver maneuver, int sourceManeuver,
            int confidence, long freshUntilElapsedMs, String reason) {
        return new NavManeuverEvidence(Source.TEXT, maneuver, sourceManeuver,
                confidence, freshUntilElapsedMs, reason);
    }

    boolean isFreshAt(long nowElapsedMs) {
        return confidence >= 70
                && maneuver != NavSnapshot.Maneuver.UNKNOWN
                && nowElapsedMs <= freshUntilElapsedMs;
    }

    String summary() {
        return "source=" + source
                + " maneuver=" + maneuver
                + " sourceManeuver=" + sourceManeuver
                + " confidence=" + confidence
                + " freshUntil=" + freshUntilElapsedMs
                + " reason=" + reason;
    }
}
