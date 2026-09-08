package com.bydhud.app;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class HudExperimentalIntegrationTest {
    @Test public void separateWarningNeverCompetesWithCloserOrFartherRoute() {
        for (boolean routeCloser : new boolean[]{false, true}) {
            List<HudExperimentalCompositor.Inputs> calls = new ArrayList<>();
            DirectTbtFrame frame = frame().withAlertOverlay(DirectTbtFrame.AlertOverlay
                    .active(7, routeCloser ? 300 : 10, "Camera", new byte[]{9})
                    .withRouteFrame(routeCloser));
            DirectTbtPayload.Prepared out = DirectTbtPayload.prepare(frame, options(true, true, 1), compositor(calls));
            assertEquals(13, out.nativeManeuver());
            assertEquals(30, out.distanceMeters());
            assertEquals("Road", out.displayText());
            assertEquals(1, out.laneCount());
            assertArrayEquals(new byte[]{1}, calls.get(0).maneuverPng);
            assertArrayEquals(new byte[]{2}, calls.get(1).lanePng);
            assertArrayEquals(new byte[]{9}, calls.get(1).warningPng);
            assertEquals(routeCloser ? "300 m" : "10 m", calls.get(1).warningDistance);
            assertEquals(frame().getLanes().get(0).getAmapCode(), frame.getLanes().get(0).getAmapCode());
            assertEquals(30, frame.getDistanceMeters());
        }
    }

    @Test public void unknownWarningDistanceIsNotZeroAndExpiryOnlyReplacesLowerPlane() {
        List<HudExperimentalCompositor.Inputs> calls = new ArrayList<>();
        HudExperimentalCompositor renderer = compositor(calls);
        DirectTbtFrame alert = frame().withAlertOverlay(DirectTbtFrame.AlertOverlay
                .active(7, -1, "Camera", new byte[]{9}));
        DirectTbtPayload.prepare(alert, options(true, true, 1), renderer);
        assertEquals("", calls.get(1).warningDistance);
        DirectTbtPayload.Prepared expired = DirectTbtPayload.prepare(frame(), options(true, true, 1), renderer);
        assertEquals(3, calls.size());
        assertEquals(0, calls.get(2).warningPng.length);
        assertEquals(0, calls.get(2).maneuverPng.length); // Upper cache reused.
        assertEquals(13, expired.nativeManeuver());
        assertFalse(HudOutputCoordinator.requiresAlertClear(true));
        assertTrue(HudOutputCoordinator.requiresAlertClear(false));
    }

    @Test public void primaryVisibilityDoesNotHideOtherContentOnSamePlane() {
        List<HudExperimentalCompositor.Inputs> calls = new ArrayList<>();
        DirectTbtPayload.Prepared out = DirectTbtPayload.prepare(frame(), options(false, false, 1), compositor(calls));
        assertEquals(0, calls.get(0).maneuverPng.length);
        assertEquals("03:00", calls.get(0).arrival);
        assertEquals("25 min", calls.get(0).duration);
        assertEquals(0, calls.get(1).lanePng.length);
        assertEquals("8.4 km", calls.get(1).remaining);
        assertTrue(out.maneuverPngBytes() > 0);
        assertTrue(out.lanePngBytes() > 0);
        assertEquals(0, out.laneCount());
    }

    @Test public void warningDoesNotReservePrimarySpeedSlotInSeparateMode() {
        DirectTbtFrame frame = frame().withAlertOverlay(DirectTbtFrame.AlertOverlay
                .active(7, 10, "Camera", new byte[]{9})).withSpeedLimit(
                new DirectTbtFrame.SpeedLimit(50, 50, "km/h", 1));
        for (int mode = 1; mode <= 4; mode++) {
            DirectTbtPayload.Options options = new DirectTbtPayload.Options(
                    true, true, true, true, true, true, false, 1, true, true, true,
                    mode, HudPrefs.SPEED_LIMIT_FALLBACK_MANEUVER, 5, null)
                    .withPresentation(style(1, 1));
            int expected = mode == HudPrefs.SPEED_LIMIT_LANES
                    ? DirectTbtPayload.SPEED_PLACEMENT_LANES : DirectTbtPayload.SPEED_PLACEMENT_MANEUVER;
            assertEquals(expected, DirectTbtPayload.speedPlacement(frame, options));
            List<HudExperimentalCompositor.Inputs> calls = new ArrayList<>();
            DirectTbtPayload.Prepared out = DirectTbtPayload.prepare(frame, options, compositor(calls));
            assertEquals(13, out.nativeManeuver());
            assertEquals("Road", out.displayText());
            assertArrayEquals(new byte[]{9}, calls.get(1).warningPng);
        }
    }

    @Test public void sharedModeKeepsNearestEventAndDoesNotCallCompositor() {
        DirectTbtPayload.Options shared = options(true, true, 1).withPresentation(style(0, 0));
        DirectTbtFrame closeAlert = frame().withAlertOverlay(DirectTbtFrame.AlertOverlay
                .active(7, 10, "Camera", new byte[]{9}));
        HudExperimentalCompositor forbidden = new HudExperimentalCompositor(input -> {
            throw new AssertionError("Shared mode must not render experimental planes");
        });
        assertEquals("Camera", DirectTbtPayload.prepare(closeAlert, shared, forbidden).displayText());
        assertEquals(99, DirectTbtPayload.prepare(closeAlert, shared, forbidden).nativeManeuver());
        DirectTbtFrame farther = closeAlert.withAlertOverlay(closeAlert.getAlertOverlay().withRouteFrame(true));
        assertEquals(13, DirectTbtPayload.prepare(farther, shared, forbidden).nativeManeuver());
        assertEquals("[03:00 | 25 min | 8.4 km] Road",
                DirectTbtPayload.prepare(farther, shared, forbidden).displayText());
    }

    @Test public void loggingIsNonRenderingAndDoesNotCreateWaitOrTransportState() throws Exception {
        assertEquals("Road", DirectTbtPayload.describe(frame(), options(true, true, 1)).displayText());
        String sender = source("NavHudLiveSender.java");
        assertFalse(sender.contains("DirectTbtPayload.prepare("));
        assertTrue(sender.contains("DirectTbtPayload.describe("));
        String payload = source("DirectTbtPayload.java");
        assertTrue(payload.contains("HudPrefs.isEtaWaitForFullTextEnabled(safeContext)"));
        assertFalse(payload.contains("postDelayed("));
        String tbt = source("VehicleTbtPublisher.java");
        assertFalse(tbt.contains("HudExperimentalCompositor"));
        assertTrue(tbt.contains("frame.getAmapBroadcastManeuver()"));
        assertTrue(tbt.contains("frame.getLanes()"));
    }

    private static String source(String name) throws Exception {
        java.nio.file.Path path = Paths.get("src/main/java/com/bydhud/app", name);
        if (!Files.exists(path)) path = Paths.get("app/src/main/java/com/bydhud/app", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static HudExperimentalCompositor compositor(List<HudExperimentalCompositor.Inputs> calls) {
        return new HudExperimentalCompositor(input -> {
            calls.add(input);
            return new HudExperimentalCompositor.Result(
                    HudExperimentalCompositor.hasMeaningfulF8Content(input) ? new byte[]{8} : null,
                    HudExperimentalCompositor.hasMeaningfulF7Content(input) ? new byte[]{7} : null);
        });
    }

    private static DirectTbtPayload.Presentation style(int eta, int warning) {
        return new DirectTbtPayload.Presentation(eta, warning, 0, false, -1, -1, -1, 0xffffff00);
    }

    private static DirectTbtPayload.Options options(boolean png, boolean lanes, int mode) {
        return new DirectTbtPayload.Options(png, true, lanes, true, true, true, false,
                mode, true, true, true, 0, 0, 5, null).withPresentation(style(1, 1));
    }

    private static DirectTbtFrame frame() {
        return new DirectTbtFrame(1, 2, 13, 30, "Road", "Cue", "Road",
                new byte[]{1}, new byte[]{2}, Collections.singletonList(
                new DirectTbtFrame.Lane(2, true, "L")), DirectTbtFrame.AlertOverlay.inactive(),
                DirectTbtFrame.TripMetrics.nextStopOnly(new DirectTbtFrame.TravelMetrics(
                        3_600_000L, 7200, 1500, 8400)));
    }
}
