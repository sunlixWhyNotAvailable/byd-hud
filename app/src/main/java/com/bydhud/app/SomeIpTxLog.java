package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;

/** Detailed-mode journal of every payload handed to the SOME/IP service, without images. */
final class SomeIpTxLog {
    private static SomeIpTxLog instance;

    static synchronized SomeIpTxLog get(Context context) {
        if (instance == null) instance = new SomeIpTxLog(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SomeIpPacketTrace trace = new SomeIpPacketTrace();

    private SomeIpTxLog(Context context) {
        this.context = context;
    }

    synchronized void recordSend(String source, String channel, long topic, String kind,
            String reason, byte[] payload, Integer result, String error, long startedAtMs,
            String owner, long ownerGeneration) {
        if (!HudPrefs.isDetailedDebugArtifactsEnabled(context)) return;
        long wallMs = System.currentTimeMillis();
        SomeIpPacketTrace.Record record = trace.capture("send", source, channel, topic, kind,
                reason, payload == null ? new byte[0] : payload, result, error, startedAtMs,
                SystemClock.elapsedRealtime(), wallMs, owner, ownerGeneration);
        enqueue(wallMs, record);
    }

    synchronized void recordLifecycle(String kind, String source, String channel, String reason,
            Integer result, String error, long durationMs) {
        if (!HudPrefs.isDetailedDebugArtifactsEnabled(context)) return;
        long wallMs = System.currentTimeMillis();
        long elapsedMs = SystemClock.elapsedRealtime();
        SomeIpPacketTrace.Record record = trace.capture("lifecycle", source, channel, 0L, kind,
                reason, null, result, error, elapsedMs - Math.max(0L, durationMs), elapsedMs,
                wallMs, "", -1L);
        enqueue(wallMs, record);
    }

    private void enqueue(long wallMs, SomeIpPacketTrace.Record record) {
        String day = NavCaptureStore.todayDir(wallMs);
        WazeCaptureDebugWriter.get().someIpTx(() ->
                NavCaptureStore.writeSomeIpTx(context, day, record.toJson()));
    }
}
