package com.bydhud.app;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Pure command/readback sequence used by every package-level display move. */
final class TaskMoveSequencer {
    interface Io {
        NavAppDisplayState query(String reason) throws IOException;
        CommandResult move(String reason) throws IOException;
    }

    static final class CommandResult {
        final boolean success;
        final String detail;

        CommandResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail == null ? "" : detail;
        }
    }

    static final class Result {
        final NavAppDisplayState state;
        final int queries;
        final int moves;
        final boolean current;
        final String error;
        final long queryMs;
        final long moveMs;
        final long confirmationMs;

        Result(NavAppDisplayState state, int queries, int moves,
                boolean current, String error, long queryMs, long moveMs,
                long confirmationMs) {
            this.state = state;
            this.queries = queries;
            this.moves = moves;
            this.current = current;
            this.error = error == null ? "" : error;
            this.queryMs = queryMs;
            this.moveMs = moveMs;
            this.confirmationMs = confirmationMs;
        }
    }

    private TaskMoveSequencer() {
    }

    static Result execute(NavAppDisplayState admitted, int targetDisplay,
            BooleanSupplier requestCurrent, Io io) throws IOException {
        int queries = 1; // The admitted state is the operation's fresh pre-query.
        int moves = 0;
        long queryNanos = 0L;
        long moveNanos = 0L;
        long confirmationNanos = 0L;
        if (admitted == null || admitted.taskId < 0
                || admitted.displayId == NavAppDisplayState.DISPLAY_UNKNOWN) {
            return result(admitted, queries, moves, false, "task/display state unknown",
                    queryNanos, moveNanos);
        }
        if (!requestCurrent.getAsBoolean()) {
            return result(admitted, queries, moves, false, "request changed",
                    queryNanos, moveNanos);
        }
        CommandResult command;
        long moveStarted = System.nanoTime();
        try {
            moves++;
            command = io.move("command");
            moveNanos += System.nanoTime() - moveStarted;
        } catch (IOException uncertain) {
            moveNanos += System.nanoTime() - moveStarted;
            queries++;
            long started = System.nanoTime();
            NavAppDisplayState readback = io.query("uncertain-readback");
            queryNanos += System.nanoTime() - started;
            if (readback != null && readback.taskId == admitted.taskId
                    && readback.displayId == targetDisplay) {
                return result(readback, queries, moves,
                        requestCurrent.getAsBoolean(), "", queryNanos, moveNanos);
            }
            if (readback == null || readback.taskId != admitted.taskId
                    || readback.displayId == NavAppDisplayState.DISPLAY_UNKNOWN) {
                throw uncertain;
            }
            if (!requestCurrent.getAsBoolean()) {
                return result(readback, queries, moves, false, "request changed",
                        queryNanos, moveNanos);
            }
            moves++;
            started = System.nanoTime();
            try {
                command = io.move("retry-after-readback");
                moveNanos += System.nanoTime() - started;
            } catch (IOException retryUncertain) {
                moveNanos += System.nanoTime() - started;
                queries++;
                started = System.nanoTime();
                NavAppDisplayState finalReadback = io.query("retry-uncertain-readback");
                confirmationNanos += System.nanoTime() - started;
                boolean current = requestCurrent.getAsBoolean();
                boolean applied = current && finalReadback != null
                        && finalReadback.taskId == admitted.taskId
                        && finalReadback.displayId == targetDisplay;
                return result(finalReadback, queries, moves, current,
                        applied ? "" : current ? "confirmation failed" : "request changed",
                        queryNanos, moveNanos, confirmationNanos);
            }
        }
        if (!command.success) {
            return result(admitted, queries, moves,
                    requestCurrent.getAsBoolean(), command.detail, queryNanos, moveNanos);
        }
        queries++;
        long started = System.nanoTime();
        NavAppDisplayState confirmed = io.query("confirm");
        confirmationNanos += System.nanoTime() - started;
        boolean current = requestCurrent.getAsBoolean();
        String error = confirmed != null && confirmed.taskId == admitted.taskId
                && confirmed.displayId == targetDisplay
                ? "" : "confirmation failed";
        return result(confirmed, queries, moves, current, error,
                queryNanos, moveNanos, confirmationNanos);
    }

    private static Result result(NavAppDisplayState state, int queries, int moves,
            boolean current, String error, long queryNanos, long moveNanos) {
        return result(state, queries, moves, current, error, queryNanos, moveNanos, 0L);
    }

    private static Result result(NavAppDisplayState state, int queries, int moves,
            boolean current, String error, long queryNanos, long moveNanos,
            long confirmationNanos) {
        return new Result(state, queries, moves, current, error,
                queryNanos / 1_000_000L, moveNanos / 1_000_000L,
                confirmationNanos / 1_000_000L);
    }
}
