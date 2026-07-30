package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Detailed-mode journal of the exact payloads handed to the SOME/IP service. */
final class SomeIpTxLog {
    private static SomeIpTxLog instance;

    static synchronized SomeIpTxLog get(Context context) {
        if (instance == null) {
            instance = new SomeIpTxLog(context.getApplicationContext());
        }
        return instance;
    }

    static void onDetailedModeChanged(Context context, boolean enabled) {
        get(context).setEnabled(enabled);
    }

    private final Context context;
    private final Set<String> writerPayloadHashes = new HashSet<>();
    private final Map<Long, String> writerPayloadHashBySequence = new HashMap<>();
    private final Map<Long, String> writerSemanticHashBySequence = new HashMap<>();

    private boolean enabled;
    private String day = "";
    private long sequence;
    private RepeatedState repeatedState;
    private String writerDay = "";

    private SomeIpTxLog(Context context) {
        this.context = context;
    }

    synchronized void recordSend(
            String source,
            String channel,
            long topic,
            String kind,
            String reason,
            byte[] payload,
            byte[] semanticPayload,
            Integer result,
            String error,
            long durationMs) {
        if (!syncEnabled()) return;
        long wallMs = System.currentTimeMillis();
        long elapsedMs = SystemClock.elapsedRealtime();
        rollDay(NavCaptureStore.todayDir(wallMs));
        long currentSequence = ++sequence;
        byte[] exact = payload == null ? new byte[0] : payload;
        byte[] semantic = semanticPayload == null ? exact : semanticPayload;

        boolean failed = result == null || result != 0 || (error != null && !error.isEmpty());
        if (failed || !sameState(repeatedState, source, channel, topic, kind, result, semantic)) {
            flushRepeated();
            queueSend(day, currentSequence, elapsedMs, wallMs, source, channel, topic,
                    kind, reason, exact.clone(), semantic.clone(), result, error, durationMs);
            if (!failed) {
                repeatedState = new RepeatedState(source, channel, topic, kind, result,
                        semantic.clone(), currentSequence, elapsedMs, wallMs);
            }
            return;
        }

        repeatedState.lastSequence = currentSequence;
        repeatedState.lastElapsedMs = elapsedMs;
        repeatedState.lastWallMs = wallMs;
        repeatedState.repeatCount++;
        repeatedState.totalDurationMs += Math.max(0L, durationMs);
    }

    synchronized void recordLifecycle(
            String kind,
            String source,
            String channel,
            String reason,
            Integer result,
            String error,
            long durationMs) {
        if (!syncEnabled()) return;
        long wallMs = System.currentTimeMillis();
        long elapsedMs = SystemClock.elapsedRealtime();
        rollDay(NavCaptureStore.todayDir(wallMs));
        flushRepeated();
        long currentSequence = ++sequence;
        String targetDay = day;
        WazeCaptureDebugWriter.get().someIpTx(() -> writeLifecycle(
                targetDay, currentSequence, elapsedMs, wallMs, kind, source, channel,
                reason, result, error, durationMs));
    }

    private synchronized void setEnabled(boolean nextEnabled) {
        if (!nextEnabled) flushRepeated();
        enabled = nextEnabled;
        if (!nextEnabled) {
            day = "";
            sequence = 0L;
        }
    }

    private boolean syncEnabled() {
        boolean preferenceEnabled = HudPrefs.isDetailedDebugArtifactsEnabled(context);
        if (!preferenceEnabled) {
            if (enabled) setEnabled(false);
            return false;
        }
        enabled = true;
        return true;
    }

    private void rollDay(String nextDay) {
        if (nextDay.equals(day)) return;
        flushRepeated();
        day = nextDay;
        sequence = 0L;
    }

    private void flushRepeated() {
        RepeatedState state = repeatedState;
        repeatedState = null;
        if (state == null || state.repeatCount == 0L || day.isEmpty()) return;
        String targetDay = day;
        WazeCaptureDebugWriter.get().someIpTx(() -> writeRepeat(targetDay, state));
    }

    private void queueSend(
            String targetDay,
            long currentSequence,
            long elapsedMs,
            long wallMs,
            String source,
            String channel,
            long topic,
            String kind,
            String reason,
            byte[] payload,
            byte[] semanticPayload,
            Integer result,
            String error,
            long durationMs) {
        WazeCaptureDebugWriter.get().someIpTx(() -> writeSend(targetDay,
                currentSequence, elapsedMs, wallMs, source, channel, topic, kind,
                reason, payload, semanticPayload, result, error, durationMs));
    }

    private void writeSend(
            String targetDay,
            long currentSequence,
            long elapsedMs,
            long wallMs,
            String source,
            String channel,
            long topic,
            String kind,
            String reason,
            byte[] payload,
            byte[] semanticPayload,
            Integer result,
            String error,
            long durationMs) {
        prepareWriterDay(targetDay);
        String payloadHash = sha256(payload);
        String semanticHash = sha256(semanticPayload);
        writerPayloadHashBySequence.put(currentSequence, payloadHash);
        writerSemanticHashBySequence.put(currentSequence, semanticHash);
        boolean includePayload = writerPayloadHashes.add(payloadHash);
        StringBuilder line = new StringBuilder("{")
                .append(NavCaptureStore.timeFields(elapsedMs, wallMs))
                .append(",\"sequence\":").append(currentSequence)
                .append(",\"event\":\"send\"")
                .append(",\"kind\":\"").append(esc(kind)).append('"')
                .append(",\"source\":\"").append(esc(source)).append('"')
                .append(",\"channel\":\"").append(esc(channel)).append('"')
                .append(",\"topic\":\"").append(topicHex(topic)).append('"')
                .append(",\"payloadLength\":").append(payload.length)
                .append(",\"payloadSha256\":\"").append(payloadHash).append('"')
                .append(",\"semanticSha256\":\"").append(semanticHash).append('"')
                .append(",\"result\":").append(result == null ? "null" : result)
                .append(",\"durationMs\":").append(Math.max(0L, durationMs))
                .append(",\"reason\":\"").append(esc(reason)).append('"');
        if (error != null && !error.isEmpty()) {
            line.append(",\"error\":\"").append(esc(error)).append('"');
        }
        if (includePayload) {
            line.append(",\"payloadBase64\":\"")
                    .append(Base64.encodeToString(payload, Base64.NO_WRAP)).append('"');
        } else {
            line.append(",\"payloadRefSha256\":\"").append(payloadHash).append('"');
        }
        line.append('}');
        NavCaptureStore.writeSomeIpTx(context, targetDay, line.toString());
    }

    private void writeRepeat(String targetDay, RepeatedState state) {
        prepareWriterDay(targetDay);
        String payloadHash = safe(writerPayloadHashBySequence.get(state.firstSequence));
        String semanticHash = safe(writerSemanticHashBySequence.get(state.firstSequence));
        String line = "{"
                + NavCaptureStore.timeFields(state.lastElapsedMs, state.lastWallMs)
                + ",\"event\":\"repeat\""
                + ",\"kind\":\"" + esc(state.kind) + "\""
                + ",\"source\":\"" + esc(state.source) + "\""
                + ",\"channel\":\"" + esc(state.channel) + "\""
                + ",\"topic\":\"" + topicHex(state.topic) + "\""
                + ",\"firstSequence\":" + state.firstSequence
                + ",\"lastSequence\":" + state.lastSequence
                + ",\"repeatCount\":" + state.repeatCount
                + ",\"firstT\":" + state.firstElapsedMs
                + ",\"lastT\":" + state.lastElapsedMs
                + ",\"firstTs\":" + state.firstWallMs
                + ",\"lastTs\":" + state.lastWallMs
                + ",\"payloadRefSha256\":\"" + payloadHash + "\""
                + ",\"semanticSha256\":\"" + semanticHash + "\""
                + ",\"result\":" + state.result
                + ",\"totalDurationMs\":" + state.totalDurationMs
                + "}";
        NavCaptureStore.writeSomeIpTx(context, targetDay, line);
    }

    private void writeLifecycle(
            String targetDay,
            long currentSequence,
            long elapsedMs,
            long wallMs,
            String kind,
            String source,
            String channel,
            String reason,
            Integer result,
            String error,
            long durationMs) {
        prepareWriterDay(targetDay);
        StringBuilder line = new StringBuilder("{")
                .append(NavCaptureStore.timeFields(elapsedMs, wallMs))
                .append(",\"sequence\":").append(currentSequence)
                .append(",\"event\":\"lifecycle\"")
                .append(",\"kind\":\"").append(esc(kind)).append('"')
                .append(",\"source\":\"").append(esc(source)).append('"')
                .append(",\"channel\":\"").append(esc(channel)).append('"')
                .append(",\"result\":").append(result == null ? "null" : result)
                .append(",\"durationMs\":").append(Math.max(0L, durationMs))
                .append(",\"reason\":\"").append(esc(reason)).append('"');
        if (error != null && !error.isEmpty()) {
            line.append(",\"error\":\"").append(esc(error)).append('"');
        }
        line.append('}');
        NavCaptureStore.writeSomeIpTx(context, targetDay, line.toString());
    }

    private void prepareWriterDay(String targetDay) {
        if (targetDay.equals(writerDay)) return;
        writerDay = targetDay;
        writerPayloadHashes.clear();
        writerPayloadHashBySequence.clear();
        writerSemanticHashBySequence.clear();
    }

    static boolean sameState(
            RepeatedState state,
            String source,
            String channel,
            long topic,
            String kind,
            Integer result,
            byte[] semanticPayload) {
        return state != null
                && state.topic == topic
                && state.result.equals(result)
                && state.source.equals(safe(source))
                && state.channel.equals(safe(channel))
                && state.kind.equals(safe(kind))
                && Arrays.equals(state.semanticPayload,
                semanticPayload == null ? new byte[0] : semanticPayload);
    }

    private static String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                int unsigned = value & 0xff;
                if (unsigned < 0x10) out.append('0');
                out.append(Integer.toHexString(unsigned));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String topicHex(long topic) {
        return String.format(Locale.US, "0x%016x", topic);
    }

    private static String esc(String value) {
        return NavCaptureStore.esc(safe(value));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class RepeatedState {
        final String source;
        final String channel;
        final long topic;
        final String kind;
        final Integer result;
        final byte[] semanticPayload;
        final long firstSequence;
        final long firstElapsedMs;
        final long firstWallMs;
        long lastSequence;
        long lastElapsedMs;
        long lastWallMs;
        long repeatCount;
        long totalDurationMs;

        RepeatedState(
                String source,
                String channel,
                long topic,
                String kind,
                Integer result,
                byte[] semanticPayload,
                long firstSequence,
                long firstElapsedMs,
                long firstWallMs) {
            this.source = safe(source);
            this.channel = safe(channel);
            this.topic = topic;
            this.kind = safe(kind);
            this.result = result;
            this.semanticPayload = semanticPayload == null ? new byte[0] : semanticPayload;
            this.firstSequence = firstSequence;
            this.firstElapsedMs = firstElapsedMs;
            this.firstWallMs = firstWallMs;
            this.lastSequence = firstSequence;
            this.lastElapsedMs = firstElapsedMs;
            this.lastWallMs = firstWallMs;
        }
    }
}
