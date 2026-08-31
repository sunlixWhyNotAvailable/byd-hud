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

/** Source contracts for the Waze terminal fence and accepted fresh-route opening. */
public final class WazeDirectTerminalFenceContractTest {
    @Test
    public void bridgeSupportedHintsReturnBeforeAnyTerminalOrSessionMutation()
            throws IOException {
        String hint = body(source("WazeDirectChannel.java"),
                "private void handleNavigationEndHint(String reason)");
        // The guard is state-independent: before NEW_DEST, between NEW_DEST and
        // active, or after active, neither hint can reach the destructive fallback.
        assertEquals(compact("if (WazeRouteLifecycleStore.isBridgeSupported(context)) {"
                        + "log(\"navigation end hint deferred to lifecycle bridge reason=\""
                        + " + safeText(reason)); return; }"
                        + "latchRouteTerminal(reason); endNavigation(reason);"),
                compact(hint));
    }

    @Test
    public void bothAndroidxHintsUseTheSameGuardInClusterAndSurface() throws IOException {
        String channel = source("WazeDirectChannel.java");
        assertEquals(compact("postBinder(expectedGeneration, () ->"
                        + " handleNavigationEndHint(\"waze_navigation_ended\"));"),
                compact(body(channel, "public void navigationEnded()")));
        String template = body(channel, "private void onTemplate(");
        assertEquals(compact("if (acceptedRouteFrame) {"
                        + "handleNavigationEndHint(\"navigation_info_null\");"
                        + "} else {"
                        + "log(\"navigation info null ignored before first route frame\");}"),
                compact(body(template, "if (info == null)")));
        assertTrue(body(channel, "CarHost(int expectedGeneration)").contains(
                "navigationHost = new NavigationHost(expectedGeneration)"));
        assertTrue(template.contains("mode == Mode.MAIN_SURFACE"));
        assertFalse(template.contains("mode == Mode.CLUSTER"));
        assertEquals(3, channel.split("handleNavigationEndHint\\(", -1).length - 1);
        assertTrue(body(channel, "private void postBinder(")
                .contains("if (isCurrent(expectedGeneration)) action.run()"));
    }

    @Test
    public void explicitFinishAndStopStillBypassTheAndroidxHintGuard() throws IOException {
        String channel = source("WazeDirectChannel.java");
        assertEquals(compact("postBinder(expectedGeneration, () -> {"
                        + "latchRouteTerminal(\"car_host_finish\");"
                        + "endNavigation(\"car_host_finish\"); });"),
                compact(body(channel, "public void finish()")));
        assertTrue(body(channel, "public void stop(String reason)")
                .contains("runOnChannel(() -> suspendOnChannel(reason))"));
        assertTrue(body(channel, "private void suspendOnChannel(")
                .contains("endNavigation(\"suspended:\" + stopReason, false)"));
        assertTrue(body(channel, "private void hardStopOnChannel(")
                .contains("endNavigation(\"hard-stopped:\" + stopReason, false)"));
    }

    @Test
    public void realBridgeTerminalClearsProofAndOutputWithoutAnAndroidxEnd()
            throws IOException {
        String store = source("WazeRouteLifecycleStore.java");
        assertEquals(compact("pendingGeneration = 0L; pendingElapsedMs = 0L;"
                        + "pendingReasonCode = REASON_UNAVAILABLE;"),
                compact(body(store, "if (terminal || freshRouteAccepted)")));
        String sender = source("NavHudLiveSender.java");
        String terminal = body(body(sender,
                "private void onWazeRouteLifecycleEventOnMain("), "if (result.terminal)");
        assertTrue(terminal.contains("wazeDirectRouteTerminalFence = true"));
        assertTrue(terminal.contains("wazeDirectChannel.noteRouteTerminalGeneration("));
        assertTrue(terminal.contains("wazeSurfaceDirectChannel.noteRouteTerminalGeneration("));
        assertTrue(terminal.contains("invalidatePendingWazeDirectFrames()"));
        assertTrue(terminal.contains("onWazeDirectNavigationEnded("));
        String end = body(sender, "private void onWazeDirectNavigationEnded(");
        assertTrue(end.contains("hudOutput.endNavigationOutput("));
        assertTrue(end.contains("handoffOrEndDirectRoute("));
        assertTrue(end.contains("wazeDirectChannel.stop("));
    }

    @Test
    public void channelCallbacksCannotRearmTerminalWithoutStoreDecision() throws IOException {
        String source = source("WazeDirectChannel.java");
        assertTrue(source.contains("void openAcceptedFreshRoute("));
        assertTrue(source.contains("void noteRouteTerminalGeneration("));
        assertTrue(source.contains("navigation start rejected by terminal latch"));
        assertFalse(source.contains("shouldAcceptFreshRouteProofForTest"));
        assertFalse(source.contains("rearmRouteTerminal(\"channel_start\")"));
        assertFalse(source.contains("rearmRouteTerminal(\"car_app_session_connected\")"));
        assertFalse(source.contains("rearmRouteTerminal(\"waze_navigation_started\")"));
    }

    @Test
    public void navHudWiresTerminalFenceAcrossQueuedCallbacksAndFreshLifecycle() throws IOException {
        String source = source("NavHudLiveSender.java");
        assertTrue(source.contains("wazeDirectRouteTerminalFence = true"));
        assertTrue(source.contains("wazeDirectChannel.noteRouteTerminalGeneration"));
        assertTrue(source.contains("wazeDirectChannel.openAcceptedFreshRoute"));
        assertTrue(source.contains("wazeSurfaceDirectChannel.openAcceptedFreshRoute"));
        assertTrue(source.contains("result.freshRouteAccepted"));
        assertTrue(source.contains("result.supersedingInactive"));
        assertFalse(source.contains("isExplicitFreshWazeLifecycleReasonForTest"));
        assertFalse(source.contains("wazeDirectLifecycleBridgeGeneration"));
        assertTrue(source.contains("legacy-session-start:"));
        assertTrue(source.contains("shouldAcceptWazeFrameAfterTerminalForTest"));
        assertTrue(source.contains("surface_navigation_started"));
        assertTrue(source.contains("surface_frame"));
        assertTrue(source.contains("surface_alert_cleared"));
        assertTrue(source.contains("surface_liveness"));
        assertTrue(source.contains("surface_ready"));
        assertTrue(source.contains("wazeDirectRouteTerminalFence) return;"));
        assertTrue(source.contains("wazeDirectRouteTerminalFence || !shouldUseWazeSurface()"));
        assertTrue(source.contains("waze inactive newer-generation snapshot closed owner"));
        int host = source.indexOf("private void startWazeDirectHost(");
        int hostEnd = source.indexOf("private void waitForWazeRouteLifecycle(", host);
        String hostBlock = source.substring(host, hostEnd);
        assertTrue(host >= 0 && hostEnd > host);
        assertTrue(hostBlock.contains("boolean legacySessionProof"));
        assertTrue(hostBlock.contains("wazeLegacyDirectRearmPending = true"));
        assertTrue(hostBlock.contains("wazeLegacyDirectSessionFloor"));
        assertTrue(hostBlock.contains("wazeLegacySurfaceRearmPending = true"));
        assertTrue(hostBlock.contains("wazeLegacySurfaceSessionFloor"));
        assertFalse(hostBlock.contains("wazeDirectRouteTerminalFence = false"));
        assertTrue(hostBlock.indexOf("wazeLegacyDirectSessionFloor")
                < hostBlock.indexOf("openAcceptedFreshRoute"));
        assertTrue(source.contains("openLegacyRearmIfFreshSession("));
        assertTrue(source.contains("surface_navigation_started"));
        assertTrue(source.contains("surface_frame"));
        assertTrue(source.contains("surface_alert_cleared"));
        assertTrue(source.contains("surface_liveness"));
        assertTrue(source.contains("surface_ready"));
    }

    private static String body(String source, String marker) {
        int markerIndex = source.indexOf(marker);
        assertTrue("missing source boundary: " + marker, markerIndex >= 0);
        int start = source.indexOf('{', markerIndex);
        assertTrue("missing body: " + marker, start >= 0);
        int depth = 1;
        for (int index = start + 1; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) return source.substring(start + 1, index);
        }
        throw new AssertionError("unterminated body: " + marker);
    }

    private static String compact(String source) {
        return source.replaceAll("//[^\\n]*", "").replaceAll("\\s+", "");
    }

    private static String source(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + fileName);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
    }
}
