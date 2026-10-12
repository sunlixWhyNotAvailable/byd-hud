package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Application;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class HudGuideLinePayloadTest {
    private static final String LINE = "[[121.4737,31.2304,0],[121.4740,31.2307,0]]";

    @Test public void basicAndDirectKeepDirectionsAndDistanceWithTheSameRepeatedLine() {
        HudCheckState basic = new HudCheckState().step(HudCheckState.Field.DISTANCE, 2);
        int[] directions = {11, 1, 2};
        for (int i = 0; i < directions.length; i++) {
            HudCheckState state = basic.step(HudCheckState.Field.MANEUVER, i);
            Map<Integer, Object> fields = road(HudCheckPayload.buildRoadInfo(null, state));
            assertEquals(LINE, string(fields, 30));
            assertEquals((long) directions[i], fields.get(28));
            assertEquals((long) state.toHudState().distanceToIntersection, fields.get(9));

            DirectTbtFrame frame = new DirectTbtFrame(0, 0, directions[i], 50,
                    "Test street", "", "Test street", null, null,
                    Collections.emptyList(), DirectTbtFrame.AlertOverlay.inactive());
            DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(frame,
                    new DirectTbtPayload.Options(false, true, false, true, true));
            for (int counter : new int[]{1, 2}) {
                fields = road(prepared.build(counter));
                assertEquals(LINE, string(fields, 30));
                assertEquals((long) directions[i], fields.get(28));
                assertEquals(50L, fields.get(9));
                assertEquals("Test street", string(fields, 10));
            }
        }
    }

    @Test public void stopClearsGeometryAndExtendedStillOwnsItsOriginalStep12Only() {
        Map<Integer, Object> clear = road(DirectTbtPayload.buildClear());
        assertEquals("", string(clear, 30));
        assertEquals(1L, clear.get(16));
        HudCheckState extended = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED)
                .withAutomatic(false);
        for (int i = 0; i < 25; i++) {
            assertEquals(i == 11 ? LINE : "",
                    string(road(HudCheckPayload.buildRoadInfo(null, extended.stepExtended(i))), 30));
        }
    }

    @Test public void geometryControlsChangeOnlyPolylineWithCoherentStockContext() throws Exception {
        HudCheckState initial = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED)
                .withAutomatic(false);
        Map<Integer, Object> previous = null;
        org.json.JSONArray firstLine = null;
        double[] turn = new double[3];
        for (int index = 25; index < 29; index++) {
            HudCheckState state = initial.stepExtended(index);
            Map<Integer, Object> fields = road(HudCheckPayload.buildRoadInfo(null, state));
            assertEquals(11L, fields.get(28));
            assertEquals(9L, fields.get(9));
            assertEquals("", string(fields, 31));
            assertEquals(121.47832472222223, number(fields, 19), 0);
            assertEquals(31.228410277777776, number(fields, 20), 0);
            assertEquals(57.50603485107422, number(fields, 32), 0);
            assertEquals(5_000L, NavHudLiveSender.hudCheckIntervalMs(state.withAutomatic(true)));
            assertTrue(HudCheckPayload.auxiliaryPackets(null, state).isEmpty());
            String line = string(fields, 30);
            assertEquals(line.replaceAll("\\s", ""),
                    (index == 25 ? state.extendedValue() : "[" + state.extendedValue().replace("\n", ",") + "]")
                            .replaceAll("\\s", ""));
            org.json.JSONArray points = new org.json.JSONArray(line);
            assertEquals(index == 25 ? 0 : 7, points.length());
            if (index > 25) {
                if (firstLine == null) firstLine = points;
                for (int point = 0; point < 7; point++) {
                    org.json.JSONArray triple = points.getJSONArray(point);
                    assertEquals(3, triple.length());
                    for (int component = 0; component < 3; component++)
                        assertTrue(Double.isFinite(triple.getDouble(component)));
                    assertEquals(0, triple.getInt(2));
                    if (point < 2) assertEquals(firstLine.getJSONArray(point).toString(), triple.toString());
                }
                org.json.JSONArray a = points.getJSONArray(0), b = points.getJSONArray(1), c = points.getJSONArray(6);
                turn[index - 26] = (b.getDouble(0) - a.getDouble(0)) * (c.getDouble(1) - a.getDouble(1))
                        - (b.getDouble(1) - a.getDouble(1)) * (c.getDouble(0) - a.getDouble(0));
            }
            if (previous != null) {
                assertEquals(previous.keySet(), fields.keySet());
                for (int field : fields.keySet()) {
                    if (field == 30) continue;
                    if (fields.get(field) instanceof byte[])
                        assertArrayEquals((byte[]) previous.get(field), (byte[]) fields.get(field));
                    else assertEquals(previous.get(field), fields.get(field));
                }
            }
            previous = fields;
        }
        assertEquals(0.0, turn[0], 1e-14);
        assertTrue(turn[1] > 0 && turn[2] < 0);
    }

    private static double number(Map<Integer, Object> fields, int field) {
        return java.nio.ByteBuffer.wrap((byte[]) fields.get(field))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getDouble();
    }

    private static String string(Map<Integer, Object> fields, int field) {
        assertTrue("missing string field " + field, fields.get(field) instanceof byte[]);
        return new String((byte[]) fields.get(field), StandardCharsets.UTF_8);
    }

    private static Map<Integer, Object> road(byte[] payload) {
        return fields((byte[]) fields(payload).get(1));
    }

    private static Map<Integer, Object> fields(byte[] data) {
        Map<Integer, Object> fields = new LinkedHashMap<>();
        int[] cursor = {0};
        while (cursor[0] < data.length) {
            long tag = varint(data, cursor);
            Object value;
            switch ((int) tag & 7) {
                case 0: value = varint(data, cursor); break;
                case 1: value = Arrays.copyOfRange(data, cursor[0], cursor[0] += 8); break;
                case 2:
                    int length = (int) varint(data, cursor);
                    value = Arrays.copyOfRange(data, cursor[0], cursor[0] += length);
                    break;
                default: throw new AssertionError("unexpected wire tag " + tag);
            }
            assertTrue(cursor[0] <= data.length);
            fields.put((int) (tag >>> 3), value);
        }
        return fields;
    }

    private static long varint(byte[] data, int[] cursor) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int next = data[cursor[0]++] & 255;
            value |= (long) (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new AssertionError("invalid varint");
    }
}
