package com.bydhud.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Bounded asynchronous raw SOME/IP journal. Duplicate payloads are intentionally retained. */
final class ShanghaiEventJournal implements Closeable {
    static final int QUEUE_CAPACITY = 512;
    static final long MAX_QUEUED_PAYLOAD_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;

    private final ArrayBlockingQueue<Item> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong persisted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final Map<Long, TopicStats> topics = new LinkedHashMap<>();
    private final BufferedWriter writer;
    private final Thread writerThread;
    private final LongSupplier elapsedRealtimeNanos;
    private volatile boolean accepting = true;
    private volatile boolean closed;
    private volatile String firstError = "";

    ShanghaiEventJournal(File file, Iterable<Long> requestedTopics) throws Exception {
        this(file, requestedTopics, android.os.SystemClock::elapsedRealtimeNanos);
    }

    ShanghaiEventJournal(File file, Iterable<Long> requestedTopics,
            LongSupplier elapsedRealtimeNanos) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create Shanghai journal directory");
        }
        for (Long topic : requestedTopics) topics.put(topic, new TopicStats(topic));
        this.elapsedRealtimeNanos = elapsedRealtimeNanos;
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8), 128 * 1024);
        writerThread = new Thread(this::writeLoop, "shanghai-someip-journal");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    void offer(long topic, long oemTimestamp, int declaredLength, byte[] payload) {
        byte[] safe = payload == null ? new byte[0] : payload.clone();
        long wall = System.currentTimeMillis();
        long elapsedNanos = elapsedRealtimeNanos.getAsLong();
        received.incrementAndGet();
        synchronized (topics) {
            topics.computeIfAbsent(topic, TopicStats::new).received(wall, safe.length);
        }
        if (!accepting || safe.length > MAX_PAYLOAD_BYTES || !reserve(safe.length)) {
            drop(topic);
            return;
        }
        if (!queue.offer(new Item(topic, oemTimestamp, declaredLength, safe, wall, elapsedNanos))) {
            queuedBytes.addAndGet(-safe.length);
            drop(topic);
        }
    }

    void rejectAfterSeal(long topic, byte[] payload) {
        byte[] safe = payload == null ? new byte[0] : payload;
        long wall = System.currentTimeMillis();
        received.incrementAndGet();
        synchronized (topics) {
            topics.computeIfAbsent(topic, TopicStats::new).received(wall, safe.length);
        }
        drop(topic);
    }

    private boolean reserve(int bytes) {
        while (true) {
            long current = queuedBytes.get();
            if (current + bytes > MAX_QUEUED_PAYLOAD_BYTES) return false;
            if (queuedBytes.compareAndSet(current, current + bytes)) return true;
        }
    }

    private void writeLoop() {
        long sequence = 0;
        try {
            while (accepting || !queue.isEmpty()) {
                Item item = queue.poll(250, TimeUnit.MILLISECONDS);
                if (item == null) continue;
                queuedBytes.addAndGet(-item.payload.length);
                String hash = sha256(item.payload);
                JSONObject line = new JSONObject();
                line.put("kind", "someip_event");
                line.put("sequence", ++sequence);
                line.put("topic", ShanghaiTopics.hex(item.topic));
                line.put("oemTimestamp", item.oemTimestamp);
                line.put("localWallTimestampMs", item.wallMs);
                line.put("localElapsedRealtimeNanos", item.elapsedNanos);
                line.put("journalDelayNanos", Math.max(0L,
                        elapsedRealtimeNanos.getAsLong() - item.elapsedNanos));
                line.put("declaredPayloadLength", item.declaredLength);
                line.put("payloadLength", item.payload.length);
                line.put("sha256", hash);
                line.put("payloadBase64", Base64.getEncoder().encodeToString(item.payload));
                writer.write(line.toString());
                writer.newLine();
                persisted.incrementAndGet();
                synchronized (topics) { topics.get(item.topic).persisted(hash); }
                if ((sequence & 63L) == 0L) writer.flush();
            }
            writer.flush();
        } catch (Throwable error) {
            writeFailures.incrementAndGet();
            firstError = error.toString();
        } finally {
            Item item;
            while ((item = queue.poll()) != null) {
                queuedBytes.addAndGet(-item.payload.length);
                drop(item.topic);
            }
        }
    }

    private void drop(long topic) {
        dropped.incrementAndGet();
        synchronized (topics) { topics.computeIfAbsent(topic, TopicStats::new).dropped++; }
    }

    long receivedCount() { return received.get(); }
    long persistedCount() { return persisted.get(); }
    long droppedCount() { return dropped.get(); }
    boolean healthy() { return writeFailures.get() == 0 && (writerThread.isAlive() || closed); }
    boolean finalized() { return !accepting && !writerThread.isAlive(); }
    String error() { return firstError; }

    JSONArray topicSummary() {
        JSONArray result = new JSONArray();
        synchronized (topics) {
            for (TopicStats topic : topics.values()) result.put(topic.json());
        }
        return result;
    }

    @Override public synchronized void close() {
        if (closed) return;
        accepting = false;
        try {
            writerThread.join(10_000L);
            if (writerThread.isAlive()) {
                writerThread.interrupt();
                writerThread.join(1_000L);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            firstError = "journal close interrupted";
            writeFailures.incrementAndGet();
        }
        try { writer.close(); }
        catch (Exception error) {
            firstError = error.toString();
            writeFailures.incrementAndGet();
        }
        closed = !writerThread.isAlive() && writeFailures.get() == 0;
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static final class Item {
        final long topic;
        final long oemTimestamp;
        final int declaredLength;
        final byte[] payload;
        final long wallMs;
        final long elapsedNanos;

        Item(long topic, long oemTimestamp, int declaredLength, byte[] payload,
                long wallMs, long elapsedNanos) {
            this.topic = topic;
            this.oemTimestamp = oemTimestamp;
            this.declaredLength = declaredLength;
            this.payload = payload;
            this.wallMs = wallMs;
            this.elapsedNanos = elapsedNanos;
        }
    }

    private static final class TopicStats {
        final long topic;
        long received;
        long persisted;
        long dropped;
        long firstWallMs;
        long lastWallMs;
        int minLength = Integer.MAX_VALUE;
        int maxLength;
        final Set<String> hashes = new LinkedHashSet<>();

        TopicStats(long topic) { this.topic = topic; }
        void received(long wall, int length) {
            received++;
            if (firstWallMs == 0L) firstWallMs = wall;
            lastWallMs = wall;
            minLength = Math.min(minLength, length);
            maxLength = Math.max(maxLength, length);
        }
        void persisted(String hash) { persisted++; hashes.add(hash); }
        JSONObject json() {
            return ShanghaiJson.object(
                    "topic", ShanghaiTopics.hex(topic),
                    "received", received,
                    "persisted", persisted,
                    "dropped", dropped,
                    "uniquePayloads", hashes.size(),
                    "firstWallMs", firstWallMs == 0L ? JSONObject.NULL : firstWallMs,
                    "lastWallMs", lastWallMs == 0L ? JSONObject.NULL : lastWallMs,
                    "minPayloadLength", received == 0L ? JSONObject.NULL : minLength,
                    "maxPayloadLength", received == 0L ? JSONObject.NULL : maxLength);
        }
    }
}
