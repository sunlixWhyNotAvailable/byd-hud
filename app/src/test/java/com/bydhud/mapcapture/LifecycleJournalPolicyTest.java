package com.bydhud.mapcapture;

import org.junit.Test;
import static org.junit.Assert.*;

public class LifecycleJournalPolicyTest {
    @Test public void basicKeepsFailuresAndLifecycleButDetailedIncludesSuccessfulTrace() {
        for (String event : new String[]{"hud_error", "hud_camera_trace_error", "hud_first_frame",
                "hud_stop", "hud_snapshot_timeout", "hud_camera_observation_recovered"})
            assertTrue(event, LifecycleJournalPolicy.shouldLog(false, event));
        for (String event : new String[]{"hud_camera_trace_sample", "hud_marker_sample",
                "hud_follow_input", "hud_route_applied", "hud_alert_boundary"}) {
            assertFalse(event, LifecycleJournalPolicy.shouldLog(false, event));
            assertTrue(event, LifecycleJournalPolicy.shouldLog(true, event));
        }
    }

    @Test public void acknowledgementCannotCrossTheActualSentPrefix() {
        assertTrue(LifecycleJournalPolicy.ackWithinBatch(8, 9, 2, 8, 9, 3));
        assertTrue(LifecycleJournalPolicy.ackWithinBatch(8, 0, 0, 8, 9, 3));
        assertFalse(LifecycleJournalPolicy.ackWithinBatch(9, 0, 0, 8, 9, 3));
        assertFalse(LifecycleJournalPolicy.ackWithinBatch(8, 9, 4, 8, 9, 3));
        assertFalse(LifecycleJournalPolicy.ackWithinBatch(8, 10, 1, 8, 9, 3));
        assertFalse(LifecycleJournalPolicy.ackWithinBatch(8, 8, 1, 8, 9, 3));
        assertFalse(LifecycleJournalPolicy.ackWithinBatch(8, 0, 1, 8, 9, 3));
        assertFalse(LifecycleJournalPolicy.ackWithinBatch(-1, 0, 0, 8, 9, 3));
    }
}
