package com.bydhud.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public final class NativeSpeedLimitEngineTest {
    @Test public void nativeIgnoresClearPolicyAndNoDataLeavesSignAlone() {
        for (int clear = 0; clear <= 2; clear++) {
            assertEquals(-1, NativeSpeedLimitEngine.requestedTarget(true, clear, 0));
            assertEquals(60, NativeSpeedLimitEngine.requestedTarget(true, clear, 60));
        }
        assertEquals(-1, NativeSpeedLimitEngine.requestedTarget(false, 0, 0));
        assertEquals(1, NativeSpeedLimitEngine.requestedTarget(false, 1, 0));
        assertEquals(-1, NativeSpeedLimitEngine.requestedTarget(false, 1, 60));
        assertEquals(1, NativeSpeedLimitEngine.requestedTarget(false, 2, 60));
        assertEquals(1, NativeSpeedLimitEngine.requestedTarget(false, 2, 0));
    }

    @Test public void fallbackModeRetainsOrdinaryRenderingAndOnlyReplacesFailedNative() {
        for (int primary = 0; primary <= 4; primary++) {
            assertEquals(primary, NativeSpeedLimitEngine.bitmapMode(primary, 4, false));
            assertEquals(primary, NativeSpeedLimitEngine.bitmapMode(primary, 4, true));
        }
        for (int fallback = 0; fallback <= 4; fallback++) {
            assertEquals(0, NativeSpeedLimitEngine.bitmapMode(5, fallback, false));
            assertEquals(fallback, NativeSpeedLimitEngine.bitmapMode(5, fallback, true));
        }
    }
    @Test public void writesRoadPairAroundLimitAndConfirmsWithoutResetOrThirdRoad() {
        Fake f = new Fake();
        f.apply = true;
        f.engine.configure("waze:1", 60, false);
        f.until(4_000);
        assertEquals(List.of("0:1=7", "100:2=60", "200:1=6"), f.writes);
        assertEquals(13, f.raw);
        assertFalse(f.fallback);
    }

    @Test public void timeoutIsThreeSecondsAfterLimitAndRetriesTenSecondsBetweenStarts() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 55, false);
        f.until(3_099);
        assertFalse(f.fallback);
        f.until(3_100);
        assertTrue(f.fallback);
        f.until(9_999);
        assertEquals(3, f.writes.size());
        f.until(10_200);
        assertEquals(List.of("0:1=7", "100:2=55", "200:1=6",
                "10000:1=7", "10100:2=55", "10200:1=6"), f.writes);
        assertTrue(f.fallback);
    }

    @Test public void unrelatedRawDoesNotConfirmButLateTargetRemovesFallback() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60, false);
        f.raw = 7;
        f.until(3_100);
        assertTrue(f.fallback);
        f.raw = 13;
        f.until(3_400);
        assertFalse(f.fallback);
        f.until(15_000);
        assertEquals(3, f.writes.size());
    }

    @Test public void alreadyMatchingRawNeedsNoWritesAndLaterMismatchRecovers() {
        Fake f = new Fake();
        f.raw = 13;
        f.engine.configure("waze:1", 60, false);
        f.until(600);
        assertTrue(f.writes.isEmpty());
        f.raw = 12;
        f.apply = true;
        f.until(1_000);
        assertEquals(List.of("800:1=7", "900:2=60", "1000:1=6"), f.writes);
    }

    @Test public void clearingUsesOneAndConfirmsRawOneWithoutRepeating() {
        Fake f = new Fake();
        f.raw = 13;
        f.apply = true;
        f.engine.configure("manual:1", 1, true);
        f.until(20_000);
        assertEquals(List.of("0:1=7", "100:2=1", "200:1=6"), f.writes);
        assertEquals(1, f.raw);
        assertFalse(f.fallback);
        f.raw = 7;
        f.until(20_600);
        assertEquals(6, f.writes.size());
    }

    @Test public void clearFailureRetriesButNeverEnablesBitmapFallback() {
        Fake f = new Fake();
        f.raw = 13;
        f.engine.configure("manual:1", 1, true);
        f.until(10_200);
        assertEquals(6, f.writes.size());
        assertFalse(f.fallback);
    }

    @Test public void invalidNumericTargetFallsBackWithoutIoOrRetries() {
        for (int limit : new int[]{1, 2, 54, 135, 200}) {
            Fake f = new Fake();
            f.engine.configure("waze:1", limit, false);
            f.until(30_000);
            assertTrue(f.fallback);
            assertEquals(0, f.reads);
            assertTrue(f.writes.isEmpty());
        }
    }

    @Test public void unavailableReadFallsBackWithoutTreatingErrorAsZero() {
        Fake f = new Fake();
        f.readError = true;
        f.engine.configure("waze:1", 60, false);
        assertTrue(f.fallback);
        f.until(9_999);
        assertEquals(1, f.reads);
        assertTrue(f.writes.isEmpty());
        f.readError = false;
        f.apply = true;
        f.until(10_200);
        assertEquals(13, f.raw);
        assertFalse(f.fallback);
    }

    @Test public void failedWriteDoesNotSendLaterSteps() {
        Fake f = new Fake();
        f.failOperation = NativeSpeedLimitEngine.LIMIT;
        f.engine.configure("waze:1", 60, false);
        f.until(500);
        assertEquals(List.of("0:1=7", "100:2=60"), f.writes);
        assertTrue(f.fallback);
    }

    @Test public void stopCancelsFutureWritesAndDoesNotClearField() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60, false);
        f.until(50);
        f.engine.stop("HUD stopped");
        f.until(30_000);
        assertEquals(List.of("0:1=7"), f.writes);
        assertFalse(f.fallback);
    }

    @Test public void newTargetAndSessionRetirePendingOperations() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60, false);
        f.until(50);
        f.apply = true;
        f.engine.configure("gmaps:2", 55, false);
        f.until(4_000);
        assertEquals(List.of("0:1=7", "50:1=7", "150:2=55", "250:1=6"), f.writes);
        assertEquals(12, f.raw);
    }

    @Test public void lateIoCallbackCannotRestartCancelledAttempt() {
        Fake f = new Fake();
        f.deferWrites = true;
        f.engine.configure("waze:1", 60, false);
        Consumer<NativeSpeedLimitEngine.Result> late = f.pending;
        BooleanSupplier current = f.pendingCurrent;
        f.engine.stop("Shanghai or source stop");
        assertFalse(current.getAsBoolean());
        late.accept(new NativeSpeedLimitEngine.Result(true, 0, 0, 50, ""));
        f.until(30_000);
        assertEquals(1, f.writes.size());
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("stale=true")));
    }

    @Test public void sameInputDoesNotRestartAndBoundaryRejectsArbitraryWrites() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60, false);
        f.until(50);
        f.engine.configure("waze:1", 60, false);
        f.until(250);
        assertEquals(3, f.writes.size());
        assertFalse(NativeSpeedLimitEngine.validOperation(3, 60));
        assertFalse(NativeSpeedLimitEngine.validOperation(1, 8));
        assertFalse(NativeSpeedLimitEngine.validOperation(2, 0));
        assertFalse(NativeSpeedLimitEngine.validOperation(2, 54));
        assertTrue(NativeSpeedLimitEngine.validOperation(2, 1));
        assertTrue(NativeSpeedLimitEngine.validOperation(2, 130));
    }

    private static final class Task {
        final long at, id;
        final Runnable run;
        Task(long at, long id, Runnable run) { this.at = at; this.id = id; this.run = run; }
    }

    private static final class Fake implements NativeSpeedLimitEngine.Port {
        long now, order;
        int raw, pendingLimit, reads, failOperation = -1;
        boolean apply, fallback, readError, deferWrites;
        Consumer<NativeSpeedLimitEngine.Result> pending;
        BooleanSupplier pendingCurrent;
        final List<String> writes = new ArrayList<>(), logs = new ArrayList<>();
        final PriorityQueue<Task> queue = new PriorityQueue<>(Comparator
                .comparingLong((Task t) -> t.at).thenComparingLong(t -> t.id));
        final NativeSpeedLimitEngine engine = new NativeSpeedLimitEngine(this);
        public long now() { return now; }
        public void post(Runnable task, long delay) { queue.add(new Task(now + delay, ++order, task)); }
        public void cancelTasks() { queue.clear(); }
        public void fallback(boolean value) { fallback = value; }
        public void log(String value) { logs.add(value); }
        public void call(int operation, int value, BooleanSupplier current,
                Consumer<NativeSpeedLimitEngine.Result> callback) {
            assertTrue(current.getAsBoolean());
            if (operation == NativeSpeedLimitEngine.READ) reads++;
            else {
                writes.add(now + ":" + operation + "=" + value);
                if (deferWrites) { pending = callback; pendingCurrent = current; return; }
                if (operation == NativeSpeedLimitEngine.LIMIT) pendingLimit = value;
                if (apply && operation == NativeSpeedLimitEngine.ROAD && value == 6) {
                    raw = pendingLimit == 1 ? 1 : pendingLimit / 5 + 1;
                }
            }
            boolean success = operation != failOperation && !(operation == 0 && readError);
            callback.accept(new NativeSpeedLimitEngine.Result(success, raw, now, now,
                    success ? "" : "unavailable"));
        }
        void until(long end) {
            int guard = 0;
            while (!queue.isEmpty() && queue.peek().at <= end) {
                assertTrue("unbounded scheduler", guard++ < 1_000);
                Task t = queue.poll();
                now = t.at;
                t.run.run();
            }
            now = end;
        }
    }
}
