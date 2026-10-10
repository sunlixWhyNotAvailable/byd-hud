package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.*;

public final class RoadInfoTxHistoryTest {
    @Test public void matchesDelayedEarlierPacketWithoutCallingItTheLatestValue() {
        RoadInfoTxHistory history = new RoadInfoTxHistory();
        RoadInfoDiagnosticFrame left = frame(1), right = frame(2);
        RoadInfoTxHistory.Attempt first = history.add(left, 100, 1000);
        first.complete(0);
        RoadInfoTxHistory.Attempt next = history.add(right, 200, 1100);
        assertSame(first, history.match(left, 250));
        assertSame(next, history.latest(250));
        assertFalse(left.matches(history.latest(250).frame));
        assertNull(history.match(right, 150));
        assertSame(first, history.latest(150));
    }

    @Test public void boundsOnlyTheComparisonHistoryAndExpiresOldMatches() {
        RoadInfoTxHistory history = new RoadInfoTxHistory();
        RoadInfoDiagnosticFrame left = frame(1), right = frame(2);
        history.add(left, 1, 1);
        assertNull(history.match(left, RoadInfoTxHistory.MAX_AGE_MS + 2));
        for (int n = 0; n < RoadInfoTxHistory.MAX_ENTRIES; n++) history.add(right, n + 2, n + 2);
        assertNull(history.match(left, 500));
        assertNotNull(history.match(right, 500));
    }

    @Test public void pendingFailedAndSuccessfulAttemptsRemainDistinguishable() {
        RoadInfoTxHistory history = new RoadInfoTxHistory();
        RoadInfoTxHistory.Attempt pending = history.add(frame(1), 1, 1);
        assertFalse(pending.completed);
        pending.complete(null);
        assertTrue(pending.completed);
        assertNull(pending.result);
        RoadInfoTxHistory.Attempt refused = history.add(frame(1), 2, 2);
        refused.complete(5);
        assertEquals(Integer.valueOf(5), history.match(frame(1), 3).result);
        RoadInfoTxHistory.Attempt success = history.add(frame(1), 4, 4);
        success.complete(0);
        assertEquals(Integer.valueOf(0), history.match(frame(1), 5).result);
    }

    private static RoadInfoDiagnosticFrame frame(int direction) {
        return RoadInfoDiagnosticFrame.parse(RoadInfoDiagnosticFrameTest.packet(direction, 100));
    }

    @Test public void receiveConfirmationWorksInEitherOrderAndOnlyOnceForSuccess() {
        RoadInfoTxHistory history = new RoadInfoTxHistory();
        java.util.concurrent.atomic.AtomicInteger confirmed = new java.util.concurrent.atomic.AtomicInteger();
        RoadInfoTxHistory.Attempt pending = history.add(frame(1), 1, 1);
        pending.observeReceive(confirmed::incrementAndGet);
        assertEquals(0, confirmed.get());
        pending.complete(0);
        pending.observeReceive(confirmed::incrementAndGet);
        assertEquals(1, confirmed.get());
        RoadInfoTxHistory.Attempt completed = history.add(frame(1), 2, 2);
        completed.complete(0);
        completed.observeReceive(confirmed::incrementAndGet);
        assertEquals(2, confirmed.get());
        RoadInfoTxHistory.Attempt failed = history.add(frame(1), 3, 3);
        failed.observeReceive(confirmed::incrementAndGet);
        failed.complete(5);
        assertEquals(2, confirmed.get());
    }
}
