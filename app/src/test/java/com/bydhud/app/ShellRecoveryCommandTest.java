package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ShellRecoveryCommandTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    @Test public void realCommandExitIsLoggedAndAdmitsTheNextCommand() throws Exception {
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        File output = files.newFile();
        ProcessBuilder javaCommand = javaVersion(output);
        try (ShellRecoveryCommand owner = new ShellRecoveryCommand(10_000L, events::add)) {
            assertTrue(owner.start(javaCommand));
            assertTrue(awaitExit(events).contains("exitCode=0 reason=completed"));
            assertTrue(new String(Files.readAllBytes(output.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).contains("version"));
            assertTrue(owner.start(javaCommand));
            assertTrue(awaitExit(events).contains("exitCode=0 reason=completed"));
        }
    }

    @Test public void timeoutReapsRealProcessAndShutdownClosesAdmission() throws Exception {
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        ShellRecoveryCommand owner = new ShellRecoveryCommand(500L, events::add);
        File rejectedOutput = new File(files.getRoot(), "must-not-run.txt");
        try {
            assertTrue(owner.start(longRunningCommand()));
            assertFalse(owner.start(javaVersion(rejectedOutput)));
            assertFalse(rejectedOutput.exists());
            assertTrue(awaitExit(events).contains("reason=timeout"));
            assertTrue(owner.start(longRunningCommand()));
            owner.close();
            assertTrue(awaitExit(events).contains("reason=runtime_stopped"));
            assertFalse(owner.start(javaVersion(rejectedOutput)));
            assertFalse(rejectedOutput.exists());
        } finally {
            owner.close();
        }
    }

    private static String awaitExit(LinkedBlockingQueue<String> events) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String event = events.poll(100L, TimeUnit.MILLISECONDS);
            if (event != null && event.contains("exited exitCode=")) return event;
        }
        throw new AssertionError("Real command did not exit");
    }

    private static ProcessBuilder javaVersion(File output) {
        String binary = isWindows() ? "java.exe" : "java";
        return new ProcessBuilder(new File(System.getProperty("java.home"), "bin/" + binary)
                .getPath(), "-version").redirectErrorStream(true).redirectOutput(output);
    }

    private static ProcessBuilder longRunningCommand() {
        // Exercise real OS process lifetime; no replacement Android/Binder service.
        ProcessBuilder command = isWindows()
                ? new ProcessBuilder(new File(System.getenv("SystemRoot"), "System32/ping.exe")
                        .getPath(), "-t", "127.0.0.1")
                : new ProcessBuilder("/bin/sleep", "30");
        return command.redirectErrorStream(true)
                .redirectOutput(new File(isWindows() ? "NUL" : "/dev/null"));
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }
}
