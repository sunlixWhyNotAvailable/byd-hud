package com.bydhud.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class GMapsRegistrationScheduleTest {
    @Test public void readyBridgeStopsAfterThreeAttemptsAndCanRestart() {
        GMapsRegistrationSchedule schedule = new GMapsRegistrationSchedule();
        schedule.begin(1000L);
        assertEquals(4900L, schedule.afterAttempt(1100L, true));
        assertEquals(9900L, schedule.afterAttempt(6100L, true));
        assertEquals(-1L, schedule.afterAttempt(16100L, true));
        schedule.begin(30000L);
        assertEquals(5000L, schedule.afterAttempt(30000L, true));
    }

    @Test public void legacyBridgeKeepsSlowDiscoveryUntilReadyCapabilityArrives() {
        GMapsRegistrationSchedule schedule = new GMapsRegistrationSchedule();
        schedule.begin(0L);
        assertEquals(5000L, schedule.afterAttempt(0L, false));
        assertEquals(10000L, schedule.afterAttempt(5000L, false));
        assertEquals(60000L, schedule.afterAttempt(15000L, false));
        assertEquals(60000L, schedule.afterAttempt(75000L, false));
        assertEquals(-1L, schedule.afterAttempt(135000L, true));
    }
}
