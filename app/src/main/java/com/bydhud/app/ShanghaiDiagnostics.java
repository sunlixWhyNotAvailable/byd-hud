package com.bydhud.app;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full available Shanghai capture owner. No method in this class writes vehicle state. */
final class ShanghaiDiagnostics {
    private static final int PENDING_EVENT_LIMIT = 128;
    // Covers bounded setup, 295-second route, cleanup and 5-second tail without changing UI timing.
    static final int HELPER_HARD_LIMIT_SECONDS = 600;
    private static final Object ACTIVE_LOCK = new Object();
    private static final Set<ShanghaiDiagnostics> ACTIVE_CAPTURES = new HashSet<>();
    private final android.content.Context context;
    private final File directory;
    private final String storageDay;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final ArrayDeque<PendingRecord> pending = new ArrayDeque<>();
    private volatile State state = State.IDLE;
    private volatile boolean ready;
    private volatile String sessionId = "";
    private volatile String failure = "";
    private volatile Thread worker;
    private volatile boolean workerFinished;
    private volatile ShanghaiSessionJournal sessionJournal;
    private volatile ShanghaiEventJournal someIpJournal;
    private volatile ShanghaiSomeIpClient someIp;
    private volatile ShanghaiDiagnosticAdb adb;

    ShanghaiDiagnostics(android.content.Context context, File directory, String storageDay) {
        if (context == null || directory == null) throw new IllegalArgumentException("context and directory are required");
        if (storageDay == null || !storageDay.matches("\\d{8}")) {
            throw new IllegalArgumentException("storageDay must be yyyyMMdd");
        }
        this.context = context.getApplicationContext();
        this.directory = directory;
        this.storageDay = storageDay;
    }

    synchronized boolean start(String requestedSessionId) {
        if (state != State.IDLE && state != State.STOPPED && state != State.FAILED) return false;
        String safe = requestedSessionId == null ? "" : requestedSessionId.trim();
        if (!safe.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("Invalid Shanghai diagnostic session ID");
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            failure = "Could not create diagnostic directory";
            state = State.FAILED;
            return false;
        }
        sessionId = safe;
        stopRequested.set(false);
        ready = false;
        workerFinished = false;
        failure = "";
        state = State.STARTING;
        worker = new Thread(() -> run(safe), "shanghai-diagnostics");
        worker.setDaemon(true);
        synchronized (ACTIVE_LOCK) { ACTIVE_CAPTURES.add(this); }
        try {
            worker.start();
        } catch (RuntimeException error) {
            workerFinished = true;
            synchronized (ACTIVE_LOCK) { ACTIVE_CAPTURES.remove(this); }
            state = State.FAILED;
            failure = error.toString();
            throw error;
        }
        return true;
    }

    private void run(String safeSessionId) {
        try {
            sessionJournal = new ShanghaiSessionJournal(new File(directory, "session-events.jsonl"));
            flushPending();
            record("diagnostics_start", "session=" + safeSessionId);
            if (stopRequested.get()) return;
            String token = token(safeSessionId);

            adb = new ShanghaiDiagnosticAdb(context, directory, token);
            adb.start();
            record("snapshot_before", adb.coverageJson().optString("beforeSnapshot"));
            if (stopRequested.get()) return;

            someIpJournal = new ShanghaiEventJournal(
                    new File(directory, "someip-events.jsonl"), ShanghaiTopics.ALL);
            someIp = new ShanghaiSomeIpClient(context, safeSessionId, someIpJournal);
            boolean bound = false;
            try { bound = someIp.start(8_000L, stopRequested::get); }
            catch (Throwable error) {
                record("someip_unavailable", error.toString());
            }
            if (!bound) record("someip_unavailable", "bind failed or timed out");
            else record("someip_subscriptions", someIp.subscriptionResults().toString());
            if (stopRequested.get()) return;
            adb.resolveReadiness(5_000L);

            ready = true;
            state = hasLiveChannel() ? State.CAPTURING : State.PARTIAL;
            record("diagnostics_ready", coverage().toString());
            while (!stopRequested.get()) {
                if (!internalWritersHealthy()) {
                    failure = "Diagnostic journal writer failed";
                    state = State.FAILED;
                    break;
                }
                try { Thread.sleep(250L); }
                catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable error) {
            failure = error.toString();
            state = State.FAILED;
        } finally {
            try {
                ready = true;
                closeChannels();
                if (state != State.FAILED) state = State.STOPPED;
                ShanghaiSessionJournal journal = sessionJournal;
                if (journal != null) {
                    try { journal.record("diagnostics_stopped", coverage().toString()); }
                    finally { journal.close(); }
                }
                if (!internalWritersHealthy() && state != State.FAILED) {
                    state = State.FAILED;
                    failure = "Diagnostic writer finalization was incomplete";
                }
                writeCoverage(coverage());
            } finally {
                workerFinished = true;
                synchronized (ACTIVE_LOCK) {
                    if (finalizationConfirmed()) ACTIVE_CAPTURES.remove(this);
                }
            }
        }
    }

    void record(String event, String detail) {
        ShanghaiSessionJournal journal = sessionJournal;
        if (journal != null) {
            journal.record(event, detail);
            return;
        }
        synchronized (pending) {
            if (pending.size() == PENDING_EVENT_LIMIT) pending.removeFirst();
            pending.addLast(new PendingRecord(event, detail));
        }
    }

    private void flushPending() {
        synchronized (pending) {
            while (!pending.isEmpty()) {
                PendingRecord item = pending.removeFirst();
                sessionJournal.record(item.event, item.detail);
            }
        }
    }

    boolean isReady() { return ready; }

    boolean isHealthy() {
        if (state == State.FAILED || state == State.STOPPED || state == State.IDLE) return false;
        if (!ready) return state == State.STARTING;
        return internalWritersHealthy();
    }

    boolean hasLiveChannel() {
        ShanghaiSomeIpClient currentSomeIp = someIp;
        ShanghaiDiagnosticAdb currentAdb = adb;
        return currentSomeIp != null && currentSomeIp.subscribedCount() > 0 && currentSomeIp.healthy()
                || currentAdb != null && currentAdb.hasLiveChannel();
    }

    private boolean internalWritersHealthy() {
        ShanghaiSessionJournal events = sessionJournal;
        ShanghaiEventJournal raw = someIpJournal;
        return (events == null || events.healthy()) && (raw == null || raw.healthy());
    }

    Coverage coverage() {
        ShanghaiSomeIpClient currentSomeIp = someIp;
        ShanghaiEventJournal raw = someIpJournal;
        ShanghaiSessionJournal events = sessionJournal;
        ShanghaiDiagnosticAdb currentAdb = adb;
        JSONObject channels = ShanghaiJson.object(
                "someIpRequestedTopics", ShanghaiTopics.ALL.size(),
                "someIpSubscribedTopics", currentSomeIp == null ? 0 : currentSomeIp.subscribedCount(),
                "someIpReceived", raw == null ? 0 : raw.receivedCount(),
                "someIpPersisted", raw == null ? 0 : raw.persistedCount(),
                "someIpDropped", raw == null ? 0 : raw.droppedCount(),
                "someIpTopics", raw == null ? JSONObject.NULL : raw.topicSummary(),
                "someIpSubscriptionResults", currentSomeIp == null
                        ? JSONObject.NULL : currentSomeIp.subscriptionResults(),
                "someIpClientResults", currentSomeIp == null
                        ? JSONObject.NULL : currentSomeIp.clientResults(),
                "someIpCleanup", currentSomeIp == null
                        ? JSONObject.NULL : currentSomeIp.cleanupResult(),
                "adb", currentAdb == null ? JSONObject.NULL : currentAdb.coverageJson(),
                "sessionEventsPersisted", events == null ? 0 : events.writtenCount(),
                "sessionEventsDropped", events == null ? pendingCount() : events.droppedCount(),
                "physicalHudVerified", false,
                "amapIntentExtrasComplete", false);
        boolean live = hasLiveChannel();
        State reported = state == State.CAPTURING && !live ? State.PARTIAL : state;
        return new Coverage(reported.name().toLowerCase(Locale.ROOT), ready, live,
                internalWritersHealthy(), failure, channels);
    }

    void stop() throws java.io.IOException {
        stopRequested.set(true);
        Thread current = worker;
        if (current == null || current == Thread.currentThread()) return;
        try {
            current.join(15_000L);
            if (current.isAlive()) {
                current.interrupt();
                current.join(1_000L);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Shanghai diagnostic stop interrupted", error);
        }
        if (current.isAlive()) {
            state = State.FAILED;
            failure = "Diagnostic writers did not stop within the bounded shutdown window";
            throw new java.io.IOException(failure);
        }
        if (!finalizationConfirmed()) {
            state = State.FAILED;
            failure = "Diagnostic writer finalization is unconfirmed";
            throw new java.io.IOException(failure);
        }
        if (state == State.FAILED || !internalWritersHealthy()) {
            throw new java.io.IOException(failure.isEmpty() ? "Diagnostic capture failed" : failure);
        }
    }

    private void closeChannels() {
        ShanghaiSomeIpClient currentSomeIp = someIp;
        try { if (currentSomeIp != null) currentSomeIp.close(); }
        catch (Throwable error) { closeFailed("SOME/IP cleanup", error); }
        ShanghaiEventJournal raw = someIpJournal;
        try { if (raw != null) raw.close(); }
        catch (Throwable error) { closeFailed("SOME/IP journal cleanup", error); }
        ShanghaiDiagnosticAdb currentAdb = adb;
        try { if (currentAdb != null) currentAdb.close(); }
        catch (Throwable error) { closeFailed("ADB diagnostic cleanup", error); }
    }

    private void closeFailed(String channel, Throwable error) {
        String detail = channel + " failed: " + error;
        failure = failure.isEmpty() ? detail : failure + "; " + detail;
        state = State.FAILED;
    }

    private boolean finalizationConfirmed() {
        ShanghaiEventJournal raw = someIpJournal;
        ShanghaiSessionJournal events = sessionJournal;
        ShanghaiDiagnosticAdb currentAdb = adb;
        return (raw == null || raw.finalized())
                && (events == null || events.finalized())
                && (currentAdb == null || currentAdb.finalized());
    }

    private void writeCoverage(Coverage coverage) {
        try (FileOutputStream output = new FileOutputStream(new File(directory, "coverage.json"), false)) {
            output.write(coverage.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable error) {
            failure = failure.isEmpty() ? "coverage write failed: " + error : failure;
            state = State.FAILED;
        }
    }

    private int pendingCount() {
        synchronized (pending) { return pending.size(); }
    }

    static boolean isDayWriting(String day) {
        if (day == null || day.isEmpty()) return false;
        synchronized (ACTIVE_LOCK) {
            pruneFinishedCaptures();
            for (ShanghaiDiagnostics capture : ACTIVE_CAPTURES) {
                if (day.equals(capture.storageDay)) return true;
            }
            return false;
        }
    }

    static boolean hasUnfinishedCapture() {
        synchronized (ACTIVE_LOCK) {
            pruneFinishedCaptures();
            return !ACTIVE_CAPTURES.isEmpty();
        }
    }

    private static void pruneFinishedCaptures() {
        // A bounded stop may return before an I/O thread exits. Release protection
        // after actual termination, independently of whether capture succeeded.
        ACTIVE_CAPTURES.removeIf(capture -> capture.workerFinished
                && capture.finalizationConfirmed());
    }

    private static String token(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(12);
        for (int index = 0; index < 6; index++) {
            result.append(String.format(Locale.ROOT, "%02x", hash[index] & 0xff));
        }
        return result.toString();
    }

    static final class Coverage {
        final String state;
        final boolean ready;
        final boolean liveChannel;
        final boolean writersHealthy;
        final String failure;
        final JSONObject channels;

        Coverage(String state, boolean ready, boolean liveChannel,
                boolean writersHealthy, String failure, JSONObject channels) {
            this.state = state;
            this.ready = ready;
            this.liveChannel = liveChannel;
            this.writersHealthy = writersHealthy;
            this.failure = failure == null ? "" : failure;
            this.channels = ShanghaiJson.copy(channels);
        }

        boolean hasPartialCoverage() {
            if ("partial".equals(state)
                    || channels.optInt("someIpSubscribedTopics") < channels.optInt("someIpRequestedTopics")
                    || channels.optLong("someIpDropped") > 0L
                    || channels.optInt("sessionEventsDropped") > 0) return true;
            JSONObject adb = channels.optJSONObject("adb");
            return adb == null || partialStream(adb.optJSONObject("adas"), "adas")
                    || partialStream(adb.optJSONObject("pcap"), "pcap");
        }

        private boolean partialStream(JSONObject stream, String name) {
            if (stream == null) return true;
            String status = stream.optString("status", "unavailable");
            if ("unavailable".equals(status) || "failed".equals(status)
                    || "stop_unconfirmed".equals(status) || "not_started".equals(status)) return true;
            if (stream.optLong("droppedBytes") > 0L || !stream.optString("error").isEmpty()
                    || !stream.optString("stopError").isEmpty()) return true;
            boolean finalized = "stopped".equals(state) || "failed".equals(state);
            if (finalized) return !stream.optBoolean("captureComplete", false);
            if ("adas".equals(name) && !stream.optBoolean("ready")) return true;
            if ("pcap".equals(name) && stream.optLong("bytes") < 24L) return true;
            return "completed".equals(status) || "stopped".equals(status);
        }

        JSONObject toJson() {
            return ShanghaiJson.object(
                    "schemaVersion", 1,
                    "state", state,
                    "ready", ready,
                    "hasLiveChannel", liveChannel,
                    "writersHealthy", writersHealthy,
                    "failure", failure.isEmpty() ? JSONObject.NULL : failure,
                    "channels", ShanghaiJson.copy(channels),
                    "coverageClaim", "available channels only; no physical HUD confirmation");
        }

        @Override public String toString() {
            JSONObject adb = channels.optJSONObject("adb");
            JSONObject adasJson = adb == null ? null : adb.optJSONObject("adas");
            JSONObject pcapJson = adb == null ? null : adb.optJSONObject("pcap");
            String adas = adasJson == null ? "unavailable"
                    : adasJson.optString("status", "unavailable");
            String pcap = pcapJson == null ? "unavailable"
                    : pcapJson.optString("status", "unavailable");
            return "state=" + state + " ready=" + ready + " live=" + liveChannel
                    + " someip=" + channels.optInt("someIpSubscribedTopics") + "/26"
                    + " raw=" + channels.optLong("someIpPersisted")
                    + " dropped=" + channels.optLong("someIpDropped")
                    + " adas=" + adas + " pcap=" + pcap
                    + " physicalHud=unverified";
        }
    }

    private static final class PendingRecord {
        final String event;
        final String detail;
        PendingRecord(String event, String detail) { this.event = event; this.detail = detail; }
    }

    private enum State { IDLE, STARTING, CAPTURING, PARTIAL, STOPPED, FAILED }
}
