package com.bydhud.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded transition bookkeeping for an ADB-to-app-visible Logcat fallback. */
final class LogcatStreamBoundary {
    private static final int TIMESTAMP_BYTES = 18;
    private static final int MAX_LINE_BYTES = 64 * 1024;
    private static final int MAX_DISTINCT_LINES = 4_096;
    private static final int MAX_KEY_BYTES = 512 * 1024;

    private final byte[] line = new byte[MAX_LINE_BYTES];
    private final LinkedHashMap<String, Integer> occurrences = new LinkedHashMap<>();
    private int lineBytes;
    private boolean lineOverflow;
    private boolean occurrenceOverflow;
    private int keyBytes;
    private String timestamp = "";

    void observe(byte[] bytes, int offset, int length) {
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            byte value = bytes[index];
            if (value == '\n') {
                if (!lineOverflow) observeLine(line, lineBytes);
                lineBytes = 0;
                lineOverflow = false;
            } else if (!lineOverflow && lineBytes < line.length) {
                line[lineBytes++] = value;
            } else {
                lineOverflow = true;
            }
        }
    }

    Boundary snapshot() {
        return new Boundary(timestamp, new LinkedHashMap<>(occurrences),
                !timestamp.isEmpty() && !occurrenceOverflow);
    }

    int bufferedBytesForTest() {
        return lineBytes;
    }

    int trackedLinesForTest() {
        return occurrences.size();
    }

    private void observeLine(byte[] bytes, int length) {
        String lineTimestamp = timestamp(bytes, length);
        if (lineTimestamp.isEmpty()) return;
        if (!lineTimestamp.equals(timestamp)) {
            timestamp = lineTimestamp;
            occurrences.clear();
            occurrenceOverflow = false;
            keyBytes = 0;
        }
        String key = binaryKey(bytes, length);
        Integer count = occurrences.get(key);
        if (count != null) {
            occurrences.put(key, count + 1);
        } else if (occurrences.size() < MAX_DISTINCT_LINES
                && keyBytes + length <= MAX_KEY_BYTES) {
            occurrences.put(key, 1);
            keyBytes += length;
        } else {
            occurrenceOverflow = true;
        }
    }

    static final class Boundary {
        final String timestamp;
        final Map<String, Integer> occurrences;
        final boolean reliable;

        private Boundary(String timestamp, Map<String, Integer> occurrences, boolean reliable) {
            this.timestamp = timestamp;
            this.occurrences = Collections.unmodifiableMap(occurrences);
            this.reliable = reliable;
        }
    }

    static final class Reconciler extends OutputStream {
        private final Boundary boundary;
        private final OutputStream output;
        private final LinkedHashMap<String, Integer> seen = new LinkedHashMap<>();
        private final byte[] line = new byte[MAX_LINE_BYTES];
        private byte[] pendingPrefix;
        private int lineBytes;
        private boolean filtering;
        private boolean overflow;
        private boolean pendingMismatch;
        private int discardedBytes;

        Reconciler(Boundary boundary, OutputStream output) {
            this(boundary, output, new byte[0]);
        }

        Reconciler(Boundary boundary, OutputStream output, byte[] pendingPrefix) {
            this.boundary = boundary;
            this.output = output;
            filtering = boundary != null && boundary.reliable;
            this.pendingPrefix = pendingPrefix == null ? new byte[0] : pendingPrefix.clone();
        }

        @Override public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (!filtering && pendingPrefix.length == 0) {
                output.write(bytes, offset, length);
                return;
            }
            int end = offset + length;
            for (int index = offset; index < end; index++) {
                byte value = bytes[index];
                if (value == '\n') {
                    if (overflow) {
                        filtering = false;
                        output.write(line, 0, lineBytes);
                        output.write('\n');
                    } else {
                        reconcileLine(line, lineBytes);
                    }
                    lineBytes = 0;
                    if (!filtering && pendingPrefix.length == 0 && index + 1 < end) {
                        output.write(bytes, index + 1, end - index - 1);
                        return;
                    }
                } else if (lineBytes < line.length) {
                    line[lineBytes++] = value;
                } else {
                    overflow = true;
                    filtering = false;
                    if (pendingPrefix.length > 0
                            && !startsWith(line, lineBytes, pendingPrefix)) {
                        pendingMismatch = true;
                        int kept = writeUtf8Prefix(pendingPrefix, pendingPrefix.length);
                        if (kept > 0) output.write('\n');
                    }
                    output.write(line, 0, lineBytes);
                    output.write(bytes, index, end - index);
                    pendingPrefix = new byte[0];
                    lineBytes = 0;
                    return;
                }
            }
        }

        boolean overflowed() {
            return overflow;
        }

        Finish finish() throws IOException {
            if (lineBytes > 0) writePartial(line, lineBytes);
            else if (pendingPrefix.length > 0) writeUtf8Prefix(pendingPrefix, pendingPrefix.length);
            lineBytes = 0;
            pendingPrefix = new byte[0];
            return new Finish(discardedBytes, pendingMismatch);
        }

        private void reconcileLine(byte[] bytes, int length) throws IOException {
            String valueTimestamp = timestamp(bytes, length);
            if (filtering && valueTimestamp.isEmpty()) return;
            if (filtering && !valueTimestamp.equals(boundary.timestamp)) {
                filtering = false;
                writeAfterBoundary(bytes, length);
                return;
            }
            if (!filtering) {
                writeAfterBoundary(bytes, length);
                return;
            }
            String key = binaryKey(bytes, length);
            int persisted = boundary.occurrences.getOrDefault(key, 0);
            if (persisted == 0) {
                writeAfterBoundary(bytes, length);
                return;
            }
            int occurrence = seen.containsKey(key) ? seen.get(key) + 1 : 1;
            seen.put(key, occurrence);
            if (occurrence > persisted) {
                writeAfterBoundary(bytes, length);
            }
        }

        private void writeLine(byte[] bytes, int length) throws IOException {
            output.write(bytes, 0, length);
            output.write('\n');
        }

        private void writeAfterBoundary(byte[] bytes, int length) throws IOException {
            if (pendingPrefix.length == 0) {
                writeLine(bytes, length);
            } else if (startsWith(bytes, length, pendingPrefix)) {
                writeLine(bytes, length);
            } else {
                pendingMismatch = true;
                int kept = writeUtf8Prefix(pendingPrefix, pendingPrefix.length);
                if (kept > 0) output.write('\n');
                writeLine(bytes, length);
            }
            pendingPrefix = new byte[0];
        }

        private void writePartial(byte[] bytes, int length) throws IOException {
            if (pendingPrefix.length > 0 && startsWith(bytes, length, pendingPrefix)) {
                writeUtf8Prefix(bytes, length);
            } else {
                if (pendingPrefix.length > 0) {
                    pendingMismatch = true;
                    int kept = writeUtf8Prefix(pendingPrefix, pendingPrefix.length);
                    if (kept > 0 && length > 0) output.write('\n');
                }
                writeUtf8Prefix(bytes, length);
            }
        }

        private int writeUtf8Prefix(byte[] bytes, int length) throws IOException {
            int complete = completeUtf8PrefixLength(bytes, length);
            if (complete > 0) output.write(bytes, 0, complete);
            discardedBytes += length - complete;
            return complete;
        }

        private static boolean startsWith(byte[] bytes, int length, byte[] prefix) {
            if (length < prefix.length) return false;
            for (int index = 0; index < prefix.length; index++) {
                if (bytes[index] != prefix[index]) return false;
            }
            return true;
        }
    }

    static final class Finish {
        final int discardedBytes;
        final boolean pendingMismatch;

        private Finish(int discardedBytes, boolean pendingMismatch) {
            this.discardedBytes = discardedBytes;
            this.pendingMismatch = pendingMismatch;
        }
    }

    static int completeUtf8PrefixLength(byte[] bytes, int length) {
        if (length <= 0) return 0;
        int index = length - 1;
        int continuation = 0;
        while (index >= 0 && (bytes[index] & 0xc0) == 0x80 && continuation < 3) {
            continuation++;
            index--;
        }
        if (index < 0) return 0;
        int lead = bytes[index] & 0xff;
        int expected = lead < 0x80 ? 1
                : (lead & 0xe0) == 0xc0 ? 2
                : (lead & 0xf0) == 0xe0 ? 3
                : (lead & 0xf8) == 0xf0 ? 4 : 1;
        return length - index < expected ? index : length;
    }

    private static String timestamp(byte[] bytes, int length) {
        if (length < TIMESTAMP_BYTES
                || bytes[2] != '-' || bytes[5] != ' ' || bytes[8] != ':'
                || bytes[11] != ':' || bytes[14] != '.') return "";
        for (int index = 0; index < TIMESTAMP_BYTES; index++) {
            if (index == 2 || index == 5 || index == 8 || index == 11 || index == 14) continue;
            if (bytes[index] < '0' || bytes[index] > '9') return "";
        }
        return new String(bytes, 0, TIMESTAMP_BYTES, StandardCharsets.US_ASCII);
    }

    private static String binaryKey(byte[] bytes, int length) {
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
    }
}
