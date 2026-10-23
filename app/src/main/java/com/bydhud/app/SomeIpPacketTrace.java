package com.bydhud.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/** Snapshots the real wire bytes; decoding and hashing run on the journal writer. */
final class SomeIpPacketTrace {
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] JPEG = {(byte) 255, (byte) 216, (byte) 255};
    private static final byte[] BASE64_PNG = "iVBORw0KGgo".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BASE64_JPEG = "/9j/".getBytes(StandardCharsets.US_ASCII);
    private final String session = UUID.randomUUID().toString();
    private long sequence;

    synchronized Record capture(String event, String source, String channel, long topic,
            String kind, String reason, byte[] payload, Integer result, String error,
            long startedMs, long finishedMs, long wallMs, String owner, long ownerGeneration) {
        String header = "{\"schemaVersion\":2," + NavCaptureStore.timeFields(finishedMs, wallMs)
                + ",\"journalSession\":" + text(session) + ",\"sequence\":" + (++sequence)
                + ",\"event\":" + text(event) + ",\"source\":" + text(source)
                + ",\"channel\":" + text(channel) + ",\"owner\":" + text(owner)
                + ",\"ownerGeneration\":" + ownerGeneration + ",\"kind\":" + text(kind)
                + ",\"reason\":" + text(reason) + ",\"startedT\":" + startedMs
                + ",\"result\":" + result + ",\"durationMs\":" + Math.max(0L, finishedMs - startedMs)
                + ",\"error\":" + text(error);
        return new Record(header, topic, payload);
    }

    static final class Record {
        private final String header;
        private final long topic;
        private final byte[] payload;

        private Record(String header, long topic, byte[] payload) {
            this.header = header;
            this.topic = topic;
            this.payload = payload == null ? null : payload.clone();
        }

        String toJson() {
            if (payload == null) return header + "}";
            return header + ",\"topic\":" + text(String.format(Locale.US, "0x%016x", topic))
                    + ",\"payloadLength\":" + payload.length
                    + ",\"payloadSha256\":" + text(hash(payload, 0, payload.length))
                    + ",\"packet\":" + message(payload, 0, payload.length, topic, true) + "}";
        }
    }

    private static String message(byte[] data, int start, int end, long topic, boolean outer) {
        StringBuilder out = new StringBuilder("{\"fields\":[");
        Cursor cursor = new Cursor(data, start, end);
        boolean first = true;
        int fieldStart = start;
        try {
            while (cursor.position < end) {
                fieldStart = cursor.position;
                long tag = cursor.varint();
                long field = tag >>> 3;
                int wire = (int) (tag & 7);
                if (field == 0 || field > 0x1fffffffL) throw new IOException("Invalid field tag " + tag);
                int valueStart = cursor.position;
                long number = 0;
                switch (wire) {
                    case 0: number = cursor.varint(); break;
                    case 1: cursor.skip(8); break;
                    case 2:
                        long length = cursor.varint();
                        if (length < 0 || length > end - cursor.position)
                            throw new IOException("Invalid field length " + Long.toUnsignedString(length));
                        valueStart = cursor.position;
                        cursor.skip((int) length);
                        break;
                    case 5: cursor.skip(4); break;
                    default: throw new IOException("Unsupported wire type " + wire);
                }
                int length = cursor.position - valueStart;
                if (!first) out.append(',');
                first = false;
                out.append("{\"field\":").append(field).append(",\"wire\":").append(wire)
                        .append(",\"offset\":").append(fieldStart).append(",\"length\":").append(length)
                        .append(",\"headerBase64\":").append(text(encoded(data, fieldStart, valueStart)));
                boolean imageField = outer
                        ? topic == SomeIpHudClient.HUD_TOPIC_8003 && field == 1
                        : (topic == SomeIpHudClient.HUD_TOPIC_8001 && (field == 7 || field == 8))
                                || (topic == SomeIpHudClient.HUD_TOPIC_8002 && field == 6);
                String image = wire == 2 && imageField ? imageEncoding(data, valueStart, length) : "";
                if (!image.isEmpty()) {
                    out.append(",\"omitted\":").append(text(image))
                            .append(",\"sha256\":").append(text(hash(data, valueStart, length)));
                } else if (outer && topic != SomeIpHudClient.HUD_TOPIC_8003 && field == 1 && wire == 2) {
                    out.append(",\"message\":").append(message(data, valueStart, cursor.position, topic, false));
                } else {
                    if (wire == 0) out.append(",\"uint64\":").append(text(Long.toUnsignedString(number)));
                    out.append(",\"valueBase64\":").append(text(encoded(data, valueStart, cursor.position)));
                }
                out.append('}');
            }
            return out.append("]}").toString();
        } catch (IOException error) {
            // Never dump undecoded bytes: a malformed tail can contain image data.
            return out.append("],\"decodeError\":").append(text(error.toString()))
                    .append(",\"errorOffset\":").append(fieldStart)
                    .append(",\"remainingLength\":").append(end - fieldStart)
                    .append(",\"remainingSha256\":").append(text(hash(data, fieldStart, end - fieldStart)))
                    .append('}').toString();
        }
    }

    private static String imageEncoding(byte[] bytes, int start, int length) {
        if (startsWith(bytes, start, length, PNG)) return "png";
        if (startsWith(bytes, start, length, JPEG)) return "jpeg";
        if (startsWith(bytes, start, length, BASE64_PNG)) return "base64-png";
        if (startsWith(bytes, start, length, BASE64_JPEG)) return "base64-jpeg";
        return "";
    }

    private static boolean startsWith(byte[] data, int start, int length, byte[] prefix) {
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[start + i] != prefix[i]) return false;
        return true;
    }

    private static String encoded(byte[] data, int start, int end) {
        ByteBuffer encoded = Base64.getEncoder().encode(ByteBuffer.wrap(data, start, end - start));
        return StandardCharsets.US_ASCII.decode(encoded).toString();
    }

    private static String hash(byte[] data, int start, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, start, length);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) {
                int unsigned = value & 255;
                if (unsigned < 16) out.append('0');
                out.append(Integer.toHexString(unsigned));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String text(String value) {
        return "\"" + NavCaptureStore.esc(value == null ? "" : value) + "\"";
    }

    private static final class Cursor {
        final byte[] data;
        final int end;
        int position;

        Cursor(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position == end) throw new IOException("Truncated varint");
                int next = data[position++] & 255;
                if (shift == 63 && next > 1) throw new IOException("Varint overflow");
                value |= (long) (next & 127) << shift;
                if ((next & 128) == 0) return value;
            }
            throw new IOException("Varint overflow");
        }

        void skip(int length) throws IOException {
            if (length > end - position) throw new IOException("Truncated fixed-width value");
            position += length;
        }
    }
}
