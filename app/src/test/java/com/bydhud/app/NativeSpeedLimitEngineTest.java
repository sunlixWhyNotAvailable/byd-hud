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
    @Test public void onlyNativeWithLimitRequestsIoAndNoDataLeavesSignAlone() {
        assertEquals(-1, NativeSpeedLimitEngine.requestedTarget(true, 0));
        assertEquals(60, NativeSpeedLimitEngine.requestedTarget(true, 60));
        assertEquals(-1, NativeSpeedLimitEngine.requestedTarget(false, 0));
        assertEquals(-1, NativeSpeedLimitEngine.requestedTarget(false, 60));
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

    @Test public void delayOffWaitsOneSecondAndDelayOnWaitsSixSeconds() {
        Fake off = new Fake();
        off.engine.setDelayEnabled(false);
        off.engine.configure("waze:1", 60);
        off.until(999);
        assertTrue(off.writes.isEmpty());
        off.until(1_000);
        assertEquals(1_000L, firstWriteAt(off));

        Fake on = new Fake();
        on.engine.configure("waze:1", 60);
        on.until(5_999);
        assertTrue(on.writes.isEmpty());
        on.until(6_000);
        assertEquals(6_000L, firstWriteAt(on));
    }

    @Test public void actualRawChangeRestartsDelayButSameTargetHeartbeatDoesNot() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60);
        f.at(4_000, () -> f.raw = 8);
        f.at(5_000, () -> f.engine.configure("waze:1", 60));
        f.until(9_999);
        assertTrue(f.writes.isEmpty());
        f.until(10_000);
        assertEquals(10_000L, firstWriteAt(f));
    }

    @Test public void targetMatchDuringDelayCancelsTheInitialWrite() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60);
        f.at(4_000, () -> f.raw = 13);
        f.until(20_000);
        assertTrue(f.writes.isEmpty());
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("delay cancel reason=target-match")));
    }

    @Test public void seriesKeepsRoadGapsAndSuccessfulSetterNeedsReadback() {
        Fake f = new Fake();
        f.apply = true;
        f.engine.configure("waze:1", 60);
        f.until(6_200);
        assertEquals(List.of("6000:1=7", "6100:2=60", "6200:1=6"), f.writes);
        assertEquals(13, f.raw);
        assertFalse(f.fallback);

        Fake unconfirmed = new Fake();
        unconfirmed.engine.configure("waze:1", 55);
        unconfirmed.until(9_099);
        assertFalse(unconfirmed.fallback);
        unconfirmed.until(9_100);
        assertFalse(unconfirmed.fallback);
        unconfirmed.until(9_101);
        assertTrue(unconfirmed.fallback);
        assertTrue(unconfirmed.logs.stream().anyMatch(line -> line.contains("outcome=unconfirmed")));
        assertFalse(unconfirmed.logs.stream().anyMatch(line -> line.contains("outcome=io_error")));
    }

    @Test public void matchAtThreeSecondBoundaryCountsAsConfirmed() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60);
        f.until(6_200);
        f.at(9_000, () -> { f.raw = 13; f.deferOperation = NativeSpeedLimitEngine.READ; });
        f.until(9_000);
        assertNotNull(f.pending);
        f.until(9_100);
        f.releasePending(true);
        assertFalse(f.fallback);
        f.until(9_101);
        assertFalse(f.fallback);
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("outcome=confirmed")));
        assertFalse(f.logs.stream().anyMatch(line -> line.contains("outcome=unconfirmed")));
    }

    @Test public void retryWaitsTenSecondsFromAttemptStartAndStopsAfterTwoAttempts() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 55);
        f.until(15_999);
        assertEquals(3, f.writes.size());
        f.until(16_200);
        assertEquals(List.of("6000:1=7", "6100:2=55", "6200:1=6",
                "16000:1=7", "16100:2=55", "16200:1=6"), f.writes);
        f.until(50_000);
        assertEquals(6, f.writes.size());
        assertEquals(1, countLogs(f, "outcome=exhausted"));
    }

    @Test public void equalReadbackNeedsNoWritesAndLaterDriftStartsANewCycle() {
        Fake f = new Fake();
        f.raw = 13;
        f.engine.configure("waze:1", 60);
        f.at(8_000, () -> { f.raw = 12; f.apply = true; });
        f.until(13_999);
        assertTrue(f.writes.isEmpty());
        f.until(14_200);
        assertEquals(List.of("14000:1=7", "14100:2=60", "14200:1=6"), f.writes);
        assertFalse(f.fallback);
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("cycle rearmed")));
    }

    @Test public void lateMatchCancelsRetryAndLaterDriftRearmsOnlyThen() {
        Fake f = new Fake();
        f.raw = 7;
        f.engine.configure("waze:1", 60);
        f.at(10_000, () -> f.raw = 13);
        f.at(18_000, () -> { f.raw = 12; f.apply = true; });
        f.until(9_100);
        assertFalse(f.fallback);
        f.until(9_101);
        assertTrue(f.fallback);
        f.until(10_000);
        assertFalse(f.fallback);
        assertEquals(3, f.writes.size());
        f.until(23_999);
        assertEquals(3, f.writes.size());
        f.until(24_200);
        assertEquals(List.of("6000:1=7", "6100:2=60", "6200:1=6",
                "24000:1=7", "24100:2=60", "24200:1=6"), f.writes);
    }

    @Test public void unchangedRawFiveAndLaterRawChangeCannotExceedTheCycleBudget() {
        Fake f = new Fake();
        f.raw = 5;
        f.engine.configure("waze:1", 55);
        f.until(19_101);
        assertEquals(6, f.writes.size());
        f.at(20_000, () -> f.raw = 6);
        f.until(80_000);
        assertEquals(6, f.writes.size());
        assertEquals(1, countLogs(f, "outcome=exhausted"));
    }

    @Test public void delayPreferenceChangeRecalculatesFromLastCauseWithoutResettingBudget() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60);
        f.until(4_000);
        assertTrue(f.writes.isEmpty());
        f.engine.setDelayEnabled(false);
        f.until(4_200);
        assertEquals(4_200L, firstWriteAt(f));

        Fake retry = new Fake();
        retry.engine.setDelayEnabled(false);
        retry.engine.configure("waze:1", 60);
        retry.until(4_101);
        assertTrue(retry.fallback);
        retry.engine.setDelayEnabled(true);
        retry.until(10_999);
        assertEquals(3, retry.writes.size());
        retry.until(11_200);
        assertEquals(6, retry.writes.size());
        retry.until(30_000);
        assertEquals(6, retry.writes.size());
    }

    @Test public void readErrorUsesBackoffWithoutTreatingItAsRawZeroOrAnAttempt() {
        Fake f = new Fake();
        f.readError = true;
        f.engine.configure("waze:1", 60);
        assertTrue(f.fallback);
        f.until(9_999);
        assertEquals(1, f.reads);
        assertTrue(f.writes.isEmpty());
        f.readError = false;
        f.raw = 5;
        f.until(15_999);
        assertTrue(f.writes.isEmpty());
        f.until(16_000);
        assertEquals(1, countWrites(f, NativeSpeedLimitEngine.ROAD));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("outcome=io_error")));
    }

    @Test public void readErrorDuringConfirmationDoesNotUndoAttemptOrEnableAThird() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60);
        f.until(6_000);
        f.readError = true;
        f.until(6_200);
        f.until(9_101);
        assertTrue(f.fallback);
        f.until(15_999);
        assertEquals(3, f.writes.size());
        f.readError = false;
        f.until(16_400);
        assertEquals(6, f.writes.size());
        f.until(30_000);
        assertEquals(6, f.writes.size());
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("outcome=unconfirmed")));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("outcome=io_error")));
    }

    @Test public void failedWriteStopsSeriesAndConsumesOneOfTwoAttempts() {
        Fake f = new Fake();
        f.failOperation = NativeSpeedLimitEngine.LIMIT;
        f.engine.configure("waze:1", 60);
        f.until(6_200);
        assertEquals(List.of("6000:1=7", "6100:2=60"), f.writes);
        assertTrue(f.fallback);
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("outcome=io_error")));
        f.failOperation = -1;
        f.until(16_300);
        assertEquals(5, f.writes.size());
        f.until(35_000);
        assertEquals(5, f.writes.size());
    }

    @Test public void slowReadStartsWaitWhenMismatchIsActuallyObserved() {
        Fake f = new Fake();
        f.deferOperation = NativeSpeedLimitEngine.READ;
        f.engine.configure("waze:1", 60);
        f.until(5_000);
        assertTrue(f.writes.isEmpty());
        f.releasePending(true);
        f.until(10_999);
        assertTrue(f.writes.isEmpty());
        f.until(11_000);
        assertEquals(11_000L, firstWriteAt(f));
    }

    @Test public void invalidNumericTargetFallsBackWithoutIoOrRetries() {
        for (int limit : new int[]{1, 2, 54, 135, 200}) {
            Fake f = new Fake();
            f.engine.configure("waze:1", limit);
            f.until(30_000);
            assertTrue(f.fallback);
            assertEquals(0, f.reads);
            assertTrue(f.writes.isEmpty());
        }
    }

    @Test public void stopCancelsFutureWritesAndDoesNotClearField() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60);
        f.until(50);
        f.engine.stop("HUD stopped");
        f.until(30_000);
        assertTrue(f.writes.isEmpty());
        assertFalse(f.fallback);
    }

    @Test public void newTargetAndSessionRetirePendingOperations() {
        Fake f = new Fake();
        f.engine.setDelayEnabled(false);
        f.engine.configure("waze:1", 60);
        f.until(50);
        f.apply = true;
        f.engine.configure("gmaps:2", 55);
        f.until(1_400);
        assertEquals(List.of("1050:1=7", "1150:2=55", "1250:1=6"), f.writes);
        assertEquals(12, f.raw);
    }

    @Test public void lateIoCallbackCannotRestartCancelledAttempt() {
        Fake f = new Fake();
        f.deferOperation = NativeSpeedLimitEngine.ROAD;
        f.engine.configure("waze:1", 60);
        f.until(6_000);
        Consumer<NativeSpeedLimitEngine.Result> late = f.pending.callback;
        BooleanSupplier current = f.pending.current;
        f.engine.stop("Shanghai or source stop");
        assertFalse(current.getAsBoolean());
        late.accept(new NativeSpeedLimitEngine.Result(true, 0, 6_000, 6_050, ""));
        f.until(30_000);
        assertEquals(1, f.writes.size());
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("stale=true")));
    }

    @Test public void sameInputDoesNotRestartAndBoundaryRejectsArbitraryWrites() {
        Fake f = new Fake();
        f.engine.configure("waze:1", 60);
        f.until(5_000);
        f.engine.configure("waze:1", 60);
        f.until(6_200);
        assertEquals(3, f.writes.size());
        assertFalse(NativeSpeedLimitEngine.validOperation(3, 60));
        assertFalse(NativeSpeedLimitEngine.validOperation(1, 8));
        assertFalse(NativeSpeedLimitEngine.validOperation(2, 0));
        assertFalse(NativeSpeedLimitEngine.validOperation(2, 54));
        assertFalse(NativeSpeedLimitEngine.validOperation(2, 1));
        assertTrue(NativeSpeedLimitEngine.validOperation(2, 130));
    }

    private static long firstWriteAt(Fake fake) {
        return Long.parseLong(fake.writes.get(0).substring(0, fake.writes.get(0).indexOf(':')));
    }

    private static int countWrites(Fake fake, int operation) {
        String marker = ":" + operation + "=";
        return (int) fake.writes.stream().filter(write -> write.contains(marker)).count();
    }

    private static int countLogs(Fake fake, String marker) {
        return (int) fake.logs.stream().filter(line -> line.contains(marker)).count();
    }

    private static final class Task {
        final long at, id;
        final Runnable run;
        Task(long at, long id, Runnable run) { this.at = at; this.id = id; this.run = run; }
    }

    private static final class Pending {
        final int operation;
        final int value;
        final int raw;
        final long startedAt;
        final BooleanSupplier current;
        final Consumer<NativeSpeedLimitEngine.Result> callback;
        Pending(int operation, int value, int raw, long startedAt,
                BooleanSupplier current, Consumer<NativeSpeedLimitEngine.Result> callback) {
            this.operation = operation;
            this.value = value;
            this.raw = raw;
            this.startedAt = startedAt;
            this.current = current;
            this.callback = callback;
        }
    }

    private static final class Fake implements NativeSpeedLimitEngine.Port {
        long now, order;
        int raw, pendingLimit, reads, failOperation = -1, deferOperation = -1;
        boolean apply, fallback, readError;
        Pending pending;
        final List<String> writes = new ArrayList<>(), logs = new ArrayList<>();
        final PriorityQueue<Task> queue = new PriorityQueue<>(Comparator
                .comparingLong((Task task) -> task.at).thenComparingLong(task -> task.id));
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
                if (operation == NativeSpeedLimitEngine.LIMIT) pendingLimit = value;
            }
            if (operation == deferOperation) {
                deferOperation = -1;
                pending = new Pending(operation, value, raw, now, current, callback);
                return;
            }
            complete(operation, value, raw, now, current, callback,
                    operation != failOperation && !(operation == NativeSpeedLimitEngine.READ && readError));
        }

        void complete(int operation, int value, int sampledRaw, long startedAt,
                BooleanSupplier current, Consumer<NativeSpeedLimitEngine.Result> callback,
                boolean success) {
            if (success && apply && operation == NativeSpeedLimitEngine.ROAD && value == 6) {
                raw = pendingLimit / 5 + 1;
            }
            callback.accept(new NativeSpeedLimitEngine.Result(success, sampledRaw,
                    startedAt, now, success ? "" : "unavailable"));
        }

        void releasePending(boolean success) {
            Pending call = pending;
            assertNotNull(call);
            pending = null;
            complete(call.operation, call.value, call.raw, call.startedAt,
                    call.current, call.callback, success);
        }

        void at(long time, Runnable action) { queue.add(new Task(time, ++order, action)); }

        void until(long end) {
            int guard = 0;
            while (!queue.isEmpty() && queue.peek().at <= end) {
                assertTrue("unbounded scheduler", guard++ < 10_000);
                Task task = queue.poll();
                now = task.at;
                task.run.run();
            }
            now = end;
        }
    }
}
