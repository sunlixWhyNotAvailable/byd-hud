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
        assertEquals(-1, NativeSpeedLimitEngine.requestedTarget(false, 1, 0));
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

    @Test public void clearOnceAlreadyClearAndSuccessUseOnlyOneOrderedSequence() {
        Fake precleared = new Fake();
        precleared.raw = 1;
        int[] preclearedDone = {0};
        precleared.engine.clearOnce("manual:end:1", () -> preclearedDone[0]++);
        precleared.until(10_000);
        assertEquals(1, preclearedDone[0]);
        assertTrue(precleared.writes.isEmpty());
        assertFalse(precleared.fallback);

        Fake success = new Fake();
        success.raw = 13;
        success.apply = true;
        int[] successDone = {0};
        success.engine.clearOnce("manual:end:2", () -> successDone[0]++);
        success.until(1_000);
        assertEquals(List.of("0:1=7", "100:2=1", "200:1=6"), success.writes);
        assertEquals(1, success.raw);
        assertEquals(1, successDone[0]);
        assertFalse(success.fallback);
    }

    @Test public void clearOnceReadAndWriteErrorsTerminateWithoutRetryOrFallback() {
        Fake readError = new Fake();
        readError.readError = true;
        int[] readDone = {0};
        readError.engine.clearOnce("manual:end:read-error", () -> readDone[0]++);
        readError.until(30_000);
        assertEquals(1, readError.reads);
        assertTrue(readError.writes.isEmpty());
        assertEquals(1, readDone[0]);
        assertFalse(readError.fallback);

        Fake writeError = new Fake();
        writeError.raw = 13;
        writeError.failOperation = NativeSpeedLimitEngine.LIMIT;
        int[] writeDone = {0};
        writeError.engine.clearOnce("manual:end:write-error", () -> writeDone[0]++);
        writeError.until(30_000);
        assertEquals(List.of("0:1=7", "100:2=1"), writeError.writes);
        assertEquals(1, writeDone[0]);
        assertFalse(writeError.fallback);
    }

    @Test public void clearOnceConfirmationTimesOutThreeSecondsAfterLimitStart() {
        Fake f = new Fake();
        f.raw = 13;
        int[] done = {0};
        f.engine.clearOnce("manual:end:timeout", () -> {
            done[0]++;
            f.completionAt = f.now;
        });
        f.until(4_000);
        assertEquals(List.of("0:1=7", "100:2=1", "200:1=6"), f.writes);
        assertEquals(1, done[0]);
        assertEquals(3_100L, f.completionAt);
        assertFalse(f.fallback);
        assertEquals(List.of(0L, 200L, 400L, 600L), f.readTimes.subList(0, 4));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("confirmation-timeout")));
    }

    @Test public void clearOnceMissingInitialOrFirstWriteCallbackStopsAtSixSeconds() {
        Fake initialReadMissing = new Fake();
        initialReadMissing.missingCallbackOperation = NativeSpeedLimitEngine.READ;
        int[] initialDone = {0};
        initialReadMissing.engine.clearOnce("manual:end:no-read-callback", () -> initialDone[0]++);
        initialReadMissing.until(6_000);
        assertTrue(initialReadMissing.writes.isEmpty());
        assertEquals(1, initialDone[0]);
        assertFalse(initialReadMissing.fallback);

        Fake firstWriteMissing = new Fake();
        firstWriteMissing.raw = 13;
        firstWriteMissing.missingCallbackOperation = NativeSpeedLimitEngine.ROAD;
        int[] writeDone = {0};
        firstWriteMissing.engine.clearOnce("manual:end:no-write-callback", () -> {
            writeDone[0]++;
            firstWriteMissing.completionAt = firstWriteMissing.now;
        });
        firstWriteMissing.until(6_000);
        assertEquals(List.of("0:1=7"), firstWriteMissing.writes);
        assertEquals(1, writeDone[0]);
        assertEquals(6_000L, firstWriteMissing.completionAt);
        assertFalse(firstWriteMissing.fallback);
    }

    @Test public void clearOnceMissingFinalRoadCallbackEndsAtLimitConfirmationDeadline() {
        Fake f = new Fake();
        f.raw = 13;
        f.deferWriteOperation = NativeSpeedLimitEngine.ROAD;
        f.deferWriteValue = 6;
        int[] done = {0};
        f.engine.clearOnce("manual:end:no-final-road-callback", () -> {
            done[0]++;
            f.completionAt = f.now;
        });
        f.until(4_000);
        assertEquals(List.of("0:1=7", "100:2=1", "200:1=6"), f.writes);
        assertEquals(1, done[0]);
        assertEquals(3_100L, f.completionAt);
        assertFalse(f.pendingCurrent.getAsBoolean());
    }

    @Test public void lateRawOneCallbackCannotConfirmAfterLimitDeadline() {
        Fake f = new Fake();
        f.raw = 13;
        f.apply = true;
        f.deferConfirmationRead = true;
        int[] done = {0};
        f.engine.clearOnce("manual:end:late-read", () -> {
            done[0]++;
            f.completionAt = f.now;
        });
        f.until(200);
        Consumer<NativeSpeedLimitEngine.Result> late = f.pending;
        BooleanSupplier lateCurrent = f.pendingCurrent;
        assertNotNull(late);
        f.until(3_100);
        assertEquals(1, done[0]);
        assertEquals(3_100L, f.completionAt);
        assertFalse(lateCurrent.getAsBoolean());
        late.accept(new NativeSpeedLimitEngine.Result(true, 1, f.pendingStartedAt, 3_101, ""));
        assertEquals(1, done[0]);
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("stale=true")
                && line.contains("operation=0")));
    }

    @Test public void clearOnceStopAndSupersedeCompleteOnceAndCancelPendingSteps() {
        Fake stopped = new Fake();
        stopped.raw = 13;
        int[] stoppedDone = {0};
        stopped.engine.clearOnce("manual:end:stop", () -> stoppedDone[0]++);
        stopped.until(50);
        stopped.engine.stop("HUD stopped");
        stopped.engine.stop("repeated stop");
        stopped.until(10_000);
        assertEquals(List.of("0:1=7"), stopped.writes);
        assertEquals(1, stoppedDone[0]);

        Fake superseded = new Fake();
        superseded.raw = 13;
        superseded.apply = true;
        int[] supersededDone = {0};
        superseded.engine.clearOnce("manual:end:superseded", () -> supersededDone[0]++);
        superseded.until(50);
        superseded.engine.configure("waze:replacement", 55, false);
        superseded.until(300);
        assertEquals(1, supersededDone[0]);
        assertEquals(List.of("0:1=7", "50:1=7", "150:2=55", "250:1=6"), superseded.writes);
    }

    @Test public void callbackCanInstallOperationDuringSupersedingConfigure() {
        Fake f = new Fake();
        f.raw = 13;
        f.missingCallbackOperation = NativeSpeedLimitEngine.ROAD;
        int[] done = {0};
        f.engine.clearOnce("manual:end:pending", () -> {
            done[0]++;
            f.engine.configure("callback-installed", 55, false);
        });
        f.until(10);
        f.missingCallbackOperation = -1;
        f.apply = true;
        f.engine.configure("outer-configure", 60, false);
        f.until(300);
        assertEquals(1, done[0]);
        assertEquals(List.of("0:1=7", "10:1=7", "110:2=55", "210:1=6"), f.writes);
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("session=callback-installed")
                && line.contains("target=55")));
        assertFalse(f.logs.stream().anyMatch(line -> line.contains("session=outer-configure")));
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
        int raw, pendingLimit, reads, failOperation = -1, missingCallbackOperation = -1;
        int deferWriteOperation = -1, deferWriteValue = -1;
        long completionAt = -1, pendingStartedAt;
        boolean apply, fallback, readError, deferWrites, deferConfirmationRead;
        Consumer<NativeSpeedLimitEngine.Result> pending;
        BooleanSupplier pendingCurrent;
        final List<String> writes = new ArrayList<>(), logs = new ArrayList<>();
        final List<Long> readTimes = new ArrayList<>();
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
            if (operation == NativeSpeedLimitEngine.READ) {
                reads++;
                readTimes.add(now);
            }
            else {
                writes.add(now + ":" + operation + "=" + value);
                if (deferWrites || operation == deferWriteOperation && value == deferWriteValue) {
                    pending = callback;
                    pendingCurrent = current;
                    pendingStartedAt = now;
                    return;
                }
                if (operation == NativeSpeedLimitEngine.LIMIT) pendingLimit = value;
                if (apply && operation == NativeSpeedLimitEngine.ROAD && value == 6) {
                    raw = pendingLimit == 1 ? 1 : pendingLimit / 5 + 1;
                }
            }
            boolean success = operation != failOperation && !(operation == 0 && readError);
            if (operation == missingCallbackOperation) return;
            if (operation == NativeSpeedLimitEngine.READ && reads > 1 && deferConfirmationRead) {
                pending = callback;
                pendingCurrent = current;
                pendingStartedAt = now;
                return;
            }
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
