package com.bydhud.app;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;

public final class RoadInfoDiagnosticFrameTest {
    @Test public void keepsNavigationFieldsAndFingerprintsWithoutPrivateOrImageBytes() {
        byte[] input = wrap(new byte[]{(byte) 0x80, 1, 2, (byte) 0xe0, 1, 1, 0x48, 100,
                0x3a, 3, 11, 22, 33, 0x42, 2, 44, 55,
                0x52, 6, 'S', 'E', 'C', 'R', 'E', 'T',
                (byte) 0x99, 1, 0, 1, 2, 3, 4, 5, 6, 7});
        RoadInfoDiagnosticFrame frame = RoadInfoDiagnosticFrame.parse(input);
        assertEquals(2, frame.fields.get("navigationStatus"));
        assertEquals(1, frame.fields.get("direction"));
        assertEquals(100, frame.fields.get("distanceM"));
        assertEquals(3, frame.fields.get("lanesBytes"));
        assertEquals(2, frame.fields.get("turnBytes"));
        assertTrue(frame.fields.containsKey("turnCrc32"));
        assertFalse(frame.json().toString().contains("SECRET"));
        assertEquals(7, frame.fields.size());
        input[input.length - 1] = 9;
        assertTrue(frame.matches(RoadInfoDiagnosticFrame.parse(input)));
    }

    @Test public void absentEmptyAndDifferentTurnImagesStayDistinct() {
        RoadInfoDiagnosticFrame absent = RoadInfoDiagnosticFrame.parse(wrap(new byte[]{0x48, 1}));
        RoadInfoDiagnosticFrame empty = RoadInfoDiagnosticFrame.parse(wrap(new byte[]{0x48, 1, 0x42, 0}));
        RoadInfoDiagnosticFrame first = RoadInfoDiagnosticFrame.parse(wrap(new byte[]{0x48, 1, 0x42, 1, 2}));
        RoadInfoDiagnosticFrame other = RoadInfoDiagnosticFrame.parse(wrap(new byte[]{0x48, 1, 0x42, 1, 3}));
        assertFalse(absent.matches(empty));
        assertFalse(empty.matches(first));
        assertFalse(first.matches(other));
    }

    @Test public void rejectsTruncatedLengthsVarintsAndInvalidWireTypes() {
        for (byte[] bytes : new byte[][]{
                {10, 5, 0}, {10, 1, (byte) 0x80}, {10, 2, 0x4d, 1},
                {10, 1, 0}, {10, 2, 0x48, (byte) 0x80}, {8, 1}}) {
            try { RoadInfoDiagnosticFrame.parse(bytes); fail("Accepted malformed packet"); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
    }

    @Test public void logsSignedClearValueWithoutChangingIt() {
        RoadInfoDiagnosticFrame frame = RoadInfoDiagnosticFrame.parse(wrap(new byte[]{
                0x48, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 15,
                (byte) 0x80, 1, 0}));
        assertEquals(-1, frame.fields.get("distanceM"));
        assertEquals(0, frame.fields.get("navigationStatus"));
    }

    static byte[] packet(int direction, int distance) {
        return wrap(new byte[]{(byte) 0x80, 1, 2, (byte) 0xe0, 1, (byte) direction, 0x48, (byte) distance});
    }

    static byte[] wrap(byte[] road) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(10); out.write(road.length); out.write(road, 0, road.length);
        return out.toByteArray();
    }
}
