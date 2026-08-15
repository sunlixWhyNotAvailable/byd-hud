package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SystemDiagnosticRecorderPolicyTest {
    @Test
    public void FullSystemCommandsAreTypedAndShellInjectionIsRejected() {
        String logcat = LogcatRecorder.fullLogcatCommandForTest(1_786_793_345_678L);
        assertTrue(LocalAdbBridge.isAllowedDiagnosticShellCommandForTest(logcat));
        assertTrue(LocalAdbBridge.isAllowedDiagnosticShellCommandForTest(
                "dumpsys gfxinfo com.bydhud.app reset"));
        assertTrue(LocalAdbBridge.isAllowedDiagnosticShellCommandForTest(
                "dumpsys gfxinfo com.bydhud.app framestats"));
        assertTrue(LocalAdbBridge.isAllowedDiagnosticShellCommandForTest(
                "cat /proc/12345/stat"));
        assertFalse(LocalAdbBridge.isAllowedDiagnosticShellCommandForTest(
                logcat + "; rm -rf /data/local/tmp"));
        assertFalse(LocalAdbBridge.isAllowedDiagnosticShellCommandForTest(
                "cat /proc/12345/maps"));
    }

    @Test
    public void PresetPhasesStayMatchedToTheDiagnosticProtocol() {
        assertEquals(10_000L, LogcatRecorder.presetDurationMs(LogcatRecorder.PHASE_IDLE));
        assertEquals(24_000L,
                LogcatRecorder.presetDurationMs(LogcatRecorder.PHASE_INTERACTION_1));
        assertEquals(24_000L,
                LogcatRecorder.presetDurationMs(LogcatRecorder.PHASE_INTERACTION_2));
        assertEquals(-1L, LogcatRecorder.presetDurationMs("arbitrary"));
    }

    @Test
    public void RecorderNeverClearsGlobalBuffersAndHasBoundedFallbackEvidence()
            throws IOException {
        String source = source("LogcatRecorder.java");
        assertFalse(source.contains("logcat\", \"-c"));
        assertFalse(source.contains("logcat -c"));
        assertTrue(source.contains("full_system_adb"));
        assertTrue(source.contains("app_uid_fallback"));
        assertTrue(source.contains("SEGMENT_BYTES = 16L * 1024L * 1024L"));
        assertTrue(source.contains("MAX_SEGMENTS = 4"));
        assertTrue(source.contains("lockTopologyRead()"));
        assertTrue(source.contains("session.context.getCacheDir()"));
        assertTrue(source.contains("yyyyMMdd_HHmmss_SSS"));
        assertTrue(source.contains("manifest.json"));
        assertTrue(source.contains("dumpsys gfxinfo com.bydhud.app framestats"));
    }

    private static String source(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/")
                .resolve(name).normalize();
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/")
                    .resolve(name).normalize();
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
