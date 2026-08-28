package com.bydhud.app;

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
