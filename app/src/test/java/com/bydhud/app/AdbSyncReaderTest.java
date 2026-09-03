package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class AdbSyncReaderTest {
    @Test
    public void fragmentedDataAndDoneStreamToCallerAndReportCumulativeProgress() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<Long> progress = new ArrayList<>();
        AdbSyncReader reader = new AdbSyncReader(output, 5, progress::add);
        byte[] response = concat(frame("DATA", bytes(1, 2, 3)), frame("DATA", bytes(4, 5)),
                frame("DONE", new byte[0]));
        for (int i = 0; i < response.length; i++) {
            reader.accept(response, i, 1);
        }
        reader.finish();
        assertArrayEquals(bytes(1, 2, 3, 4, 5), output.toByteArray());
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L), progress);
    }

    @Test
    public void failFrameNeverLooksLikeSuccessfulEmptyCopy() {
        AdbSyncReader reader = new AdbSyncReader(new ByteArrayOutputStream(), 0, null);
        IOException error = assertThrows(IOException.class,
                () -> reader.accept(frame("FAIL", "permission denied".getBytes(StandardCharsets.UTF_8))));
        assertEquals("ADB sync FAIL: permission denied", error.getMessage());
    }

    @Test
    public void oversizeFrameIsRejectedBeforeWritingAnyBytes() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AdbSyncReader reader = new AdbSyncReader(output, 2, null);
        IOException error = assertThrows(IOException.class,
                () -> reader.accept(frame("DATA", bytes(1, 2, 3))));
        assertEquals("ADB sync oversize read: expected 2 bytes, received at least 3",
                error.getMessage());
        assertEquals(0, output.size());
    }

    @Test
    public void doneBeforeExpectedBytesIsShortRead() {
        AdbSyncReader reader = new AdbSyncReader(new ByteArrayOutputStream(), 2, null);
        IOException error = assertThrows(IOException.class,
                () -> reader.accept(frame("DONE", new byte[0])));
        assertEquals("ADB sync short read: expected 2 bytes, received 0", error.getMessage());
    }

    @Test
    public void closeWithoutDoneIsRejected() throws Exception {
        AdbSyncReader reader = new AdbSyncReader(new ByteArrayOutputStream(), 1, null);
        reader.accept(frame("DATA", bytes(9)));
        IOException error = assertThrows(IOException.class, reader::finish);
        assertEquals("ADB sync ended before DONE", error.getMessage());
    }

    @Test
    public void callbackCanCancelByThrowingWithoutClosingCallerOutput() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AdbSyncReader reader = new AdbSyncReader(output, 2, copied -> {
            if (copied == 1) throw new RuntimeException("cancel");
        });
        assertThrows(RuntimeException.class, () -> reader.accept(frame("DATA", bytes(7))));
        assertArrayEquals(bytes(7), output.toByteArray());
    }

    private static byte[] frame(String id, byte[] body) {
        byte[] frame = new byte[8 + body.length];
        int command = AdbPacket.command(id);
        writeIntLe(frame, 0, command);
        writeIntLe(frame, 4, body.length);
        System.arraycopy(body, 0, frame, 8, body.length);
        return frame;
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.write(part);
        return output.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private static void writeIntLe(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }
}
