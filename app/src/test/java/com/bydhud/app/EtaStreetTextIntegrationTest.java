package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class EtaStreetTextIntegrationTest {
    private static final String ROAD = "Dniprovske shose";

    @Test
    public void freshPreparedPayloadKeepsOnlyF10HeldAcrossStreetFormatsAndMetricScopes() {
        for (int format : new int[]{0, 1}) {
            for (int metricMode : new int[]{
                    HudPrefs.ROUTE_METRICS_NEXT_STOP,
                    HudPrefs.ROUTE_METRICS_WHOLE_ROUTE}) {
                DirectTbtPayload.Options options = streetOptions(metricMode, format, false, true);
                String context = HudOutputCoordinator.etaStreetContext(options, 0);
                EtaStreetTextGate gate = new EtaStreetTextGate();

                DirectTbtPayload.Prepared initial = DirectTbtPayload.prepare(
                        frame(ROAD, metrics(0), 13, 120, 1), options);
                assertTrue(initial.hasStreetEta());
                assertTrue(EtaStreetTextGate.estimatedWidth(initial.displayText()) > 100);
                assertTrue(initial.displayText().contains(metricMode
                        == HudPrefs.ROUTE_METRICS_NEXT_STOP ? "01:00" : "02:00"));
                if (format == 0) {
                    assertTrue(initial.displayText().startsWith("["));
                    assertTrue(initial.displayText().endsWith(ROAD));
                } else {
                    assertFalse(initial.displayText().contains(ROAD));
                }
                DirectTbtPayload.Prepared first = selected(
                        gate, initial, ROAD, context, true, 0);
                gate.onSent(first.displayText(), 0);

                DirectTbtPayload.Prepared middle = DirectTbtPayload.prepare(
                        frame(ROAD, metrics(1), 55, 222, 2), options);
                DirectTbtPayload.Prepared heldMiddle = selected(
                        gate, middle, ROAD, context, true, 100);
                assertEquals(initial.displayText(), heldMiddle.displayText());
                assertEquals(middle.distanceMeters(), heldMiddle.distanceMeters());
                assertEquals(middle.nativeManeuver(), heldMiddle.nativeManeuver());
                assertEquals(middle.laneCount(), heldMiddle.laneCount());

                DirectTbtPayload.Prepared latest = DirectTbtPayload.prepare(
                        frame(ROAD, metrics(2), 66, 333, 3), options);
                assertTrue(latest.displayText().contains(metricMode
                        == HudPrefs.ROUTE_METRICS_NEXT_STOP ? "05:00" : "06:00"));
                DirectTbtPayload.Prepared heldLatest = selected(
                        gate, latest, ROAD, context, true, 200);
                assertEquals(initial.displayText(), heldLatest.displayText());
                assertEquals(latest.distanceMeters(), heldLatest.distanceMeters());
                assertEquals(latest.nativeManeuver(), heldLatest.nativeManeuver());
                assertEquals(latest.laneCount(), heldLatest.laneCount());

                long deadline = EtaStreetTextGate.holdMillis(initial.displayText());
                DirectTbtPayload.Prepared released = selected(
                        gate, latest, ROAD, context, true, deadline);
                assertEquals(latest.displayText(), released.displayText());
                assertNotEquals(middle.displayText(), released.displayText());
                assertEquals(latest.distanceMeters(), released.distanceMeters());
                assertEquals(latest.nativeManeuver(), released.nativeManeuver());
                assertEquals(latest.laneCount(), released.laneCount());
            }
        }
    }

    @Test
    public void replaceModeUsesHiddenRawRoadChangeToDiscardPendingText() {
        DirectTbtPayload.Options options = streetOptions(
                HudPrefs.ROUTE_METRICS_NEXT_STOP, 1, false, true);
        String context = HudOutputCoordinator.etaStreetContext(options, 0);
        EtaStreetTextGate gate = new EtaStreetTextGate();

        DirectTbtPayload.Prepared initial = DirectTbtPayload.prepare(
                frame("Hidden old road", metrics(0), 13, 120, 1), options);
        DirectTbtPayload.Prepared first = selected(
                gate, initial, "Hidden old road", context, true, 0);
        gate.onSent(first.displayText(), 0);
        DirectTbtPayload.Prepared changedEta = DirectTbtPayload.prepare(
                frame("Hidden old road", metrics(1), 13, 120, 1), options);
        assertEquals(initial.displayText(), selected(
                gate, changedEta, "Hidden old road", context, true, 100).displayText());

        DirectTbtPayload.Prepared sameVisibleText = DirectTbtPayload.prepare(
                frame("Hidden new road", metrics(0), 13, 120, 1), options);
        assertEquals(initial.displayText(), sameVisibleText.displayText());
        DirectTbtPayload.Prepared roadChanged = selected(
                gate, sameVisibleText, "Hidden new road", context, true, 200);
        gate.onSent(roadChanged.displayText(), 200);

        DirectTbtPayload.Prepared immediate = DirectTbtPayload.prepare(
                frame("Hidden new road", metrics(1), 13, 120, 1), options);
        assertEquals(immediate.displayText(), selected(
                gate, immediate, "Hidden new road", context, true, 201).displayText());
    }

    @Test
    public void separateWarningClearStillHonorsTheOriginalStreetChange() throws Exception {
        DirectTbtPayload.Options options = streetOptions(
                HudPrefs.ROUTE_METRICS_NEXT_STOP, 1, false, true)
                .withPresentation(presentation(0, 1, 1, false, true));
        String context = HudOutputCoordinator.etaStreetContext(options, 0);
        EtaStreetTextGate gate = new EtaStreetTextGate();
        DirectTbtPayload.Prepared warning = DirectTbtPayload.describe(
                frame("Same transformed road", metrics(0), 13, 120, 1)
                        .withAlertOverlay(DirectTbtFrame.AlertOverlay.active(
                                7, 25, "Camera", new byte[]{9})), options);
        assertTrue(warning.hasStreetEta());
        assertFalse(HudOutputCoordinator.requiresAlertClear(true));
        gate.select(warning.displayText(), "Original road A", context, true, 0);
        gate.onSent(warning.displayText(), 0);

        DirectTbtPayload.Prepared cleared = DirectTbtPayload.describe(
                frame("Same transformed road", metrics(1), 13, 111, 1), options);
        assertTrue(cleared.hasStreetEta());
        // Clearing only an independent warning does not restart the ETA pass.
        assertEquals(warning.displayText(), gate.select(cleared.displayText(),
                "Original road A", context, true, 100));
        // But the same callback carrying a new raw street must bypass it,
        // even when Replace hides the street and the transformed names match.
        assertEquals(cleared.displayText(), gate.select(cleared.displayText(),
                "Original road B", context, true, 101));

        String coordinator = source("HudOutputCoordinator.java");
        String clearMethod = coordinator.substring(
                coordinator.indexOf("void clearDirectAlertAndRepublish("),
                coordinator.indexOf("static boolean requiresAlertClear("));
        assertTrue(clearMethod.contains("directSourceRoad = sourceRoad == null ? \"\" : sourceRoad;"));
        assertEquals(2, occurrences(source("NavHudLiveSender.java"),
                "SystemClock.elapsedRealtime(), frame.getRoadText());"));
    }

    @Test
    public void nonStreetEtaCasesAndDisabledWaitBypassTheGate() {
        DirectTbtPayload.Options waiting = streetOptions(
                HudPrefs.ROUTE_METRICS_NEXT_STOP, 1, false, true);
        DirectTbtPayload.Prepared initial = DirectTbtPayload.prepare(
                frame(ROAD, metrics(0), 13, 120, 1), waiting);

        DirectTbtPayload.Options separateEtaOptions = options(
                HudPrefs.ROUTE_METRICS_NEXT_STOP, true, true, true, true, true,
                presentation(1, 0, 1, false, true));
        DirectTbtPayload.Prepared separateEta = DirectTbtPayload.describe(
                frame(ROAD, metrics(1), 13, 120, 1), separateEtaOptions);
        assertFalse(separateEta.hasStreetEta());
        assertBypasses(initial, waiting, separateEta, separateEtaOptions, true);

        DirectTbtPayload.Options noMetricsOptions = streetOptions(
                HudPrefs.ROUTE_METRICS_OFF, 1, false, true);
        DirectTbtPayload.Prepared noMetrics = DirectTbtPayload.describe(
                frame(ROAD, metrics(1), 13, 120, 1), noMetricsOptions);
        assertFalse(noMetrics.hasStreetEta());
        assertBypasses(initial, waiting, noMetrics, noMetricsOptions, true);

        DirectTbtPayload.Prepared sharedAlert = DirectTbtPayload.describe(
                frame(ROAD, metrics(1), 13, 120, 1).withAlertOverlay(
                        DirectTbtFrame.AlertOverlay.active(7, 25, "Camera", new byte[]{9})),
                waiting);
        assertFalse(sharedAlert.hasStreetEta());
        assertBypasses(initial, waiting, sharedAlert, waiting, true);

        DirectTbtPayload.Options waitDisabled = streetOptions(
                HudPrefs.ROUTE_METRICS_NEXT_STOP, 1, false, false);
        DirectTbtPayload.Prepared eligibleText = DirectTbtPayload.describe(
                frame(ROAD, metrics(1), 13, 120, 1), waitDisabled);
        assertTrue(eligibleText.hasStreetEta());
        assertBypasses(initial, waiting, eligibleText, waitDisabled, false);
    }

    @Test
    public void etaStreetContextTracksOnlyTextAffectingInputs() {
        DirectTbtPayload.Presentation basePresentation = presentation(0, 0, 0, false, true);
        DirectTbtPayload.Options base = options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                true, true, true, true, true, basePresentation);
        String expected = HudOutputCoordinator.etaStreetContext(base, 7);

        DirectTbtPayload.Options irrelevant = new DirectTbtPayload.Options(
                false, false, false, false, true, true, true,
                HudPrefs.ROUTE_METRICS_NEXT_STOP, true, true, true,
                HudPrefs.SPEED_LIMIT_COMPOSITE, HudPrefs.SPEED_LIMIT_FALLBACK_OFF,
                17, HudPrefs.SPEED_LIMIT_COMPOSITE_LANES_ONLY, 80, 42, null)
                .withPresentation(new DirectTbtPayload.Presentation(
                        0, 1, 0, false, 0xff010203, 0xff040506,
                        0xff070809, 0xff0a0b0c, false));
        assertEquals(expected, HudOutputCoordinator.etaStreetContext(irrelevant, 7));

        DirectTbtPayload.Options[] relevant = {
                base.withPresentation(presentation(0, 0, 1, false, true)),
                base.withPresentation(presentation(0, 0, 0, true, true)),
                base.withPresentation(presentation(1, 0, 0, false, true)),
                options(HudPrefs.ROUTE_METRICS_WHOLE_ROUTE,
                        true, true, true, true, true, basePresentation),
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        false, true, true, true, true, basePresentation),
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, false, true, true, true, basePresentation),
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, false, true, true, basePresentation),
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, true, false, true, basePresentation),
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, true, true, false, basePresentation)
        };
        for (DirectTbtPayload.Options changed : relevant) {
            assertNotEquals(expected, HudOutputCoordinator.etaStreetContext(changed, 7));
        }
        assertNotEquals(expected, HudOutputCoordinator.etaStreetContext(base, 8));
    }

    @Test
    public void coordinatorSourceKeepsGateAtTheSuccessfulTransportBoundary() throws Exception {
        String coordinator = source("HudOutputCoordinator.java");
        String sender = source("NavHudLiveSender.java");

        assertTrue(coordinator.contains("byte[] payload = buildPayload(source);"));
        assertTrue(coordinator.contains(
                "}\n            String displayText = etaStreetTextGate.select(candidateDirectPayload.displayText(),"));
        assertTrue(coordinator.contains(
                "preparedDirectPayload = candidateDirectPayload.withDisplayText(displayText);"));
        assertTrue(coordinator.contains(
                "preparedDirectSemanticPayload = preparedDirectPayload.build(0);"));
        assertTrue(coordinator.contains(
                "? preparedDirectSemanticPayload : payload;"));
        assertTrue(coordinator.indexOf("etaStreetTextGate.onSent(")
                > coordinator.indexOf("if (!isPayloadSuccessResult(result))"));
        assertEquals(2, occurrences(sender,
                "ownerPackage, sessionGeneration, frame.getRoadText());"));

        for (String reset : new String[]{
                "source-transition", "clear:", "transport-stop:", "transport-failure:",
                "service-unstarted", "producer-loss:", "owner-session"}) {
            assertTrue("missing reset " + reset,
                    coordinator.contains("resetEtaStreetText(\"" + reset));
        }
    }

    private static void assertBypasses(DirectTbtPayload.Prepared initial,
            DirectTbtPayload.Options initialOptions, DirectTbtPayload.Prepared candidate,
            DirectTbtPayload.Options candidateOptions, boolean wait) {
        EtaStreetTextGate gate = new EtaStreetTextGate();
        String initialContext = HudOutputCoordinator.etaStreetContext(initialOptions, 0);
        gate.select(initial.displayText(), ROAD, initialContext, true, 0);
        gate.onSent(initial.displayText(), 0);
        boolean eligible = wait && candidate.hasStreetEta();
        assertEquals(candidate.displayText(), gate.select(candidate.displayText(), ROAD,
                HudOutputCoordinator.etaStreetContext(candidateOptions, 0), eligible, 1));
    }

    private static DirectTbtPayload.Prepared selected(EtaStreetTextGate gate,
            DirectTbtPayload.Prepared candidate, String rawRoad, String context,
            boolean eligible, long nowMs) {
        return candidate.withDisplayText(
                gate.select(candidate.displayText(), rawRoad, context, eligible, nowMs));
    }

    private static DirectTbtPayload.Options streetOptions(
            int metricMode, int format, boolean ukrainian, boolean wait) {
        return options(metricMode, true, true, true, true, true,
                presentation(0, 0, format, ukrainian, wait));
    }

    private static DirectTbtPayload.Options options(int metricMode,
            boolean eta, boolean time, boolean remaining, boolean street, boolean cue,
            DirectTbtPayload.Presentation presentation) {
        return new DirectTbtPayload.Options(
                true, true, true, true, street, cue, false,
                metricMode, eta, time, remaining,
                HudPrefs.SPEED_LIMIT_OFF, HudPrefs.SPEED_LIMIT_FALLBACK_OFF, 5, null)
                .withPresentation(presentation);
    }

    private static DirectTbtPayload.Presentation presentation(int etaField, int warningField,
            int format, boolean ukrainian, boolean wait) {
        return new DirectTbtPayload.Presentation(etaField, warningField, format, ukrainian,
                0xffffffff, 0xffffffff, 0xffffffff, 0xffffff00, wait);
    }

    private static DirectTbtFrame frame(String road, DirectTbtFrame.TripMetrics metrics,
            int nativeManeuver, int distance, int laneCount) {
        List<DirectTbtFrame.Lane> lanes = new ArrayList<>();
        for (int index = 0; index < laneCount; index++) {
            lanes.add(new DirectTbtFrame.Lane(index % 2 == 0 ? 2 : 3,
                    index == 0, index % 2 == 0 ? "L" : "S"));
        }
        return new DirectTbtFrame(1, 2, nativeManeuver, distance, road, "Cue", road,
                new byte[]{1}, new byte[]{2}, lanes,
                DirectTbtFrame.AlertOverlay.inactive(), metrics);
    }

    private static DirectTbtFrame.TripMetrics metrics(int revision) {
        long nextHour = 1L + revision * 2L;
        long wholeHour = 2L + revision * 2L;
        DirectTbtFrame.TravelMetrics next = new DirectTbtFrame.TravelMetrics(
                nextHour * 3_600_000L, 0, 45_000L - revision * 600L,
                8_400L - revision * 100L);
        DirectTbtFrame.TravelMetrics whole = new DirectTbtFrame.TravelMetrics(
                wholeHour * 3_600_000L, 0, 90_000L - revision * 1_200L,
                17_000L - revision * 500L);
        return new DirectTbtFrame.TripMetrics(next, whole);
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("src/main/java/com/bydhud/app", name);
        if (!Files.exists(path)) path = Paths.get("app/src/main/java/com/bydhud/app", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        for (int index = 0; (index = source.indexOf(value, index)) >= 0; index += value.length()) {
            count++;
        }
        return count;
    }
}
