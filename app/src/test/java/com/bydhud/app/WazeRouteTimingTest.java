package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.Test;

public final class WazeRouteTimingTest {
    @After
    public void clearPendingTimings() {
        WazeRouteTiming.clearPendingForTest();
    }

    @Test
    public void finishContainsAllStagesAndCorrelationFields() {
        WazeRouteTiming timing = new WazeRouteTiming(
                "v2", "route_start", 100L, 42L, 110L, true);
        timing.markPreEnqueue(112L, 3);
        timing.markExecutorStart(118L, 4);
        timing.markTrustStart(119L);
        timing.markTrustEnd(125L);
        timing.markDeliveryStart(126L);
        timing.markDeliveryEnd(131L);

        String line = timing.finish("delivered");

        assertTrue(line.contains("channel=v2"));
        assertTrue(line.contains("eventType=route_start"));
        assertTrue(line.contains("eventElapsedMs=100"));
        assertTrue(line.contains("receiverEntryElapsedMs=110"));
        assertTrue(line.contains("preEnqueueElapsedMs=112"));
        assertTrue(line.contains("executorStartElapsedMs=118"));
        assertTrue(line.contains("trustStartElapsedMs=119"));
        assertTrue(line.contains("trustEndElapsedMs=125"));
        assertTrue(line.contains("deliveryStartElapsedMs=126"));
        assertTrue(line.contains("deliveryEndElapsedMs=131"));
        assertTrue(line.contains("queueDepthAtEnqueue=3"));
        assertTrue(line.contains("queueDepthAtExecutorStart=4"));
        assertTrue(line.contains("bridgeGeneration=42"));
        assertTrue(line.contains("producerToReceiverMs=10"));
        assertTrue(line.contains("trustDurationMs=6"));
        assertTrue(line.contains("deliveryDurationMs=5"));
        assertTrue(line.contains("outcome=delivered"));
    }

    @Test
    public void missingStagesAreExplicitAndTokensAreBounded() {
        WazeRouteTiming timing = new WazeRouteTiming(
                "v2", "route\nstart with spaces and a deliberately long suffix",
                100L, 0L, 100L, true);
        timing.markPreEnqueue(101L, 0);

        String line = timing.finish("trust timeout");

        assertTrue(line.contains("eventType=route-start-with-spaces-and-a-deliberately-long-s"));
        assertTrue(line.contains("executorStartElapsedMs=-1"));
        assertTrue(line.contains("trustDurationMs=-1"));
        assertTrue(line.contains("outcome=trust-timeout"));
    }

    @Test
    public void directLineCorrelatesBindSessionFrameAndDispatchStages() {
        WazeRouteTiming.clearPendingForTest();
        WazeRouteTiming timing = new WazeRouteTiming(
                "v2", "route_start", 1_000L, 7L, 1_010L, true);
        timing.markAcceptedRouteState(true);
        assertTrue(WazeRouteTiming.claimForDirect(1_020L) == timing);
        timing.markDirectStart(1_021L, 3, "route-lifecycle-start");
        timing.markBindRequest(1_022L);
        timing.markBindStart(1_023L);
        timing.markBindResult(1_030L, true);
        timing.markCarAppConnected(1_040L);
        timing.markSessionReady(1_050L);
        WazeRouteTiming.Frame frame = timing.beginFrame(1_060L, "routing_info:1");
        frame.markListenerHandoff(1_061L);
        frame.markListenerCallback(1_062L);
        timing.markFirstTbtDispatch(1_063L);
        timing.markFirstHudDispatch(1_064L);

        String line = timing.directLine("first_dispatch");
        assertTrue(line.contains("bindRequestElapsedMs=1022"));
        assertTrue(line.contains("bindStartElapsedMs=1023"));
        assertTrue(line.contains("bindResultElapsedMs=1030"));
        assertTrue(line.contains("bindAttemptCount=1"));
        assertTrue(line.contains("firstSuccessfulBindElapsedMs=1030"));
        assertTrue(line.contains("bindResult=true"));
        assertTrue(line.contains("carAppConnectedElapsedMs=1040"));
        assertTrue(line.contains("sessionReadyElapsedMs=1050"));
        assertTrue(line.contains("firstNavigationFrameElapsedMs=1060"));
        assertTrue(line.contains("firstListenerHandoffElapsedMs=1061"));
        assertTrue(line.contains("firstTbtDispatchElapsedMs=1063"));
        assertTrue(line.contains("firstHudDispatchElapsedMs=1064"));
    }

    @Test
    public void fastLaterFramesStayQuietButFirstDetailedAndSlowFramesLog() {
        WazeRouteTiming timing = new WazeRouteTiming(
                "v2", "route_start", 1_000L, 1L, 1_001L, true);
        WazeRouteTiming.Frame first = timing.beginFrame(1_010L, "first");
        first.markListenerHandoff(1_011L);
        first.markListenerCallback(1_012L);
        assertTrue(first.shouldLog(false, 1_013L, 1_014L));

        WazeRouteTiming.Frame fast = timing.beginFrame(2_000L, "fast");
        fast.markListenerHandoff(2_001L);
        fast.markListenerCallback(2_002L);
        assertTrue(!fast.shouldLog(false, 2_003L, 2_004L));
        assertTrue(fast.shouldLog(true, 2_003L, 2_004L));

        WazeRouteTiming.Frame slow = timing.beginFrame(3_000L, "slow");
        slow.markListenerHandoff(3_001L);
        slow.markListenerCallback(3_301L);
        assertTrue(slow.shouldLog(false, 3_302L, 3_303L));
    }

    @Test
    public void onlyRouteLifecycleEventsCanClaimDirectCorrelation() {
        WazeRouteTiming.clearPendingForTest();
        new WazeRouteTiming(
                "v2", "speed_limit", 1_100L, 1L, 1_101L, false);
        WazeRouteTiming route = new WazeRouteTiming(
                "v2", "route_start", 1_200L, 2L, 1_201L, true);
        route.markAcceptedRouteState(true);

        assertTrue(WazeRouteTiming.claimForDirect(1_210L) == route);
        assertTrue(WazeRouteTiming.claimForDirect(1_211L) == null);
        WazeRouteTiming.clearPendingForTest();
    }

    @Test
    public void newestRouteEventSupersedesOlderUnclaimedEvents() {
        WazeRouteTiming.clearPendingForTest();
        WazeRouteTiming older = new WazeRouteTiming(
                "v2", "route_start", 1_000L, 1L, 1_001L, true);
        WazeRouteTiming newest = new WazeRouteTiming(
                "v2", "route_start", 1_100L, 2L, 1_101L, true);
        older.markAcceptedRouteState(true);
        newest.markAcceptedRouteState(true);

        assertTrue(WazeRouteTiming.claimForDirect(1_110L) == newest);
        assertTrue(WazeRouteTiming.claimForDirect(1_111L) == null);
    }

    @Test
    public void onlyTrustedAcceptedLifecycleEventsEnterDirectCorrelation() {
        WazeRouteTiming rejected = new WazeRouteTiming(
                "v2", "route_start", 1_000L, 1L, 1_001L, true);
        assertTrue(WazeRouteTiming.claimForDirect(1_010L) == null);

        rejected.markAcceptedRouteState(true);
        rejected.cancelDirectCorrelation();
        assertTrue(WazeRouteTiming.claimForDirect(1_011L) == null);
    }

    @Test
    public void trustedRouteStopCannotClaimTheNextDirectStart() {
        WazeRouteTiming start = new WazeRouteTiming(
                "v2", "route_state", 900L, 1L, 901L, true);
        WazeRouteTiming stop = new WazeRouteTiming(
                "v2", "route_state", 1_000L, 1L, 1_001L, false);
        start.markAcceptedRouteState(true);
        stop.markAcceptedRouteState(false);

        assertTrue(WazeRouteTiming.claimForDirect(1_010L) == null);
    }

    @Test
    public void directHooksAndFrameLoggingRemainScoped() throws IOException {
        String channel = source("WazeDirectChannel.java");
        String sender = source("NavHudLiveSender.java");

        assertTrue(channel.contains("markBindRequest"));
        assertTrue(channel.contains("markBindStart"));
        assertTrue(channel.contains("markBindResult"));
        assertTrue(channel.contains("markCarAppConnected"));
        assertTrue(channel.contains("markSessionReady"));
        assertTrue(channel.contains("beginFrame"));
        assertTrue(sender.contains("pendingWazeDirectFrameTiming"));
        assertTrue(sender.contains("logWazeDirectTiming"));
        assertTrue(sender.contains("HudPrefs.isDetailedDebugArtifactsEnabled(context)"));
        assertTrue(sender.contains("timing.shouldLog("));
    }

    private static String source(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + name);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + name);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
