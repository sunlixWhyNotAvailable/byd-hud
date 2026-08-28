package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import androidx.car.app.model.DateTimeWithZone;
import androidx.car.app.model.Distance;
import androidx.car.app.navigation.model.TravelEstimate;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

public final class WazeTravelEstimateTest {
    @Test
    public void singleDestinationBecomesNextStopAndCanFallBackForWholeRoute() {
        long arrivalTimeMs = 1_800_000_000_000L;
        TravelEstimate estimate = estimate(arrivalTimeMs, 660, 5.1);

        DirectTbtFrame.TripMetrics metrics =
                WazeDirectChannel.destinationMetrics(Collections.singletonList(estimate));

        assertEquals(arrivalTimeMs, metrics.getNextStop().getArrivalTimeEpochMs());
        assertEquals(0, metrics.getNextStop().getArrivalZoneOffsetSeconds());
        assertEquals(660, metrics.getNextStop().getRemainingTimeSeconds());
        assertEquals(5100, metrics.getNextStop().getRemainingDistanceMeters());
        assertFalse(metrics.getWholeRoute().hasAnyValue());
    }

    @Test
    public void multiStopDestinationsMapFirstToNextAndLastToWholeRoute() {
        TravelEstimate first = estimate(1_800_000_000_000L, 660, 5.1);
        TravelEstimate middle = estimate(1_800_100_000_000L, 1200, 10.2);
        TravelEstimate last = estimate(1_800_200_000_000L, 2400, 20.4);

        DirectTbtFrame.TripMetrics metrics = WazeDirectChannel.destinationMetrics(
                Arrays.asList(first, middle, last));

        assertEquals(1_800_000_000_000L,
                metrics.getNextStop().getArrivalTimeEpochMs());
        assertEquals(660, metrics.getNextStop().getRemainingTimeSeconds());
        assertEquals(5100, metrics.getNextStop().getRemainingDistanceMeters());
        assertEquals(1_800_200_000_000L,
                metrics.getWholeRoute().getArrivalTimeEpochMs());
        assertEquals(2400, metrics.getWholeRoute().getRemainingTimeSeconds());
        assertEquals(20400, metrics.getWholeRoute().getRemainingDistanceMeters());
    }

    private static TravelEstimate estimate(
            long arrivalTimeMs, long remainingSeconds, double kilometers) {
        return new TravelEstimate.Builder(
                Distance.create(kilometers, Distance.UNIT_KILOMETERS_P1),
                DateTimeWithZone.create(arrivalTimeMs, TimeZone.getTimeZone("UTC")))
                .setRemainingTimeSeconds(remainingSeconds)
                .build();
    }
}
