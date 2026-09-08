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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class LocalAdbLogcatStreamTest {
    @Test
    public void packetsStreamDirectlyAndPreserveSplitUtf8Bytes() throws Exception {
        byte[] expected = "09-08 15:45:20.226  1  2 I Tag: кінець €\n"
                .getBytes(StandardCharsets.UTF_8);
        int split = expected.length - 2;
        ByteArrayOutputStream peer = new ByteArrayOutputStream();
        peer.write(packet(AdbPacket.A_WRTE, 7, 1, Arrays.copyOfRange(expected, 0, split)));
        peer.write(packet(AdbPacket.A_WRTE, 7, 1,
                Arrays.copyOfRange(expected, split, expected.length)));
        peer.write(packet(AdbPacket.A_CLSE, 7, 1, new byte[0]));
        MemorySocket socket = new MemorySocket(peer.toByteArray());
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try (LocalAdbBridge.LogcatStreamSession session =
                     LocalAdbBridge.logcatStreamForTest(socket)) {
            session.readTo(captured);
            assertThrows(IOException.class, () -> session.readTo(captured));
        }

        assertArrayEquals(expected, captured.toByteArray());
        InputStream client = new ByteArrayInputStream(socket.written.toByteArray());
        AdbPacket open = AdbPacket.read(client);
        assertEquals(AdbPacket.A_OPEN, open.command);
        assertEquals("shell:logcat -b all -v threadtime -T '09-08 15:45:20.226'\0",
                new String(open.payload, StandardCharsets.UTF_8));
        assertEquals(AdbPacket.A_OKAY, AdbPacket.read(client).command);
        assertEquals(AdbPacket.A_OKAY, AdbPacket.read(client).command);
        assertEquals(AdbPacket.A_CLSE, AdbPacket.read(client).command);
        assertEquals(0, client.available());
    }

    @Test
    public void closeUnblocksSilentReaderAndDoesNotTouchAnotherSocket() throws Exception {
        BlockingInput input = new BlockingInput();
        MemorySocket socket = new MemorySocket(input);
        MemorySocket unrelated = new MemorySocket(new byte[0]);
        LocalAdbBridge.LogcatStreamSession session = LocalAdbBridge.logcatStreamForTest(socket);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var read = executor.submit(() -> {
                session.readTo(new ByteArrayOutputStream());
                return null;
            });
            assertTrue(input.entered.await(1, TimeUnit.SECONDS));
            session.close();
            ExecutionException stopped = assertThrows(
                    ExecutionException.class, () -> read.get(1, TimeUnit.SECONDS));
            assertTrue(stopped.getCause() instanceof IOException);
            assertTrue(socket.closed);
            assertFalse(unrelated.closed);
        } finally {
            session.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void dedicatedSourceIsPromptFreeAndDoesNotUseRuntimeConnectionLock() throws Exception {
        assertThrows(SecurityException.class,
                () -> LocalAdbBridge.openLogcatStream(null, "09-08 15:45:20.226; id"));
        String source = source("LocalAdbBridge.java");
        String open = source.substring(source.indexOf("static LogcatStreamSession openLogcatStream"),
                source.indexOf("static final class LogcatStreamSession"));
        assertTrue(open.contains("AuthorizationPromptMode.NEVER"));
        assertTrue(open.contains("endpointLabel(PORT), 0L, false"));
        assertTrue(open.contains("loadConfigurationExportKeyPair"));
        assertFalse(open.contains("RUNTIME_CONNECTION_LOCK"));
        assertFalse(open.contains("loadOrCreateKeyPair"));
        assertFalse(open.contains("markAuthorizedFingerprint"));
        String stream = source.substring(source.indexOf("private void streamShell("),
                source.indexOf("//Uses the ADB sync RECV protocol", source.indexOf("private void streamShell(")));
        assertTrue(stream.contains("output.write(packet.payload)"));
        assertFalse(stream.contains("OutputAccumulator"));
        assertFalse(stream.contains("RUNTIME_CONNECTION_LOCK"));
    }

    private static byte[] packet(int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AdbPacket.write(output, command, arg0, arg1, payload);
        return output.toByteArray();
    }

    private static String source(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/").resolve(name);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/").resolve(name);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static class MemorySocket extends Socket {
        final InputStream input;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        volatile boolean closed;

        MemorySocket(byte[] input) {
            this(new ByteArrayInputStream(input));
        }

        MemorySocket(InputStream input) {
            this.input = input;
        }

        @Override public InputStream getInputStream() {
            return input;
        }

        @Override public OutputStream getOutputStream() {
            return written;
        }

        @Override public synchronized void close() throws IOException {
            closed = true;
            input.close();
        }
    }

    private static final class BlockingInput extends InputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        private boolean closed;

        @Override public synchronized int read() throws IOException {
            entered.countDown();
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", error);
                }
            }
            throw new IOException("closed");
        }

        @Override public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
