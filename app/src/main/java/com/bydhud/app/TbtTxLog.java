package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.Base64;

/** Detailed-mode journal for TBT lifecycle and transport transactions. */
final class TbtTxLog {
    private static TbtTxLog instance;

    static synchronized TbtTxLog get(Context context) {
        if (instance == null) {
            if (context == null) {
                throw new IllegalArgumentException("context == null");
            }
            instance = new TbtTxLog(context.getApplicationContext());
        }
        return instance;
    }

    static boolean record(Context context, Entry entry) {
        return context != null && entry != null && get(context).recordEntry(entry);
    }

    static boolean record(Context context, Entry.Builder builder) {
        return builder != null && record(context, builder.build());
    }

    static void onDetailedModeChanged(Context context, boolean enabled) {
        if (context != null) {
            get(context).setEnabled(enabled);
        }
    }

    private final Context context;
    private boolean enabled;
    private String day = "";
    private long sequence;
    private RepeatedState repeatedState;

    private TbtTxLog(Context context) {
        this.context = context;
    }

    private synchronized boolean recordEntry(Entry entry) {
        if (!syncEnabled()) {
            return false;
        }
        long wallMs = System.currentTimeMillis();
        long elapsedMs = SystemClock.elapsedRealtime();
        rollDay(NavCaptureStore.todayDir(wallMs));
        long currentSequence = ++sequence;

        if (!entry.successful() || !sameIdentity(repeatedState == null
                ? null : repeatedState.entry, entry)) {
            flushRepeated();
            queueSend(day, currentSequence, elapsedMs, wallMs, entry);
            if (entry.successful()) {
                repeatedState = new RepeatedState(
                        entry, currentSequence, elapsedMs, wallMs);
            }
            return true;
        }

        repeatedState.lastSequence = currentSequence;
        repeatedState.lastElapsedMs = elapsedMs;
        repeatedState.lastWallMs = wallMs;
        repeatedState.lastTransactionId = entry.transactionId;
        repeatedState.repeatCount++;
        repeatedState.totalDurationMs += Math.max(0L, entry.durationMs);
        return true;
    }

    private synchronized void setEnabled(boolean nextEnabled) {
        if (!nextEnabled) {
            flushRepeated();
        }
        enabled = nextEnabled;
        if (!nextEnabled) {
            day = "";
            sequence = 0L;
        }
    }

    private boolean syncEnabled() {
        boolean preferenceEnabled = HudPrefs.isDetailedDebugArtifactsEnabled(context);
        if (!preferenceEnabled) {
            if (enabled) {
                setEnabled(false);
            }
            return false;
        }
        enabled = true;
        return true;
    }

    private void rollDay(String nextDay) {
        if (nextDay.equals(day)) {
            return;
        }
        flushRepeated();
        day = nextDay;
        sequence = 0L;
    }

    private void flushRepeated() {
        RepeatedState state = repeatedState;
        repeatedState = null;
        if (state == null || state.repeatCount == 0L || day.isEmpty()) {
            return;
        }
        String targetDay = day;
        WazeCaptureDebugWriter.get().someIpTx(
                () -> NavCaptureStore.writeTbtTx(
                        context, targetDay, toRepeatJson(state)));
    }

    private void queueSend(
            String targetDay,
            long currentSequence,
            long elapsedMs,
            long wallMs,
            Entry entry) {
        WazeCaptureDebugWriter.get().someIpTx(
                () -> NavCaptureStore.writeTbtTx(
                        context, targetDay,
                        toJson(entry, "send", currentSequence, elapsedMs, wallMs)));
    }

    static boolean sameIdentity(Entry left, Entry right) {
        if (left == null || right == null) {
            return false;
        }
        return safe(left.source).equals(safe(right.source))
                && safe(left.owner).equals(safe(right.owner))
                && left.generation == right.generation
                && safe(left.reason).equals(safe(right.reason))
                && safe(left.plane).equals(safe(right.plane))
                && safe(left.operation).equals(safe(right.operation))
                && safe(left.target).equals(safe(right.target))
                && equal(left.nativeId, right.nativeId)
                && equal(left.intermediateAmapIcon, right.intermediateAmapIcon)
                && equal(left.amapIcon, right.amapIcon)
                && equal(left.roundaboutExit, right.roundaboutExit)
                && equal(left.distanceMeters, right.distanceMeters)
                && safe(left.road).equals(safe(right.road))
                && equal(left.routeEtaMs, right.routeEtaMs)
                && equal(left.routeDurationSeconds, right.routeDurationSeconds)
                && equal(left.routeDistanceMeters, right.routeDistanceMeters)
                && equal(left.nextStopEtaMs, right.nextStopEtaMs)
                && equal(left.nextStopDurationSeconds, right.nextStopDurationSeconds)
                && equal(left.nextStopDistanceMeters, right.nextStopDistanceMeters)
                && equal(left.result, right.result)
                && safe(left.error).equals(safe(right.error))
                && Arrays.equals(left.argumentBytes, right.argumentBytes);
    }

    static String toJson(
            Entry entry,
            String event,
            long sequence,
            long elapsedMs,
            long wallMs) {
        StringBuilder line = new StringBuilder("{")
                .append(NavCaptureStore.timeFields(elapsedMs, wallMs))
                .append(",\"sequence\":").append(sequence)
                .append(",\"event\":\"").append(esc(event)).append('"')
                .append(",\"source\":\"").append(esc(entry.source)).append('"')
                .append(",\"owner\":\"").append(esc(entry.owner)).append('"')
                .append(",\"generation\":").append(entry.generation)
                .append(",\"transactionId\":\"")
                .append(esc(entry.transactionId)).append('"')
                .append(",\"reason\":\"").append(esc(entry.reason)).append('"')
                .append(",\"plane\":\"").append(esc(entry.plane)).append('"')
                .append(",\"operation\":\"").append(esc(entry.operation)).append('"')
                .append(",\"target\":\"").append(esc(entry.target)).append('"');
        appendSemanticFields(line, entry);
        appendResultFields(line, entry);
        line.append('}');
        return line.toString();
    }

    private static String toRepeatJson(RepeatedState state) {
        Entry entry = state.entry;
        StringBuilder line = new StringBuilder("{")
                .append(NavCaptureStore.timeFields(state.lastElapsedMs, state.lastWallMs))
                .append(",\"event\":\"repeat\"")
                .append(",\"source\":\"").append(esc(entry.source)).append('"')
                .append(",\"owner\":\"").append(esc(entry.owner)).append('"')
                .append(",\"generation\":").append(entry.generation)
                .append(",\"firstTransactionId\":\"")
                .append(esc(entry.transactionId)).append('"')
                .append(",\"lastTransactionId\":\"")
                .append(esc(state.lastTransactionId)).append('"')
                .append(",\"reason\":\"").append(esc(entry.reason)).append('"')
                .append(",\"plane\":\"").append(esc(entry.plane)).append('"')
                .append(",\"operation\":\"").append(esc(entry.operation)).append('"')
                .append(",\"target\":\"").append(esc(entry.target)).append('"')
                .append(",\"firstSequence\":").append(state.firstSequence)
                .append(",\"lastSequence\":").append(state.lastSequence)
                .append(",\"repeatCount\":").append(state.repeatCount)
                .append(",\"firstT\":").append(state.firstElapsedMs)
                .append(",\"lastT\":").append(state.lastElapsedMs)
                .append(",\"firstTs\":").append(state.firstWallMs)
                .append(",\"lastTs\":").append(state.lastWallMs);
        appendSemanticFields(line, entry);
        line.append(",\"result\":").append(entry.result)
                .append(",\"totalDurationMs\":").append(state.totalDurationMs);
        appendArgumentBytes(line, entry);
        line.append('}');
        return line.toString();
    }

    private static void appendSemanticFields(StringBuilder line, Entry entry) {
        appendNullableInt(line, "nativeId", entry.nativeId);
        appendNullableInt(line, "intermediateAmapIcon", entry.intermediateAmapIcon);
        appendNullableInt(line, "amapIcon", entry.amapIcon);
        appendNullableInt(line, "roundaboutExit", entry.roundaboutExit);
        appendNullableLong(line, "distanceMeters", entry.distanceMeters);
        line.append(",\"road\":\"").append(esc(entry.road)).append('"');
        appendNullableLong(line, "routeEtaMs", entry.routeEtaMs);
        appendNullableLong(line, "routeDurationSeconds", entry.routeDurationSeconds);
        appendNullableLong(line, "routeDistanceMeters", entry.routeDistanceMeters);
        appendNullableLong(line, "nextStopEtaMs", entry.nextStopEtaMs);
        appendNullableLong(line, "nextStopDurationSeconds", entry.nextStopDurationSeconds);
        appendNullableLong(line, "nextStopDistanceMeters", entry.nextStopDistanceMeters);
    }

    private static void appendResultFields(StringBuilder line, Entry entry) {
        line.append(",\"result\":")
                .append(entry.result == null ? "null" : entry.result)
                .append(",\"durationMs\":").append(Math.max(0L, entry.durationMs));
        if (!safe(entry.error).isEmpty()) {
            line.append(",\"error\":\"").append(esc(entry.error)).append('"');
        }
        appendArgumentBytes(line, entry);
    }

    private static void appendArgumentBytes(StringBuilder line, Entry entry) {
        if (entry.argumentBytes == null) {
            return;
        }
        line.append(",\"argumentLength\":").append(entry.argumentBytes.length)
                .append(",\"argumentsBase64\":\"")
                .append(Base64.getEncoder().encodeToString(entry.argumentBytes))
                .append('"');
    }

    private static void appendNullableInt(StringBuilder line, String name, Integer value) {
        if (value != null) {
            line.append(",\"").append(name).append("\":").append(value);
        }
    }

    private static void appendNullableLong(StringBuilder line, String name, Long value) {
        if (value != null) {
            line.append(",\"").append(name).append("\":").append(value);
        }
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String esc(String value) {
        return NavCaptureStore.esc(safe(value));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class Entry {
        final String source;
        final String owner;
        final long generation;
        final String transactionId;
        final String reason;
        final String plane;
        final String operation;
        final String target;
        final Integer nativeId;
        final Integer intermediateAmapIcon;
        final Integer amapIcon;
        final Integer roundaboutExit;
        final Long distanceMeters;
        final String road;
        final Long routeEtaMs;
        final Long routeDurationSeconds;
        final Long routeDistanceMeters;
        final Long nextStopEtaMs;
        final Long nextStopDurationSeconds;
        final Long nextStopDistanceMeters;
        final byte[] argumentBytes;
        final Integer result;
        final long durationMs;
        final String error;

        private Entry(Builder builder) {
            source = safe(builder.source);
            owner = safe(builder.owner);
            generation = builder.generation;
            transactionId = safe(builder.transactionId);
            reason = safe(builder.reason);
            plane = safe(builder.plane);
            operation = safe(builder.operation);
            target = safe(builder.target);
            nativeId = builder.nativeId;
            intermediateAmapIcon = builder.intermediateAmapIcon;
            amapIcon = builder.amapIcon;
            roundaboutExit = builder.roundaboutExit;
            distanceMeters = builder.distanceMeters;
            road = safe(builder.road);
            routeEtaMs = builder.routeEtaMs;
            routeDurationSeconds = builder.routeDurationSeconds;
            routeDistanceMeters = builder.routeDistanceMeters;
            nextStopEtaMs = builder.nextStopEtaMs;
            nextStopDurationSeconds = builder.nextStopDurationSeconds;
            nextStopDistanceMeters = builder.nextStopDistanceMeters;
            argumentBytes = builder.argumentBytes == null
                    ? null : builder.argumentBytes.clone();
            result = builder.result;
            durationMs = Math.max(0L, builder.durationMs);
            error = safe(builder.error);
        }

        boolean successful() {
            return result != null && result == 0 && error.isEmpty();
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private String source = "";
            private String owner = "";
            private long generation;
            private String transactionId = "";
            private String reason = "";
            private String plane = "";
            private String operation = "";
            private String target = "";
            private Integer nativeId;
            private Integer intermediateAmapIcon;
            private Integer amapIcon;
            private Integer roundaboutExit;
            private Long distanceMeters;
            private String road = "";
            private Long routeEtaMs;
            private Long routeDurationSeconds;
            private Long routeDistanceMeters;
            private Long nextStopEtaMs;
            private Long nextStopDurationSeconds;
            private Long nextStopDistanceMeters;
            private byte[] argumentBytes;
            private Integer result;
            private long durationMs;
            private String error = "";

            Builder source(String value) { source = value; return this; }
            Builder owner(String value) { owner = value; return this; }
            Builder generation(long value) { generation = value; return this; }
            Builder transactionId(String value) { transactionId = value; return this; }
            Builder reason(String value) { reason = value; return this; }
            Builder plane(String value) { plane = value; return this; }
            Builder operation(String value) { operation = value; return this; }
            Builder target(String value) { target = value; return this; }
            Builder nativeId(Integer value) { nativeId = value; return this; }
            Builder intermediateAmapIcon(Integer value) { intermediateAmapIcon = value; return this; }
            Builder amapIcon(Integer value) { amapIcon = value; return this; }
            Builder roundaboutExit(Integer value) { roundaboutExit = value; return this; }
            Builder distanceMeters(Long value) { distanceMeters = value; return this; }
            Builder road(String value) { road = value; return this; }
            Builder routeEtaMs(Long value) { routeEtaMs = value; return this; }
            Builder routeDurationSeconds(Long value) { routeDurationSeconds = value; return this; }
            Builder routeDistanceMeters(Long value) { routeDistanceMeters = value; return this; }
            Builder nextStopEtaMs(Long value) { nextStopEtaMs = value; return this; }
            Builder nextStopDurationSeconds(Long value) { nextStopDurationSeconds = value; return this; }
            Builder nextStopDistanceMeters(Long value) { nextStopDistanceMeters = value; return this; }
            Builder argumentBytes(byte[] value) { argumentBytes = value == null ? null : value.clone(); return this; }
            Builder result(Integer value) { result = value; return this; }
            Builder durationMs(long value) { durationMs = value; return this; }
            Builder error(String value) { error = value; return this; }

            Entry build() {
                return new Entry(this);
            }
        }
    }

    private static final class RepeatedState {
        final Entry entry;
        final long firstSequence;
        final long firstElapsedMs;
        final long firstWallMs;
        long lastSequence;
        long lastElapsedMs;
        long lastWallMs;
        String lastTransactionId;
        long repeatCount;
        long totalDurationMs;

        RepeatedState(Entry entry, long sequence, long elapsedMs, long wallMs) {
            this.entry = entry;
            firstSequence = sequence;
            firstElapsedMs = elapsedMs;
            firstWallMs = wallMs;
            lastSequence = sequence;
            lastElapsedMs = elapsedMs;
            lastWallMs = wallMs;
            lastTransactionId = entry.transactionId;
        }
    }
}
