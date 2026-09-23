package com.bydhud.app;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Executes the production command bodies locally; never connects to ADB. */
public final class ShanghaiShellResultTest {
    private static final String TOKEN = "123456abcdef";

    @Test public void stopPreservesAllExitBranchesAndIdentityGuards() throws Exception {
        Path pidFile = Files.createTempFile("bydhud-stop-test", ".pid");
        try {
            String command = LocalAdbBridge.shanghaiStopCommand(TOKEN, "adas")
                    .replace("/data/local/tmp/bydhud-shanghai-" + TOKEN + "-adas.pid", quote(pidFile));
            String hooks = "awk() { echo 123; }; kill() { echo signal=$1 pid=$2; return 0; }; ";
            Files.delete(pidFile);
            assertEquals(0, run(hooks + command).exitCode);

            Files.write(pidFile, "invalid 123".getBytes(StandardCharsets.UTF_8));
            LocalAdbBridge.ShellResult invalid = run(hooks + command);
            assertEquals(65, invalid.exitCode);
            assertFalse(invalid.output.contains("signal="));
            assertTrue(Files.exists(pidFile));

            Files.write(pidFile, "321 122".getBytes(StandardCharsets.UTF_8));
            LocalAdbBridge.ShellResult mismatch = run(hooks + command);
            assertEquals(66, mismatch.exitCode);
            assertFalse(mismatch.output.contains("signal="));
            assertTrue(Files.exists(pidFile));

            Files.write(pidFile, "321 123".getBytes(StandardCharsets.UTF_8));
            LocalAdbBridge.ShellResult stopped = run(hooks + command);
            assertEquals(0, stopped.exitCode);
            assertTrue(stopped.output.contains("signal=-TERM pid=321"));
            assertFalse(Files.exists(pidFile));

            Files.write(pidFile, "321 123".getBytes(StandardCharsets.UTF_8));
            LocalAdbBridge.ShellResult failed = run(hooks.replace("return 0", "return 1") + command);
            assertEquals(1, failed.exitCode);
            assertFalse(failed.success());
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    @Test public void unavailableTcpdumpReturns127InsteadOfMissingMarker() throws Exception {
        // A temporary empty directory provides deterministic absent binary paths on any host.
        Path absent = Files.createTempDirectory("bydhud-pcap-test");
        try {
            String command = LocalAdbBridge.shanghaiPcapProbeCommand()
                    .replace("/system/bin/tcpdump", quote(absent.resolve("system-tcpdump")))
                    .replace("/vendor/bin/tcpdump", quote(absent.resolve("vendor-tcpdump")));
            assertEquals(127, run(command).exitCode);
        } finally {
            Files.deleteIfExists(absent);
        }
    }

    @Test public void availableTcpdumpPreservesPathAndInterfaceSelection() throws Exception {
        // The host shell stands in for the fixed executable; tcpdump is never started.
        String command = LocalAdbBridge.shanghaiPcapProbeCommand()
                .replace("/system/bin/tcpdump", "/bin/sh");
        LocalAdbBridge.ShellResult eth0 = run("ip() { return 0; }; " + command);
        assertEquals(0, eth0.exitCode);
        assertTrue(eth0.output.contains("/bin/sh"));
        assertTrue(eth0.output.contains("interface=eth0"));
        LocalAdbBridge.ShellResult any = run("ip() { return 1; }; " + command);
        assertEquals(0, any.exitCode);
        assertTrue(any.output.contains("interface=any"));
    }

    @Test public void stopRejectsUntrustedArgumentsBeforeBuildingShell() {
        assertThrows(SecurityException.class, () -> LocalAdbBridge.shanghaiStopCommand("x;id", "adas"));
        assertThrows(SecurityException.class, () -> LocalAdbBridge.shanghaiStopCommand(TOKEN, "adas;id"));
    }

    private static LocalAdbBridge.ShellResult run(String command) throws Exception {
        Process process = new ProcessBuilder("sh", "-c", command + "; echo __BYDHUD_EXIT__:$?")
                .redirectErrorStream(true).start();
        try {
            assertTrue("local shell timed out", process.waitFor(5, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 0, process.exitValue());
            return LocalAdbBridge.ShellResult.parse(output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String quote(Path path) {
        return "'" + path.toAbsolutePath().toString().replace('\\', '/').replace("'", "'\"'\"'") + "'";
    }
}
