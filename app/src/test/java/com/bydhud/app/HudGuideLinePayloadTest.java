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
        for (int i = 0; i < HudCheckState.extendedCount(); i++) {
            assertEquals(i == 11 ? LINE : "",
                    string(road(HudCheckPayload.buildRoadInfo(null, extended.stepExtended(i))), 30));
        }
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
