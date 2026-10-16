package com.bydhud.navexport;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/** Navigator-side wire model; coordinates retain the source's degree precision. */
public final class RouteData {
    public final double[] points;
    public final Maneuver[] maneuvers;
    public final boolean maneuversAvailable;

    public RouteData(double[] points, Maneuver[] maneuvers, boolean maneuversAvailable) {
        this.points = points;
        this.maneuvers = maneuvers;
        this.maneuversAvailable = maneuversAvailable;
    }

    public byte[] encode() throws IOException {
        if (points.length % 2 != 0) throw new IOException("incomplete coordinate pair");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(0x42595231); // BYR1, big endian, gzip envelope.
            out.writeInt(points.length / 2);
            for (int i = 0; i < points.length; i += 2) {
                coordinate(out, points[i], points[i + 1]);
            }
            out.writeBoolean(maneuversAvailable);
            out.writeInt(maneuvers.length);
            for (Maneuver m : maneuvers) {
                out.writeUTF(m.id);
                out.writeInt(m.ordinal);
                coordinate(out, m.latitude, m.longitude);
                out.writeInt(m.vertexIndex);
                out.writeInt(m.instructionSegmentIndex);
                out.writeInt(m.navigationSegmentIndex);
                out.writeUTF(m.type);
                out.writeUTF(m.turnSide);
                out.writeDouble(m.incomingBearing);
                out.writeDouble(m.outgoingBearing);
                out.writeInt(m.sourceRotation);
                out.writeBoolean(m.syntheticPolyline);
            }
        }
        return bytes.toByteArray();
    }

    private static void coordinate(DataOutputStream out, double lat, double lon)
            throws IOException {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new IOException("invalid source coordinate");
        }
        out.writeDouble(lat);
        out.writeDouble(lon);
    }

    public static final class Maneuver {
        public String id = "";
        public int ordinal = -1;
        public double latitude;
        public double longitude;
        public int vertexIndex = -1;
        public int instructionSegmentIndex = -1;
        public int navigationSegmentIndex = -1;
        public String type = "";
        public String turnSide = "";
        public double incomingBearing = Double.NaN;
        public double outgoingBearing = Double.NaN;
        public int sourceRotation = Integer.MIN_VALUE;
        public boolean syntheticPolyline;
    }
}
