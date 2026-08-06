package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicInteger;

public final class DirectTbtPayloadTest {
    @Test
    public void outputOptionsSnapshotRetriesWhenRevisionChangesDuringRead() {
        AtomicInteger revision = new AtomicInteger();
        AtomicInteger value = new AtomicInteger(10);
        AtomicInteger reads = new AtomicInteger();

        DirectTbtPayload.RevisionedSnapshot<Integer> snapshot =
                DirectTbtPayload.readStableSnapshot(revision::get, () -> {
                    int read = value.get();
                    if (reads.getAndIncrement() == 0) {
                        value.set(20);
                        revision.incrementAndGet();
                    }
                    return read;
                });

        assertEquals(1, snapshot.revision);
        assertEquals(20, snapshot.values.intValue());
        assertEquals(2, reads.get());
    }

    @Test
    public void activeAlertUsesBlankNativeAndKeepsLanes() {
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(11, 9, DirectTbtFrame.AlertOverlay.active(
                        7, 25, "Camera", new byte[]{8, 9})),
                DirectTbtPayload.Options.ALL);

        assertEquals(99, prepared.nativeManeuver());
        assertEquals(1, prepared.laneCount());
        assertEquals(3, prepared.lanePngBytes());
        assertEquals(2, prepared.maneuverPngBytes());
        assertEquals("alert", prepared.maneuverMode());
    }

    @Test
    public void activeAlertWithoutRawManeuverUsesBlankNative() {
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(-1, 9, DirectTbtFrame.AlertOverlay.active(
                        7, 25, "Camera", new byte[]{8, 9})),
                DirectTbtPayload.Options.ALL);

        assertEquals(99, prepared.nativeManeuver());
    }

    @Test
    public void activeAlertUsesRouteNativeWhenPolicyAllowsIt() {
        DirectTbtFrame.AlertOverlay alert = DirectTbtFrame.AlertOverlay.active(
                7, 25, "Camera", new byte[]{8, 9}).withRouteNative(true);

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(11, 9, 80, alert), DirectTbtPayload.Options.ALL);

        assertEquals(9, prepared.nativeManeuver());
        assertEquals(2, prepared.maneuverPngBytes());
        assertEquals(1, prepared.laneCount());
    }

    @Test
    public void alertNativePolicyCoversOrderingNearnessAndUnknowns() {
        DirectTbtFrame.AlertOverlay alert250 = DirectTbtFrame.AlertOverlay.active(
                7, 250, "Camera", new byte[]{8, 9});
        DirectTbtFrame.AlertOverlay alert25 = DirectTbtFrame.AlertOverlay.active(
                7, 25, "Camera", new byte[]{8, 9});
        DirectTbtFrame.AlertOverlay unknownAlertDistance = DirectTbtFrame.AlertOverlay.active(
                7, 0, "Camera", new byte[]{8, 9});

        assertTrue(WazeDirectChannel.shouldUseRouteNativeDuringAlert(
                frame(11, 9, 200, alert250), true, alert250));
        assertTrue(WazeDirectChannel.shouldUseRouteNativeDuringAlert(
                frame(11, 9, 100, alert25), true, alert25));
        assertTrue(WazeDirectChannel.shouldUseRouteNativeDuringAlert(
                frame(11, 9, 0, alert25), true, alert25));
        assertFalse(WazeDirectChannel.shouldUseRouteNativeDuringAlert(
                frame(11, 9, 101, alert25), true, alert25));
        assertFalse(WazeDirectChannel.shouldUseRouteNativeDuringAlert(
                frame(11, 9, 0, alert25), false, alert25));
        assertFalse(WazeDirectChannel.shouldUseRouteNativeDuringAlert(
                frame(11, 9, 101, unknownAlertDistance), true, unknownAlertDistance));
        assertFalse(WazeDirectChannel.shouldUseRouteNativeDuringAlert(
                frame(-1, 9, 50, alert25), true, alert25));
        assertFalse(WazeDirectChannel.shouldUseRouteNativeDuringAlert(
                frame(11, 99, 50, alert25), true, alert25));
    }

    @Test
    public void inactiveAlertUsesRouteManeuver() {
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(11, 9, DirectTbtFrame.AlertOverlay.inactive()),
                DirectTbtPayload.Options.ALL);

        assertEquals(9, prepared.nativeManeuver());
        assertEquals(3, prepared.maneuverPngBytes());
        assertEquals("current", prepared.maneuverMode());
    }

    @Test
    public void laneOnlyFrameUsesBlankPngAndNative99() {
        DirectTbtFrame frame = new DirectTbtFrame(
                -1, 0, 14, 120, "Road", "Continue", "Road",
                new byte[0], new byte[]{4, 5, 6},
                Collections.singletonList(new DirectTbtFrame.Lane(2, true, "R")),
                DirectTbtFrame.AlertOverlay.inactive());
        DirectTbtPayload.Options options = new DirectTbtPayload.Options(
                true, true, true, true, true, true, false, new byte[]{7, 2});

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(frame, options);

        assertEquals(99, prepared.nativeManeuver());
        assertEquals(2, prepared.maneuverPngBytes());
        assertEquals("blank_s72", prepared.maneuverMode());
    }

    @Test
    public void enabledSmallDistanceClampAppliesToDirectPayload() {
        DirectTbtFrame frame = new DirectTbtFrame(
                11, 3, 9, 10, "Road", "Turn right", "Road",
                new byte[]{1}, new byte[0], Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive());
        DirectTbtPayload.Options options = new DirectTbtPayload.Options(
                true, true, true, true, true, true, true);

        assertEquals(11, DirectTbtPayload.prepare(frame, options).distanceMeters());
    }

    @Test
    public void selectedTripMetricsPrefixUsesStableOrderAndFormatting() {
        long nextArrival = localTime(12, 20);
        long wholeArrival = localTime(13, 40);
        DirectTbtFrame frame = frameWithMetrics(
                "Road",
                new DirectTbtFrame.TripMetrics(
                        new DirectTbtFrame.TravelMetrics(nextArrival, 601, 5100),
                        new DirectTbtFrame.TravelMetrics(wholeArrival, 1069, 10310)));

        DirectTbtPayload.Prepared nextStop = DirectTbtPayload.prepare(
                frame, metricOptions(false, true, true, true, true, true));
        DirectTbtPayload.Prepared wholeRoute = DirectTbtPayload.prepare(
                frame, metricOptions(true, true, true, true, true, true));

        assertEquals("[ETA: 12:20 | 11 min | 5.1 km] Road", nextStop.displayText());
        assertEquals("[ETA: 13:40 | 18 min | 10.3 km] Road", wholeRoute.displayText());
    }

    @Test
    public void tripMetricsCanRenderWithoutStreetAndFallBackToNextStop() {
        DirectTbtFrame frame = frameWithMetrics(
                "",
                DirectTbtFrame.TripMetrics.nextStopOnly(
                        new DirectTbtFrame.TravelMetrics(-1, 60, 999)));

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, metricOptions(true, false, true, true, false, false));

        assertEquals("[1 min | 999 m]", prepared.displayText());
    }

    @Test
    public void wholeRoutePreferenceFallsBackPerUnavailableField() {
        DirectTbtFrame frame = frameWithMetrics(
                "Road",
                new DirectTbtFrame.TripMetrics(
                        new DirectTbtFrame.TravelMetrics(localTime(12, 20), 601, 5100),
                        new DirectTbtFrame.TravelMetrics(-1, 1069, -1)));

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, metricOptions(true, true, true, true, true, true));

        assertEquals("[ETA: 12:20 | 18 min | 5.1 km] Road", prepared.displayText());
    }

    @Test
    public void etaUsesNavigatorSuppliedZoneOffset() {
        DirectTbtFrame frame = frameWithMetrics(
                "Road",
                DirectTbtFrame.TripMetrics.nextStopOnly(
                        new DirectTbtFrame.TravelMetrics(3_600_000L, 7200, 60, 100)));

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, metricOptions(false, true, false, false, true, true));

        assertEquals("[ETA: 03:00] Road", prepared.displayText());
    }

    @Test
    public void activeAlertSuppressesTripMetricsPrefix() {
        DirectTbtFrame base = frameWithMetrics(
                "Road",
                DirectTbtFrame.TripMetrics.nextStopOnly(
                        new DirectTbtFrame.TravelMetrics(localTime(12, 20), 601, 5100)));
        DirectTbtFrame frame = base.withAlertOverlay(
                DirectTbtFrame.AlertOverlay.active(7, 25, "Camera", new byte[]{8, 9}));

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, metricOptions(false, true, true, true, true, true));

        assertEquals("Camera", prepared.displayText());
    }

    @Test
    public void speedLimitPlacementRespectsFreeFieldsAndFallback() {
        DirectTbtFrame frame = frame(
                11, 9, DirectTbtFrame.AlertOverlay.inactive()).withSpeedLimit(
                new DirectTbtFrame.SpeedLimit(50, 50, "km/h", 1L));

        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_NONE,
                DirectTbtPayload.speedPlacement(frame, speedOptions(
                        HudPrefs.SPEED_LIMIT_FREE, HudPrefs.SPEED_LIMIT_FALLBACK_OFF)));
        DirectTbtPayload.Options fallback = speedOptions(
                HudPrefs.SPEED_LIMIT_FREE, HudPrefs.SPEED_LIMIT_FALLBACK_MANEUVER);
        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_MANEUVER,
                DirectTbtPayload.speedPlacement(frame, fallback));
        assertTrue(DirectTbtPayload.speedOverlaysOccupiedField(frame, fallback));
    }

    @Test
    public void speedLimitStoreNormalizesMphAndDeduplicates() {
        assertTrue(DirectSpeedLimitStore.update("com.waze", 30, -1, "mph", 10L));
        DirectTbtFrame.SpeedLimit speed = DirectSpeedLimitStore.snapshot("com.waze");
        assertEquals(30, speed.getDisplayValue());
        assertEquals(48, speed.getKph());
        assertFalse(DirectSpeedLimitStore.update("com.waze", 30, -1, "mph", 20L));
        assertTrue(DirectSpeedLimitStore.clear("com.waze"));
        assertFalse(DirectSpeedLimitStore.snapshot("com.waze").isActive());
    }

    private static DirectTbtPayload.Options speedOptions(int mode, int fallback) {
        return new DirectTbtPayload.Options(
                true, true, true, true, true, true, false,
                HudPrefs.ROUTE_METRICS_OFF, false, false, false,
                mode, fallback, 5, new byte[]{7, 2});
    }

    private static DirectTbtPayload.Options metricOptions(
            boolean wholeRoute, boolean eta, boolean time, boolean tripDistance,
            boolean street, boolean textDirection) {
        return new DirectTbtPayload.Options(
                true, true, true, true, street, textDirection, false,
                wholeRoute, eta, time, tripDistance, new byte[]{7, 2});
    }

    private static DirectTbtFrame frameWithMetrics(
            String road, DirectTbtFrame.TripMetrics metrics) {
        return new DirectTbtFrame(
                11, 3, 9, 120, road, "Turn right", road,
                new byte[]{1, 2, 3}, new byte[0], Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive(), metrics);
    }

    private static long localTime(int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2026, Calendar.JANUARY, 1, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static DirectTbtFrame frame(
            int rawManeuver,
            int bydManeuver,
            DirectTbtFrame.AlertOverlay alert) {
        return frame(rawManeuver, bydManeuver, 120, alert);
    }

    private static DirectTbtFrame frame(
            int rawManeuver,
            int bydManeuver,
            int distanceMeters,
            DirectTbtFrame.AlertOverlay alert) {
        return new DirectTbtFrame(
                rawManeuver,
                3,
                bydManeuver,
                distanceMeters,
                "Road",
                "Turn right",
                "Road",
                new byte[]{1, 2, 3},
                new byte[]{4, 5, 6},
                Collections.singletonList(new DirectTbtFrame.Lane(2, true, "R")),
                alert);
    }
}
