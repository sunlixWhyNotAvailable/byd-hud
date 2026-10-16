package com.bydhud.navexport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WazeManeuverRecoveryTest {
    @Test public void retriesAtTwoThenFiveSecondsAndStopsAfterThreeAttempts() {
        WazeManeuverRecovery recovery = new WazeManeuverRecovery();

        assertEquals(1, recovery.beginAttempt());
        recovery.finishAttempt(1_000L, true);
        assertFalse(recovery.retryDue(2_999L));
        assertTrue(recovery.retryDue(3_000L));
        assertEquals(2_000L, recovery.retryDelayMs(1_000L));

        assertEquals(2, recovery.beginAttempt());
        recovery.finishAttempt(3_000L, true);
        assertFalse(recovery.retryDue(7_999L));
        assertTrue(recovery.retryDue(8_000L));

        assertEquals(3, recovery.beginAttempt());
        recovery.finishAttempt(8_000L, true);
        assertEquals(-1L, recovery.retryDelayMs(8_000L));
        assertEquals(0, recovery.beginAttempt());

        recovery.reset();
        assertEquals(1, recovery.beginAttempt());
        recovery.finishAttempt(20_000L, false);
        assertEquals(-1L, recovery.retryDelayMs(20_000L));
    }

    @Test public void countsOnlyMissingZeroCoordinatePairs() {
        RouteData.Maneuver missing = maneuver(0, 0);
        RouteData.Maneuver position = maneuver(49.5, 0);
        RouteData.Maneuver turn = maneuver(49.5, 30.5);

        assertEquals(1, WazeManeuverRecovery.missingCoordinateCount(
                new RouteData.Maneuver[] {missing, position, turn}));
        assertTrue(WazeManeuverRecovery.needsRetry(new RouteData.Maneuver[0]));
        assertTrue(WazeManeuverRecovery.needsRetry(new RouteData.Maneuver[] {missing}));
        assertFalse(WazeManeuverRecovery.needsRetry(new RouteData.Maneuver[] {turn}));
    }

    @Test public void keepsPriorUsefulCoordinatesWhenARepeatIsLessComplete() {
        RouteData.Maneuver[] prior = {
                maneuver("1:2", 49.5, 30.5), maneuver("3:4", 0, 0)};
        RouteData.Maneuver[] received = {
                maneuver("1:2", 0, 0), maneuver("3:4", 0, 0)};

        assertTrue(prior == WazeManeuverRecovery.preserveUsefulResponse(prior, received));
        assertEquals(1, WazeManeuverRecovery.missingCoordinateCount(prior));

        RouteData.Maneuver[] priorWithoutCoordinates = {maneuver("5:6", 0, 0)};
        assertTrue(priorWithoutCoordinates == WazeManeuverRecovery.preserveUsefulResponse(
                priorWithoutCoordinates, new RouteData.Maneuver[0]));

        RouteData.Maneuver[] priorWithTwoCoordinates = {
                maneuver("old-a", 49.5, 30.5), maneuver("old-b", 49.6, 30.6),
                maneuver("old-c", 0, 0)};
        RouteData.Maneuver[] changedPartial = {
                maneuver("new-a", 49.5, 30.5), maneuver("new-b", 0, 0)};
        assertTrue(priorWithTwoCoordinates == WazeManeuverRecovery.preserveUsefulResponse(
                priorWithTwoCoordinates, changedPartial));

        RouteData.Maneuver[] completeShorter = {maneuver("complete", 49.7, 30.7)};
        assertTrue(completeShorter == WazeManeuverRecovery.preserveUsefulResponse(
                priorWithTwoCoordinates, completeShorter));
    }

    private static RouteData.Maneuver maneuver(double latitude, double longitude) {
        return maneuver("", latitude, longitude);
    }

    private static RouteData.Maneuver maneuver(String id, double latitude, double longitude) {
        RouteData.Maneuver value = new RouteData.Maneuver();
        value.id = id;
        value.latitude = latitude;
        value.longitude = longitude;
        return value;
    }
}
