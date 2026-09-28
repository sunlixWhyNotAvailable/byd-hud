package com.bydhud.app;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class InstrumentLaunchWireTest {
    @Test public void launchCompletesOnSplitStatusWithoutRemoteCloseAndSessionIsReusable() throws Exception {
        ByteArrayOutputStream peer = new ByteArrayOutputStream();
        write(peer, 1, "14207\n__BYDHUD_EX");
        write(peer, 1, "IT__:0");
        write(peer, 1, "\n");
        // Late close belongs to the completed launch, not the next command.
        AdbPacket.write(peer, AdbPacket.A_CLSE, 41, 1, new byte[0]);
        write(peer, 2, "ok\n__BYDHUD_EXIT__:0\n");
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        Socket socket = new Socket() {
            final InputStream input = new ByteArrayInputStream(peer.toByteArray());
            @Override public InputStream getInputStream() { return input; }
            @Override public OutputStream getOutputStream() { return sent; }
        };
        Class<?> type = Class.forName("com.bydhud.app.LocalAdbBridge$Connection");
        Constructor<?> constructor = type.getDeclaredConstructor(Socket.class, java.security.KeyPair.class, boolean.class);
        constructor.setAccessible(true);
        Object connection = constructor.newInstance(socket, null, false);
        Method shell = type.getDeclaredMethod("shellWithExit", String.class, int.class, boolean.class);
        shell.setAccessible(true);
        LocalAdbBridge.ShellResult result = (LocalAdbBridge.ShellResult) shell.invoke(connection, "launch", 4096, true);
        assertTrue(result.success());
        assertEquals(14207, LocalAdbBridge.instrumentProxyPid(result));
        assertTrue(((LocalAdbBridge.ShellResult) shell.invoke(connection, "next", 4096, true)).success());
        int opens = 0, closes = 0;
        InputStream client = new ByteArrayInputStream(sent.toByteArray());
        while (client.available() > 0) {
            AdbPacket packet = AdbPacket.read(client);
            if (packet.command == AdbPacket.A_OPEN) opens++;
            if (packet.command == AdbPacket.A_CLSE) closes++;
        }
        assertEquals(2, opens);
        assertTrue(closes >= 2);
    }

    private static void write(OutputStream out, int local, String text) throws IOException {
        AdbPacket.write(out, AdbPacket.A_WRTE, 40 + local, local, text.getBytes(StandardCharsets.UTF_8));
    }
}
