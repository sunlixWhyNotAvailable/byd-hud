package com.bydhud.app;

//anchors Waze crop regions so parsing stays stable across screen sizes and theme differences.

enum WazeVisualLayout {
    //defines each visual layout bucket so crop diagnostics can explain which screen shape was parsed.
    NO_ACTIVE_PANEL,
    ARRIVAL,
    SINGLE_MANEUVER,
    LANES,
    UNKNOWN
}
