package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Focused contract for serialized Waze template refreshes. */
public final class WazeTemplateRefreshGateTest {
    @Test
    public void burstCoalescesAndCompletionReleasesPendingRefresh() {
        WazeDirectChannel.TemplateRefreshGate gate =
                new WazeDirectChannel.TemplateRefreshGate();

        WazeDirectChannel.TemplateRefreshGate.Request first = gate.begin();
        assertNotNull(first);
        assertNull(gate.begin());
        assertNull(gate.begin());
        WazeDirectChannel.TemplateRefreshGate.Request followUp = gate.complete(first);
        assertNotNull(followUp);
        assertNull(gate.complete(first));
        assertNull(gate.complete(followUp));
        assertNotNull(gate.begin());
    }

    @Test
    public void resetRejectsStaleCompletionAndAllowsNewSessionRequest() {
        WazeDirectChannel.TemplateRefreshGate gate =
                new WazeDirectChannel.TemplateRefreshGate();

        WazeDirectChannel.TemplateRefreshGate.Request stale = gate.begin();
        assertNotNull(stale);
        gate.begin();
        gate.reset();

        WazeDirectChannel.TemplateRefreshGate.Request current = gate.begin();
        assertNotNull(current);
        assertNotSame(stale, current);
        assertNull(gate.complete(stale));
        assertNull(gate.begin());
        WazeDirectChannel.TemplateRefreshGate.Request followUp = gate.complete(current);
        assertNotNull(followUp);
        assertNull(gate.complete(followUp));
    }

    @Test
    public void binderCallbacksRequireTheSameActiveSession() {
        assertTrue(WazeDirectChannel.callbackMatchesSession(7, 7, false));
        assertFalse(WazeDirectChannel.callbackMatchesSession(7, 7, true));
        assertFalse(WazeDirectChannel.callbackMatchesSession(7, 8, false));
    }
}
