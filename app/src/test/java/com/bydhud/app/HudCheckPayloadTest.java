package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HudCheckPayloadTest {
    @Test
    public void stockRoadInfoUsesTypedEmptyLaneAndNeutralPngFields() {
        Map<Integer, Value> fields = inner(HudCheckPayload.buildRoadInfo(null,
                new HudCheckState()));
        assertEquals(5L, fields.get(5).varint);
        assertEquals(6L, fields.get(6).varint);
        assertArrayEquals(new byte[0], fields.get(7).bytes);
        assertTrue(fields.get(8).bytes.length > 8);
        assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47},
                new byte[]{fields.get(8).bytes[0], fields.get(8).bytes[1],
                        fields.get(8).bytes[2], fields.get(8).bytes[3]});
        assertEquals("0,0|0,255|0,0|0,255|0,0|",
                new String(fields.get(29).bytes, StandardCharsets.UTF_8));
        assertEquals(0L, fields.get(3).varint);
        assertEquals(0L, fields.get(11).varint);
        assertEquals("", new String(fields.get(26).bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void extendedRoadInfoKeepsBaselineAndClearsOptionalFields() {
        HudCheckState state = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED)
                .withAutomatic(false).stepExtended(1);
        Map<Integer, Value> fields = inner(HudCheckPayload.buildRoadInfo(null, state));
        assertEquals(5L, fields.get(5).varint);
        assertEquals(77L, fields.get(9).varint);
        assertEquals("Continue straight", new String(fields.get(10).bytes,
                StandardCharsets.UTF_8));
        assertEquals(12_345L, fields.get(3).varint);
        assertEquals(1_200L, fields.get(4).varint);
        assertEquals("20 min", new String(fields.get(27).bytes, StandardCharsets.UTF_8));
        assertEquals(0L, fields.get(11).varint);
        assertEquals(0L, fields.get(23).varint);
    }

    @Test
    public void everyAuxiliaryCaseUsesKnownTopicAndHasMatchingCleanup() {
        Set<Long> sentTopics = new HashSet<>();
        int[] expectedCounts = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                1, 1, 1, 1, 1, 1, 1, 1, 2, 1};
        for (int i = 0; i < HudCheckPayload.extendedCount(); i++) {
            HudCheckState state = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED)
                    .withAutomatic(false).stepExtended(i);
            List<HudCheckPayload.Packet> packets = HudCheckPayload.auxiliaryPackets(null, state);
            assertEquals(expectedCounts[i], packets.size());
            for (HudCheckPayload.Packet packet : packets) {
                assertTrue(packet.serviceId != 0L);
                assertTrue(packet.topicId != 0L);
                assertTrue(packet.payload.length > 0);
                sentTopics.add(packet.topicId);
            }
        }
        Map<Long, HudCheckPayload.Packet> clears = new HashMap<>();
        for (HudCheckPayload.Packet packet : HudCheckPayload.clearAuxiliaryPackets()) {
            clears.put(packet.topicId, packet);
        }
        assertFalse(sentTopics.isEmpty());
        for (Long topic : sentTopics) {
            assertTrue("missing clear for topic " + Long.toHexString(topic), clears.containsKey(topic));
            assertTrue(clears.get(topic).payload.length > 0);
        }
    }

    @Test
    public void statisticReadoutsMatchEstablishedProbeFields() {
        HudCheckState segments = new HudCheckState()
                .selectMode(HudCheckState.Mode.EXTENDED)
                .withAutomatic(false)
                .stepExtended(23);
        assertTrue(segments.extendedField().contains("f12"));
        assertTrue(segments.extendedValue().contains("f12=5.0"));
        List<HudCheckPayload.Packet> segmentPackets = HudCheckPayload.auxiliaryPackets(null, segments);
        Map<Integer, Value> first = inner(segmentPackets.get(0).payload);
        assertEquals(5.0d, first.get(12).fixedDouble(), 0.0001d);
        assertEquals(2.2d, first.get(13).fixedDouble(), 0.0001d);
        Map<Integer, Value> second = inner(segmentPackets.get(1).payload);
        assertEquals(0xE38A6876L, second.get(1).varint);
        assertFalse(second.containsKey(12));

        HudCheckState summary = segments.stepExtended(1);
        assertTrue(summary.extendedField().contains("RouteMetadata"));
        assertTrue(summary.extendedValue().contains("routeId=1"));
        Map<Integer, Value> summaryFields = inner(
                HudCheckPayload.auxiliaryPackets(null, summary).get(0).payload);
        assertEquals(1L, summaryFields.get(1).varint);
        assertEquals(1L, summaryFields.get(3).varint);
        assertTrue(summaryFields.get(4).fixed64 != 0L);
    }

    private static Map<Integer, Value> inner(byte[] outer) {
        Map<Integer, Value> wrapped = decode(outer);
        return decode(wrapped.get(1).bytes);
    }

    private static Map<Integer, Value> decode(byte[] bytes) {
        Map<Integer, Value> result = new HashMap<>();
        int[] offset = {0};
        while (offset[0] < bytes.length) {
            long tag = readVarint(bytes, offset);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            Value value = new Value();
            if (wire == 0) value.varint = readVarint(bytes, offset);
            else if (wire == 1) value.fixed64 = readFixed64(bytes, offset);
            else if (wire == 2) {
                int length = (int) readVarint(bytes, offset);
                value.bytes = new byte[length];
                System.arraycopy(bytes, offset[0], value.bytes, 0, length);
                offset[0] += length;
            } else if (wire == 5) offset[0] += 4;
            else throw new AssertionError("unsupported wire type " + wire);
            result.put(field, value);
        }
        return result;
    }

    private static long readVarint(byte[] bytes, int[] offset) {
        long result = 0L;
        int shift = 0;
        while (shift < 64) {
            int value = bytes[offset[0]++] & 0xff;
            result |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) return result;
            shift += 7;
        }
        throw new AssertionError("varint too long");
    }

    private static long readFixed64(byte[] bytes, int[] offset) {
        long value = 0L;
        for (int index = 0; index < 8; index++) {
            value |= (long) (bytes[offset[0]++] & 0xff) << (8 * index);
        }
        return value;
    }

    private static final class Value {
        long varint = -1L;
        long fixed64;
        byte[] bytes = new byte[0];

        double fixedDouble() {
            return Double.longBitsToDouble(fixed64);
        }

    }
}
