package com.bydhud.app;

//models parser output so downstream HUD code can handle success, idle, and clear states uniformly.

class NavParserResult {
    final HudState state;
    final NavSnapshot snapshot;
    final String reason;
    final NavManeuverEvidence maneuverEvidence;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    NavParserResult(HudState state, NavSnapshot snapshot, String reason) {
        this(state, snapshot, reason, NavManeuverEvidence.NONE);
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
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
