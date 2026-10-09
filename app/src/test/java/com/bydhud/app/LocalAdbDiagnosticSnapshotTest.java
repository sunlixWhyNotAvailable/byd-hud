package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class LocalAdbDiagnosticSnapshotTest {
    private static final String TEST_MARKER = "\0BYDHUD_DIAG_EXIT_TEST_NONCE:";

    @Test
    public void allFixedCommandsStreamTheirBytesAndReportExitCodes() throws Exception {
        LocalAdbBridge.DiagnosticSnapshotCommand[] commands =
                LocalAdbBridge.DiagnosticSnapshotCommand.values();
        int[] exitCodes = {0, 127, 255, 1};
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (int i = 0; i < commands.length; i++) {
            byte[] output = ("snapshot-" + i + "\n").getBytes(StandardCharsets.UTF_8);
            wire.write(packet(AdbPacket.A_WRTE, 7, i + 1, output));
            byte[] status = trailer(exitCodes[i]);
            int split = status.length / 2;
            wire.write(packet(AdbPacket.A_WRTE, 7, i + 1,
                    Arrays.copyOfRange(status, 0, split)));
            wire.write(packet(AdbPacket.A_WRTE, 7, i + 1,
                    Arrays.copyOfRange(status, split, status.length)));
            wire.write(packet(AdbPacket.A_CLSE, 7, i + 1, new byte[0]));
        }
        MemorySocket socket = new MemorySocket(wire.toByteArray());
        List<String> openedCommands = new ArrayList<>();

        try (LocalAdbBridge.DiagnosticSnapshotSession session =
                     LocalAdbBridge.diagnosticSnapshotForTest(socket)) {
            for (int i = 0; i < commands.length; i++) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                assertEquals(exitCodes[i], session.stream(commands[i], output));
                assertEquals("snapshot-" + i + "\n",
                        new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        }

        ByteArrayInputStream requests = new ByteArrayInputStream(socket.written.toByteArray());
        while (requests.available() > 0) {
            AdbPacket request = AdbPacket.read(requests);
            if (request.command == AdbPacket.A_OPEN) {
                openedCommands.add(new String(request.payload, StandardCharsets.UTF_8));
            }
        }
        assertEquals(Arrays.asList(
                "shell:dumpsys SurfaceFlinger; __bydhud_diag_exit=$?; printf '\\000BYDHUD_DIAG_EXIT_TEST_NONCE:%d' \"$__bydhud_diag_exit\"\0",
                "shell:dumpsys display; __bydhud_diag_exit=$?; printf '\\000BYDHUD_DIAG_EXIT_TEST_NONCE:%d' \"$__bydhud_diag_exit\"\0",
                "shell:dumpsys activity activities; __bydhud_diag_exit=$?; printf '\\000BYDHUD_DIAG_EXIT_TEST_NONCE:%d' \"$__bydhud_diag_exit\"\0",
                "shell:dumpsys window windows; __bydhud_diag_exit=$?; printf '\\000BYDHUD_DIAG_EXIT_TEST_NONCE:%d' \"$__bydhud_diag_exit\"\0"), openedCommands);
    }

    @Test
    public void streamIsByteExactAboveTheNormalFourMiBCap() throws Exception {
        byte[] expected = new byte[5 * 1024 * 1024 + 137];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 31 + i / 251);
        ByteArrayOutputStream wire = new ByteArrayOutputStream(expected.length + 4096);
        int offset = 0;
        int packetId = 1;
        while (offset < expected.length) {
            int count = Math.min(128 * 1024, expected.length - offset);
            wire.write(packet(AdbPacket.A_WRTE, 7, packetId,
                    Arrays.copyOfRange(expected, offset, offset + count)));
            offset += count;
        }
        wire.write(packet(AdbPacket.A_WRTE, 7, packetId, trailer(0)));
        wire.write(packet(AdbPacket.A_CLSE, 7, packetId, new byte[0]));
        MemorySocket socket = new MemorySocket(wire.toByteArray());
        ByteArrayOutputStream output = new ByteArrayOutputStream(expected.length);

        try (LocalAdbBridge.DiagnosticSnapshotSession session =
                     LocalAdbBridge.diagnosticSnapshotForTest(socket)) {
            assertEquals(0, session.stream(
                    LocalAdbBridge.DiagnosticSnapshotCommand.DISPLAY, output));
        }

        assertArrayEquals(expected, output.toByteArray());
    }

    @Test
    public void commandTimeoutPreservesPartialOutputAndClosesOnlyItsSocket() throws Exception {
        byte[] partial = "partial dump before timeout\n".getBytes(StandardCharsets.UTF_8);
        BlockingInput input = new BlockingInput(packet(AdbPacket.A_WRTE, 7, 1, partial));
        MemorySocket socket = new MemorySocket(input);
        MemorySocket unrelated = new MemorySocket(new byte[0]);
        LocalAdbBridge.DiagnosticSnapshotSession session =
                LocalAdbBridge.diagnosticSnapshotForTest(socket);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var read = executor.submit(() -> session.stream(
                    LocalAdbBridge.DiagnosticSnapshotCommand.SURFACE_FLINGER, output));
            assertTrue(input.entered.await(2, TimeUnit.SECONDS));
            ExecutionException timeout = assertThrows(
                    ExecutionException.class, () -> read.get(8, TimeUnit.SECONDS));
            assertTrue(timeout.getCause()
                    instanceof LocalAdbBridge.DiagnosticSnapshotTimeoutException);
            assertArrayEquals(partial, output.toByteArray());
            assertTrue(socket.closed);
            assertFalse(unrelated.closed);
        } finally {
            session.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void explicitClosePreservesPartialOutputAndDoesNotTouchOtherSockets() throws Exception {
        byte[] partial = "partial before Stop\n".getBytes(StandardCharsets.UTF_8);
        BlockingInput input = new BlockingInput(packet(AdbPacket.A_WRTE, 7, 1, partial));
        MemorySocket socket = new MemorySocket(input);
        MemorySocket unrelated = new MemorySocket(new byte[0]);
        LocalAdbBridge.DiagnosticSnapshotSession session =
                LocalAdbBridge.diagnosticSnapshotForTest(socket);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var read = executor.submit(() -> session.stream(
                    LocalAdbBridge.DiagnosticSnapshotCommand.WINDOW_WINDOWS, output));
            assertTrue(input.entered.await(2, TimeUnit.SECONDS));
            session.close();
            ExecutionException stopped = assertThrows(
                    ExecutionException.class, () -> read.get(2, TimeUnit.SECONDS));
            assertTrue(stopped.getCause().getMessage().contains("cancelled"));
            assertArrayEquals(partial, output.toByteArray());
            assertTrue(socket.closed);
            assertFalse(unrelated.closed);
        } finally {
            session.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void missingExistingKeyIsUnavailableAndDoesNotCreateOne() throws Exception {
        var directory = Files.createTempDirectory("bydhud-snapshot-no-key");
        try {
            assertThrows(IOException.class,
                    () -> LocalAdbBridge.diagnosticSnapshotKeyForTest(directory.toFile()));
            try (java.util.stream.Stream<java.nio.file.Path> entries = Files.list(directory)) {
                assertEquals(0L, entries.count());
            }
            MemorySocket socket = new MemorySocket(new byte[0]);
            assertThrows(IOException.class,
                    () -> LocalAdbBridge.diagnosticSnapshotOpenForTest(socket, null));
            assertTrue(socket.closed);
            assertEquals(0, socket.written.size());
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void secondAuthTokenIsRejectedWithoutSendingThePublicKeyPrompt() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        byte[] token = new byte[20];
        wire.write(packet(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token));
        wire.write(packet(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token));
        MemorySocket socket = new MemorySocket(wire.toByteArray());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair pair = generator.generateKeyPair();

        IOException unavailable = assertThrows(IOException.class,
                () -> LocalAdbBridge.diagnosticSnapshotOpenForTest(socket, pair));

        assertTrue(unavailable.getMessage().contains("not authorized"));
        ByteArrayInputStream responses = new ByteArrayInputStream(socket.written.toByteArray());
        assertEquals(AdbPacket.A_CNXN, AdbPacket.read(responses).command);
        AdbPacket signature = AdbPacket.read(responses);
        assertEquals(AdbPacket.A_AUTH, signature.command);
        assertEquals(AdbPacket.AUTH_SIGNATURE, signature.arg0);
        assertEquals(0, responses.available());
        assertTrue(socket.closed);
    }

    private static byte[] trailer(int exitCode) {
        return (TEST_MARKER + exitCode).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] packet(int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AdbPacket.write(output, command, arg0, arg1, payload);
        return output.toByteArray();
    }

    private static final class MemorySocket extends Socket {
        final InputStream input;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        volatile boolean closed;

        MemorySocket(byte[] input) {
            this(new ByteArrayInputStream(input));
        }

        MemorySocket(InputStream input) {
            this.input = input;
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return written; }
        @Override public void setSoTimeout(int timeout) { }

        @Override public synchronized void close() throws IOException {
            closed = true;
            input.close();
        }
    }

    private static final class BlockingInput extends InputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        private final ByteArrayInputStream prefix;
        private boolean closed;

        BlockingInput(byte[] prefix) { this.prefix = new ByteArrayInputStream(prefix); }

        @Override public synchronized int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override public synchronized int read(byte[] bytes, int offset, int length)
                throws IOException {
            int count = prefix.read(bytes, offset, length);
            if (count >= 0) return count;
            entered.countDown();
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", error);
                }
            }
            throw new IOException("socket closed");
        }

        @Override public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
