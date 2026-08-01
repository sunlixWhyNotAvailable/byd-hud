package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import androidx.car.app.model.DateTimeWithZone;
import androidx.car.app.model.Distance;
import androidx.car.app.navigation.model.TravelEstimate;

import org.junit.Test;

import java.util.TimeZone;

public final class WazeTravelEstimateTest {
    @Test
    public void destinationEstimateBecomesNextStopMetricsOnly() {
        long arrivalTimeMs = 1_800_000_000_000L;
        TravelEstimate estimate = new TravelEstimate.Builder(
                Distance.create(5.1, Distance.UNIT_KILOMETERS_P1),
                DateTimeWithZone.create(arrivalTimeMs, TimeZone.getTimeZone("UTC")))
                .setRemainingTimeSeconds(660)
                .build();

        DirectTbtFrame.TripMetrics metrics =
                WazeDirectChannel.destinationMetrics(estimate);

        assertEquals(arrivalTimeMs, metrics.getNextStop().getArrivalTimeEpochMs());
        assertEquals(0, metrics.getNextStop().getArrivalZoneOffsetSeconds());
        assertEquals(660, metrics.getNextStop().getRemainingTimeSeconds());
        assertEquals(5100, metrics.getNextStop().getRemainingDistanceMeters());
        assertFalse(metrics.getWholeRoute().hasAnyValue());
    }
}
