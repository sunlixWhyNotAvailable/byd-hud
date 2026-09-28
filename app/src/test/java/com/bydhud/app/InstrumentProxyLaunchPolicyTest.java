package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public final class InstrumentProxyLaunchPolicyTest {
    private static final String APK =
            "/data/app/~~AbCd/com.bydhud.app-XyZ==/base.apk";
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String TOKEN = "0123456789abcdef";
    private static final String STAT =
            "123 (main) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 4242";

    @Test
    public void launchCommandIsFixedAndExcludedFromGenericRuntimeAllowlist() {
        String command = LocalAdbBridge.instrumentProxyLaunchCommandForTest(
                APK, 42L, NONCE, 10_123, TOKEN, 89);

        assertTrue(command.contains("/system/bin/app_process"));
        assertTrue(command.contains("nohup /system/bin/sh -c"));
        assertTrue(command.contains("--nice-name="
                + InstrumentProxyContract.processName(10_123, TOKEN)));
        assertTrue(command.contains("com.bydhud.app.InstrumentProxyEntryPoint"));
        assertTrue(command.contains("--generation=42"));
        assertTrue(command.contains("--nonce=" + NONCE));
        assertTrue(command.contains("--app-uid=10123"));
        assertTrue(command.contains("--launch-token=" + TOKEN));
        assertTrue(command.contains("--version-code=89"));
        assertTrue(command.startsWith(
                "umask 077; rm -f /data/local/tmp/bydhud-instrument-10123-42.log "));
        assertTrue(command.contains(
                " >/data/local/tmp/bydhud-instrument-10123-42.log 2>&1"));
        assertFalse(command.contains(">/dev/null 2>&1"));
        assertTrue(command.contains("wait $child; code=$?"));
        assertTrue(command.contains("instrument_child_exit childPid=$child exitCode=$code"));
        assertTrue(command.contains("cat /data/local/tmp/bydhud-instrument-10123-42.log.pid; exit 0"));
        assertFalse(command.endsWith("& echo $!"));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(command));
    }

    @Test
    public void startupDiagnosticRedactsLaunchSecretsAndStaysBounded() {
        String raw = "pidAlive=0 --nonce=" + NONCE
                + " --launch-token=" + TOKEN + "\n"
                + "x".repeat(2_000);
        String diagnostic = LocalAdbBridge
                .sanitizeInstrumentProxyStartupDiagnosticForTest(raw);

        assertFalse(diagnostic.contains(NONCE));
        assertFalse(diagnostic.contains(TOKEN));
        assertTrue(diagnostic.contains("--nonce=<redacted>"));
        assertTrue(diagnostic.contains("--launch-token=<redacted>"));
        assertTrue(diagnostic.length() <= 1_605);
    }

    @Test
    public void supervisorReturnsChildPidAndPersistsExitBeforeJavaStarts() throws Exception {
        checkSupervisorExit("exit 17", 17);
        checkSupervisorExit("kill -TERM $$", 143);
    }

    private static void checkSupervisorExit(String childScript, int expected) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path shell = windows ? Paths.get("D:/Programs/Git/bin/bash.exe") : Paths.get("/bin/sh");
        org.junit.Assume.assumeTrue("POSIX shell required for launch behavior check", Files.isRegularFile(shell));
        Path temp = Files.createTempDirectory("bydhud-instrument-test-");
        Path log = temp.resolve("startup.log");
        String shellPath = log.toString().replace('\\', '/');
        if (windows) shellPath = "/" + Character.toLowerCase(shellPath.charAt(0)) + shellPath.substring(2);
        String command = LocalAdbBridge.supervisedInstrumentCommand(
                "sh -c 'sleep 0.2; " + childScript + "'", shellPath, "sh");
        // Windows nohup detaches through a different process model; exercise wait/PID/exit here.
        // The production-command assertion separately requires the existing Android nohup wrapper.
        if (windows) command = command.replace("nohup sh", "sh");
        Path script = temp.resolve("launch.sh");
        Files.write(script, command.getBytes(StandardCharsets.UTF_8));
        // A file avoids Windows command-line quote rewriting of the nested shell program.
        ProcessBuilder launch = new ProcessBuilder(shell.toString(), script.toString().replace('\\', '/'))
                .redirectErrorStream(true);
        launch.environment().put("MSYS_NO_PATHCONV", "1");
        if (windows) launch.environment().put("PATH", "D:/Programs/Git/usr/bin;" + System.getenv("PATH"));
        Process process = launch.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue("launcher must finish", finished);
        String pid = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        org.junit.Assert.assertEquals(pid, 0, process.exitValue());
        assertTrue("actual child PID: " + pid, pid.matches("[0-9]+"));
        String evidence = "";
        for (int i = 0; i < 150; i++) {
            if (Files.exists(log)) evidence = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            if (evidence.contains("exitCode=" + expected)) break;
            Thread.sleep(20);
        }
        assertTrue(evidence, evidence.contains("instrument_child_started childPid=" + pid));
        assertTrue(evidence, evidence.contains("instrument_child_exit childPid=" + pid + " exitCode=" + expected));
        assertFalse(evidence.contains("ENTRY_VALIDATED"));
        if (expected == 143) assertTrue(evidence.contains("signalNotProvenByExitCodeAlone=1"));
        Files.deleteIfExists(log);
        Files.deleteIfExists(temp.resolve("startup.log.pid"));
        Files.deleteIfExists(script);
        Files.delete(temp);
    }

    @Test
    public void cleanupIdentityRequiresExactShellOwnedProcess() {
        String legacyName = InstrumentProxyContract.legacyProcessName(10_123);
        assertTrue(LocalAdbBridge.hasExpectedInstrumentProxyIdentityForTest(
                "Name:\t" + legacyName + "\nUid:\t2000\t2000\t2000\t2000\n",
                legacyName));
        assertFalse(LocalAdbBridge.hasExpectedInstrumentProxyIdentityForTest(
                "Name:\t" + legacyName + "\nUid:\t10123\t10123\t10123\t10123\n",
                legacyName));
        assertFalse(LocalAdbBridge.hasExpectedInstrumentProxyIdentityForTest(
                "Name:\tother\nUid:\t2000\t2000\t2000\t2000\n", legacyName));

        String name = InstrumentProxyContract.processName(10_123, TOKEN);
        InstrumentProxyStore.Identity expected = new InstrumentProxyStore.Identity(
                123, 10_123, 42L, NONCE, TOKEN, name, 4242L, 89, true);
        assertTrue(LocalAdbBridge.hasExpectedInstrumentProxyIdentityForTest(
                "Name:\tmain\nUid:\t2000\t2000\t2000\t2000\n",
                name, STAT, expected, 123));
        assertFalse(LocalAdbBridge.hasExpectedInstrumentProxyIdentityForTest(
                "Name:\tmain\nUid:\t2000\t2000\t2000\t2000\n",
                name, STAT.replace("4242", "4243"), expected, 123));
        assertFalse(LocalAdbBridge.hasExpectedInstrumentProxyIdentityForTest(
                "Name:\tmain\nUid:\t2000\t2000\t2000\t2000\n",
                name, STAT, expected, 124));
        assertTrue(LocalAdbBridge.hasExpectedLegacyInstrumentProxyIdentityForTest(
                "Name:\tmain\nUid:\t2000\t2000\t2000\t2000\n",
                legacyName, legacyName));
        assertFalse(LocalAdbBridge.hasExpectedLegacyInstrumentProxyIdentityForTest(
                "Name:\tmain\nUid:\t2000\t2000\t2000\t2000\n",
                name, legacyName));
    }

    @Test
    public void launchCommandRejectsUntrustedParameters() {
        assertThrows(SecurityException.class,
                () -> LocalAdbBridge.instrumentProxyLaunchCommandForTest(
                        "/data/app/../system/base.apk", 42L, NONCE,
                        10_123, TOKEN, 89));
        assertThrows(SecurityException.class,
                () -> LocalAdbBridge.instrumentProxyLaunchCommandForTest(
                        APK + ";id", 42L, NONCE, 10_123, TOKEN, 89));
        assertThrows(SecurityException.class,
                () -> LocalAdbBridge.instrumentProxyLaunchCommandForTest(
                        APK, 42L, "bad", 10_123, TOKEN, 89));
        assertThrows(SecurityException.class,
                () -> LocalAdbBridge.instrumentProxyLaunchCommandForTest(
                        APK, 42L, NONCE, 2_000, TOKEN, 89));
        assertThrows(SecurityException.class,
                () -> LocalAdbBridge.instrumentProxyLaunchCommandForTest(
                        APK, 42L, NONCE, 10_123, "bad", 89));
    }

    @Test
    public void persistedIdentityRejectsPidReuseAndCleanupSkipsForeignProcess()
            throws IOException {
        String name = InstrumentProxyContract.processName(10_123, TOKEN);
        InstrumentProxyStore.Identity first = new InstrumentProxyStore.Identity(
                123, 10_123, 42L, NONCE, TOKEN, name, 4242L, 89, true);
        InstrumentProxyStore.Identity same = new InstrumentProxyStore.Identity(
                123, 10_123, 42L, NONCE, TOKEN, name, 4242L, 89, false);
        InstrumentProxyStore.Identity replacement = InstrumentProxyStore.Identity.pending(
                10_123, 43L, NONCE, "fedcba9876543210", 89);

        assertTrue(first.isValid());
        assertTrue(first.sameLaunch(same));
        assertFalse(first.sameLaunch(replacement));
        assertFalse(LocalAdbBridge.hasExpectedInstrumentProxyIdentityForTest(
                "Name:\tmain\nUid:\t2000\t2000\t2000\t2000\n",
                name, STAT.replace("4242", "5000"), first, 123));
        String bridge = source(
                "app/src/main/java/com/bydhud/app/LocalAdbBridge.java");
        assertTrue(bridge.contains("cleanup_identity_not_owned"));
        assertTrue(bridge.contains("if (!identity.matches(expected, pid))"));
        assertTrue(bridge.contains("continue;"));
    }

    private static String source(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
