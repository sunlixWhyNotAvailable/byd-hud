package com.bydhud.app;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the two isolated local-ADB streams used by one Shanghai capture. */
final class ShanghaiDiagnosticAdb implements AutoCloseable {
    private final android.content.Context context;
    private final File directory;
    private final String token;
    private final StreamState adas = new StreamState("adas");
    private final StreamState pcap = new StreamState("pcap");
    private volatile String beforeSnapshot = "pending";
    private volatile String afterSnapshot = "pending";
    private volatile boolean stopped;

    ShanghaiDiagnosticAdb(android.content.Context context, File directory, String token) {
        this.context = context.getApplicationContext();
        this.directory = directory;
        this.token = token;
    }

    void start() {
        beforeSnapshot = snapshot("before");
        startAdas();
        startPcap();
    }

    private void startAdas() {
        try {
            LocalAdbBridge.ShanghaiStreamSession session =
                    LocalAdbBridge.openShanghaiAdasStream(context, token);
            File file = new File(directory, "adas.jsonl");
            adas.start(session, file, true);
        } catch (Throwable error) {
            adas.fail(error);
        }
    }

    private void startPcap() {
        try {
            LocalAdbBridge.ShellResult probe = LocalAdbBridge.probeShanghaiPcap(context);
            writeResult(new File(directory, "pcap-probe.txt"), probe);
            if (!probe.success() || probe.truncated) {
                pcap.fail(new IOException("tcpdump unavailable: " + probe.shortDetail()));
                return;
            }
            String path = probe.output.contains("/vendor/bin/tcpdump")
                    ? "/vendor/bin/tcpdump" : "/system/bin/tcpdump";
            String network = probe.output.contains("interface=eth0") ? "eth0" : "any";
            LocalAdbBridge.ShanghaiStreamSession session =
                    LocalAdbBridge.openShanghaiPcapStream(context, token, path, network);
            pcap.detail = path + " interface=" + network + " filter=udp";
            pcap.start(session, new File(directory, "udp.pcap"), false);
        } catch (Throwable error) {
            pcap.fail(error);
        }
    }

    String snapshot(String label) {
        String safe = "after".equals(label) ? "after" : "before";
        try {
            LocalAdbBridge.ShellResult result = LocalAdbBridge.captureShanghaiSnapshot(context);
            writeResult(new File(directory, safe + "-snapshot.txt"), result);
            if (result.success() && !result.truncated) return "captured";
            return result.success() ? "partial_truncated" : "unavailable:" + result.exitCode;
        } catch (Throwable error) {
            writeError(new File(directory, safe + "-snapshot-error.txt"), error);
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    boolean adasReady() { return adas.ready.get(); }
    boolean pcapReady() { return pcap.bytes.get() >= 24L; }

    void resolveReadiness(long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if ((adas.ready.get() || !adas.live()) && (pcapReady() || !pcap.live())) return;
            android.os.SystemClock.sleep(50L);
        }
        if (!adas.ready.get() && adas.live()) readinessTimeout(adas);
        if (!pcapReady() && pcap.live()) readinessTimeout(pcap);
    }

    private void readinessTimeout(StreamState stream) {
        stopStream(stream);
        stream.error = "channel readiness timed out";
        stream.status = "unavailable";
    }
    boolean hasLiveChannel() { return adas.live() || pcap.live(); }
    boolean healthy() { return !stopped && (adas.live() || pcap.live()); }
    boolean finalized() {
        return stopped && adas.isTerminated() && pcap.isTerminated();
    }

    JSONObject coverageJson() {
        return ShanghaiJson.object(
                "beforeSnapshot", beforeSnapshot,
                "afterSnapshot", afterSnapshot,
                "adas", adas.json(),
                "pcap", pcap.json());
    }

    @Override public synchronized void close() {
        if (stopped) return;
        stopped = true;
        stopStream(adas);
        stopStream(pcap);
        afterSnapshot = snapshot("after");
    }

    private void stopStream(StreamState stream) {
        try {
            LocalAdbBridge.ShellResult result =
                    LocalAdbBridge.stopShanghaiStream(context, token, stream.name);
            if (result == null || !result.success() || result.truncated) {
                stream.stopError = result == null ? "missing stop result" : result.shortDetail();
            }
        }
        catch (Throwable error) { stream.stopError = error.toString(); }
        stream.close();
    }

    private static void writeResult(File file, LocalAdbBridge.ShellResult result) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(("status=" + result.status + " exit=" + result.exitCode
                    + " truncated=" + result.truncated + " droppedBytes=" + result.droppedBytes
                    + "\n" + result.output + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeError(File file, Throwable error) {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write((error.getClass().getName() + ": "
                    + String.valueOf(error.getMessage())).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    static final class StreamState {
        final String name;
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong droppedBytes = new AtomicLong();
        final AtomicBoolean ready = new AtomicBoolean();
        final AtomicBoolean helperError = new AtomicBoolean();
        volatile LocalAdbBridge.ShanghaiStreamSession session;
        volatile Thread thread;
        volatile String status = "not_started";
        volatile String detail = "";
        volatile String error = "";
        volatile String stopError = "";

        StreamState(String name) { this.name = name; }

        void start(LocalAdbBridge.ShanghaiStreamSession session, File file, boolean watchReady)
                throws IOException {
            this.session = session;
            OutputStream output = new WatchOutputStream(
                    new FileOutputStream(file, false), bytes, droppedBytes, ready, helperError,
                    watchReady, watchReady ? 32L * 1024L * 1024L : 128L * 1024L * 1024L);
            status = "starting";
            thread = new Thread(() -> {
                status = "streaming";
                try (OutputStream owned = output; LocalAdbBridge.ShanghaiStreamSession ownedSession = session) {
                    ownedSession.readTo(owned);
                    if (!"stopping".equals(status)) {
                        boolean insufficient = watchReady ? !ready.get() : bytes.get() < 24L;
                        if (helperError.get() || insufficient) {
                            error = helperError.get() ? "helper reported an error"
                                    : "stream ended before channel readiness";
                            status = "failed";
                        } else {
                            status = "completed";
                        }
                    }
                } catch (Throwable failure) {
                    if (!"stopping".equals(status)) {
                        error = failure.toString();
                        status = "failed";
                    }
                }
            }, "shanghai-" + name + "-stream");
            thread.setDaemon(true);
            thread.start();
        }

        void fail(Throwable failure) {
            error = failure.toString();
            status = "unavailable";
        }

        boolean live() {
            Thread current = thread;
            return current != null && current.isAlive()
                    && ("starting".equals(status) || "streaming".equals(status));
        }

        boolean isTerminated() {
            Thread current = thread;
            return current == null || !current.isAlive();
        }

        void close() {
            status = "stopping";
            LocalAdbBridge.ShanghaiStreamSession current = session;
            if (current != null) current.close();
            Thread worker = thread;
            if (worker != null) {
                try { worker.join(3_000L); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                if (worker.isAlive()) worker.interrupt();
            }
            status = worker != null && worker.isAlive() ? "stop_unconfirmed" : "stopped";
        }

        JSONObject json() {
            return ShanghaiJson.object(
                    "status", status,
                    "ready", ready.get(),
                    "bytes", bytes.get(),
                    "droppedBytes", droppedBytes.get(),
                    "captureComplete", droppedBytes.get() == 0L && error.isEmpty()
                            && stopError.isEmpty() && !"stop_unconfirmed".equals(status),
                    "detail", detail,
                    "error", error,
                    "stopError", stopError);
        }
    }

    private static final class WatchOutputStream extends FilterOutputStream {
        private static final byte[] READY = "\"event\":\"adas_sampler_ready\""
                .getBytes(StandardCharsets.UTF_8);
        private static final byte[] ERROR = "\"event\":\"adas_sampler_error\""
                .getBytes(StandardCharsets.UTF_8);
        private final AtomicLong bytes;
        private final AtomicLong droppedBytes;
        private final AtomicBoolean ready;
        private final AtomicBoolean helperError;
        private final boolean watchReady;
        private final long maxBytes;
        private int readyMatch;
        private int errorMatch;

        WatchOutputStream(OutputStream output, AtomicLong bytes, AtomicLong droppedBytes,
                AtomicBoolean ready, AtomicBoolean helperError, boolean watchReady, long maxBytes) {
            super(output);
            this.bytes = bytes;
            this.droppedBytes = droppedBytes;
            this.ready = ready;
            this.helperError = helperError;
            this.watchReady = watchReady;
            this.maxBytes = maxBytes;
        }

        @Override public void write(int value) throws IOException {
            if (bytes.get() < maxBytes) {
                super.write(value);
                bytes.incrementAndGet();
            } else {
                droppedBytes.incrementAndGet();
            }
            inspect((byte) value);
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            long remaining = Math.max(0L, maxBytes - bytes.get());
            int accepted = (int) Math.min(length, remaining);
            if (accepted > 0) {
                out.write(value, offset, accepted);
                bytes.addAndGet(accepted);
            }
            droppedBytes.addAndGet(length - accepted);
            if (watchReady) {
                for (int index = offset; index < offset + length; index++) inspect(value[index]);
            }
        }

        private void inspect(byte value) {
            if (!watchReady) return;
            if (!ready.get()) {
                if (value == READY[readyMatch]) {
                    if (++readyMatch == READY.length) ready.set(true);
                } else {
                    readyMatch = value == READY[0] ? 1 : 0;
                }
            }
            if (!helperError.get()) {
                if (value == ERROR[errorMatch]) {
                    if (++errorMatch == ERROR.length) helperError.set(true);
                } else {
                    errorMatch = value == ERROR[0] ? 1 : 0;
                }
            }
        }
    }
}
