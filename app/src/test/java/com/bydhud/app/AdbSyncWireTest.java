package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class AdbSyncWireTest {
    @Test public void cancellationClosesFileAndDiagnosticsSessionsEvenAfterDetach() throws Exception {
        Constructor<LocalAdbBridge.ConfigurationExportSession> constructor =
                LocalAdbBridge.ConfigurationExportSession.class.getDeclaredConstructor(
                        Socket.class, long.class, boolean.class);
        constructor.setAccessible(true);
        FakeSocket fileSocket = new FakeSocket(new byte[0]);
        FakeSocket diagnosticsSocket = new FakeSocket(new byte[0]);
        VehicleConfigurationZip.Control control = new VehicleConfigurationZip.Control();
        control.attach(constructor.newInstance(fileSocket, 0, true));
        control.attach(constructor.newInstance(diagnosticsSocket, 0, true));
        control.attach(null);
        control.cancel();
        org.junit.Assert.assertTrue(fileSocket.closed);
        org.junit.Assert.assertTrue(diagnosticsSocket.closed);
        FakeSocket lateSocket = new FakeSocket(new byte[0]);
        control.attach(constructor.newInstance(lateSocket, 0, true));
        org.junit.Assert.assertTrue(lateSocket.closed);
        control.close(); // Closing a cancelled operation remains idempotent.
    }

    @Test public void deniedFileDoesNotPoisonNextReadOnSameExportSession() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        AdbPacket.write(wire, AdbPacket.A_OKAY, 42, 1, new byte[0]);
        AdbPacket.write(wire, AdbPacket.A_WRTE, 42, 1,
                frame("FAIL", "permission denied".getBytes(StandardCharsets.UTF_8)));
        AdbPacket.write(wire, AdbPacket.A_CLSE, 42, 1, new byte[0]);
        AdbPacket.write(wire, AdbPacket.A_OKAY, 43, 2, new byte[0]);
        AdbPacket.write(wire, AdbPacket.A_WRTE, 43, 2,
                concat(frame("DATA", new byte[]{7, 8}), frame("DONE", new byte[0])));
        FakeSocket socket = new FakeSocket(wire.toByteArray());
        Constructor<LocalAdbBridge.ConfigurationExportSession> constructor =
                LocalAdbBridge.ConfigurationExportSession.class.getDeclaredConstructor(
                        Socket.class, long.class, boolean.class);
        constructor.setAccessible(true);
        try (LocalAdbBridge.ConfigurationExportSession session = constructor.newInstance(socket, 0, true)) {
            java.lang.reflect.Field connection = session.getClass().getDeclaredField("connection");
            connection.setAccessible(true);
            connection.set(session, newConnection(socket));
            org.junit.Assert.assertThrows(AdbSyncReader.FileUnavailableException.class,
                    () -> session.readFile("/vendor/etc/denied.conf", new ByteArrayOutputStream(), 2, null));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertEquals(2L, session.readFile("/vendor/etc/readable.conf", output, 2, null));
            assertArrayEquals(new byte[]{7, 8}, output.toByteArray());
        }
    }

    @Test
    public void doneReturnsWithoutWaitingForRemoteClose() throws Exception {
        int localId = 1;
        int remoteId = 42;
        byte[] syncData = concat(frame("DATA", new byte[] {0, (byte) 0xff}),
                frame("DONE", new byte[4]));
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        AdbPacket.write(wire, AdbPacket.A_OKAY, remoteId, localId, new byte[0]);
        AdbPacket.write(wire, AdbPacket.A_WRTE, remoteId, localId, syncData);
        FakeSocket socket = new FakeSocket(wire.toByteArray());
        Object connection = newConnection(socket);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long copied = invokeReadFile(connection, "/system/etc/raw.bin", output, 2L);

        assertEquals(2L, copied);
        assertArrayEquals(new byte[] {0, (byte) 0xff}, output.toByteArray());
        ByteArrayInputStream sent = new ByteArrayInputStream(socket.sent.toByteArray());
        AdbPacket open = AdbPacket.read(sent);
        assertEquals(AdbPacket.A_OPEN, open.command);
        assertEquals("sync:\0", new String(open.payload, StandardCharsets.UTF_8));
        AdbPacket request = AdbPacket.read(sent);
        assertEquals(AdbPacket.A_WRTE, request.command);
        assertEquals("RECV", new String(request.payload, 0, 4, StandardCharsets.US_ASCII));
        AdbPacket ack = AdbPacket.read(sent);
        assertEquals(AdbPacket.A_OKAY, ack.command);
        AdbPacket close = AdbPacket.read(sent);
        assertEquals(AdbPacket.A_CLSE, close.command);
    }

    private static Object newConnection(FakeSocket socket) throws Exception {
        Class<?> connectionClass = Class.forName(
                "com.bydhud.app.LocalAdbBridge$Connection");
        Constructor<?> constructor = connectionClass.getDeclaredConstructor(
                Socket.class, java.security.KeyPair.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(socket, null, false);
    }

    private static long invokeReadFile(Object connection, String path,
            OutputStream output, long expectedBytes) throws Exception {
        Method method = connection.getClass().getDeclaredMethod("readFile",
                String.class, OutputStream.class, long.class, java.util.function.LongConsumer.class);
        method.setAccessible(true);
        try {
            return (Long) method.invoke(connection, path, output, expectedBytes, (Object) null);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private static byte[] frame(String id, byte[] body) {
        byte[] frame = new byte[8 + body.length];
        writeIntLe(frame, 0, AdbPacket.command(id));
        writeIntLe(frame, 4, body.length);
        System.arraycopy(body, 0, frame, 8, body.length);
        return frame;
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.write(part);
        return output.toByteArray();
    }

    private static void writeIntLe(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }

    private static final class FakeSocket extends Socket {
        private final InputStream input;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean closed;

        FakeSocket(byte[] incoming) {
            input = new ByteArrayInputStream(incoming);
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public void setSoTimeout(int timeout) { }
        @Override public synchronized void close() { closed = true; }

    }
}
