package com.bydhud.app;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Uses in-memory ADB peers only: no socket connect, app process, emulator or vehicle. */
public final class VehicleConfigurationReadbackTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void catalogIsFiniteTypedGetterOnlyAndDistinguishesReferenceBooleans() {
        Set<Integer> ids = new HashSet<>();
        assertEquals(28, VehicleConfigurationReadback.READS.size());
        for (VehicleConfigurationReadback.Read read : VehicleConfigurationReadback.READS) {
            assertTrue(ids.add(read.id));
            //The device's package contains '.setting'; check the exact method, not a package substring.
            assertTrue(read.namedGetter.isEmpty() || "getSpeedUnit".equals(read.namedGetter));
            assertEquals(read.deviceClass + (read.namedGetter.isEmpty()
                    ? ".get(int[],Class)" : ".getSpeedUnit()"), read.api());
            assertTrue(read.type == Integer.TYPE || read.type == Double.TYPE);
            assertFalse(read.id == 0x43F01010 || read.id == 0x43F01018 || read.id == 0x4CA00018);
        }
        assertEquals(Double.TYPE, read("hud.angle").type);
        assertEquals(VehicleConfigurationReadback.INSTRUMENT, read("hud.navigationMap").deviceClass);
        assertEquals("reference: off", VehicleConfigurationReadback.interpretation(read("hud.navigationFusion"), 1));
        assertEquals("reference: on", VehicleConfigurationReadback.interpretation(read("hud.dynamicNavigation"), 1));
        assertEquals("", VehicleConfigurationReadback.interpretation(read("hud.variant"), 0));
        assertEquals("reference semantic height: 0", VehicleConfigurationReadback.interpretation(read("hud.height"), 11));
        assertTrue(VehicleConfigurationReadback.isSentinel(-2147482648));
        assertTrue(VehicleConfigurationReadback.isSentinel(-999999999));
        assertFalse(VehicleConfigurationReadback.isSentinel(-1.5));
    }

    @Test public void commandsExcludeEveryMutationAndExternalGetterParameter() {
        assertTrue(VehicleConfigurationReadback.isAllowedCommand("settings get secure enabled_accessibility_services"));
        assertTrue(VehicleConfigurationReadback.isAllowedCommand("getprop ro.build.system.fission_single_os"));
        assertTrue(VehicleConfigurationReadback.isAllowedCommand("dumpsys audio"));
        for (String packageName : new String[]{"com.example.amapservice", "com.byd.amapservice",
                "com.byd.containerservice", "com.byd.someipsystemservice", "com.byd.clusterdebug",
                "com.android.launcher3"}) {
            assertTrue(VehicleConfigurationReadback.isAllowedCommand("pm path " + packageName));
        }
        for (String jar : new String[]{"services.jar", "dilink-services.jar"}) {
            assertTrue(VehicleConfigurationReadback.isAllowedCommand("stat -c %s /system/framework/" + jar));
            assertTrue(VehicleConfigurationReadback.isAllowedCommand("sha256sum /system/framework/" + jar));
            assertFalse(VehicleConfigurationReadback.isAllowedCommand("cat /system/framework/" + jar));
        }
        for (String command : new String[]{"settings put secure accessibility_enabled 1",
                "dumpsys gfxinfo com.bydhud.app reset", "service call AutoContainer 2 i32 1000 i32 16",
                "pm grant com.bydhud.app android.permission.WRITE_SECURE_SETTINGS", "getprop; id",
                "cat /data/user/0/com.waze/shared_prefs/user.xml", "app_process /system/bin Arbitrary",
                "pm path com.unrelated.example", "pm path com.byd.amapservice; id",
                "find /system/framework -type f", "sha256sum /system/framework/other.jar",
                "stat -c %s /system/framework/other.jar", "sha256sum /system/framework/services.jar; id"}) {
            assertFalse(command, VehicleConfigurationReadback.isAllowedCommand(command));
        }
        String launch = VehicleConfigurationReadback.launchCommand("/data/app/~~abc/com.bydhud.app-123/base.apk");
        assertTrue(launch.contains("/system/bin/timeout -s KILL 15 /system/bin/app_process"));
        assertTrue(launch.contains("com.bydhud.app.VehicleConfigurationReadbackEntryPoint"));
        assertFalse(launch.contains("InstrumentProxyEntryPoint"));
        assertFalse(launch.contains("nohup"));
        assertFalse(launch.contains("/data/local/tmp"));
        assertThrows(SecurityException.class, () -> VehicleConfigurationReadback.launchCommand("/data/app/../base.apk"));
        assertThrows(SecurityException.class, () -> VehicleConfigurationReadback.launchCommand("/data/app/x/base.apk; id"));
        assertTrue(VehicleConfigurationReadbackEntryPoint.allowedPermission("android.permission.BYDAUTO_SETTING_GET"));
        assertFalse(VehicleConfigurationReadbackEntryPoint.allowedPermission("android.permission.BYDAUTO_SETTING_SET"));
        assertFalse(VehicleConfigurationReadbackEntryPoint.allowedPermission("android.permission.BYDAUTO_INSTRUMENT_SET"));
        assertEquals("denied", VehicleConfigurationReadbackEntryPoint.failureStatus(new SecurityException()));
        assertEquals("unsupported", VehicleConfigurationReadbackEntryPoint.failureStatus(new NoSuchMethodException()));
    }

    @Test public void existingPairIsReadOnlyAndPartialOrMismatchedPairIsRejected() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path privateFile = directory.resolve("key.priv");
        Path publicFile = directory.resolve("key.pub");
        assertNull(LocalAdbBridge.loadConfigurationExportKeyPair(privateFile.toFile(), publicFile.toFile()));
        assertEquals(0, directory.toFile().list().length);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Files.write(privateFile, pair.getPrivate().getEncoded());
        assertNull(LocalAdbBridge.loadConfigurationExportKeyPair(privateFile.toFile(), publicFile.toFile()));
        assertFalse(Files.exists(publicFile));
        Files.write(publicFile, pair.getPublic().getEncoded());
        assertNotNull(LocalAdbBridge.loadConfigurationExportKeyPair(privateFile.toFile(), publicFile.toFile()));
        assertArrayEquals(pair.getPrivate().getEncoded(), Files.readAllBytes(privateFile));
        assertArrayEquals(pair.getPublic().getEncoded(), Files.readAllBytes(publicFile));
        Files.write(publicFile, generator.generateKeyPair().getPublic().getEncoded());
        assertNull(LocalAdbBridge.loadConfigurationExportKeyPair(privateFile.toFile(), publicFile.toFile()));
        Files.write(publicFile, new byte[]{1, 2, 3});
        assertNull(LocalAdbBridge.loadConfigurationExportKeyPair(privateFile.toFile(), publicFile.toFile()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(publicFile));
    }

    @Test public void silentAndBlockingWritePeersHaveAbsoluteDeadline() throws Exception {
        for (boolean blockWrite : new boolean[]{false, true}) {
            MemorySocket socket = new MemorySocket(new byte[0], false, blockWrite);
            try (LocalAdbBridge.ConfigurationExportSession session = session(socket, 2000)) {
                long start = System.nanoTime();
                LocalAdbBridge.ShellResult result = execute(session, 100);
                assertEquals("timeout", result.status);
                assertEquals(124, result.exitCode);
                assertTrue(result.observedAtMs > 0);
                assertTrue(result.durationMs >= 0);
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
                assertTrue(socket.closed);
                assertEquals("skipped", session.run("id").status);
            }
        }
    }

    @Test public void streamingPeerIsBoundedAndRetainsCompletedRecords() throws Exception {
        String line = VehicleConfigurationReadback.RECORD_PREFIX + "{\"parameter\":\"fixture\",\"status\":\"success\"}\n";
        MemorySocket socket = new MemorySocket(packet(AdbPacket.A_WRTE, 7, 1, line), true, false);
        try (LocalAdbBridge.ConfigurationExportSession session = session(socket, 2000)) {
            LocalAdbBridge.ShellResult result = execute(session, 100);
            assertEquals("timeout", result.status);
            assertTrue(result.output.startsWith(line));
            assertTrue(result.output.getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024);
        }
    }

    @Test public void completedOutputSurvivesProtocolFailureAndSuccessKeepsFinalNewline() throws Exception {
        String line = VehicleConfigurationReadback.RECORD_PREFIX + "{\"parameter\":\"fixture\"}\n";
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write(packet(AdbPacket.A_WRTE, 7, 1, line));
        byte[] badHeader = new byte[24];
        badHeader[12] = 1;
        badHeader[14] = 16; //1 MiB + 1: reject before allocating a payload.
        input.write(badHeader);
        try (var session = session(new MemorySocket(input.toByteArray(), false, false), 2000)) {
            var result = session.run("id");
            assertEquals("error", result.status);
            assertEquals(line, result.output);
        }
        input.reset();
        input.write(packet(AdbPacket.A_WRTE, 7, 1, line + "__BYDHUD_EXIT__:0\n"));
        input.write(packet(AdbPacket.A_CLSE, 7, 1, ""));
        try (var session = session(new MemorySocket(input.toByteArray(), false, false), 2000)) {
            var result = session.run("id");
            assertEquals("success", result.status);
            assertTrue(result.raw.contains(line));
        }
    }

    @Test public void sessionDeadlineAndCancellationCloseOnlyOwnedTransport() throws Exception {
        MemorySocket unrelated = new MemorySocket(new byte[0], false, false);
        Field pending = LocalAdbBridge.class.getDeclaredField("pendingAuthorizationSocket");
        pending.setAccessible(true);
        Object previous = pending.get(null);
        pending.set(null, unrelated);
        try {
            MemorySocket socket = new MemorySocket(new byte[0], false, false);
            try (LocalAdbBridge.ConfigurationExportSession session = session(socket, 100)) {
                LocalAdbBridge.ShellResult result = execute(session, 2000);
                assertEquals("timeout", result.status);
                assertTrue(result.error.contains("session deadline"));
                assertFalse(unrelated.closed);
                assertSame(unrelated, pending.get(null));
            }
            MemorySocket cancelled = new MemorySocket(new byte[0], false, false);
            try (LocalAdbBridge.ConfigurationExportSession session = session(cancelled, 2000)) {
                var executor = Executors.newSingleThreadExecutor();
                try {
                    var running = executor.submit(() -> session.run("id"));
                    assertTrue(cancelled.entered.await(1, TimeUnit.SECONDS));
                    assertEquals("skipped", session.run("id").status);
                    session.close();
                    assertEquals("error", running.get(1, TimeUnit.SECONDS).status);
                    assertTrue(cancelled.closed);
                    assertFalse(unrelated.closed);
                } finally { executor.shutdownNow(); }
            }
        } finally { pending.set(null, previous); }
    }

    @Test public void exhaustedCollectorBudgetNeverReadsContextOrConnects() {
        for (long budget : new long[]{0L, -1L, Long.MIN_VALUE}) {
            //Null Context proves the exhausted fast path does not perform even local setup.
            try (var session = LocalAdbBridge.openConfigurationExport(null, budget)) {
                var skipped = session.run("id");
                assertEquals("skipped", skipped.status);
                assertTrue(skipped.observedAtMs > 0);
                assertTrue(skipped.durationMs >= 0);
                var oem = session.readOem();
                assertTrue(oem.error.contains("collection deadline"));
                assertTrue(oem.observedAtMs > 0);
                assertTrue(oem.durationMs >= 0);
            }
        }
    }

    @Test public void runtimeParsingDoesNotInventObservationTiming() {
        var result = LocalAdbBridge.ShellResult.parse("fixture\n__BYDHUD_EXIT__:0\n");
        assertTrue(result.success());
        assertEquals(-1L, result.observedAtMs);
        assertEquals(-1L, result.durationMs);
    }

    @Test public void exportHandshakeSignsButNeverPromptsOrPublishesAuthorization() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write(packet(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, "12345678901234567890"));
        input.write(packet(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, "12345678901234567890"));
        MemorySocket socket = new MemorySocket(input.toByteArray(), false, false);
        Class<?> connection = Class.forName("com.bydhud.app.LocalAdbBridge$Connection");
        Method open = connection.getDeclaredMethod("openConnectedSocket", android.content.Context.class,
                LocalAdbBridge.AuthorizationPromptMode.class, KeyPair.class, String.class,
                Socket.class, String.class, long.class, boolean.class);
        open.setAccessible(true);
        //A null Context intentionally fails if the passive path reads/writes preferences or publishes callbacks.
        Object result = open.invoke(null, null, LocalAdbBridge.AuthorizationPromptMode.NEVER,
                pair, "", socket, "test", 0L, false);
        Field required = result.getClass().getDeclaredField("authorizationRequired");
        required.setAccessible(true);
        assertEquals(true, required.get(result));
        InputStream written = new java.io.ByteArrayInputStream(socket.written.toByteArray());
        assertEquals(AdbPacket.A_CNXN, AdbPacket.read(written).command);
        AdbPacket signed = AdbPacket.read(written);
        assertEquals(AdbPacket.AUTH_SIGNATURE, signed.arg0);
        assertEquals(0, written.available());
        assertTrue(socket.closed);
        MemorySocket accepted = new MemorySocket(packet(AdbPacket.A_CNXN, AdbPacket.VERSION, AdbPacket.MAX_DATA, "device::"), false, false);
        result = open.invoke(null, null, LocalAdbBridge.AuthorizationPromptMode.NEVER, pair, "", accepted, "test", 0L, false);
        assertEquals(false, required.get(result));
        accepted.close();
    }

    @Test public void exportSourceCannotStartReadinessOrRuntimeRepair() throws Exception {
        String bridge = source("LocalAdbBridge.java");
        String export = bridge.substring(bridge.indexOf("static ConfigurationExportSession openConfigurationExport"),
                bridge.indexOf("//closes only the socket waiting for RSA approval"));
        for (String forbidden : new String[]{"loadOrCreateKeyPair", "RUNTIME_CONNECTION_LOCK", "KEY_PAIR_LOCK",
                "runRuntimeShellCommand", "markAuthorizedFingerprint", "clearAuthorizedFingerprint",
                "trackPendingAuthorization", "MainActivity", "InstrumentProxy"}) assertFalse(forbidden, export.contains(forbidden));
        String helper = source("VehicleConfigurationReadbackEntryPoint.java");
        assertFalse(helper.contains("getMethod(\"set\""));
        assertFalse(helper.contains("registerListener"));
        assertFalse(helper.contains("CountryCodeManager"));
        assertFalse(helper.contains("Looper.loop"));
        assertFalse(helper.contains("ensureStarted"));
        assertTrue(helper.contains("Process.killProcess(Process.myPid())"));
        assertEquals(5000, VehicleConfigurationReadback.COMMAND_TIMEOUT_MS);
        assertEquals(1500, VehicleConfigurationReadback.GETTER_TIMEOUT_MS);
        assertEquals(15000, VehicleConfigurationReadback.OEM_TIMEOUT_MS);
        assertEquals(60000, VehicleConfigurationReadback.SESSION_TIMEOUT_MS);
    }

    private static VehicleConfigurationReadback.Read read(String name) {
        return VehicleConfigurationReadback.READS.stream().filter(r -> name.equals(r.parameter)).findFirst().get();
    }
    private static LocalAdbBridge.ConfigurationExportSession session(MemorySocket socket, long budgetMs) throws Exception {
        Constructor<?> constructor = LocalAdbBridge.ConfigurationExportSession.class.getDeclaredConstructor(Socket.class, long.class);
        constructor.setAccessible(true);
        var session = (LocalAdbBridge.ConfigurationExportSession) constructor.newInstance(socket, budgetMs);
        Class<?> connection = Class.forName("com.bydhud.app.LocalAdbBridge$Connection");
        Constructor<?> connected = connection.getDeclaredConstructor(Socket.class, KeyPair.class, boolean.class);
        connected.setAccessible(true);
        Field field = session.getClass().getDeclaredField("connection");
        field.setAccessible(true);
        field.set(session, connected.newInstance(socket, null, false));
        return session;
    }
    private static LocalAdbBridge.ShellResult execute(LocalAdbBridge.ConfigurationExportSession session, long timeoutMs) throws Exception {
        Method execute = session.getClass().getDeclaredMethod("execute", String.class, long.class);
        execute.setAccessible(true);
        return (LocalAdbBridge.ShellResult) execute.invoke(session, "id", timeoutMs);
    }
    private static byte[] packet(int command, int arg0, int arg1, String payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AdbPacket.write(bytes, command, arg0, arg1, payload.getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }
    private static String source(String file) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path path = root.resolve("app/src/main/java/com/bydhud/app/" + file);
        if (!Files.isRegularFile(path)) path = root.resolve("src/main/java/com/bydhud/app/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    private static final class MemorySocket extends Socket {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean closed;
        private final byte[] input;
        private final boolean repeat;
        private final boolean blockWrite;
        private int offset;
        MemorySocket(byte[] input, boolean repeat, boolean blockWrite) {
            this.input = input; this.repeat = repeat; this.blockWrite = blockWrite;
        }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    synchronized (MemorySocket.this) {
                        entered.countDown();
                        while (!closed && offset >= input.length) {
                            if (repeat && input.length > 0) { offset = 0; break; }
                            waitForClose();
                        }
                        if (closed) throw new IOException("closed");
                        return input[offset++] & 255;
                    }
                }
            };
        }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    synchronized (MemorySocket.this) {
                        entered.countDown();
                        while (blockWrite && !closed) waitForClose();
                        if (closed) throw new IOException("closed");
                        written.write(value);
                    }
                }
            };
        }
        private void waitForClose() throws IOException {
            try { wait(); } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IOException(error);
            }
        }
        @Override public synchronized void close() { closed = true; notifyAll(); }
    }
}
