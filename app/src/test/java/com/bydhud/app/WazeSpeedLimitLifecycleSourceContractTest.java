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

public final class WazeSpeedLimitLifecycleSourceContractTest {
    @Test
    public void heartbeatCapabilityAloneArmsSpeedLimitExpiry() {
        assertFalse(NavHudLiveSender.shouldArmWazeSpeedLimitExpiry(50, 0));
        assertFalse(NavHudLiveSender.shouldArmWazeSpeedLimitExpiry(
                0, NavHudLiveSender.WAZE_CAP_SPEED_LIMIT_HEARTBEAT));
        assertTrue(NavHudLiveSender.shouldArmWazeSpeedLimitExpiry(
                50, NavHudLiveSender.WAZE_CAP_SPEED_LIMIT_HEARTBEAT));
        assertTrue(NavHudLiveSender.shouldRetainWazeSpeedWithoutSender(0));
        assertFalse(NavHudLiveSender.shouldRetainWazeSpeedWithoutSender(
                NavHudLiveSender.WAZE_CAP_SPEED_LIMIT_HEARTBEAT));
    }

    @Test
    public void bridgeGenerationOrHeartbeatCapabilityTransitionClearsPriorSpeed() {
        int heartbeat = NavHudLiveSender.WAZE_CAP_SPEED_LIMIT_HEARTBEAT;

        assertFalse(NavHudLiveSender.shouldClearWazeSpeedForBridgeTransition(
                false, 0L, 0, 0L, 0));
        assertTrue(NavHudLiveSender.shouldClearWazeSpeedForBridgeTransition(
                false, 0L, 0, 11L, heartbeat));
        assertTrue(NavHudLiveSender.shouldClearWazeSpeedForBridgeTransition(
                true, 11L, heartbeat, 12L, heartbeat));
        assertTrue(NavHudLiveSender.shouldClearWazeSpeedForBridgeTransition(
                true, 0L, 0, 0L, heartbeat));
        assertFalse(NavHudLiveSender.shouldClearWazeSpeedForBridgeTransition(
                true, 12L, heartbeat, 12L, heartbeat));
    }

    @Test
    public void capableSpeedCannotBecomeUntimedBeforeSenderExists() {
        DirectSpeedLimitStore.clear("com.waze");

        NavHudLiveSender.onWazeSpeedLimitEvent(
                null, 50, "km/h", 10L, 11L,
                NavHudLiveSender.WAZE_CAP_SPEED_LIMIT_HEARTBEAT);
        assertFalse(DirectSpeedLimitStore.snapshot("com.waze").isActive());

        NavHudLiveSender.onWazeSpeedLimitEvent(
                null, 30, "mph", 20L, 0L, 0);
        assertTrue(DirectSpeedLimitStore.snapshot("com.waze").isActive());
        DirectSpeedLimitStore.clear("com.waze");
    }

    @Test
    public void navigationStartPreservesSpeedLimitUntilTerminalLifecycle() throws IOException {
        String source = source();
        String navigationStarted = between(source,
                "private void onWazeDirectNavigationStarted(",
                "void onWazeSurfaceActivityCreated(");
        String navigationEnded = between(source,
                "private void onWazeDirectNavigationEnded(",
                "private void onWazeRouteLifecycleEventOnMain(");
        String lifecycleTerminal = between(source,
                "if (result.terminal) {",
                "if (!routeActive) {");

        assertFalse(navigationStarted.contains("clearDirectSpeedLimit("));
        assertTrue(lifecycleTerminal.contains("onWazeDirectNavigationEnded("));
        assertTrue(navigationEnded.contains("clearDirectSpeedLimit(WAZE_PACKAGE)"));
    }

    @Test
    public void capableBridgePublishesOneRouteScopedHeartbeatRunnable() throws IOException {
        String source = bridgeSource();
        String speedEmitter = between(source,
                "public static synchronized void emitSpeedLimit(",
                "private static void sendSpeedLimit(");
        String sameValue = between(speedEmitter,
                "if (lastSpeedLimit == safeLimit",
                "lastSpeedLimit = safeLimit");

        assertTrue(source.contains("public static final int PROTOCOL_VERSION = 2"));
        assertTrue(source.contains(
                "public static final String EXTRA_BRIDGE_CAPABILITIES = \"bridge_capabilities\""));
        assertTrue(source.contains("public static final int CAP_SPEED_LIMIT_HEARTBEAT = 1"));
        assertTrue(source.contains("implements Application.ActivityLifecycleCallbacks, Runnable"));
        assertTrue(source.contains("SPEED_LIMIT_HEARTBEAT_MS = 20_000L"));
        assertTrue(source.contains("postDelayed(ACTIVITY_CALLBACKS, SPEED_LIMIT_HEARTBEAT_MS)"));
        assertFalse(source.contains("new Runnable"));
        assertFalse(sameValue.contains("scheduleSpeedLimitHeartbeat"));
        assertFalse(sameValue.contains("postDelayed"));
        assertEquals(3, occurrences(source,
                ".putExtra(EXTRA_BRIDGE_CAPABILITIES, CAP_SPEED_LIMIT_HEARTBEAT)"));
    }

    @Test
    public void heartbeatExpiryClearsAndRepublishesWhileLegacyEventsRemainUntimed()
            throws IOException {
        String source = source();
        String speedEntry = between(source,
                "static void onWazeSpeedLimitEvent(\n            Context context, int displayValue",
                "static void onWazeAppForegroundEvent(");
        String expiry = between(source,
                "private void onWazeSpeedLimitExpiry()",
                "private DirectTbtFrame applySpeedLimitOverlay(");

        assertTrue(source.contains(
                "onWazeSpeedLimitEvent(context, displayValue, unit, eventElapsedMs, 0L, 0)"));
        assertTrue(source.contains("WAZE_SPEED_LIMIT_EXPIRY_MS = 60_000L"));
        assertTrue(speedEntry.contains(
                "shouldRetainWazeSpeedWithoutSender(bridgeCapabilities)"));
        assertTrue(speedEntry.contains("DirectSpeedLimitStore.clear(WAZE_PACKAGE)"));
        assertTrue(expiry.contains("DirectSpeedLimitStore.clear(WAZE_PACKAGE)"));
        assertTrue(expiry.contains("republishLatestDirectFrame("));
        assertTrue(source.contains(
                "if (WAZE_PACKAGE.equals(ownerPackage)) cancelWazeSpeedLimitExpiry()"));
    }

    private static String source() throws IOException {
        return source("app/src/main/java/com/bydhud/app/NavHudLiveSender.java",
                "src/main/java/com/bydhud/app/NavHudLiveSender.java");
    }

    private static String bridgeSource() throws IOException {
        return source("app/src/main/java/com/waze/bydhud/RouteStateBridgeV2.java",
                "src/main/java/com/waze/bydhud/RouteStateBridgeV2.java");
    }

    private static String source(String projectPath, String modulePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(projectPath);
        if (!Files.isRegularFile(file)) {
            file = root.resolve(modulePath);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }
}
