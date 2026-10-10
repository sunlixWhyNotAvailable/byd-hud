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

    @Test public void seriesKeepsRoadGapsAndSuccessfulSetterNeedsReadback() {
        Fake f = new Fake();
        f.apply = true;
        f.engine.configure("waze:1", 60);
        f.until(1_200);
        assertEquals(List.of("1000:1=7", "1100:2=60", "1200:1=6"), f.writes);
        assertEquals(13, f.raw);
        assertFalse(f.fallback);

        Fake unconfirmed = new Fake();
        unconfirmed.engine.configure("waze:1", 55);
        unconfirmed.until(4_099);
        assertFalse(unconfirmed.fallback);
        unconfirmed.until(4_100);
        assertFalse(unconfirmed.fallback);
        unconfirmed.until(4_101);
        assertTrue(unconfirmed.fallback);
        assertTrue(unconfirmed.logs.stream().anyMatch(line -> line.contains("outcome=unconfirmed")));
        assertFalse(unconfirmed.logs.stream().anyMatch(line -> line.contains("outcome=io_error")));
    }

    private static final class Task {
        final long at, id;
        final Runnable run;
        Task(long at, long id, Runnable run) { this.at = at; this.id = id; this.run = run; }
    }

    private static final class Fake implements NativeSpeedLimitEngine.Port {
        long now, order;
        int raw, pendingLimit;
        boolean apply, fallback;
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
            int sampledRaw = raw;
            if (operation != NativeSpeedLimitEngine.READ) {
                writes.add(now + ":" + operation + "=" + value);
                if (operation == NativeSpeedLimitEngine.LIMIT) pendingLimit = value;
            }
            if (apply && operation == NativeSpeedLimitEngine.ROAD && value == 6) {
                raw = pendingLimit / 5 + 1;
            }
            callback.accept(new NativeSpeedLimitEngine.Result(true, sampledRaw, now, now, ""));
        }

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
