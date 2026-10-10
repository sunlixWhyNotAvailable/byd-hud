package com.bydhud.app;

import android.content.Context;
import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

/** Optional receive-only observer; owns no HUD provider, GPS or navigation state. */
final class SomeIpRoadInfoDiagnostics {
    // The private constructor receives only the application context from get().
    @SuppressLint("StaticFieldLeak")
    private static volatile SomeIpRoadInfoDiagnostics instance;

    static synchronized SomeIpRoadInfoDiagnostics get(Context context) {
        if (instance == null) instance = new SomeIpRoadInfoDiagnostics(context.getApplicationContext());
        return instance;
    }

    static void onRuntimeStarted(Context context) { get(context).runtime(true); }
    static void onRuntimeStopped() {
        SomeIpRoadInfoDiagnostics current = instance;
        if (current != null) current.runtime(false);
    }
    static void onDetailedModeChanged(Context context) { get(context).reconcile(); }

    static RoadInfoTxHistory.Attempt beforeSend(long topic, byte[] payload) {
        SomeIpRoadInfoDiagnostics current = instance;
        Session active = current == null ? null : current.session;
        if (active == null || active.closed || active.generation != current.generation
                || topic != SomeIpHudClient.HUD_ROAD_INFO_TOPIC) return null;
        long elapsed = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        try {
            return active.history.add(RoadInfoDiagnosticFrame.parse(payload), elapsed, wall);
        } catch (RuntimeException error) {
            try { active.event("tx_parse_error", error.getClass().getSimpleName()); }
            catch (RuntimeException ignored) { /* Diagnostics must not block the actual send. */ }
            return null;
        }
    }

    private final Context context;
    private Handler control;
    private boolean runtimeActive, desired;
    private volatile int generation;
    private volatile Session session;

    private SomeIpRoadInfoDiagnostics(Context context) { this.context = context; }

    private synchronized void runtime(boolean active) {
        runtimeActive = active;
        reconcile();
    }

    private synchronized void reconcile() {
        boolean next = runtimeActive && HudPrefs.isDetailedDebugArtifactsEnabled(context);
        if (desired == next) return;
        desired = next;
        int requested = ++generation;
        if (session != null) session.closed = true;
        if (control == null) {
            HandlerThread thread = new HandlerThread("hud-roadinfo-receiver", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            control = new Handler(thread.getLooper());
        }
        control.post(() -> replace(requested, next));
    }

    private synchronized void reconnect(Session failed) {
        if (!desired || session != failed || failed.closed) return;
        failed.closed = true;
        int requested = ++generation;
        control.postDelayed(() -> replace(requested, true), 1_000L);
    }

    private void replace(int requested, boolean start) {
        if (requested != generation) return;
        Session previous = session;
        session = null;
        if (previous != null) previous.close();
        if (!start || requested != generation) return;
        Session next = new Session(requested);
        session = next;
        next.event("start", "selected_fields_crc32; match_is_not_sender_identity_or_render_ack");
        try {
            boolean bound = next.client.start(3_000L, () -> requested != generation);
            if (requested != generation) return;
            next.event("subscription", new JSONObject()
                    .put("bound", bound)
                    .put("clients", next.client.clientResults())
                    .put("topics", next.client.subscriptionResults()).toString());
            if (!bound || next.client.subscribedCount() != 1) {
                next.event("unavailable", "no_accepted_subscription");
                next.close();
            } else {
                control.postDelayed(() -> {
                    if (session == next && !next.closed) next.summary("observation_check");
                }, 10_000L);
            }
        } catch (Exception error) {
            next.event("start_error", error.getClass().getSimpleName());
            next.close();
        }
    }

    private final class Session implements ShanghaiSomeIpClient.EventSink {
        final String id;
        final int generation;
        final RoadInfoTxHistory history = new RoadInfoTxHistory();
        final ShanghaiSomeIpClient client;
        final AtomicLong received = new AtomicLong(), rejected = new AtomicLong();
        final AtomicLong parseErrors = new AtomicLong(), enqueueFailures = new AtomicLong();
        volatile boolean closed, positiveControl;
        private boolean released;

        Session(int generation) {
            this.generation = generation;
            id = System.currentTimeMillis() + "-" + generation;
            client = new ShanghaiSomeIpClient(context, "bydhud-roadinfo-rx-" + id,
                    Collections.singletonList(SomeIpHudClient.HUD_ROAD_INFO_TOPIC), this);
        }

        @Override public void offer(long topic, long timestamp, int length, byte[] payload) {
            long elapsed = SystemClock.elapsedRealtime();
            long wall = System.currentTimeMillis();
            if (closed || generation != SomeIpRoadInfoDiagnostics.this.generation) {
                rejected.incrementAndGet();
                return;
            }
            if (topic != SomeIpHudClient.HUD_ROAD_INFO_TOPIC) {
                rejected.incrementAndGet();
                event("unexpected_topic", Long.toHexString(topic));
                return;
            }
            long sequence = received.incrementAndGet();
            try {
                if (payload == null || length != payload.length) {
                    throw new IllegalArgumentException("length_mismatch");
                }
                RoadInfoDiagnosticFrame frame = RoadInfoDiagnosticFrame.parse(payload);
                // Match at callback time so later TX cannot overwrite the evidence window.
                RoadInfoTxHistory.Attempt match = history.match(frame, elapsed);
                RoadInfoTxHistory.Attempt latest = history.latest(elapsed);
                if (match != null) match.observeReceive(() -> confirmControl(match));
                enqueue(wall, () -> {
                    JSONObject row = base("receive", elapsed, wall)
                            .put("sequence", sequence).put("oemTimestamp", timestamp)
                            .put("payloadBytes", length).put("fields", frame.json())
                            .put("latestTxElapsedMs", latest == null ? JSONObject.NULL : latest.elapsedMs)
                            .put("latestTxMatches", latest != null && frame.matches(latest.frame));
                    if (match == null) {
                        row.put("match", "not_found_in_retained_history");
                    } else {
                        boolean completed = match.completed;
                        row.put("match", "selected_fields")
                                .put("txSequence", match.sequence).put("txWallMs", match.wallMs)
                                .put("txElapsedMs", match.elapsedMs).put("txAgeMs", elapsed - match.elapsedMs)
                                .put("txCompleted", completed)
                                .put("txResult", completed && match.result != null ? match.result : JSONObject.NULL);
                    }
                    row.put("positiveControlObserved", positiveControl);
                    return row;
                });
            } catch (RuntimeException error) {
                parseErrors.incrementAndGet();
                event("rx_parse_error", error.getMessage());
            }
        }

        @Override public void rejectAfterSeal(long topic, byte[] payload) {
            rejected.incrementAndGet();
            event("late_callback", Long.toHexString(topic));
        }
        @Override public long receivedCount() { return received.get(); }
        @Override public boolean healthy() { return !closed; }
        @Override public void malformed(String reason) {
            parseErrors.incrementAndGet();
            event("callback_parse_error", reason);
        }
        @Override public void status(String event) {
            event("service_status", event);
            if ("disconnected".equals(event)) reconnect(this);
        }

        private synchronized void confirmControl(RoadInfoTxHistory.Attempt attempt) {
            if (positiveControl) return;
            positiveControl = true;
            try { event("positive_control", "selected_fields_match_tx_sequence=" + attempt.sequence); }
            catch (RuntimeException ignored) {
                // A diagnostic queue failure must not alter the completed HUD send.
                enqueueFailures.incrementAndGet();
            }
        }

        void event(String event, String detail) {
            long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
            enqueue(wall, () -> base(event, elapsed, wall).put("detail", detail));
        }

        void summary(String event) {
            long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
            long count = received.get(), errors = parseErrors.get(), rejects = rejected.get();
            enqueue(wall, () -> base(event, elapsed, wall).put("received", count)
                    .put("parseErrors", errors).put("rejected", rejects)
                    .put("enqueueFailures", enqueueFailures.get())
                    .put("positiveControlObserved", positiveControl)
                    .put("interpretation", positiveControl ? "selected_fields_control_seen"
                            : "inconclusive_without_control"));
        }

        void close() {
            if (released) return;
            released = true;
            closed = true;
            client.close();
            event("cleanup", client.cleanupResult().toString());
            summary("stop");
        }

        private JSONObject base(String event, long elapsed, long wall) throws Exception {
            return new JSONObject().put("event", event).put("session", id)
                    .put("t", elapsed).put("ts", wall)
                    .put("topic", "0004010a00018001");
        }

        private void enqueue(long wall, JsonRecord record) {
            String day = NavCaptureStore.todayDir(wall);
            boolean queued = WazeCaptureDebugWriter.get().someIpRx(() -> {
                try { NavCaptureStore.writeSomeIpRx(context, day, record.build().toString()); }
                catch (Exception error) {
                    enqueueFailures.incrementAndGet();
                    AppEventLogger.event(context, "roadinfo_rx journal_error=" + error.getClass().getSimpleName());
                }
            });
            if (!queued) {
                enqueueFailures.incrementAndGet();
                AppEventLogger.event(context, "roadinfo_rx enqueue_failed");
            }
        }
    }

    private interface JsonRecord { JSONObject build() throws Exception; }
}
