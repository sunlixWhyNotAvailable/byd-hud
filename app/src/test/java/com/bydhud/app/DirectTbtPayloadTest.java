package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.Calendar;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class DirectTbtPayloadTest {
    @Test
    public void currentFrameWireBytesStayStable() {
        assertArrayEquals(new byte[]{
                        0x0a, 0x28, 0x10, 0x07, 0x28, 0x01, 0x30, 0x06,
                        0x3a, 0x03, 0x04, 0x05, 0x06, 0x42, 0x03, 0x01,
                        0x02, 0x03, 0x48, 0x78, 0x52, 0x04, 0x52, 0x6f,
                        0x61, 0x64, (byte) 0x80, 0x01, 0x02, (byte) 0xd2,
                        0x01, 0x00, (byte) 0xe0, 0x01, 0x09, (byte) 0xea,
                        0x01, 0x04, 0x34, 0x2c, 0x34, 0x7c},
                DirectTbtPayload.build(
                        frame(11, 9, DirectTbtFrame.AlertOverlay.inactive()),
                        7, DirectTbtPayload.Options.ALL));
    }

    @Test
    public void displayTextOverrideChangesOnlyF10AcrossUtf8SizesAndEmptyText() {
        byte[] maneuverPng = pngHeader(37, 19);
        DirectTbtFrame source = frame(11, 9, DirectTbtFrame.AlertOverlay.inactive())
                .withManeuverPng(maneuverPng)
                .withTripMetrics(DirectTbtFrame.TripMetrics.nextStopOnly(
                        new DirectTbtFrame.TravelMetrics(-1, 601, -1)));
        DirectTbtPayload.Prepared original = DirectTbtPayload.prepare(
                source, metricOptions(false, false, true, false, true, true));
        byte[] originalPayload = original.build(255);

        assertTrue(original.hasStreetEta());
        assertSame(original, original.withDisplayText(original.displayText()));
        assertEquals(37, original.maneuverPngWidth());
        assertEquals(19, original.maneuverPngHeight());
        assertFalse(original.maneuverPngSha().isEmpty());

        DirectTbtPayload.Prepared current = original;
        for (String replacement : new String[]{repeat("Ї", 80), ""}) {
            current = current.withDisplayText(replacement);
            byte[] payload = current.build(255);
            assertFraming(payload);
            assertEquals(replacement, lengthDelimitedText(payload, 10));
            for (int field : new int[]{7, 8, 9, 28, 29}) {
                assertArrayEquals("field " + field,
                        fieldEncoding(originalPayload, field), fieldEncoding(payload, field));
            }
            assertEquals(original.maneuverPngBytes(), current.maneuverPngBytes());
            assertEquals(original.maneuverPngSha(), current.maneuverPngSha());
            assertEquals(original.maneuverPngWidth(), current.maneuverPngWidth());
            assertEquals(original.maneuverPngHeight(), current.maneuverPngHeight());
            assertEquals(original.hasStreetEta(), current.hasStreetEta());
            assertEquals(replacement, current.displayText());
        }
    }

    @Test
    public void streetEtaMarkerRequiresSelectedMetricsWithoutSharedAlertOrSeparateEta() {
        DirectTbtFrame metrics = frame(11, 9, DirectTbtFrame.AlertOverlay.inactive())
                .withTripMetrics(DirectTbtFrame.TripMetrics.nextStopOnly(
                        new DirectTbtFrame.TravelMetrics(-1, 601, -1)));
        DirectTbtPayload.Options streetEta = metricOptions(
                false, false, true, false, true, true);

        assertTrue(DirectTbtPayload.describe(metrics, streetEta).hasStreetEta());
        assertFalse(DirectTbtPayload.describe(
                metrics.withAlertOverlay(DirectTbtFrame.AlertOverlay.active(
                        7, 25, "Camera", new byte[]{8, 9})), streetEta).hasStreetEta());
        assertFalse(DirectTbtPayload.describe(metrics, streetEta.withPresentation(
                new DirectTbtPayload.Presentation(1, 0, 0, false,
                        -1, -1, -1, 0xffffff00))).hasStreetEta());
        assertFalse(DirectTbtPayload.describe(metrics, metricOptions(
                false, false, false, false, true, true)).hasStreetEta());
    }

    @Test
    public void displayTextOverrideDoesNotInvokeExperimentalRendererAgain() {
        AtomicInteger renders = new AtomicInteger();
        HudExperimentalCompositor compositor = new HudExperimentalCompositor(input -> {
            renders.incrementAndGet();
            return new HudExperimentalCompositor.Result(new byte[]{8}, new byte[]{7});
        });
        DirectTbtPayload.Options options = DirectTbtPayload.Options.ALL.withPresentation(
                new DirectTbtPayload.Presentation(0, 1, 0, false,
                        -1, -1, -1, 0xffffff00));
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(11, 9, DirectTbtFrame.AlertOverlay.inactive()), options, compositor);
        int renderCount = renders.get();

        DirectTbtPayload.Prepared updated = prepared.withDisplayText("Updated street");

        assertEquals(renderCount, renders.get());
        assertEquals(prepared.maneuverPngSha(), updated.maneuverPngSha());
        assertEquals(prepared.maneuverPngBytes(), updated.maneuverPngBytes());
        assertEquals(prepared.lanePngBytes(), updated.lanePngBytes());
    }

    @Test
    public void clearWireBytesStayStable() {
        assertArrayEquals(new byte[]{
                        0x0a, 0x08, 0x10, 0x02, 0x30, (byte) 0xff,
                        0x01, (byte) 0x80, 0x01, 0x01},
                DirectTbtPayload.buildClear());
    }

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
        assertEquals(25, prepared.distanceMeters());
        assertEquals("Camera", prepared.displayText());
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
    public void routeWinnerUsesTheCompleteRouteFrame() {
        DirectTbtFrame.AlertOverlay alert = DirectTbtFrame.AlertOverlay.active(
                7, 250, "Camera", new byte[]{8, 9}).withRouteFrame(true);

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(11, 9, 80, alert), DirectTbtPayload.Options.ALL);

        assertEquals(9, prepared.nativeManeuver());
        assertEquals(80, prepared.distanceMeters());
        assertEquals("Road", prepared.displayText());
        assertEquals(3, prepared.maneuverPngBytes());
        assertEquals(1, prepared.laneCount());
        assertEquals("current", prepared.maneuverMode());
    }

    @Test
    public void closestCandidatePolicyCoversEqualityZeroAndUnknowns() {
        DirectTbtFrame.AlertOverlay alert250 = DirectTbtFrame.AlertOverlay.active(
                7, 250, "Camera", new byte[]{8, 9});
        DirectTbtFrame.AlertOverlay alert25 = DirectTbtFrame.AlertOverlay.active(
                7, 25, "Camera", new byte[]{8, 9});
        DirectTbtFrame.AlertOverlay zeroAlertDistance = DirectTbtFrame.AlertOverlay.active(
                7, 0, "Camera", new byte[]{8, 9});
        DirectTbtFrame.AlertOverlay unknownAlertDistance = DirectTbtFrame.AlertOverlay.active(
                7, -1, "Camera", new byte[]{8, 9});

        assertTrue(WazeDirectChannel.shouldUseRouteFrameDuringAlert(
                frame(11, 9, 200, alert250), true, alert250));
        assertTrue(WazeDirectChannel.shouldUseRouteFrameDuringAlert(
                frame(11, 9, 250, alert250), true, alert250));
        assertFalse(WazeDirectChannel.shouldUseRouteFrameDuringAlert(
                frame(11, 9, 251, alert250), true, alert250));
        assertTrue(WazeDirectChannel.shouldUseRouteFrameDuringAlert(
                frame(11, 9, 0, zeroAlertDistance), true, zeroAlertDistance));
        assertFalse(WazeDirectChannel.shouldUseRouteFrameDuringAlert(
                frame(11, 9, 1, zeroAlertDistance), true, zeroAlertDistance));
        assertFalse(WazeDirectChannel.shouldUseRouteFrameDuringAlert(
                frame(11, 9, 0, alert25), false, alert25));
        assertTrue(WazeDirectChannel.shouldUseRouteFrameDuringAlert(
                frame(11, 9, 101, unknownAlertDistance), true, unknownAlertDistance));
        assertFalse(WazeDirectChannel.shouldUseRouteFrameDuringAlert(
                frame(-1, 9, 50, alert25), true, alert25));
        assertFalse(WazeDirectChannel.shouldUseRouteFrameDuringAlert(
                frame(11, 99, 50, alert25), true, alert25));
    }

    @Test
    public void everyAlertAndRouteSnapshotReevaluatesTheWholeCandidate() {
        DirectTbtFrame.AlertOverlay next = DirectTbtFrame.AlertOverlay.active(
                8, 180, "Camera", new byte[]{8, 9});

        DirectTbtFrame.AlertOverlay selected = WazeDirectChannel.selectAlertOverlayCandidate(
                next, frame(11, 9, 190, next), true);

        assertFalse(selected.useRouteFrame());
        DirectTbtFrame.AlertOverlay refreshed = DirectTbtFrame.AlertOverlay.active(
                9, 200, "Camera", new byte[]{8, 9});
        selected = WazeDirectChannel.selectAlertOverlayCandidate(
                refreshed, frame(11, 9, 190, refreshed), true);
        assertTrue(selected.useRouteFrame());
        selected = WazeDirectChannel.selectAlertOverlayCandidate(
                selected, frame(11, 9, 210, selected), true);
        assertFalse(selected.useRouteFrame());
    }

    @Test
    public void unknownAlertDistanceUsesAlertOnlyWhenNoValidRouteExists() {
        DirectTbtFrame.AlertOverlay unknown = DirectTbtFrame.AlertOverlay.active(
                7, -1, "Camera", new byte[]{8, 9});
        DirectTbtFrame.AlertOverlay selected = WazeDirectChannel.selectAlertOverlayCandidate(
                unknown, frame(-1, 9, 120, unknown), false);

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(-1, 9, 120, selected), DirectTbtPayload.Options.ALL);

        assertFalse(unknown.isDistanceKnown());
        assertFalse(selected.useRouteFrame());
        assertEquals(99, prepared.nativeManeuver());
        assertEquals(0, prepared.distanceMeters());
        assertEquals("Camera", prepared.displayText());
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
    public void activeAlertKeepsItsOwnDistanceAfterManeuverClamp() {
        DirectTbtFrame frame = HudDisplayPolicy.applyActiveFrame(
                frame(11, 9, 10, DirectTbtFrame.AlertOverlay.active(
                        7, 25, "Camera", new byte[]{8, 9})), true);
        DirectTbtPayload.Options options = new DirectTbtPayload.Options(
                true, true, true, true, true, true, true);

        assertEquals(11, frame.getDistanceMeters());
        assertEquals(25, DirectTbtPayload.prepare(frame, options).distanceMeters());
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

        assertEquals("[12:20 | 11 min | 5.1 km] Road", nextStop.displayText());
        assertEquals("[13:40 | 18 min | 10.3 km] Road", wholeRoute.displayText());
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

        assertEquals("[12:20 | 18 min | 5.1 km] Road", prepared.displayText());
    }

    @Test
    public void nextStopPreferenceFallsBackPerUnavailableField() {
        DirectTbtFrame frame = frameWithMetrics(
                "Road",
                new DirectTbtFrame.TripMetrics(
                        new DirectTbtFrame.TravelMetrics(-1, -1, -1),
                        new DirectTbtFrame.TravelMetrics(
                                localTime(13, 40), 1069, 10310)));

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, metricOptions(false, true, true, true, true, true));

        assertEquals("[13:40 | 18 min | 10.3 km] Road", prepared.displayText());
    }

    @Test
    public void requestedMetricScopeWinsWhenBothFieldsExist() {
        DirectTbtFrame frame = frameWithMetrics(
                "Road",
                new DirectTbtFrame.TripMetrics(
                        new DirectTbtFrame.TravelMetrics(-1, 601, 5100),
                        new DirectTbtFrame.TravelMetrics(-1, 1069, 10310)));

        assertEquals("[11 min | 5.1 km] Road", DirectTbtPayload.prepare(
                frame, metricOptions(false, false, true, true, true, true)).displayText());
        assertEquals("[18 min | 10.3 km] Road", DirectTbtPayload.prepare(
                frame, metricOptions(true, false, true, true, true, true)).displayText());
    }

    @Test
    public void etaUsesNavigatorSuppliedZoneOffset() {
        DirectTbtFrame frame = frameWithMetrics(
                "Road",
                DirectTbtFrame.TripMetrics.nextStopOnly(
                        new DirectTbtFrame.TravelMetrics(3_600_000L, 7200, 60, 100)));

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, metricOptions(false, true, false, false, true, true));

        assertEquals("[03:00] Road", prepared.displayText());
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
    public void nativeSpeedStubPreservesNavigationWithoutAnyBitmapPlacement() {
        DirectTbtFrame[] frames = {
                speedFrame(new byte[]{1, 2, 3}, new byte[]{4, 5}),
                speedFrame(new byte[0], new byte[0]),
                speedFrame(new byte[]{1, 2, 3}, new byte[0]),
                speedFrame(new byte[0], new byte[]{4, 5}),
                frame(11, 9, DirectTbtFrame.AlertOverlay.inactive()).withSpeedLimit(
                        new DirectTbtFrame.SpeedLimit(50, 50, "km/h", 1L)),
                frame(11, 9, DirectTbtFrame.AlertOverlay.active(
                        7, 25, "Camera", new byte[]{8, 9})).withSpeedLimit(
                        new DirectTbtFrame.SpeedLimit(50, 50, "km/h", 1L))
        };
        for (DirectTbtFrame frame : frames) {
            for (int fallback = HudPrefs.SPEED_LIMIT_FALLBACK_OFF;
                    fallback <= HudPrefs.SPEED_LIMIT_FALLBACK_LANES; fallback++) {
                DirectTbtPayload.Options nativeOptions = speedOptions(
                        HudPrefs.SPEED_LIMIT_NATIVE, fallback);
                assertEquals(DirectTbtPayload.SPEED_PLACEMENT_NONE,
                        DirectTbtPayload.speedPlacement(frame, nativeOptions));
                assertFalse(DirectTbtPayload.speedOverlaysOccupiedField(frame, nativeOptions));
                assertArrayEquals(DirectTbtPayload.build(frame, 7,
                                speedOptions(HudPrefs.SPEED_LIMIT_OFF, fallback)),
                        DirectTbtPayload.build(frame, 7, nativeOptions));
            }
        }
    }

    @Test
    public void speedLimitUiOrderPreservesPersistedModeIds() {
        // Existing stored values must still select their original modes after upgrade.
        int[] modes = {HudPrefs.SPEED_LIMIT_OFF, HudPrefs.SPEED_LIMIT_NATIVE,
                HudPrefs.SPEED_LIMIT_MANEUVER, HudPrefs.SPEED_LIMIT_LANES,
                HudPrefs.SPEED_LIMIT_FREE, HudPrefs.SPEED_LIMIT_COMPOSITE};
        int[] storedIds = {0, 5, 1, 2, 3, 4};
        assertArrayEquals(storedIds, modes);
        for (int index = 0; index < storedIds.length; index++) {
            assertEquals(storedIds[index], HudPrefs.normalizeSpeedLimitMode(storedIds[index]));
            assertEquals(index, HudPrefs.speedLimitModeUiIndex(storedIds[index]));
            assertEquals(storedIds[index], HudPrefs.speedLimitModeFromUiIndex(index));
        }
        assertEquals(0, HudPrefs.speedLimitModeFromUiIndex(-1));
        assertEquals(4, HudPrefs.speedLimitModeFromUiIndex(6));
    }

    @Test
    public void compositePlacementUsesNamedAndOnlyFreeFields() {
        DirectTbtFrame bothOccupied = speedFrame(new byte[]{1}, new byte[]{2});
        DirectTbtFrame bothFree = speedFrame(new byte[0], new byte[0]);
        DirectTbtFrame maneuverOnly = speedFrame(new byte[]{1}, new byte[0]);
        DirectTbtFrame lanesOnly = speedFrame(new byte[0], new byte[]{2});

        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_MANEUVER,
                DirectTbtPayload.speedPlacement(bothOccupied, compositeOptions(
                        HudPrefs.SPEED_LIMIT_COMPOSITE_MANEUVER_ONLY)));
        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_LANES,
                DirectTbtPayload.speedPlacement(bothFree, compositeOptions(
                        HudPrefs.SPEED_LIMIT_COMPOSITE_LANES_ONLY)));

        DirectTbtPayload.Options freeOrManeuver = compositeOptions(
                HudPrefs.SPEED_LIMIT_COMPOSITE_FREE_OR_MANEUVER);
        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_LANES,
                DirectTbtPayload.speedPlacement(maneuverOnly, freeOrManeuver));
        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_MANEUVER,
                DirectTbtPayload.speedPlacement(lanesOnly, freeOrManeuver));
        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_MANEUVER,
                DirectTbtPayload.speedPlacement(bothFree, freeOrManeuver));
        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_MANEUVER,
                DirectTbtPayload.speedPlacement(bothOccupied, freeOrManeuver));

        DirectTbtPayload.Options freeOrLanes = compositeOptions(
                HudPrefs.SPEED_LIMIT_COMPOSITE_FREE_OR_LANES);
        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_LANES,
                DirectTbtPayload.speedPlacement(bothFree, freeOrLanes));
        assertEquals(DirectTbtPayload.SPEED_PLACEMENT_LANES,
                DirectTbtPayload.speedPlacement(bothOccupied, freeOrLanes));
        assertFalse(DirectTbtPayload.speedOverlaysOccupiedField(
                bothOccupied, freeOrLanes));
    }

    @Test
    public void legacyOptionsKeepCompositeDefaults() {
        DirectTbtPayload.Options options = speedOptions(
                HudPrefs.SPEED_LIMIT_FREE, HudPrefs.SPEED_LIMIT_FALLBACK_OFF);

        assertEquals(HudPrefs.SPEED_LIMIT_COMPOSITE_MANEUVER_ONLY,
                options.speedLimitCompositePlacement);
        assertEquals(64, options.speedLimitManeuverOverlaySize);
        assertEquals(36, options.speedLimitLaneOverlaySize);
    }

    @Test
    public void speedLimitPreferenceNormalizersClampBoundaries() {
        assertEquals(0, HudPrefs.normalizeSpeedLimitMode(-1));
        assertEquals(0, HudPrefs.normalizeSpeedLimitMode(0));
        assertEquals(4, HudPrefs.normalizeSpeedLimitMode(4));
        assertEquals(5, HudPrefs.normalizeSpeedLimitMode(5));
        assertEquals(4, HudPrefs.normalizeSpeedLimitMode(6));
        assertEquals(4, HudPrefs.normalizeSpeedLimitMode(Integer.MAX_VALUE));

        assertEquals(0, HudPrefs.normalizeSpeedLimitCompositePlacement(-1));
        assertEquals(0, HudPrefs.normalizeSpeedLimitCompositePlacement(0));
        assertEquals(3, HudPrefs.normalizeSpeedLimitCompositePlacement(3));
        assertEquals(3, HudPrefs.normalizeSpeedLimitCompositePlacement(4));

        assertEquals(1, HudPrefs.normalizeSpeedLimitManeuverOverlaySize(0));
        assertEquals(1, HudPrefs.normalizeSpeedLimitManeuverOverlaySize(1));
        assertEquals(103, HudPrefs.normalizeSpeedLimitManeuverOverlaySize(103));
        assertEquals(103, HudPrefs.normalizeSpeedLimitManeuverOverlaySize(104));

        assertEquals(1, HudPrefs.normalizeSpeedLimitLaneOverlaySize(0));
        assertEquals(1, HudPrefs.normalizeSpeedLimitLaneOverlaySize(1));
        assertEquals(36, HudPrefs.normalizeSpeedLimitLaneOverlaySize(36));
        assertEquals(36, HudPrefs.normalizeSpeedLimitLaneOverlaySize(37));
    }

    @Test
    public void compositeCacheKeyUsesFullBytesAndEveryOption() {
        byte[] base = {1, 2, 3};
        assertTrue(SpeedLimitPng.isSameCompositeKey(
                base, 50, 64, false, new byte[]{1, 2, 3}, 50, 64, false));
        assertFalse(SpeedLimitPng.isSameCompositeKey(
                base, 50, 64, false, new byte[]{1, 2, 4}, 50, 64, false));
        assertFalse(SpeedLimitPng.isSameCompositeKey(
                base, 50, 64, false, base, 60, 64, false));
        assertFalse(SpeedLimitPng.isSameCompositeKey(
                base, 50, 64, false, base, 50, 36, false));
        assertFalse(SpeedLimitPng.isSameCompositeKey(
                base, 50, 64, false, base, 50, 64, true));
    }

    @Test
    public void freeFieldStandaloneSignPolicyIs96Pixels() {
        assertEquals(96, SpeedLimitPng.STANDALONE_SIZE_PX);
    }

    @Test
    public void compositeFailureAndMissingLaneBitmapPreserveGuidance() {
        DirectTbtPayload.Prepared maneuver = DirectTbtPayload.prepare(
                speedFrame(new byte[]{1, 2, 3}, new byte[0]), compositeOptions(
                        HudPrefs.SPEED_LIMIT_COMPOSITE_MANEUVER_ONLY));
        DirectTbtFrame structuredLanes = new DirectTbtFrame(
                11, 3, 9, 120, "Road", "Turn right", "Road",
                new byte[]{1, 2, 3}, new byte[0],
                Collections.singletonList(new DirectTbtFrame.Lane(2, true, "R")),
                DirectTbtFrame.AlertOverlay.inactive()).withSpeedLimit(
                new DirectTbtFrame.SpeedLimit(50, 50, "km/h", 1L));
        DirectTbtPayload.Prepared lanes = DirectTbtPayload.prepare(
                structuredLanes, compositeOptions(
                        HudPrefs.SPEED_LIMIT_COMPOSITE_LANES_ONLY));

        assertEquals(3, maneuver.maneuverPngBytes());
        assertEquals("current", maneuver.maneuverMode());
        assertEquals(1, lanes.laneCount());
        assertEquals(0, lanes.lanePngBytes());
    }

    @Test
    public void explicitManeuverSpeedDoesNotOverwriteActiveAlert() {
        DirectTbtFrame frame = frame(
                11, 9, DirectTbtFrame.AlertOverlay.active(
                        7, 25, "Camera", new byte[]{8, 9})).withSpeedLimit(
                new DirectTbtFrame.SpeedLimit(50, 50, "km/h", 1L));

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, speedOptions(
                        HudPrefs.SPEED_LIMIT_MANEUVER,
                        HudPrefs.SPEED_LIMIT_FALLBACK_OFF));

        assertEquals("alert", prepared.maneuverMode());
        assertEquals(2, prepared.maneuverPngBytes());
    }

    @Test
    public void failedLaneSpeedPngKeepsStructuredLanes() {
        DirectTbtFrame frame = new DirectTbtFrame(
                11, 3, 9, 120, "Road", "Turn right", "Road",
                new byte[]{1, 2, 3}, new byte[0],
                Collections.singletonList(new DirectTbtFrame.Lane(2, true, "R")),
                DirectTbtFrame.AlertOverlay.inactive()).withSpeedLimit(
                new DirectTbtFrame.SpeedLimit(50, 50, "km/h", 1L));

        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, speedOptions(
                        HudPrefs.SPEED_LIMIT_LANES,
                        HudPrefs.SPEED_LIMIT_FALLBACK_OFF));

        assertEquals(1, prepared.laneCount());
        assertEquals(0, prepared.lanePngBytes());
    }

    @Test
    public void compositeGeometryKeepsMarginsAndUsesRightOnTies() {
        assertEquals(38, SpeedLimitPng.compositeCanvasSize(20, 36));
        assertEquals(100, SpeedLimitPng.compositeCanvasSize(100, 36));
        assertEquals(63, SpeedLimitPng.compositeBottomY(100, 36));
        assertEquals(40, SpeedLimitPng.chooseManeuverX(105, 64, 3, 3));
        assertEquals(1, SpeedLimitPng.chooseManeuverX(105, 64, 2, 3));
    }

    @Test
    public void laneGeometryUsesActualColumnRunsAndLeastOverlapGap() {
        assertArrayEquals(new int[]{1, 2, 5, 5, 8, 10},
                SpeedLimitPng.occupiedColumnRuns(new boolean[]{
                        false, true, true, false, false, true,
                        false, false, true, true, true}));
        int[] runs = {2, 6, 30, 35, 70, 75};
        assertEquals(42, SpeedLimitPng.chooseLaneX(100, 20, runs, new int[]{3, 3}));
        assertEquals(8, SpeedLimitPng.chooseLaneX(100, 20, runs, new int[]{1, 2}));
        assertEquals(79, SpeedLimitPng.chooseLaneX(
                100, 20, new int[]{2, 6, 30, 35}, new int[]{0}));
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

    private static DirectTbtPayload.Options compositeOptions(int placement) {
        return new DirectTbtPayload.Options(
                true, true, true, true, true, true, false,
                HudPrefs.ROUTE_METRICS_OFF, false, false, false,
                HudPrefs.SPEED_LIMIT_COMPOSITE, HudPrefs.SPEED_LIMIT_FALLBACK_OFF,
                5, placement, 64, 36, new byte[]{7, 2});
    }

    private static DirectTbtFrame speedFrame(byte[] maneuverPng, byte[] lanePng) {
        return new DirectTbtFrame(
                11, 3, 9, 120, "Road", "Turn right", "Road",
                maneuverPng, lanePng, Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive()).withSpeedLimit(
                new DirectTbtFrame.SpeedLimit(50, 50, "km/h", 1L));
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

    private static void assertFraming(byte[] payload) {
        assertEquals(0x0a, payload[0] & 0xff);
        int[] offset = {1};
        int innerLength = (int) readVarint(payload, offset);
        assertEquals(payload.length, offset[0] + innerLength);
        assertArrayEquals(new byte[]{0x10, (byte) 0xff, 0x01},
                fieldEncoding(payload, 2));
    }

    private static String lengthDelimitedText(byte[] payload, int wantedField) {
        byte[] encoded = fieldEncoding(payload, wantedField);
        assertNotNull(encoded);
        int[] offset = {0};
        readVarint(encoded, offset);
        int length = (int) readVarint(encoded, offset);
        return new String(encoded, offset[0], length, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] fieldEncoding(byte[] payload, int wantedField) {
        int[] offset = {1};
        int innerLength = (int) readVarint(payload, offset);
        int end = offset[0] + innerLength;
        while (offset[0] < end) {
            int start = offset[0];
            long tag = readVarint(payload, offset);
            int wireType = (int) (tag & 7);
            if (wireType == 0) {
                readVarint(payload, offset);
            } else if (wireType == 2) {
                int length = (int) readVarint(payload, offset);
                offset[0] += length;
            } else {
                throw new AssertionError("unsupported wire type " + wireType);
            }
            if ((tag >>> 3) == wantedField) {
                return Arrays.copyOfRange(payload, start, offset[0]);
            }
        }
        return null;
    }

    private static long readVarint(byte[] bytes, int[] offset) {
        long value = 0;
        int shift = 0;
        while (offset[0] < bytes.length && shift < 64) {
            int next = bytes[offset[0]++] & 0xff;
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
            shift += 7;
        }
        throw new AssertionError("invalid varint");
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static byte[] pngHeader(int width, int height) {
        byte[] png = new byte[24];
        png[0] = (byte) 0x89;
        png[1] = 0x50;
        png[2] = 0x4e;
        png[3] = 0x47;
        png[12] = 0x49;
        png[13] = 0x48;
        png[14] = 0x44;
        png[15] = 0x52;
        png[16] = (byte) (width >>> 24);
        png[17] = (byte) (width >>> 16);
        png[18] = (byte) (width >>> 8);
        png[19] = (byte) width;
        png[20] = (byte) (height >>> 24);
        png[21] = (byte) (height >>> 16);
        png[22] = (byte) (height >>> 8);
        png[23] = (byte) height;
        return png;
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
