package com.bydhud.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RoutineCountsTest {
    @Test public void keepsFirstOccurrenceAndSummarizesEveryTenSeconds() {
        AppEventLogger.RoutineCounts counts = new AppEventLogger.RoutineCounts();
        assertTrue(counts.record("waze.icon"));
        assertEquals("", counts.summary(0));
        assertFalse(counts.record("waze.icon"));
        assertTrue(counts.record("maps.bitmap"));
        assertEquals("", counts.summary(9999));
        assertEquals("{maps.bitmap=1, waze.icon=2}", counts.summary(10000));
        assertFalse(counts.record("waze.icon"));
        assertEquals("{waze.icon=1}", counts.summary(20000));
    }
}
