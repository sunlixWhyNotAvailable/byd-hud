package com.bydhud.mapcapture;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RepeatedFailureTest {
    @Test public void keepsFirstFailureAndSummarizesOnlyRepeatedEpisodes() {
        RepeatedFailure tracker = new RepeatedFailure();
        assertEquals("write: access denied", tracker.record("write: access denied"));
        assertNull(tracker.record("write: access denied"));
        assertNull(tracker.record("write: access denied"));
        assertEquals("failure_episode changed repeats=2\nwrite: disk full", tracker.record("write: disk full"));
        assertEquals("failure_episode recovered repeats=0", tracker.recovered());
        assertNull(tracker.recovered());
        assertEquals("write: disk full", tracker.record("write: disk full"));
    }
}
