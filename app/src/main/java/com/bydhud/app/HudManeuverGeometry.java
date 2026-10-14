package com.bydhud.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Schematic RoadInfo coordinates, never Android location or a real route. */
final class HudManeuverGeometry {
    static final double LONGITUDE = 121.47832472222223;
    static final double LATITUDE = 31.228410277777776;
    static final double HEADING = 57.50603485107422;
    static final Selection STRAIGHT = new Selection("straight", "", "[[121.47832416666667,31.228410277777776,0],[121.47840472222222,31.228449722222223,0],[121.47875502158523,31.22862124811722,0],[121.4790872958414,31.22878394792543,0],[121.47932299551454,31.22889935948952,0],[121.48005148939569,31.22925607028652,0],[121.4806928905369,31.22957013567292,0]]");
    // N9 calibration fixes geometry orientation only; native maneuver IDs retain their semantics.
    static final Selection LEFT = new Selection("left", "", "[[121.47832416666667,31.228410277777776,0],[121.47840472222222,31.228449722222223,0],[121.47868608886587,31.22820221516114,0],[121.47889954728439,31.22793039901512,0],[121.47905075794509,31.22773746691718,0],[121.47945578327185,31.22710870154078,0],[121.47980934765235,31.22655368249031,0]]");
    static final Selection RIGHT = new Selection("right", "", "[[121.47832416666667,31.228410277777776,0],[121.47840472222222,31.228449722222223,0],[121.4782975,31.2287825,0],[121.47813138888888,31.2290775,0],[121.47801333333334,31.229286666666667,0],[121.47758416666667,31.229903611111112,0],[121.47720333333334,31.23044527777778,0]]");
    static final Selection CLEAR = new Selection("clear", "inactive", "");
    static final Selection NATIVE_OFF = new Selection("clear", "native-off", "");
    static final Selection ALERT = new Selection("clear", "replaced-by-alert", "");
    static final Selection DESTINATION = new Selection("clear", "destination", "");
    static final Selection LANE_ONLY = new Selection("clear", "lane-only", "");
    static final Selection UNKNOWN_CIRCLE = new Selection("clear", "circle-direction-unavailable", "");
    static final Selection UNSUPPORTED_CIRCLE = new Selection("clear", "circle-shape-unvalidated", "");

    private HudManeuverGeometry() { }

    static Selection select(DirectTbtFrame frame, boolean nativeEnabled, boolean replacingAlert) {
        if (!nativeEnabled) return NATIVE_OFF;
        if (replacingAlert) return ALERT;
        if (frame == null) return CLEAR;
        int maneuver = frame.getAmapManeuver();
        if (maneuver == 15) return DESTINATION;
        if (maneuver == 22 || maneuver == 25) {
            switch (frame.getRoundaboutExit().getDirection()) {
                case LEFT: return LEFT;
                case STRAIGHT: return STRAIGHT;
                case RIGHT: return RIGHT;
                case U_TURN: return UNSUPPORTED_CIRCLE;
                default: return UNKNOWN_CIRCLE;
            }
        }
        return maneuver != 0 && frame.getBydManeuver() > 0
                && frame.getBydManeuver() != 99 ? STRAIGHT : CLEAR;
    }

    static final class Selection {
        final String name;
        final String reason;
        final String line;
        final double longitude;
        final double latitude;
        final double heading;
        private final byte[] fields;

        private Selection(String name, String reason, String line) {
            this.name = name;
            this.reason = reason;
            this.line = line;
            longitude = line.isEmpty() ? 0.0d : LONGITUDE;
            latitude = line.isEmpty() ? 0.0d : LATITUDE;
            heading = line.isEmpty() ? 0.0d : HEADING;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeDouble(out, 19, longitude);
            writeDouble(out, 20, latitude);
            writeString(out, 30, line);
            writeString(out, 31, "");
            writeDouble(out, 32, heading);
            fields = out.toByteArray();
        }

        void writeTo(ByteArrayOutputStream out) { out.write(fields, 0, fields.length); }

        String diagnostics() {
            return "geometry=" + name + " reason=" + (reason.isEmpty() ? "navigation" : reason)
                    + " f19=" + longitude + " f20=" + latitude + " f32=" + heading;
        }

        String display() {
            return line.isEmpty() ? "[]" : line.substring(1, line.length() - 1)
                    .replace("],[", "]\n[").replace(",", ", ");
        }
    }

    private static void writeDouble(ByteArrayOutputStream out, int field, double value) {
        writeVarint(out, (field << 3) | 1);
        long bits = Double.doubleToRawLongBits(value);
        for (int i = 0; i < 8; i++) out.write((int) (bits >>> (i * 8)) & 255);
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~127) != 0) { out.write((value & 127) | 128); value >>>= 7; }
        out.write(value);
    }
}
