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

public final class VehicleTbtPublisherContractTest {
    @Test
    public void publisherKeepsVerifiedDirectSdkAndAmapPlanes() throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/VehicleTbtPublisher.java");

        assertTrue(source.contains("FID_NAV_STATUS = 1_138_753_594"));
        assertTrue(source.contains("FID_SIMPLE_ICON = 1_139_806_224"));
        assertTrue(source.contains("FID_DISTANCE = 1_139_806_232"));
        assertTrue(source.contains("FID_ROAD = 1_140_461_576"));
        assertTrue(source.contains("setInt(FID_NAV_STATUS, status, trace)"));
        assertTrue(source.contains("sendAutoNaviStatus"));
        assertTrue(source.contains("sendSimpleGuidanceInfo"));
        assertTrue(source.contains("sendNextPathName"));
        assertFalse(source.contains("sendLaneGuidanceInfo"));
        assertTrue(source.contains("AUTONAVI_STANDARD_BROADCAST_SEND"));
        assertTrue(source.contains("KEY_TYPE\", 10001"));
        assertTrue(source.contains("KEY_TYPE\", 10019"));
        assertTrue(source.contains("NEW_ICON"));
        assertTrue(source.contains("frame.getAmapBroadcastManeuver()"));
        assertTrue(source.contains("frame.getRoundaboutExitNumber()"));
        assertTrue(source.contains("NEXT_ROAD_NAME"));
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
