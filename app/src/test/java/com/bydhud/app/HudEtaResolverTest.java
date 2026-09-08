package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.TimeZone;

import org.junit.Test;

public final class HudEtaResolverTest {
    private static final long MINUTE = 60_000L;

    @Test
    public void preservesBothSuppliedValuesEvenWhenTheyDisagree() {
        DirectTbtFrame.TravelMetrics raw = metrics(
                100 * MINUTE + 45_000L, 17, 601L, 5_100L, 90 * MINUTE + 12_000L);

        HudEtaResolver.Target result = resolve(raw, 95 * MINUTE).nextStop;

        assertSame(raw, result.source);
        assertEquals(100 * MINUTE + 45_000L, result.effective.getArrivalTimeEpochMs());
        assertEquals(601L, result.effective.getRemainingTimeSeconds());
        assertEquals(HudEtaResolver.Provenance.SOURCE, result.arrivalProvenance);
        assertEquals(HudEtaResolver.Provenance.SOURCE, result.durationProvenance);
    }

    @Test
    public void arrivalOnlySubtractsEpochMinutesAndNeverInventsTomorrow() {
        long arrival = 100 * MINUTE + 45_000L;
        HudEtaResolver.Target twoMinutes = resolve(
                metrics(arrival, 10_800, -1L, 470L, 98 * MINUTE + 45_000L),
                98 * MINUTE + 45_000L).nextStop;
        assertEquals(arrival, twoMinutes.effective.getArrivalTimeEpochMs());
        assertEquals(120L, twoMinutes.effective.getRemainingTimeSeconds());
        assertEquals(HudEtaResolver.Provenance.DERIVED, twoMinutes.durationProvenance);

        HudEtaResolver.Target currentMinute = resolve(
                metrics(arrival, 10_800, -1L, 470L, -1L),
                100 * MINUTE + 59_000L).nextStop;
        assertEquals(0L, currentMinute.effective.getRemainingTimeSeconds());

        HudEtaResolver.Target past = resolve(
                metrics(arrival, 10_800, -1L, 470L, -1L),
                101 * MINUTE).nextStop;
        assertEquals(-1L, past.effective.getRemainingTimeSeconds());
        assertEquals(HudEtaResolver.Provenance.UNAVAILABLE, past.durationProvenance);
        assertEquals(arrival, past.effective.getArrivalTimeEpochMs());
    }

    @Test
    public void durationOnlyUsesCeilingAndFreshSampleMinuteWithoutReplayDrift() {
        long sample = 1_439 * MINUTE + 45_000L;
        DirectTbtFrame.TravelMetrics raw = metrics(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                61L, 1_000L, sample);

        HudEtaResolver.Target initial = resolve(raw, sample).nextStop;
        HudEtaResolver.Target replay = resolve(raw, sample + 20 * MINUTE).nextStop;

        assertEquals(1_441 * MINUTE, initial.effective.getArrivalTimeEpochMs());
        assertEquals(initial.effective.getArrivalTimeEpochMs(),
                replay.effective.getArrivalTimeEpochMs());
        assertEquals(61L, initial.effective.getRemainingTimeSeconds());
        assertEquals(HudEtaResolver.Provenance.DERIVED, initial.arrivalProvenance);
        assertEquals(HudEtaResolver.Provenance.SOURCE, initial.durationProvenance);
    }

    @Test
    public void neitherTimeValueStaysUnavailableWhileDistanceSurvives() {
        HudEtaResolver.Target result = resolve(
                metrics(-1L, 0, -1L, 999L, 50 * MINUTE), 51 * MINUTE).nextStop;

        assertEquals(-1L, result.effective.getArrivalTimeEpochMs());
        assertEquals(-1L, result.effective.getRemainingTimeSeconds());
        assertEquals(999L, result.effective.getRemainingDistanceMeters());
        assertEquals(HudEtaResolver.Provenance.UNAVAILABLE, result.arrivalProvenance);
        assertEquals(HudEtaResolver.Provenance.UNAVAILABLE, result.durationProvenance);
    }

    @Test
    public void nextStopAndWholeRouteResolveIndependentlyBeforePreference() {
        long now = 200 * MINUTE + 59_000L;
        DirectTbtFrame.TravelMetrics next = metrics(
                203 * MINUTE + 1_000L, 0, -1L, 300L, now);
        DirectTbtFrame.TravelMetrics whole = metrics(
                -1L, DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                121L, 900L, 200 * MINUTE + 30_000L);

        HudEtaResolver.Result result = HudEtaResolver.resolve(
                new DirectTbtFrame.TripMetrics(next, whole), now);

        assertEquals(180L, result.nextStop.effective.getRemainingTimeSeconds());
        assertEquals(203 * MINUTE, result.wholeRoute.effective.getArrivalTimeEpochMs());
        assertEquals(121L, result.wholeRoute.effective.getRemainingTimeSeconds());
    }

    @Test
    public void hiddenArrivalStillCompletesVisibleDurationInBothLocales() {
        long now = 10 * MINUTE + 45_000L;
        DirectTbtFrame frame = frame(metrics(12 * MINUTE + 15_000L, 0,
                -1L, -1L, now));

        HudEtaText english = HudEtaText.from(frame, options(false), now);
        HudEtaText ukrainian = HudEtaText.from(frame, options(true), now);

        assertEquals("", english.arrival);
        assertEquals("2 min", english.duration);
        assertEquals("2 хв", ukrainian.duration);
        assertTrue(english.diagnostics.contains("nextArrival=source:"));
        assertTrue(english.diagnostics.contains("nextDuration=derived:120"));
    }

    @Test
    public void hiddenDurationStillCompletesVisibleArrivalAndKeepsStableCacheKey() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            long minute = 60_000L;
            long sample = 300L * minute + 45_000L;
            DirectTbtFrame frame = frame(metrics(-1L,
                    DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                    61L, -1L, sample));
            DirectTbtPayload.Options arrivalOnly = options(false, true, false);

            HudEtaText text = HudEtaText.from(frame, arrivalOnly, sample);

            assertEquals("05:02", text.arrival);
            assertEquals("", text.duration);
            assertEquals(DirectTbtPayload.etaCacheKey(frame, arrivalOnly, sample),
                    DirectTbtPayload.etaCacheKey(frame, arrivalOnly, sample + 30 * minute));
            assertTrue(text.diagnostics.contains("nextArrival=derived:"));
            assertTrue(text.diagnostics.contains("nextDuration=source:61"));
        } finally {
            TimeZone.setDefault(before);
        }
    }

    @Test
    public void cacheKeyChangesOnlyWhenAnArrivalDerivedVisibleDurationChanges() {
        long minute = 60_000L;
        long now = 400L * minute + 45_000L;
        DirectTbtFrame frame = frame(metrics(
                403L * minute + 15_000L, 0, -1L, -1L, now));
        DirectTbtPayload.Options durationOnly = options(false, false, true);
        DirectTbtPayload.Options allTimeHidden = options(false, false, false);

        assertFalse(DirectTbtPayload.etaCacheKey(frame, durationOnly, now).equals(
                DirectTbtPayload.etaCacheKey(frame, durationOnly, now + minute)));
        assertEquals(DirectTbtPayload.etaCacheKey(frame, allTimeHidden, now),
                DirectTbtPayload.etaCacheKey(frame, allTimeHidden, now + minute));
    }

    @Test
    public void metricAnchorSurvivesImmutableFrameReplayUpdates() {
        DirectTbtFrame frame = frame(metrics(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                61L, 1_000L, 500 * MINUTE + 58_000L));
        DirectTbtFrame replay = frame.withManeuverPng(new byte[]{9, 8, 7});

        assertSame(frame.getTripMetrics(), replay.getTripMetrics());
        assertEquals(500 * MINUTE + 58_000L,
                replay.getTripMetrics().getNextStop().getSampleWallTimeEpochMs());
        assertEquals(502 * MINUTE, HudEtaResolver.resolve(
                replay.getTripMetrics(), 700 * MINUTE)
                .nextStop.effective.getArrivalTimeEpochMs());
    }

    @Test
    public void semanticDirectConsumerGetsEffectiveMetricsWithoutMutatingRawFrame() {
        long minute = 60_000L;
        long now = 800L * minute + 45_000L;
        DirectTbtFrame gmapsRaw = frame(metrics(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                61L, 1_000L, now));
        DirectTbtFrame gmapsEffective = NavHudLiveSender.effectiveMetricFrame(gmapsRaw, now);
        assertEquals(-1L, gmapsRaw.getTripMetrics().getNextStop().getArrivalTimeEpochMs());
        assertEquals(802L * minute,
                gmapsEffective.getTripMetrics().getNextStop().getArrivalTimeEpochMs());

        DirectTbtFrame wazeRaw = frame(metrics(
                803L * minute + 15_000L, 0, -1L, 1_000L, now));
        DirectTbtFrame wazeEffective = NavHudLiveSender.effectiveMetricFrame(wazeRaw, now);
        assertEquals(-1L, wazeRaw.getTripMetrics().getNextStop().getRemainingTimeSeconds());
        assertEquals(180L,
                wazeEffective.getTripMetrics().getNextStop().getRemainingTimeSeconds());
    }

    private static HudEtaResolver.Result resolve(
            DirectTbtFrame.TravelMetrics metrics, long now) {
        return HudEtaResolver.resolve(DirectTbtFrame.TripMetrics.nextStopOnly(metrics), now);
    }

    private static DirectTbtFrame.TravelMetrics metrics(long arrival, int offset,
            long duration, long distance, long sample) {
        return new DirectTbtFrame.TravelMetrics(
                arrival, offset, duration, distance, sample);
    }

    private static DirectTbtFrame frame(DirectTbtFrame.TravelMetrics metrics) {
        return new DirectTbtFrame(1, 2, 13, 30, "Road", "Cue", "Road",
                new byte[]{1}, new byte[]{2}, Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive(),
                DirectTbtFrame.TripMetrics.nextStopOnly(metrics));
    }

    private static DirectTbtPayload.Options options(boolean ukrainian) {
        return options(ukrainian, false, true);
    }

    private static DirectTbtPayload.Options options(
            boolean ukrainian, boolean showArrival, boolean showDuration) {
        return new DirectTbtPayload.Options(true, true, true, true, true, true, false,
                HudPrefs.ROUTE_METRICS_NEXT_STOP, showArrival, showDuration, false,
                0, 0, 5, null).withPresentation(
                new DirectTbtPayload.Presentation(0, 0, 0, ukrainian,
                        -1, -1, -1, 0xffffff00));
    }
}
