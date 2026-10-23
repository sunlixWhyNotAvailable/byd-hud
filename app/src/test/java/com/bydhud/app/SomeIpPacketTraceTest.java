package com.bydhud.app;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class SomeIpPacketTraceTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5XcAAAAASUVORK5CYII=");

    @Test public void realClearPacketRetainsAllWireDataExceptImages() throws Exception {
        byte[] packet = DirectTbtPayload.buildClear(PNG, PNG);
        byte[] original = packet.clone();
        JSONObject record = record(new SomeIpPacketTrace(), packet, 100, SomeIpHudClient.HUD_ROAD_INFO_TOPIC);
        JSONArray fields = record.getJSONObject("packet").getJSONArray("fields")
                .getJSONObject(0).getJSONObject("message").getJSONArray("fields");
        int images = 0;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.getJSONObject(i);
            if (field.has("omitted")) {
                assertTrue(field.getInt("field") == 7 || field.getInt("field") == 8);
                assertEquals("png", field.getString("omitted"));
                assertEquals(PNG.length, field.getInt("length"));
                assertEquals(sha256(PNG), field.getString("sha256"));
                assertFalse(field.has("valueBase64"));
                images++;
            }
        }
        assertEquals(2, images);
        assertArrayEquals(original, packet);
        assertArrayEquals(original, restore(record.getJSONObject("packet"), PNG));
        assertEquals(sha256(original), record.getString("payloadSha256"));
        assertFalse(record.toString().contains(Base64.getEncoder().encodeToString(PNG)));
    }

    @Test public void everyAttemptKeepsItsOwnSequenceTimeCounterAndSnapshot() throws Exception {
        SomeIpPacketTrace trace = new SomeIpPacketTrace();
        // Counter, repeated f6, packed f7/f8, empty f31 and non-image f10 starting with /9j/.
        byte[] packet = {10, 22, 16, 1, 48, 11, 48, 0, 58, 2, 11, 10, 66, 1, 9,
                (byte) 250, 1, 0, 82, 4, '/', '9', 'j', '/'};
        SomeIpPacketTrace.Record snapshot = capture(trace, packet, 100, SomeIpHudClient.HUD_ROAD_INFO_TOPIC);
        JSONObject second = record(trace, packet, 300, SomeIpHudClient.HUD_ROAD_INFO_TOPIC);
        packet[3] = 2;
        JSONObject third = record(trace, packet, 500, SomeIpHudClient.HUD_ROAD_INFO_TOPIC);
        JSONObject first = new JSONObject(snapshot.toJson());
        assertEquals(1, first.getLong("sequence"));
        assertEquals(2, second.getLong("sequence"));
        assertEquals(3, third.getLong("sequence"));
        assertEquals(100, first.getLong("startedT"));
        assertEquals(303, second.getLong("t"));
        assertEquals(3, third.getLong("durationMs"));
        assertEquals(first.getString("payloadSha256"), second.getString("payloadSha256"));
        assertNotEquals(second.getString("payloadSha256"), third.getString("payloadSha256"));
        byte[] saved = restore(first.getJSONObject("packet"), null);
        assertEquals(1, saved[3]);
        assertArrayEquals(packet, restore(third.getJSONObject("packet"), null));
        assertEquals(first.getString("journalSession"), third.getString("journalSession"));
        assertNotEquals(first.getString("journalSession"), record(new SomeIpPacketTrace(), packet,
                100, SomeIpHudClient.HUD_ROAD_INFO_TOPIC).getString("journalSession"));
    }

    @Test public void nativeMapOmitsEncodedImageButPreservesEmptyClear() throws Exception {
        JSONObject map = record(new SomeIpPacketTrace(), HudMapImage.nativePayload(PNG),
                100, SomeIpHudClient.HUD_TOPIC_8003);
        JSONObject field = map.getJSONObject("packet").getJSONArray("fields").getJSONObject(0);
        assertEquals("base64-png", field.getString("omitted"));
        assertFalse(field.has("valueBase64"));
        assertEquals(sha256(Base64.getEncoder().encode(PNG)), field.getString("sha256"));
        byte[] clear = HudMapImage.nativePayload(null);
        JSONObject cleared = record(new SomeIpPacketTrace(), clear, 100, SomeIpHudClient.HUD_TOPIC_8003);
        assertEquals("", cleared.getJSONObject("packet").getJSONArray("fields").getJSONObject(0)
                .getString("valueBase64"));
        assertArrayEquals(clear, restore(cleared.getJSONObject("packet"), null));
    }

    @Test public void malformedTailReportsCauseWithoutLeakingImagesOrLosingEarlierFields() throws Exception {
        byte[] prefix = {10, (byte) (PNG.length + 4), 16, 1, 66, (byte) (PNG.length + 10)};
        byte[] malformed = Arrays.copyOf(prefix, prefix.length + PNG.length);
        System.arraycopy(PNG, 0, malformed, prefix.length, PNG.length);
        JSONObject logged = record(new SomeIpPacketTrace(), malformed, 100, SomeIpHudClient.HUD_ROAD_INFO_TOPIC);
        JSONObject inner = logged.getJSONObject("packet").getJSONArray("fields")
                .getJSONObject(0).getJSONObject("message");
        assertEquals("1", inner.getJSONArray("fields").getJSONObject(0).getString("uint64"));
        assertTrue(inner.getString("decodeError").startsWith("java.io.IOException: Invalid field length"));
        assertEquals(4, inner.getInt("errorOffset"));
        assertEquals(PNG.length + 2, inner.getInt("remainingLength"));
        assertFalse(logged.toString().contains(Base64.getEncoder().encodeToString(PNG)));
        for (byte[] invalid : new byte[][] {{(byte) 128}, {13, 1}, {15},
                {(byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255,
                        (byte) 255, (byte) 255, (byte) 255, (byte) 255, 2}}) {
            assertTrue(record(new SomeIpPacketTrace(), invalid, 100, SomeIpHudClient.HUD_ROAD_INFO_TOPIC)
                    .getJSONObject("packet").has("decodeError"));
        }
    }

    private static SomeIpPacketTrace.Record capture(SomeIpPacketTrace trace, byte[] packet, long time, long topic) {
        return trace.capture("send", "direct", "waze_direct", topic, "payload", "tick", packet,
                null, "", time, time + 3, 1_000_000 + time, "com.waze", 7);
    }

    private static JSONObject record(SomeIpPacketTrace trace, byte[] packet, long time, long topic) throws Exception {
        return new JSONObject(capture(trace, packet, time, topic).toJson());
    }

    // Reconstruct the wire packet from the public log format, restoring only omitted images.
    private static byte[] restore(JSONObject message, byte[] image) throws Exception {
        assertFalse(message.has("decodeError"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONArray fields = message.getJSONArray("fields");
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.getJSONObject(i);
            out.write(Base64.getDecoder().decode(field.getString("headerBase64")));
            if (field.has("message")) out.write(restore(field.getJSONObject("message"), image));
            else if (field.has("omitted")) out.write(image);
            else out.write(Base64.getDecoder().decode(field.getString("valueBase64")));
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
