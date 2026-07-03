package com.bydhud.app;

//models parser output so downstream HUD code can handle success, idle, and clear states uniformly.

class NavParserResult {
    final HudState state;
    final NavSnapshot snapshot;
    final String reason;
    final NavManeuverEvidence maneuverEvidence;
    final int sourceDisplayId;
    final String maneuverSource;
    final String laneSource;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    NavParserResult(HudState state, NavSnapshot snapshot, String reason) {
        this(state, snapshot, reason, NavManeuverEvidence.NONE, 0);
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    NavParserResult(HudState state, NavSnapshot snapshot, String reason,
            NavManeuverEvidence maneuverEvidence) {
        this(state, snapshot, reason, maneuverEvidence, 0);
    }

    //carries capture display ownership so virtual Waze frames do not reuse stale main-screen route text.
    NavParserResult(HudState state, NavSnapshot snapshot, String reason,
            NavManeuverEvidence maneuverEvidence, int sourceDisplayId) {
        this(state, snapshot, reason, maneuverEvidence, sourceDisplayId, "none", "none");
    }

    //carries parser evidence provenance so field logs can show which detector produced the HUD cue.
    NavParserResult(HudState state, NavSnapshot snapshot, String reason,
            NavManeuverEvidence maneuverEvidence, int sourceDisplayId,
            String maneuverSource, String laneSource) {
        this.state = state;
        this.snapshot = snapshot;
        this.reason = reason == null ? "" : reason;
        this.maneuverEvidence = maneuverEvidence == null
                ? NavManeuverEvidence.NONE
                : maneuverEvidence;
        this.sourceDisplayId = Math.max(0, sourceDisplayId);
        this.maneuverSource = safeSource(maneuverSource);
        this.laneSource = safeSource(laneSource);
    }

    //keeps parser output immutable while allowing capture backends to attach runtime display evidence.
    NavParserResult withSourceDisplayId(int displayId) {
        return new NavParserResult(
                state,
                snapshot,
                reason,
                maneuverEvidence,
                displayId,
                maneuverSource,
                laneSource);
    }

    //normalizes log-only source labels so new parser diagnostics stay machine-greppable.
    private static String safeSource(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase();
        if ("accessibility".equals(clean)
                || "cue".equals(clean)
                || "fixed".equals(clean)
                || "none".equals(clean)) {
            return clean;
        }
        return "none";
    }
}
