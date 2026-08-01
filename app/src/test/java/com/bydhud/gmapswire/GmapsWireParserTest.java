package com.bydhud.gmapswire;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

public final class GmapsWireParserTest {
    @Test
    public void summarizesNextStopAndWholeRouteFromRepeatedLegs() throws Exception {
        byte[] firstLeg = message(
                bytesField(2, step(7, 100)),
                bytesField(2, step(8, 200)),
                varintField(3, 1),
                bytesField(4, varintField(1, 300)));
        byte[] secondLeg = message(
                bytesField(2, stepWithoutDistance(1)),
                bytesField(2, step(50, 400)),
                varintField(3, 0),
                bytesField(4, varintField(1, 500)));
        byte[] route = message(
                varintField(1, 32),
                bytesField(2, firstLeg),
                bytesField(2, secondLeg));
        byte[] payload = bytesField(3, bytesField(2, bytesField(3, route)));

        Map<String, Object> summary = GmapsWireParser.summarize(payload);

        assertEquals(2, summary.get("routeLegCount"));
        assertEquals(8, summary.get("maneuverEnum"));
        assertEquals(200L, summary.get("distanceMeters"));
        assertEquals(300L, summary.get("nextStopRemainingSeconds"));
        assertEquals(200L, summary.get("nextStopRemainingDistanceMeters"));
        assertEquals(800L, summary.get("wholeRouteRemainingSeconds"));
        assertEquals(600L, summary.get("wholeRouteRemainingDistanceMeters"));
    }

    private static byte[] step(int maneuver, int distanceMeters) {
        return message(
                bytesField(2, varintField(1, maneuver)),
                bytesField(3, varintField(1, distanceMeters)));
    }

    private static byte[] stepWithoutDistance(int maneuver) {
        return bytesField(2, varintField(1, maneuver));
    }

    private static byte[] varintField(int field, long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, ((long) field) << 3);
        writeVarint(output, value);
        return output.toByteArray();
    }

    private static byte[] bytesField(int field, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, (((long) field) << 3) | 2L);
        writeVarint(output, value.length);
        output.write(value, 0, value.length);
        return output.toByteArray();
    }

    private static byte[] message(byte[]... fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] field : fields) output.write(field, 0, field.length);
        return output.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        long remaining = value;
        while (true) {
            int bits = (int) (remaining & 0x7fL);
            remaining >>>= 7;
            output.write(remaining == 0L ? bits : bits | 0x80);
            if (remaining == 0L) return;
        }
    }
}
