package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public final class GMapsEtaMetricTest {
    @Test
    public void durationRemainsRawAndSharedResolverOwnsArrivalSynthesis() {
        long sampleWallMs = 1_800_000_045_678L;

        DirectTbtFrame.TravelMetrics raw = GMapsDirectChannel.travelMetrics(
                601L, 5_100L, sampleWallMs);

        assertEquals(-1L, raw.getArrivalTimeEpochMs());
        assertEquals(601L, raw.getRemainingTimeSeconds());
        assertEquals(5_100L, raw.getRemainingDistanceMeters());
        assertEquals(sampleWallMs, raw.getSampleWallTimeEpochMs());
        HudEtaResolver.Target effective = HudEtaResolver.resolve(
                DirectTbtFrame.TripMetrics.nextStopOnly(raw), sampleWallMs).nextStop;
        assertEquals((HudEtaResolver.epochMinute(sampleWallMs) + 11L) * 60_000L,
                effective.effective.getArrivalTimeEpochMs());
        assertEquals(HudEtaResolver.Provenance.DERIVED, effective.arrivalProvenance);
    }

    @Test
    public void channelAnchorsBothTargetsFromTheSameFreshIngressSample() {
        long sampleWallMs = 1_800_000_045_678L;
        Map<String, Object> summary = new HashMap<>();
        summary.put("nextStopRemainingSeconds", 61L);
        summary.put("nextStopRemainingDistanceMeters", 500L);
        summary.put("wholeRouteRemainingSeconds", 601L);
        summary.put("wholeRouteRemainingDistanceMeters", 5_100L);

        DirectTbtFrame.TripMetrics raw = GMapsDirectChannel.tripMetrics(
                summary, sampleWallMs);
        HudEtaResolver.Result effective = HudEtaResolver.resolve(raw, sampleWallMs);

        assertEquals(sampleWallMs, raw.getNextStop().getSampleWallTimeEpochMs());
        assertEquals(sampleWallMs, raw.getWholeRoute().getSampleWallTimeEpochMs());
        assertEquals((HudEtaResolver.epochMinute(sampleWallMs) + 2L) * 60_000L,
                effective.nextStop.effective.getArrivalTimeEpochMs());
        assertEquals((HudEtaResolver.epochMinute(sampleWallMs) + 11L) * 60_000L,
                effective.wholeRoute.effective.getArrivalTimeEpochMs());
    }
}
