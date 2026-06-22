package com.bydhud.app;

class NavParserResult {
    final HudState state;
    final NavSnapshot snapshot;
    final String reason;
    final NavManeuverEvidence maneuverEvidence;

    NavParserResult(HudState state, NavSnapshot snapshot, String reason) {
        this(state, snapshot, reason, NavManeuverEvidence.NONE);
    }

    NavParserResult(HudState state, NavSnapshot snapshot, String reason,
            NavManeuverEvidence maneuverEvidence) {
        this.state = state;
        this.snapshot = snapshot;
        this.reason = reason == null ? "" : reason;
        this.maneuverEvidence = maneuverEvidence == null
                ? NavManeuverEvidence.NONE
                : maneuverEvidence;
    }
}
