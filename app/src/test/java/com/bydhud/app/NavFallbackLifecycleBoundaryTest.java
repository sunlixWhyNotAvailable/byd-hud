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
    public void fallbackPublisherRequiresExplicitOwnerAndFallbackMode() {
        assertTrue(NavHudLiveSender.shouldPublishLegacyFallbackForTest(
                "com.waze", "com.waze", "com.waze",
                true, false, true, false, false));
        assertFalse(NavHudLiveSender.shouldPublishLegacyFallbackForTest(
                "com.waze", "com.waze", "com.waze",
                true, true, false, false, false));
        assertTrue(NavHudLiveSender.shouldPublishLegacyFallbackForTest(
                "com.example.maps", "com.example.maps", "com.example.maps",
                true, false, false, false, false));
        assertFalse(NavHudLiveSender.shouldPublishLegacyFallbackForTest(
                "com.waze", "app.revanced.android.apps.maps", "com.waze",
                true, false, true, false, false));
    }

    @Test
    public void frameworkIngressDoesNotOwnDirectLifecycle() throws IOException {
        String source = source("NavHudLiveSender.java");
        assertIngressBodyHasNoDirectLifecycle(source, "updateOnMain");
        assertIngressBodyHasNoDirectLifecycle(source, "updateAccessibilityOnMain");
        assertIngressBodyHasNoDirectLifecycle(source, "updateVisualCueOnMain");
        assertTrue(source.contains("prepareLegacyFallbackIngress(packageName, \"notification\")"));
        assertTrue(source.contains("prepareLegacyFallbackIngress(packageName, \"accessibility\")"));
        assertTrue(source.contains("prepareLegacyFallbackIngress(packageName, \"visual\")"));
        assertTrue(source.contains("stopLegacyFallbackOnMain"));
        assertFalse(source.contains("onWazeLegacyRouteEvidence"));
        assertFalse(source.contains("forceClearNavigator"));
    }

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

    private static void assertIngressBodyHasNoDirectLifecycle(String source, String method) {
        int start = source.indexOf("private void " + method + "(");
        if (start < 0) start = source.indexOf("void " + method + "(");
        int next = source.indexOf("\n    //", start + 1);
        String body = source.substring(start, next < 0 ? source.length() : next);
        assertFalse(method + " starts Direct", body.contains("startOnMain("));
        assertFalse(method + " switches Direct", body.contains("beginSourceSwitch("));
        assertFalse(method + " stops Direct", body.contains("stopOnMain("));
        assertFalse(method + " rearms Direct", body.contains("ensureGMapsRegisteredWhenTransportReady"));
        assertFalse(method + " rearms Waze Direct", body.contains("scheduleWazeDirectColdTimeout"));
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
