package com.bydhud.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.LongConsumer;

//Consumes one ADB sync RECV response without buffering the file in memory.
final class AdbSyncReader {
    private static final int DATA = AdbPacket.command("DATA");
    private static final int DONE = AdbPacket.command("DONE");
    private static final int FAIL = AdbPacket.command("FAIL");
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final int MAX_FAIL_BYTES = 4096;

    private final OutputStream output;
    private final long expectedBytes;
    private final LongConsumer progress;
    private final byte[] header = new byte[8];
    private final byte[] failMessage = new byte[MAX_FAIL_BYTES];
    private int headerBytes;
    private int frameCommand;
    private int frameRemaining;
    private int failBytes;
    private long copiedBytes;
    private boolean done;
    private boolean failed;

    AdbSyncReader(OutputStream output, long expectedBytes, LongConsumer progress) {
        if (output == null) throw new IllegalArgumentException("output is required");
        if (expectedBytes < 0L) throw new IllegalArgumentException("expectedBytes < 0");
        this.output = output;
        this.expectedBytes = expectedBytes;
        this.progress = progress == null ? ignored -> { } : progress;
    }

    long copiedBytes() {
        return copiedBytes;
    }

    boolean isDone() {
        return done;
    }

    void accept(byte[] bytes) throws IOException {
        if (bytes == null) throw new IOException("ADB sync payload is null");
        accept(bytes, 0, bytes.length);
    }

    void accept(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new IOException("ADB sync payload is null");
        if ((offset | length) < 0 || offset > bytes.length - length) {
            throw new IOException("ADB sync payload bounds invalid");
        }
        if (done || failed) {
            throw new IOException("ADB sync data after terminal frame");
        }
        int cursor = offset;
        int end = offset + length;
        while (cursor < end) {
            if (done || failed) {
                throw new IOException("ADB sync data after terminal frame");
            }
            if (headerBytes < header.length) {
                int copy = Math.min(header.length - headerBytes, end - cursor);
                System.arraycopy(bytes, cursor, header, headerBytes, copy);
                headerBytes += copy;
                cursor += copy;
                if (headerBytes < header.length) continue;
                frameCommand = intLe(header, 0);
                int frameLength = intLe(header, 4);
                beginFrame(frameLength);
                if (frameRemaining == 0) finishFrame();
                continue;
            }

            int copy = Math.min(frameRemaining, end - cursor);
            if (frameCommand == DATA) {
                output.write(bytes, cursor, copy);
                copiedBytes += copy;
                progress.accept(copiedBytes);
            } else if (frameCommand == FAIL) {
                System.arraycopy(bytes, cursor, failMessage, failBytes, copy);
                failBytes += copy;
            }
            frameRemaining -= copy;
            cursor += copy;
            if (frameRemaining == 0) finishFrame();
        }
    }

    void finish() throws IOException {
        if (failed) throw new IOException("ADB sync failed");
        if (!done) throw new IOException("ADB sync ended before DONE");
        if (copiedBytes != expectedBytes) {
            throw new IOException("ADB sync short read: expected " + expectedBytes
                    + " bytes, received " + copiedBytes);
        }
    }

    private void beginFrame(int frameLength) throws IOException {
        if (frameCommand != DATA && frameCommand != DONE && frameCommand != FAIL) {
            throw new IOException("Unknown ADB sync frame: " + commandName(frameCommand));
        }
        if (frameLength < 0 || frameLength > MAX_FRAME_BYTES) {
            throw new IOException("ADB sync frame length out of range: " + frameLength);
        }
        if (frameCommand == DATA) {
            if (frameLength > expectedBytes - copiedBytes) {
                throw new IOException("ADB sync oversize read: expected " + expectedBytes
                        + " bytes, received at least " + (copiedBytes + frameLength));
            }
        } else if (frameCommand == FAIL) {
            if (frameLength > MAX_FAIL_BYTES) {
                throw new IOException("ADB sync FAIL message too large: " + frameLength);
            }
            failBytes = 0;
        }
        frameRemaining = frameLength;
    }

    private void finishFrame() throws IOException {
        if (frameCommand == DONE) {
            done = true;
            if (copiedBytes != expectedBytes) {
                throw new IOException("ADB sync short read: expected " + expectedBytes
                        + " bytes, received " + copiedBytes);
            }
        } else if (frameCommand == FAIL) {
            failed = true;
            String message = new String(failMessage, 0, failBytes, StandardCharsets.UTF_8).trim();
            throw new IOException("ADB sync FAIL" + (message.isEmpty() ? "" : ": " + message));
        }
        headerBytes = 0;
        frameCommand = 0;
        frameRemaining = 0;
    }

    private static int intLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static String commandName(int command) {
        return new String(new byte[] {
                (byte) command,
                (byte) (command >>> 8),
                (byte) (command >>> 16),
                (byte) (command >>> 24)
        }, StandardCharsets.US_ASCII);
    }
}
