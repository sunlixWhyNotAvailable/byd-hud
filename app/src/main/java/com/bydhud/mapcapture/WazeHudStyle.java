package com.bydhud.mapcapture;

/** Native styling is restricted to the map ID created by WazeHudMap. */
final class WazeHudStyle {
    static void load() { System.loadLibrary("bydhud_hudmap"); }
    static native void attach(String ownedMapId);
}
