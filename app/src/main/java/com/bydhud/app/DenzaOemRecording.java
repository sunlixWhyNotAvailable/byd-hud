package com.bydhud.app;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Verified first-30-second RoadInfo recording from the captured Denza N9 stream. */
public final class DenzaOemRecording {
    public static final String ASSET_PATH = "hud-check/denza-n9-roadinfo.bin.gz";
    private static final byte[] MAGIC = {'D', 'N', 'Z', '1'};
    private static final int FORMAT_VERSION = 1;
    private static final int FRAME_COUNT = 292;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final long CYCLE_DURATION_NANOS = 30_000_000_000L;
    private static final long SOURCE_START_ELAPSED_NANOS = 4_918_649_773_583L;
    private static final long FINAL_OFFSET_NANOS = 29_946_555_386L;
    private static final long PAYLOAD_BYTES = 1_647_464L;
    private static final String SOURCE_PATH =
            "evidence/nav/external/other/Denza/N9/20261012/logs/logcat/"
                    + "shanghai_a8133864-a573-4382-8a04-f3310ad62c18/someip-events.jsonl";
    private static final String SOURCE_TOPIC = "0x0004010A00018001";
    private static final String SOURCE_SHA256 =
            "39b333d11064c90535d392a2851332331e80c8d9118636cc309d2c8c502370ad";
    private static final String CAPTURED_STREET = "西藏中路";

    private final List<Frame> frames;

    private DenzaOemRecording(List<Frame> frames) {
        this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
    }

    public static DenzaOemRecording load(Context context) throws IOException {
        if (context == null) throw new IllegalArgumentException("Context is missing");
        try (InputStream input = context.getAssets().open(ASSET_PATH)) {
            return load(input);
        } catch (IOException error) {
            throw new IOException("Denza OEM recording asset load failed: "
                    + error.getMessage(), error);
        }
    }

    /** Consumes, but does not close, the supplied compressed asset stream. */
    public static DenzaOemRecording load(InputStream source) throws IOException {
        if (source == null) throw new IllegalArgumentException("Recording stream is missing");
        DataInputStream input = new DataInputStream(
                new java.util.zip.GZIPInputStream(source));
        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        if (!MessageDigest.isEqual(MAGIC, magic)) throw new IOException("Invalid Denza recording magic");
        if (readIntLe(input) != FORMAT_VERSION) throw new IOException("Unsupported Denza recording version");
        String sourcePath = readString(input, 4096);
        String sourceTopic = readString(input, 128);
        String sourceHash = hex(readBytes(input, 32));
        long sourceStart = readLongLe(input);
        long cycleDuration = readLongLe(input);
        int count = readIntLe(input);
        if (!SOURCE_PATH.equals(sourcePath) || !SOURCE_TOPIC.equals(sourceTopic)
                || !SOURCE_SHA256.equals(sourceHash) || sourceStart != SOURCE_START_ELAPSED_NANOS
                || cycleDuration != CYCLE_DURATION_NANOS || count != FRAME_COUNT) {
            throw new IOException("Denza recording provenance or cycle metadata mismatch");
        }

        List<Frame> frames = new ArrayList<>(count);
        long previousOffset = -1L;
        int previousSequence = 0;
        long payloadBytes = 0L;
        for (int index = 0; index < count; index++) {
            long offsetNanos = readLongLe(input);
            int sequence = readIntLe(input);
            int length = readIntLe(input);
            byte[] expectedHash = readBytes(input, 32);
            if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
                throw new IOException("Invalid payload length at Denza frame " + index + ": " + length);
            }
            byte[] payload = readBytes(input, length);
            if (offsetNanos < 0L || offsetNanos >= cycleDuration
                    || (index == 0 && offsetNanos != 0L)
                    || offsetNanos <= previousOffset || sequence <= previousSequence) {
                throw new IOException("Invalid offset or source sequence at Denza frame " + index);
            }
            byte[] actualHash = sha256(payload);
            if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                throw new IOException("Payload SHA-256 mismatch at Denza source sequence " + sequence);
            }
            ParsedRoadInfo parsed = parseRoadInfo(payload, sequence);
            frames.add(new Frame(offsetNanos, sequence, payload, hex(actualHash),
                    parsed.distanceMeters, parsed.nativeManeuver, parsed.street,
                    parsed.amapManeuver, parsed.amapBroadcastManeuver));
            previousOffset = offsetNanos;
            previousSequence = sequence;
            payloadBytes += length;
        }
        if (input.read() != -1) throw new IOException("Unexpected trailing Denza recording data");
        if (frames.get(0).sourceSequence != 6 || frames.get(count - 1).sourceSequence != 352
                || previousOffset != FINAL_OFFSET_NANOS || payloadBytes != PAYLOAD_BYTES) {
            throw new IOException("Denza recording segment boundary or payload total mismatch");
        }
        return new DenzaOemRecording(frames);
    }

    public List<Frame> frames() {
        return frames;
    }

    public long cycleDurationNanos() {
        return CYCLE_DURATION_NANOS;
    }

    public String sourcePath() {
        return SOURCE_PATH;
    }

    public String sourceSha256() {
        return SOURCE_SHA256;
    }

    private static ParsedRoadInfo parseRoadInfo(byte[] payload, int sequence) throws IOException {
        ProtoReader outer = new ProtoReader(payload);
        byte[] inner = null;
        while (outer.hasRemaining()) {
            int tag = outer.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1) {
                if (wire != 2 || inner != null) {
                    throw new IOException("Invalid RoadInfo outer f1 at source sequence " + sequence);
                }
                inner = outer.readBytes();
            } else {
                outer.skip(wire);
            }
        }
        if (inner == null) throw new IOException("RoadInfo outer f1 missing at source sequence " + sequence);

        ProtoReader fields = new ProtoReader(inner);
        Long distance = null;
        Long nativeCode = null;
        String street = null;
        while (fields.hasRemaining()) {
            int tag = fields.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 9) {
                if (wire != 0 || distance != null) {
                    throw new IOException("Invalid RoadInfo f9 at source sequence " + sequence);
                }
                distance = fields.readVarint();
            } else if (field == 28) {
                if (wire != 0 || nativeCode != null) {
                    throw new IOException("Invalid RoadInfo f28 at source sequence " + sequence);
                }
                nativeCode = fields.readVarint();
            } else if (field == 10) {
                if (wire != 2 || street != null) {
                    throw new IOException("Invalid RoadInfo f10 at source sequence " + sequence);
                }
                street = decodeUtf8(fields.readBytes(), sequence);
            } else {
                fields.skip(wire);
            }
        }
        if (distance == null || nativeCode == null || street == null || street.trim().isEmpty()) {
            throw new IOException("RoadInfo f9/f10/f28 missing at source sequence " + sequence);
        }
        if (distance != 9L || nativeCode != 1L || !CAPTURED_STREET.equals(street)) {
            throw new IOException("Unexpected N9 RoadInfo semantics at source sequence " + sequence);
        }
        // This captured f28=1 is independently identified as left in the N9 maneuver evidence.
        return new ParsedRoadInfo((int) (long) distance, (int) (long) nativeCode,
                street, 2, 2);
    }

    private static String decodeUtf8(byte[] bytes, int sequence) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Invalid RoadInfo UTF-8 at source sequence " + sequence, error);
        }
    }

    private static String readString(DataInputStream input, int maxLength) throws IOException {
        int length = readIntLe(input);
        if (length < 0 || length > maxLength) throw new IOException("Invalid provenance string length");
        return decodeUtf8(readBytes(input, length), -1);
    }

    private static byte[] readBytes(DataInputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static int readIntLe(DataInputStream input) throws IOException {
        return Integer.reverseBytes(input.readInt());
    }

    private static long readLongLe(DataInputStream input) throws IOException {
        return Long.reverseBytes(input.readLong());
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable while reading Denza recording", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    public static final class Frame {
        public final long offsetNanos;
        public final int sourceSequence;
        public final String payloadSha256;
        public final int distanceMeters;
        public final int nativeManeuver;
        public final String street;
        public final int amapManeuver;
        public final int amapBroadcastManeuver;
        private final byte[] payload;

        private Frame(long offsetNanos, int sourceSequence, byte[] payload, String payloadSha256,
                int distanceMeters, int nativeManeuver, String street,
                int amapManeuver, int amapBroadcastManeuver) {
            this.offsetNanos = offsetNanos;
            this.sourceSequence = sourceSequence;
            this.payload = payload;
            this.payloadSha256 = payloadSha256;
            this.distanceMeters = distanceMeters;
            this.nativeManeuver = nativeManeuver;
            this.street = street;
            this.amapManeuver = amapManeuver;
            this.amapBroadcastManeuver = amapBroadcastManeuver;
        }

        public byte[] payload() {
            return payload.clone();
        }

    }

    private static final class ParsedRoadInfo {
        final int distanceMeters;
        final int nativeManeuver;
        final String street;
        final int amapManeuver;
        final int amapBroadcastManeuver;

        ParsedRoadInfo(int distanceMeters, int nativeManeuver, String street,
                int amapManeuver, int amapBroadcastManeuver) {
            this.distanceMeters = distanceMeters;
            this.nativeManeuver = nativeManeuver;
            this.street = street;
            this.amapManeuver = amapManeuver;
            this.amapBroadcastManeuver = amapBroadcastManeuver;
        }
    }

    private static final class ProtoReader {
        private final ByteArrayInputStream input;

        ProtoReader(byte[] bytes) {
            input = new ByteArrayInputStream(bytes);
        }

        boolean hasRemaining() {
            return input.available() > 0;
        }

        int readTag() throws IOException {
            long tag = readVarint();
            if (tag <= 0L || tag > Integer.MAX_VALUE || (tag >>> 3) == 0L) {
                throw new IOException("Invalid protobuf tag");
            }
            return (int) tag;
        }

        long readVarint() throws IOException {
            long value = 0L;
            for (int shift = 0; shift < 64; shift += 7) {
                int next = input.read();
                if (next < 0) throw new IOException("Truncated protobuf varint");
                if (shift == 63 && (next & 0xfe) != 0) throw new IOException("Protobuf varint overflow");
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
            throw new IOException("Protobuf varint too long");
        }

        byte[] readBytes() throws IOException {
            long length = readVarint();
            if (length < 0L || length > input.available()) throw new IOException("Invalid protobuf byte length");
            byte[] bytes = new byte[(int) length];
            if (input.read(bytes, 0, bytes.length) != bytes.length) {
                throw new IOException("Truncated protobuf bytes");
            }
            return bytes;
        }

        void skip(int wire) throws IOException {
            switch (wire) {
                case 0: readVarint(); return;
                case 1: skipFixed(8); return;
                case 2: readBytes(); return;
                case 5: skipFixed(4); return;
                default: throw new IOException("Unsupported protobuf wire type " + wire);
            }
        }

        private void skipFixed(int length) throws IOException {
            if (input.skip(length) != length) throw new IOException("Truncated protobuf fixed-width field");
        }
    }
}
