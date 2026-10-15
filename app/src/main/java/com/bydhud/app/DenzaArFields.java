package com.bydhud.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Optional RoadInfo geometry, fields 19-22 and 30-33; a packet without AR omits all of them. */
final class DenzaArFields {
    private DenzaArFields() { }

    static byte[] encode(DenzaArGeometry geometry) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        number(out, 19, geometry.getVehicleLongitude());
        number(out, 20, geometry.getVehicleLatitude());
        integer(out, 21, Math.round(geometry.getVehicleSpeedMetersPerSecond()));
        integer(out, 22, Math.round(geometry.getVehicleAltitudeMeters()));
        text(out, 30, geometry.getGuideLine());
        text(out, 31, geometry.getGuidePoint());
        number(out, 32, geometry.getVehicleHeadingDegrees());
        number(out, 33, geometry.getNavigatingRatio());
        return out.toByteArray();
    }

    private static void integer(ByteArrayOutputStream out, int field, long value) {
        varint(out, (long) field << 3);
        varint(out, value);
    }

    private static void number(ByteArrayOutputStream out, int field, double value) {
        varint(out, ((long) field << 3) | 1);
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) out.write((int) (bits >>> (8 * i)) & 255);
    }

    private static void text(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varint(out, ((long) field << 3) | 2);
        varint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        do {
            int bits = (int) value & 127;
            value >>>= 7;
            out.write(value == 0 ? bits : bits | 128);
        } while (value != 0);
    }
}
