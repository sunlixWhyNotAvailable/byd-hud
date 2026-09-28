package com.bydhud.app;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One cancellable native-field operation, driven by the HUD owner's clock/worker. */
final class NativeSpeedLimitEngine {
    static final int READ = 0, ROAD = 1, LIMIT = 2;
    static final long ROAD_GAP_MS = 100L, READ_MS = 200L;
    static final long CONFIRM_MS = 3_000L, RETRY_MS = 10_000L;
    static final long BASE_DELAY_MS = 1_000L, EXTRA_DELAY_MS = 5_000L;
    private static final int MAX_ATTEMPTS = 2;

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
    private long observationEpoch;
    private String session = "";
    private String lastReadLogKey = "";
    private String lastReadIoError = "";
    private int target;
    private int cycle;
    private int attempt;
    private int lastRaw;
    private int writtenRaw;
    private int confirmedRaw;
    private boolean enabled;
    private boolean delayEnabled = true;
    private boolean fallback;
    private boolean hasLastRaw;
    private boolean delayStarted;
    private boolean targetConfirmed;
    private boolean adasChangeDelay;
    private boolean seriesInFlight;
    private boolean awaiting;
    private boolean exhaustedLogged;
    private long delayStartedAt;
    private long delayDueAt;
    private long nextAttemptAt;
    private long nextReadAt;
    private long confirmAt;
    private long writtenAt = -1L;
    private int confirmCycle;
    private int confirmAttempt;

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

    void setDelayEnabled(boolean enabled) {
        if (delayEnabled == enabled) return;
        delayEnabled = enabled;
        if (!this.enabled || !delayStarted) return;
        delayDueAt = delayStartedAt + delayMs();
        log("delay reset reason=preference-change startedAt=" + delayStartedAt
                + " dueAt=" + delayDueAt + " enabled=" + delayEnabled);
    }

    void configure(String session, int target) {
        if (enabled && this.session.equals(session) && this.target == target) return;
        long token = retire("superseded");
        if (epoch != token) return;
        if (!this.session.equals(session)) clearObservedState();
        this.session = session;
        this.target = target;
        enabled = true;
        cycle = 1;
        attempt = 0;
        nextAttemptAt = 0L;
        nextReadAt = 0L;
        targetConfirmed = false;
        confirmAt = 0L;
        delayStarted = false;
        seriesInFlight = false;
        awaiting = false;
        exhaustedLogged = false;
        lastReadLogKey = "";
        lastReadIoError = "";
        log("configure cycle=1 target=" + target + " expectedRaw=" + expectedRaw(target));
        if (!supportedLimit(target)) {
            setFallback(true, "unsupported-limit");
            return;
        }
        read(token);
    }

    void stop(String reason) {
        clearObservedState();
        if (!enabled && !fallback) return;
        retire(reason);
    }

    void invalidateTarget() {
        // Fence stale I/O while keeping this session's last accepted navigator value.
        retire("input-changed");
    }

    private void clearObservedState() {
        observationEpoch++;
        hasLastRaw = false;
        writtenRaw = 0;
        writtenAt = -1L;
        confirmedRaw = 0;
        adasChangeDelay = false;
    }

    private long retire(String reason) {
        if (enabled) log("cancel reason=" + reason);
        enabled = false;
        epoch++;
        long token = epoch;
        port.cancelTasks();
        awaiting = false;
        seriesInFlight = false;
        delayStarted = false;
        targetConfirmed = false;
        setFallback(false, reason);
        return token;
    }

    private boolean current(long token) { return enabled && token == epoch; }

    private void read(long token) {
        if (!current(token)) return;
        long now = port.now();
        if (nextReadAt > now) {
            later(token, () -> read(token), nextReadAt - now);
            return;
        }
        call(token, READ, 0, -1L, result -> {
            if (!result.success) {
                nextReadAt = port.now() + RETRY_MS;
                if (!result.error.equals(lastReadIoError)) {
                    lastReadIoError = result.error;
                    log("outcome=io_error operation=" + READ + " error=" + result.error
                            + " retryAt=" + nextReadAt);
                }
                setFallback(true, "io-error-read");
                later(token, () -> read(token), nextReadAt - port.now());
                return;
            }
            nextReadAt = 0L;
            lastReadIoError = "";
            observeRead(result);
            if (shouldStartAttempt(port.now())) startAttempt(token);
            else later(token, () -> read(token), READ_MS);
        });
    }

    private void observeRead(Result result) {
        int raw = result.raw;
        boolean changed = hasLastRaw && raw != lastRaw;
        long observedAt = port.now();
        // A successful write can first appear in readback after the navigator target changes.
        if (writtenRaw != 0 && raw == writtenRaw) {
            confirmedRaw = raw;
            adasChangeDelay = false;
        }
        if (raw == expectedRaw(target)) {
            boolean hasWindow = confirmCycle == cycle && confirmAttempt == attempt && confirmAt > 0L;
            boolean withinWindow = hasWindow && result.finishedAt <= confirmAt;
            boolean missedWindow = awaiting && hasWindow && !withinWindow;
            if (missedWindow) {
                unconfirmed("confirmation-timeout", raw, confirmAt);
            }
            if (!targetConfirmed) {
                String outcome = attempt == 0 ? "observed-match"
                        : withinWindow ? "confirmed" : "late-match";
                log("outcome=" + outcome + " cycle=" + cycle + " attempt=" + attempt
                        + " raw=" + raw + " observedAt=" + observedAt
                        + " readFinishedAt=" + result.finishedAt
                        + " delayedCallback=" + (!awaiting && withinWindow));
            }
            awaiting = false;
            targetConfirmed = true;
            adasChangeDelay = false;
            if (delayStarted) {
                delayStarted = false;
                log("delay cancel reason=target-match actualAt=" + observedAt);
            }
            setFallback(false, "target-match");
        } else {
            if (changed && confirmedRaw != 0) adasChangeDelay = raw != confirmedRaw;
            if (targetConfirmed) {
                cycle++;
                attempt = 0;
                targetConfirmed = false;
                exhaustedLogged = false;
                log("cycle rearmed reason=adas-diverged cycle=" + cycle
                        + " raw=" + raw + " observedAt=" + observedAt);
                resetDelay(observedAt, "adas-diverged");
            } else if (!delayStarted || changed) {
                resetDelay(observedAt, changed ? "adas-changed" : "target-mismatch");
            }
            if (awaiting && result.finishedAt > confirmAt) {
                unconfirmed("confirmation-timeout", raw, confirmAt);
            }
        }
        lastRaw = raw;
        hasLastRaw = true;
    }

    private void resetDelay(long startedAt, String reason) {
        delayStarted = true;
        delayStartedAt = startedAt;
        delayDueAt = startedAt + delayMs();
        log("delay " + (reason.equals("target-mismatch") ? "scheduled" : "reset")
                + " reason=" + reason + " startedAt=" + startedAt
                + " dueAt=" + delayDueAt + " enabled=" + delayEnabled
                + " confirmedRaw=" + confirmedRaw + " adasChange=" + adasChangeDelay);
    }

    private long delayMs() {
        return BASE_DELAY_MS + (delayEnabled && adasChangeDelay ? EXTRA_DELAY_MS : 0L);
    }

    private boolean shouldStartAttempt(long now) {
        return enabled && !seriesInFlight && !awaiting && !targetConfirmed
                && supportedLimit(target) && attempt < MAX_ATTEMPTS
                && delayStarted && now >= delayDueAt && now >= nextAttemptAt;
    }

    private void startAttempt(long token) {
        attempt++;
        long startedAt = port.now();
        nextAttemptAt = startedAt + RETRY_MS;
        seriesInFlight = true;
        log("attempt start cycle=" + cycle + " attempt=" + attempt
                + " startedAt=" + startedAt + " dueAt=" + delayDueAt
                + " nextAttemptAt=" + nextAttemptAt + " roadX=7 roadY=6 gapMs=" + ROAD_GAP_MS);
        write(token, ROAD, 7, startedAt, x -> {
            nextAttemptAt = x.startedAt + RETRY_MS;
            long s1At = x.startedAt + ROAD_GAP_MS;
            later(token, () -> write(token, LIMIT, target, s1At, s1 -> {
                awaiting = true;
                confirmAt = s1.startedAt + CONFIRM_MS;
                confirmCycle = cycle;
                confirmAttempt = attempt;
                int confirmingCycle = cycle;
                int confirmingAttempt = attempt;
                log("confirmation window cycle=" + cycle + " attempt=" + attempt
                        + " limitAt=" + s1.startedAt + " dueAt=" + confirmAt);
                later(token, () -> {
                    if (awaiting && cycle == confirmingCycle && attempt == confirmingAttempt) {
                        unconfirmed("confirmation-timeout", hasLastRaw ? lastRaw : -1, confirmAt);
                    }
                }, confirmAt - port.now() + 1L);
                long yAt = s1.startedAt + ROAD_GAP_MS;
                later(token, () -> write(token, ROAD, 6, yAt, y -> {
                    seriesInFlight = false;
                    read(token);
                }), yAt - port.now());
            }), s1At - port.now());
        });
    }

    private void write(long token, int operation, int value, long plannedAt,
            Consumer<Result> continuation) {
        call(token, operation, value, plannedAt, result -> {
            if (!result.success) {
                if (operation == ROAD && value == 7) {
                    nextAttemptAt = result.startedAt + RETRY_MS;
                }
                seriesInFlight = false;
                log("outcome=io_error cycle=" + cycle + " attempt=" + attempt
                        + " operation=" + operation + " value=" + value
                        + " actualAt=" + result.startedAt + " error=" + result.error);
                setFallback(true, "io-error-write-" + operation);
                if (attempt >= MAX_ATTEMPTS) logExhausted("io-error");
                later(token, () -> read(token), READ_MS);
            } else {
                continuation.accept(result);
            }
        });
    }

    private void unconfirmed(String reason, int observedRaw, long dueAt) {
        if (!awaiting) return;
        awaiting = false;
        log("outcome=unconfirmed cycle=" + cycle + " attempt=" + attempt
                + " reason=" + reason + " observedRaw=" + observedRaw
                + " dueAt=" + dueAt + " actualAt=" + port.now());
        setFallback(true, reason);
        if (attempt >= MAX_ATTEMPTS) logExhausted("unconfirmed");
    }

    private void logExhausted(String reason) {
        if (exhaustedLogged) return;
        exhaustedLogged = true;
        log("outcome=exhausted cycle=" + cycle + " attempts=" + attempt + " reason=" + reason);
    }

    private void call(long token, int operation, int value, long plannedAt,
            Consumer<Result> continuation) {
        if (!current(token)) return;
        String callSession = session;
        long callObservationEpoch = observationEpoch;
        int callCycle = cycle;
        int callAttempt = attempt;
        port.call(operation, value, () -> current(token), result -> {
            boolean stale = !current(token);
            // Dispatched writes can succeed after target replacement, without reviving that attempt.
            boolean trackedWrite = operation == LIMIT && result.success
                    && callObservationEpoch == observationEpoch && result.startedAt >= writtenAt;
            if (trackedWrite) {
                writtenRaw = expectedRaw(value);
                writtenAt = result.startedAt;
            }
            if (operation != READ || stale) {
                port.log("native_speed io session=" + callSession + " token=" + token
                        + " cycle=" + callCycle + " attempt=" + callAttempt
                        + " operation=" + operation
                        + " fid=" + (operation == READ ? "0x2D500020" : operation == ROAD ? "0x4CA00050" : "0x4CA00040")
                        + " value=" + value + " plannedAt=" + plannedAt
                        + " actualAt=" + result.startedAt + " finishedAt=" + result.finishedAt
                        + " success=" + result.success
                        + " raw=" + (operation == READ && result.success ? result.raw : "unavailable")
                        + " stale=" + stale + " trackedWrite=" + trackedWrite + " error=" + result.error);
            } else {
                String readKey = result.success ? "raw:" + result.raw : "error:" + result.error;
                if (!readKey.equals(lastReadLogKey)) {
                    lastReadLogKey = readKey;
                    port.log("native_speed read session=" + callSession + " token=" + token
                            + " cycle=" + callCycle + " target=" + target
                            + " actualAt=" + result.startedAt + " finishedAt=" + result.finishedAt
                            + " success=" + result.success
                            + " raw=" + (result.success ? result.raw : "unavailable")
                            + " error=" + result.error);
                }
            }
            if (!stale) continuation.accept(result);
        });
    }

    private void later(long token, Runnable task, long delay) {
        port.post(() -> { if (current(token)) task.run(); }, Math.max(0L, delay));
    }

    private void setFallback(boolean value, String reason) {
        if (fallback == value) return;
        fallback = value;
        log("fallback=" + value + " reason=" + reason);
        port.fallback(value);
    }

    private void log(String text) {
        port.log("native_speed session=" + session + " token=" + epoch
                + " target=" + target + " cycle=" + cycle + " attempt=" + attempt + " " + text);
    }
}
