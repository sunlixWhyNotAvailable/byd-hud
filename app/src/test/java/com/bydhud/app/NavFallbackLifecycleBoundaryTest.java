package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class NavFallbackLifecycleBoundaryTest {
    @Test
    public void packageReplaceResetIsFencedWithoutProcessKill() throws IOException {
        String supervisor = source("HudRuntimeSupervisor.java");
        assertFalse(supervisor.contains("killProcess"));
        assertTrue(supervisor.contains("senderTeardownComplete"));
        assertTrue(supervisor.contains("package_replace_restart_scheduled"));
        assertTrue(supervisor.contains("NavHudLiveSender.get(appContext).stop"));
        String sender = source("NavHudLiveSender.java");
        assertTrue(sender.contains("hardStopDirectNavigatorsForPackageReplace"));
        assertTrue(sender.contains("wazeDirectChannel.hardStop(\"package-replace-hard-reset\")"));
        assertTrue(sender.contains("boolean forcedDirectTeardown = pendingForcedDirectTeardown"));
        assertTrue(sender.contains("if (forcedDirectTeardown)"));
        assertTrue(sender.contains("package reinit restart suppressed by forced teardown"));
    }

    @Test
    public void persistentStartDecisionIsAtomicAtTheSharedBoundary() throws IOException {
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, false, false, false) == HudRuntimeService.StartDecision.REQUEST);
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, false, true, false) == HudRuntimeService.StartDecision.IN_FLIGHT);
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, true, false, false) == HudRuntimeService.StartDecision.ALREADY_ALIVE);
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, true, false, true) == HudRuntimeService.StartDecision.REQUEST);
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, true, true, true) == HudRuntimeService.StartDecision.IN_FLIGHT);
        String service = source("HudRuntimeService.java");
        String supervisor = source("HudRuntimeSupervisor.java");
        assertTrue(service.contains("START_IN_FLIGHT.compareAndSet(false, true)"));
        assertTrue(service.contains("if (!hardResetPending"));
        assertTrue(supervisor.contains("hardResetPending || !HudRuntimeState.isAlive"));
        assertTrue(service.contains("startPersistent skipped start_in_flight"));
        assertTrue(service.contains("static void clearStartRequestForTest()"));
    }

    private static String source(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/").resolve(name).normalize();
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/").resolve(name).normalize();
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
