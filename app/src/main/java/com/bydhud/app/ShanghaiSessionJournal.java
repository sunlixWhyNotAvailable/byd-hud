package com.bydhud.app;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded asynchronous route/session journal. */
final class ShanghaiSessionJournal implements Closeable {
    static final int CAPACITY = 1024;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final BufferedWriter writer;
    private final Thread thread;
    private volatile boolean accepting = true;
    private volatile boolean closed;
    private volatile String error = "";

    ShanghaiSessionJournal(File file) throws Exception {
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8), 64 * 1024);
        thread = new Thread(this::loop, "shanghai-session-journal");
        thread.setDaemon(true);
        thread.start();
    }

    void record(String event, String detail) {
        JSONObject line = ShanghaiJson.object(
                "event", limit(event, 96),
                "detail", limit(detail, 4096),
                "wallTimestampMs", System.currentTimeMillis(),
                "elapsedRealtimeNanos", android.os.SystemClock.elapsedRealtimeNanos());
        if (!accepting || !queue.offer(line.toString())) dropped.incrementAndGet();
    }

    private void loop() {
        try {
            while (accepting || !queue.isEmpty()) {
                String line = queue.poll(250L, TimeUnit.MILLISECONDS);
                if (line == null) continue;
                writer.write(line);
                writer.newLine();
                long count = written.incrementAndGet();
                if ((count & 63L) == 0L) writer.flush();
            }
            writer.flush();
        } catch (Throwable failure) {
            error = failure.toString();
            dropped.addAndGet(queue.size());
            queue.clear();
        }
    }

    long writtenCount() { return written.get(); }
    long droppedCount() { return dropped.get(); }
    boolean healthy() { return error.isEmpty() && (thread.isAlive() || closed); }
    boolean finalized() { return !accepting && !thread.isAlive(); }
    String error() { return error; }

    @Override public synchronized void close() {
        if (closed) return;
        accepting = false;
        try {
            thread.join(5_000L);
            if (thread.isAlive()) thread.interrupt();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            error = "session journal close interrupted";
        }
        try { writer.close(); }
        catch (Exception failure) { error = failure.toString(); }
        closed = !thread.isAlive() && error.isEmpty();
    }

    private static String limit(String value, int maximum) {
        String safe = value == null ? "" : value.replace('\0', ' ');
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }
}
