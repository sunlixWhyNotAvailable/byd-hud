package com.bydhud.app;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One cancellable native-field operation, driven by the HUD owner's clock/worker. */
final class NativeSpeedLimitEngine {
    static final int READ = 0, ROAD = 1, LIMIT = 2;
    static final long ROAD_GAP_MS = 100L, READ_MS = 200L;
    static final long CONFIRM_MS = 3_000L, RETRY_MS = 10_000L;

    interface Port {
        long now();
        void post(Runnable task, long delayMs);
        void cancelTasks();
        void call(int operation, int value, BooleanSupplier current, Consumer<Result> callback);
        void fallback(boolean enabled);
        void log(String message);
    }

    static final class Result {
        final boolean success;
        final int raw;
        final long startedAt;
        final long finishedAt;
        final String error;

        Result(boolean success, int raw, long startedAt, long finishedAt, String error) {
            this.success = success;
            this.raw = raw;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.error = error == null ? "" : error.replace('\n', ' ').replace('\r', ' ');
        }
    }

    private final Port port;
    private volatile long epoch;
    private String session = "";
    private int target;
    private boolean enabled;
    private boolean fallback;
    private boolean awaiting;
    private long attempt;
    private long nextAttemptAt;
    private long confirmAt;

    NativeSpeedLimitEngine(Port port) { this.port = port; }

    static boolean supportedLimit(int limit) {
        return limit >= 5 && limit <= 130 && limit % 5 == 0;
    }

    static boolean validOperation(int operation, int value) {
        return operation == READ && value == 0
                || operation == ROAD && (value == 6 || value == 7)
                || operation == LIMIT && supportedLimit(value);
    }

    static int expectedRaw(int limit) {
        return limit / 5 + 1;
    }

    static int requestedTarget(boolean nativeMode, int navigationLimit) {
        return nativeMode && navigationLimit > 0 ? navigationLimit : -1;
    }

    static int bitmapMode(int primary, int configuredFallback, boolean failed) {
        return primary == 5 ? failed ? configuredFallback : 0 : primary;
    }

    void configure(String session, int target) {
        if (enabled && this.session.equals(session) && this.target == target) return;
        long token = retire("superseded");
        if (epoch != token) return;
        this.session = session;
        this.target = target;
        enabled = true;
        nextAttemptAt = 0L;
        log("configure expectedRaw=" + expectedRaw(target));
        if (!supportedLimit(target)) {
            setFallback(true, "unsupported-limit");
            return;
        }
        read(token);
    }

    void stop(String reason) {
        if (!enabled && !fallback) return;
        retire(reason);
    }

    private long retire(String reason) {
        if (enabled) log("cancel reason=" + reason);
        enabled = false;
        epoch++;
        long token = epoch;
        port.cancelTasks();
        awaiting = false;
        setFallback(false, reason);
        return token;
    }

    private boolean current(long token) { return enabled && token == epoch; }

    private void read(long token) {
        if (!current(token)) return;
        call(token, READ, 0, -1L, result -> {
            if (!result.success) {
                nextAttemptAt = Math.max(nextAttemptAt, port.now() + RETRY_MS);
                fail("read-error");
                // An unavailable getter is retried with recovery, not at5Hz forever.
                later(token, () -> read(token), RETRY_MS);
                return;
            }
            if (result.raw == expectedRaw(target)) {
                if (awaiting || fallback) log("confirmed raw=" + result.raw);
                awaiting = false;
                setFallback(false, "target-match");
            } else if (awaiting && port.now() >= confirmAt) {
                fail("confirmation-timeout raw=" + result.raw);
            }
            if (!awaiting && result.raw != expectedRaw(target)
                    && port.now() >= nextAttemptAt) {
                startAttempt(token);
            } else {
                later(token, () -> read(token), READ_MS);
            }
        });
    }

    private void startAttempt(long token) {
        attempt++;
        long startedAt = port.now();
        nextAttemptAt = startedAt + RETRY_MS;
        log("attempt startAt=" + startedAt + " roadX=7 roadY=6 gapMs=" + ROAD_GAP_MS);
        write(token, ROAD, 7, startedAt, x -> {
            long s1At = x.startedAt + ROAD_GAP_MS;
            later(token, () -> write(token, LIMIT, target, s1At, s1 -> {
                awaiting = true;
                confirmAt = s1.startedAt + CONFIRM_MS;
                long confirmingAttempt = attempt;
                later(token, () -> {
                    if (awaiting && attempt == confirmingAttempt) fail("confirmation-timeout");
                }, confirmAt - port.now());
                long yAt = s1.startedAt + ROAD_GAP_MS;
                later(token, () -> write(token, ROAD, 6, yAt, y -> read(token)),
                        yAt - port.now());
            }), s1At - port.now());
        });
    }

    private void write(long token, int operation, int value, long plannedAt,
            Consumer<Result> continuation) {
        call(token, operation, value, plannedAt, result -> {
            if (!result.success) {
                fail("write-error operation=" + operation);
                later(token, () -> read(token), READ_MS);
            } else continuation.accept(result);
        });
    }

    private void call(long token, int operation, int value, long plannedAt,
            Consumer<Result> continuation) {
        if (!current(token)) return;
        String callSession = session;
        long callAttempt = attempt;
        port.call(operation, value, () -> current(token), result -> {
            // Keep evidence of completed I/O even if its target was retired meanwhile.
            port.log("native_speed io session=" + callSession + " token=" + token
                    + " attempt=" + callAttempt + " operation=" + operation
                    + " fid=" + (operation == READ ? "0x2D500020" : operation == ROAD ? "0x4CA00050" : "0x4CA00040")
                    + " value=" + value
                    + " plannedAt=" + plannedAt + " actualAt=" + result.startedAt
                    + " finishedAt=" + result.finishedAt + " success=" + result.success
                    + " raw=" + (operation == READ && result.success ? result.raw : "unavailable")
                    + " decodedKmh=" + (operation == READ && result.success && result.raw >= 1
                    && result.raw <= 51 ? (result.raw - 1) * 5 : "unavailable")
                    + " stale=" + !current(token) + " error=" + result.error);
            if (current(token)) continuation.accept(result);
        });
    }

    private void later(long token, Runnable task, long delay) {
        port.post(() -> { if (current(token)) task.run(); }, Math.max(0L, delay));
    }

    private void fail(String reason) {
        awaiting = false;
        log("failure reason=" + reason + " retryNotBefore=" + nextAttemptAt);
        setFallback(true, reason);
    }

    private void setFallback(boolean value, String reason) {
        if (fallback == value) return;
        fallback = value;
        log("fallback=" + value + " reason=" + reason);
        port.fallback(value);
    }

    private void log(String text) {
        port.log("native_speed session=" + session + " token=" + epoch + " attempt=" + attempt
                + " target=" + target + " " + text);
    }
}
