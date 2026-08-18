package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

public final class VehicleTbtPublisherContractTest {
    @Test
    public void publisherKeepsVerifiedProxyAndAmapPlanes() throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/VehicleTbtPublisher.java");
        String proxy = source(
                "app/src/main/java/com/bydhud/app/InstrumentNavigationProxyService.java");

        assertTrue(proxy.contains("FID_NAV_STATUS = 1_138_753_594"));
        assertTrue(proxy.contains("FID_SIMPLE_ICON = 1_139_806_224"));
        assertTrue(proxy.contains("FID_DISTANCE = 1_139_806_232"));
        assertTrue(proxy.contains("FID_ROAD = 1_140_461_576"));
        assertTrue(source.contains("instrument.sendNavigationStatus"));
        assertTrue(source.contains("instrument.sendGuidance"));
        assertTrue(source.contains("instrument.sendTerminalGuidanceClear"));
        assertTrue(proxy.contains("sendAutoNaviStatus"));
        assertTrue(proxy.contains("sendSimpleGuidanceInfo"));
        assertTrue(proxy.contains("sendNextPathName"));
        assertTrue(proxy.contains("sendLaneGuidanceInfo"));
        assertTrue(proxy.contains("CAP_INSTRUMENT_LANES"));
        assertFalse(source.contains("InstrumentApi.open"));
        assertTrue(source.contains("AUTONAVI_STANDARD_BROADCAST_SEND"));
        assertTrue(source.contains("KEY_TYPE\", 10001"));
        assertTrue(source.contains("KEY_TYPE\", 10019"));
        assertTrue(source.contains("NEW_ICON"));
        assertTrue(source.contains("frame.getAmapBroadcastManeuver()"));
        assertTrue(source.contains("frame.getAmapManeuver()"));
        assertTrue(source.contains("intermediateAmapIcon"));
        assertTrue(source.contains("frame.getRoundaboutExitNumber()"));
        assertTrue(source.contains("NEXT_ROAD_NAME"));
        assertTrue(source.contains("addUnavailableListener"));
        assertTrue(source.contains("tbt_proxy_unavailable preserve_amap_fallback"));
        assertTrue(source.contains("tbt_proxy_unavailable fallback_terminal"));
    }

    @Test
    public void normalizedAndManualLanesUseTheVerifiedBydDirectionPair() {
        VehicleTbtPublisher.LanePayload direct =
                VehicleTbtPublisher.lanePayloadForTest(Arrays.asList(
                        new DirectTbtFrame.Lane(2, false, "left"),
                        new DirectTbtFrame.Lane(9, true, "straight"),
                        new DirectTbtFrame.Lane(3, false, "right")));
        assertArrayEquals(new int[]{4, 0, 3}, direct.directions);
        assertArrayEquals(new int[]{255, 0, 255}, direct.recommendations);

        HudState manual = new HudState();
        manual.includeLaneBitmap = true;
        manual.numOfLanes = 3;
        manual.laneString = "L|S*|R";
        VehicleTbtPublisher.LanePayload manualPayload =
                VehicleTbtPublisher.lanePayloadForTest(manual);
        assertArrayEquals(new int[]{1, 0, 3}, manualPayload.directions);
        assertArrayEquals(new int[]{255, 0, 255}, manualPayload.recommendations);
    }

    @Test
    public void failedLanePlaneInvalidatesGuidanceDedupWithoutFailingCoreGuidance() {
        assertTrue(VehicleTbtPublisher.laneOperationsSucceededForTest(
                Collections.singletonList(new InstrumentProxyContract.Operation(
                        "instrument_fid:1139806224", 0, 0L, ""))));
        assertTrue(VehicleTbtPublisher.laneOperationsSucceededForTest(Arrays.asList(
                new InstrumentProxyContract.Operation(
                        "setting_fid:1285554200", 0, 0L, ""),
                new InstrumentProxyContract.Operation(
                        "instrument_lane:sendLaneGuidanceInfo", 0, 0L, ""))));
        assertFalse(VehicleTbtPublisher.laneOperationsSucceededForTest(Arrays.asList(
                new InstrumentProxyContract.Operation(
                        "setting_fid:1285554200", -1, 0L, "failed"),
                new InstrumentProxyContract.Operation(
                        "instrument_lane:sendLaneGuidanceInfo", 0, 0L, ""))));
    }

    @Test
    public void instrumentFailurePreservesOnlyAnActiveExactAmapFallback() {
        assertTrue(VehicleTbtPublisher.shouldPreserveAmapFallbackForTest(true, true));
        assertFalse(VehicleTbtPublisher.shouldPreserveAmapFallbackForTest(true, false));
        assertFalse(VehicleTbtPublisher.shouldPreserveAmapFallbackForTest(false, true));
    }

    @Test
    public void activeRoadUsesCueFallbackAndOneSpaceOnlyWhenBothTextsAreEmpty() {
        assertEquals("Road", VehicleTbtPublisher.roadTextForTest(
                DirectTbtFrame.empty().withNavigationText("Road", "Cue")));
        assertEquals("Cue", VehicleTbtPublisher.roadTextForTest(
                DirectTbtFrame.empty().withNavigationText("", "Cue")));
        assertEquals(" ", VehicleTbtPublisher.roadTextForTest(
                DirectTbtFrame.empty().withNavigationText("", "")));
        assertEquals("  ", VehicleTbtPublisher.roadTextForTest(
                DirectTbtFrame.empty().withNavigationText("  ", "Cue")));
        assertEquals("  ", VehicleTbtPublisher.roadTextForTest(
                DirectTbtFrame.empty().withNavigationText("", "  ")));
        assertEquals("", VehicleTbtPublisher.roadTextForTest(null));
    }

    @Test
    public void activeRoadPayloadAndTraceKeepExactWhitespace() throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/VehicleTbtPublisher.java");

        assertTrue(source.contains("String normalizedRoad = preserveText(road);"));
        assertTrue(source.contains("String nextRoad = preserveText(road);"));
        assertFalse(source.contains("activeRoadText("));
        assertTrue(source.contains("preserveText(road).getBytes(StandardCharsets.UTF_16LE)"));
        assertTrue(source.contains("preserveText(road).getBytes(StandardCharsets.UTF_8)"));
        assertTrue(source.contains("distance, preserveText(road), route, next"));
    }

    @Test
    public void status4RequiresCompletedRouteTeardownAndDoesNotRepeat() {
        assertTrue(VehicleTbtPublisher.STATUS_TEARDOWN == 4);
        assertTrue(NavAppTaskScanner.isTeardownPositiveForTest(true, false, false));
        assertFalse(NavAppTaskScanner.isTeardownPositiveForTest(true, false, true));
    }

    @Test
    public void hudOwnerWinsOverTbtOnlyOwnerButTbtOnlyRoutesMayReplaceEachOther() {
        assertTrue(VehicleTbtPublisher.shouldReplaceOwnerForTest(false, false));
        assertTrue(VehicleTbtPublisher.shouldReplaceOwnerForTest(false, true));
        assertTrue(!VehicleTbtPublisher.shouldReplaceOwnerForTest(true, false));
        assertTrue(VehicleTbtPublisher.shouldReplaceOwnerForTest(true, true));
    }

    @Test
    public void sameOwnerNewGenerationClearsOldGuidanceBeforeItsFirstFrame() {
        assertTrue(VehicleTbtPublisher.shouldClearGuidanceForGenerationReplacementForTest(
                true, GMapsDirectChannel.OWNER_PACKAGE, 7L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
        assertFalse(VehicleTbtPublisher.shouldClearGuidanceForGenerationReplacementForTest(
                true, GMapsDirectChannel.OWNER_PACKAGE, 7L,
                GMapsDirectChannel.OWNER_PACKAGE, 7L));
        assertFalse(VehicleTbtPublisher.shouldClearGuidanceForGenerationReplacementForTest(
                true, GMapsDirectChannel.OWNER_PACKAGE, 8L,
                GMapsDirectChannel.OWNER_PACKAGE, 7L));
        assertFalse(VehicleTbtPublisher.shouldClearGuidanceForGenerationReplacementForTest(
                false, GMapsDirectChannel.OWNER_PACKAGE, 7L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
        assertFalse(VehicleTbtPublisher.shouldClearGuidanceForGenerationReplacementForTest(
                true, "com.waze", 7L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
    }

    @Test
    public void sameOwnerLowerGenerationIsIgnoredBeforePriorityMutation() {
        assertTrue(VehicleTbtPublisher.shouldIgnoreOwnerGenerationForTest(
                true, GMapsDirectChannel.OWNER_PACKAGE, 8L,
                GMapsDirectChannel.OWNER_PACKAGE, 7L));
        assertFalse(VehicleTbtPublisher.shouldIgnoreOwnerGenerationForTest(
                true, GMapsDirectChannel.OWNER_PACKAGE, 8L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
        assertFalse(VehicleTbtPublisher.shouldIgnoreOwnerGenerationForTest(
                true, GMapsDirectChannel.OWNER_PACKAGE, 8L,
                GMapsDirectChannel.OWNER_PACKAGE, 9L));
        assertFalse(VehicleTbtPublisher.shouldIgnoreOwnerGenerationForTest(
                false, GMapsDirectChannel.OWNER_PACKAGE, 8L,
                GMapsDirectChannel.OWNER_PACKAGE, 7L));
    }

    @Test
    public void sameOwnerNewGenerationReassertsActiveStatusAfterClearBeforePriorityHandling()
            throws IOException {
        String source = source(
                "app/src/main/java/com/bydhud/app/VehicleTbtPublisher.java");
        int branchStart = source.indexOf("if (generationChanged) {");
        int branchEnd = source.indexOf(
                "            ownerHasHudPriority = hasHudPriority;", branchStart);
        assertTrue(branchStart >= 0 && branchEnd > branchStart);
        String branch = source.substring(branchStart, branchEnd);

        int instrumentClear = branch.indexOf("sendTerminalGuidanceClear(replaced)");
        int amapClear = branch.indexOf("sendAmapTerminal(replaced)");
        int ownerAdvance = branch.indexOf("ownerGeneration = generation;");
        int tokenAdvance = branch.indexOf("++routeToken;");
        int status = branch.indexOf("sendStatus(STATUS_ACTIVE, started)");
        int lifecycle = branch.indexOf(
                "record(started, \"lifecycle\", \"begin\", \"navigation\"");

        assertTrue(instrumentClear >= 0);
        assertTrue(amapClear > instrumentClear);
        assertTrue(ownerAdvance > amapClear);
        assertTrue(tokenAdvance > ownerAdvance);
        assertTrue(status > tokenAdvance);
        assertTrue(lifecycle > status);
    }

    @Test
    public void duplicateGenerationDoesNotEnterReplacementStatusPath() throws IOException {
        String source = source(
                "app/src/main/java/com/bydhud/app/VehicleTbtPublisher.java");
        int branchStart = source.indexOf("if (generationChanged) {");
        int branchEnd = source.indexOf(
                "            ownerHasHudPriority = hasHudPriority;", branchStart);
        assertTrue(branchStart >= 0 && branchEnd > branchStart);
        String branch = source.substring(branchStart, branchEnd);

        assertTrue(branch.contains("sendStatus(STATUS_ACTIVE, started)"));
        assertFalse(VehicleTbtPublisher.shouldClearGuidanceForGenerationReplacementForTest(
                true, GMapsDirectChannel.OWNER_PACKAGE, 8L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
    }

    @Test
    public void verifiedAmapMappingFeedsInstrumentNamespaceWithoutInventingExitNumbers() {
        int[] expected = {
                0, 0, 1, 2, 3, 5, 7, 8, 9, 11, 45, 13, 24, 46, 47, 48, 49,
                14, 23, 10, 12, 15, 18, 20, 22, 16, 17, 19, 21
        };
        for (int amap = 0; amap <= 28; amap++) {
            assertEquals("AMap NEW_ICON " + amap,
                    expected[amap], VehicleTbtPublisher.instrumentManeuverForAmap(amap));
        }
        assertEquals(0, VehicleTbtPublisher.instrumentManeuverForAmap(-1));
    }

    @Test
    public void finalAmapRoundaboutIconAndExitResolveExhaustively() {
        for (int exit = 1; exit <= 10; exit++) {
            assertEquals(24 + exit,
                    VehicleTbtPublisher.instrumentManeuverForAmap(11, exit));
            assertEquals(34 + exit,
                    VehicleTbtPublisher.instrumentManeuverForAmap(17, exit));
        }
        assertEquals(13, VehicleTbtPublisher.instrumentManeuverForAmap(11, 0));
        assertEquals(14, VehicleTbtPublisher.instrumentManeuverForAmap(17, 0));
        assertEquals(13, VehicleTbtPublisher.instrumentManeuverForAmap(11, 11));
        assertEquals(14, VehicleTbtPublisher.instrumentManeuverForAmap(17, -1));
    }

    @Test
    public void staleDashboardDispatchCannotCrossRouteGeneration() {
        assertTrue(VehicleTbtPublisher.shouldDispatchDashboardForTest(
                true, "com.waze", 4L, "com.waze", 4L, 7L, 7L));
        assertFalse(VehicleTbtPublisher.shouldDispatchDashboardForTest(
                false, "com.waze", 4L, "com.waze", 4L, 7L, 7L));
        assertFalse(VehicleTbtPublisher.shouldDispatchDashboardForTest(
                true, "com.waze", 3L, "com.waze", 4L, 7L, 7L));
        assertFalse(VehicleTbtPublisher.shouldDispatchDashboardForTest(
                true, "com.waze", 4L, "com.waze", 4L, 7L, 8L));
    }

    @Test
    public void amapRouteMetricsFallBackPerFieldToNextStop() {
        DirectTbtFrame.TravelMetrics next = new DirectTbtFrame.TravelMetrics(
                1_000_000L, 7_200, 600L, 4_000L);
        DirectTbtFrame.TravelMetrics whole = new DirectTbtFrame.TravelMetrics(
                -1L, DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                1_200L, -1L);
        DirectTbtFrame frame = DirectTbtFrame.empty().withTripMetrics(
                new DirectTbtFrame.TripMetrics(next, whole));

        DirectTbtFrame.TravelMetrics selected =
                VehicleTbtPublisher.selectMetricsForTest(frame);
        assertEquals(1_000_000L, selected.getArrivalTimeEpochMs());
        assertEquals(7_200, selected.getArrivalZoneOffsetSeconds());
        assertEquals(1_200L, selected.getRemainingTimeSeconds());
        assertEquals(4_000L, selected.getRemainingDistanceMeters());
    }

    @Test
    public void wazeRoundaboutsUseVerifiedAmapEnterAndExitContracts() {
        assertEquals(17, WazeDirectChannel.mapWazeToAmapBroadcastForTest(
                androidx.car.app.navigation.model.Maneuver.TYPE_ROUNDABOUT_ENTER_CW));
        assertEquals(18, WazeDirectChannel.mapWazeToAmapBroadcastForTest(
                androidx.car.app.navigation.model.Maneuver.TYPE_ROUNDABOUT_EXIT_CW));
        assertEquals(11, WazeDirectChannel.mapWazeToAmapBroadcastForTest(
                androidx.car.app.navigation.model.Maneuver.TYPE_ROUNDABOUT_ENTER_CCW));
        assertEquals(12, WazeDirectChannel.mapWazeToAmapBroadcastForTest(
                androidx.car.app.navigation.model.Maneuver.TYPE_ROUNDABOUT_EXIT_CCW));
    }

    @Test
    public void manualStraightDashedComboKeepsItsVerifiedDistinctTbtSymbol() {
        VehicleTbtPublisher.ManualMapping mapping =
                VehicleTbtPublisher.manualMappingForTest(20, 11);

        assertEquals(12, mapping.instrumentId);
        assertEquals(20, mapping.amapManeuver);
        assertTrue(mapping.amapSupported);
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
