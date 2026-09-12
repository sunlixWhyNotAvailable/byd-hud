package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class TaskMoveSequencerTest {
    @Test
    public void stableMoveUsesOnePreQueryOneConfirmationAndOneCommand() throws Exception {
        FakeIo io = new FakeIo(state(42, 8));
        TaskMoveSequencer.Result result = TaskMoveSequencer.execute(
                state(42, 0), 8, () -> true, io);
        assertEquals(List.of("move:command", "query:confirm"), io.events);
        assertEquals(2, result.queries);
        assertEquals(1, result.moves);
        assertEquals(8, result.state.displayId);
        assertTrue(result.current);
        assertTrue(result.error.isEmpty());
    }

    @Test
    public void uncertainMoveReadsBackBeforeResending() throws Exception {
        FakeIo io = new FakeIo(state(42, 0), state(42, 8));
        io.failFirstMove = true;
        TaskMoveSequencer.Result result = TaskMoveSequencer.execute(
                state(42, 0), 8, () -> true, io);
        assertEquals(List.of("move:command", "query:uncertain-readback",
                "move:retry-after-readback", "query:confirm"), io.events);
        assertEquals(3, result.queries);
        assertEquals(2, result.moves);
    }

    @Test
    public void cancellationAfterUncertainReadbackPreventsResend() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true);
        FakeIo io = new FakeIo(state(42, 0));
        io.failFirstMove = true;
        io.afterQuery = () -> current.set(false);
        TaskMoveSequencer.Result result = TaskMoveSequencer.execute(
                state(42, 0), 8, current::get, io);
        assertEquals(List.of("move:command", "query:uncertain-readback"), io.events);
        assertEquals(1, result.moves);
        assertFalse(result.current);
    }

    @Test
    public void uncertainRetryAppliedIsAcceptedByFinalReadbackWithoutThirdMove() throws Exception {
        FakeIo io = new FakeIo(state(42, 0), state(42, 8));
        io.failMoveCount = 2;
        TaskMoveSequencer.Result result = TaskMoveSequencer.execute(
                state(42, 0), 8, () -> true, io);
        assertEquals(List.of("move:command", "query:uncertain-readback",
                "move:retry-after-readback", "query:retry-uncertain-readback"), io.events);
        assertEquals(3, result.queries);
        assertEquals(2, result.moves);
        assertTrue(result.error.isEmpty());
        assertEquals(8, result.state.displayId);
    }

    @Test
    public void uncertainRetryNotAppliedFailsAfterFinalReadbackWithoutThirdMove() throws Exception {
        FakeIo io = new FakeIo(state(42, 0), state(42, 0));
        io.failMoveCount = 2;
        TaskMoveSequencer.Result result = TaskMoveSequencer.execute(
                state(42, 0), 8, () -> true, io);
        assertEquals(List.of("move:command", "query:uncertain-readback",
                "move:retry-after-readback", "query:retry-uncertain-readback"), io.events);
        assertEquals(3, result.queries);
        assertEquals(2, result.moves);
        assertEquals("confirmation failed", result.error);
        assertEquals(0, result.state.displayId);
    }

    @Test
    public void lateConfirmationIsObservedButFencedFromCurrentCompletion() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true);
        FakeIo io = new FakeIo(state(42, 8));
        io.afterQuery = () -> current.set(false);
        TaskMoveSequencer.Result result = TaskMoveSequencer.execute(
                state(42, 0), 8, current::get, io);
        assertEquals(2, result.queries);
        assertEquals(1, result.moves);
        assertFalse(result.current);
        assertEquals(8, result.state.displayId);
    }

    @Test
    public void unknownAdmissionCannotIssueABlindMove() throws Exception {
        FakeIo io = new FakeIo(state(42, 8));
        TaskMoveSequencer.Result result = TaskMoveSequencer.execute(
                state(42, NavAppDisplayState.DISPLAY_UNKNOWN), 8, () -> true, io);
        assertTrue(io.events.isEmpty());
        assertEquals(0, result.moves);
        assertFalse(result.current);
        assertEquals("task/display state unknown", result.error);
    }

    private static NavAppDisplayState state(int taskId, int displayId) {
        return new NavAppDisplayState("com.waze", taskId, displayId, true, "test");
    }

    private static final class FakeIo implements TaskMoveSequencer.Io {
        final List<String> events = new ArrayList<>();
        final NavAppDisplayState[] queries;
        int queryIndex;
        boolean failFirstMove;
        int failMoveCount;
        int moveCount;
        Runnable afterQuery = () -> { };

        FakeIo(NavAppDisplayState... queries) {
            this.queries = queries;
        }

        @Override
        public NavAppDisplayState query(String reason) {
            events.add("query:" + reason);
            NavAppDisplayState result = queries[Math.min(queryIndex++, queries.length - 1)];
            afterQuery.run();
            return result;
        }

        @Override
        public TaskMoveSequencer.CommandResult move(String reason) throws IOException {
            events.add("move:" + reason);
            int attempt = moveCount++;
            if ((failFirstMove && attempt == 0) || attempt < failMoveCount) {
                throw new IOException("uncertain");
            }
            return new TaskMoveSequencer.CommandResult(true, "");
        }
    }
}
