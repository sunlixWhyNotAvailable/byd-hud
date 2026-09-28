package com.bydhud.app;

import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

/** Synthetic route data sent through the production manual and RoadInfo paths. */
final class HudMapLiveFixture {
    static final int SPEED_LIMIT_KPH = 75;
    static final long REMAINING_SECONDS = 99L * 60L * 60L + 59L * 60L;
    static final long REMAINING_METERS = 88_800L;

    private HudMapLiveFixture() { }

    static HudState manualState() {
        HudState state = new HudState();
        state.distanceToIntersection = 155;
        state.maneuverId = 2;
        state.currentMaxSpeedLimit = SPEED_LIMIT_KPH;
        state.currentSpeed = 0;
        state.carToDestination = (int) REMAINING_METERS;
        state.timeToDestination = 5_999;
        state.numOfLanes = 4;
        state.roadName = "Typical Street";
        state.directionText = "Turn right";
        state.laneString = "R|R*|L|L";
        state.includeNativeArrow = true;
        state.includeLaneBitmap = true;
        return state;
    }

    static DirectTbtFrame frame(long nowWallTimeMs, long nowElapsedMs) {
        Calendar eta = Calendar.getInstance();
        eta.setTimeInMillis(nowWallTimeMs);
        eta.set(Calendar.HOUR_OF_DAY, 18);
        eta.set(Calendar.MINUTE, 45);
        eta.set(Calendar.SECOND, 0);
        eta.set(Calendar.MILLISECOND, 0);
        long arrival = eta.getTimeInMillis();
        int offsetSeconds = TimeZone.getDefault().getOffset(arrival) / 1000;
        DirectTbtFrame.TravelMetrics metrics = new DirectTbtFrame.TravelMetrics(
                arrival, offsetSeconds, REMAINING_SECONDS, REMAINING_METERS, nowWallTimeMs);
        return new DirectTbtFrame(0, 3, 2, 155,
                "Typical Street", "Turn right", "Typical Street",
                null, null, Arrays.asList(
                        new DirectTbtFrame.Lane(3, false, "R"),
                        new DirectTbtFrame.Lane(3, true, "R"),
                        new DirectTbtFrame.Lane(2, false, "L"),
                        new DirectTbtFrame.Lane(2, false, "L")),
                DirectTbtFrame.AlertOverlay.inactive(),
                new DirectTbtFrame.TripMetrics(metrics, metrics),
                new DirectTbtFrame.SpeedLimit(SPEED_LIMIT_KPH, SPEED_LIMIT_KPH, "km/h", nowElapsedMs),
                3, 0);
    }
}
