package com.bydhud.app;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClusterDebugPayloadTest {
    @Test public void fixedStatusRecordUsesBigEndianFidLengthAndValue() {
        ClusterDebugPayload.Record record = ClusterDebugPayload.navigationStatus(
                VehicleTbtPublisher.STATUS_ACTIVE);

        assertEquals("navigation_status", record.name);
        assertEquals("43,E0,00,3A,04,00,00,00,02", record.normalHex);
    }

    @Test public void guidanceRecordsKeepNativeOrderAndRoadIsUtf16LittleEndian() {
        List<ClusterDebugPayload.Record> records = ClusterDebugPayload.guidance(
                0x12, 1234, "AΩ");

        assertEquals(4, records.size());
        assertEquals("simple_icon", records.get(0).name);
        assertEquals("43,F0,10,10,04,00,00,00,12", records.get(0).normalHex);
        assertEquals("dual_icon", records.get(1).name);
        assertEquals("43,F0,10,30,04,00,00,00,12", records.get(1).normalHex);
        assertEquals("distance", records.get(2).name);
        assertEquals("43,F0,10,18,04,00,00,04,D2", records.get(2).normalHex);
        assertEquals("road", records.get(3).name);
        assertEquals("43,FA,10,08,04,41,00,A9,03", records.get(3).normalHex);
        assertEquals("byd.hud.NAVIGATION", ClusterDebugPayload.ACTION);
        assertEquals("normal", ClusterDebugPayload.EXTRA_NORMAL);
    }

    @Test public void guidanceRejectsOversizedRecordsWithoutTruncating() {
        String acceptedRoad = repeated('x', 123);
        String rejectedRoad = repeated('x', 124);

        assertTrue(ClusterDebugPayload.validGuidance(1, -1, acceptedRoad));
        assertEquals(251, ClusterDebugPayload.guidance(1, -1, acceptedRoad)
                .get(3).normalHex.split(",").length);
        assertFalse(ClusterDebugPayload.validGuidance(1, -1, rejectedRoad));
        assertThrows(IllegalArgumentException.class,
                () -> ClusterDebugPayload.guidance(1, -1, rejectedRoad));
    }

    @Test public void fixedSurfaceRejectsInvalidStatusAndGuidanceValues() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterDebugPayload.navigationStatus(0));
        assertFalse(ClusterDebugPayload.validGuidance(50, -1, "road"));
        assertFalse(ClusterDebugPayload.validGuidance(1, -2, "road"));
    }

    private static String repeated(char value, int count) {
        char[] characters = new char[count];
        java.util.Arrays.fill(characters, value);
        return new String(characters);
    }
}
