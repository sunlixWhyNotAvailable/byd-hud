package com.bydhud.mapcapture;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;

/** Durable payload-side storage for lifecycle records awaiting collector acknowledgement. */
final class LifecycleJournalSpool {
    private static final String FILE_NAME = "bydhud-mapcapture-lifecycle.jsonl";
    private static final String EVENT = "event";
    private static final String CURSOR = "cursor";
    private static final long MAX_PENDING_BYTES = 512L * 1024L;
    private static final int MAX_PENDING_EVENTS = 128;
    private static final long CURSOR_COMPACTION_BYTES = 512L * 1024L;
    private static final int CURSOR_COMPACTION_RECORDS = 128;

    private final Object lock = new Object();
    private final File file;
    private int processId;
    private String sessionId;
    private long sequence;
    private long acknowledgedSequence;
    private long acknowledgedPartialSequence;
    private int acknowledgedPartialChunk;
    private final ArrayDeque<JSONObject> pendingEvents = new ArrayDeque<>();
    private long pendingBytes;
    private int cursorRecordsSinceCompaction;
    private boolean recoveredPartialTail;
    private String writeFault = "";

    LifecycleJournalSpool(Context context, int processId, String newSessionId) throws IOException {
        if (context == null || newSessionId == null || newSessionId.isEmpty()) {
            throw new IOException("lifecycle journal context unavailable");
        }
        File files = context.getFilesDir();
        if (files == null || (!files.isDirectory() && !files.mkdirs())) {
            throw new IOException("lifecycle journal directory unavailable");
        }
        this.file = new File(files, FILE_NAME);
        this.processId = processId;
        restore(newSessionId);
    }

    String sessionId() {
        synchronized (lock) { return sessionId; }
    }

    JSONObject append(String name, String owner, String detail, long elapsedNs) throws IOException {
        synchronized (lock) {
            try { flushPending(); }
            catch (IOException error) { writeFault = error.getClass().getSimpleName(); }
            JSONObject entry = event(sequence + pendingEvents.size() + 1L,
                    name, owner, detail, elapsedNs);
            if (!pendingEvents.isEmpty()) {
                enqueue(entry);
                return entry;
            }
            try {
                writeLine(file, new JSONObject().put("kind", EVENT).put("entry", entry));
                sequence++;
                writeFault = "";
            } catch (IOException | JSONException error) {
                writeFault = error.getClass().getSimpleName();
                enqueue(entry);
            }
            return entry;
        }
    }

    LifecycleJournalBatch batch(boolean include) throws IOException {
        synchronized (lock) {
            try { flushPending(); }
            catch (IOException error) { writeFault = error.getClass().getSimpleName(); }
            if (!include) {
                return LifecycleJournalBatch.build(Collections.<JSONObject>emptyList(), false,
                        acknowledgedSequence, acknowledgedPartialSequence, acknowledgedPartialChunk);
            }
            if (pendingEvents.isEmpty()) {
                Recovery recovery = scanAndRepairTail(null);
                if (recovery.recoveredPartialTail) {
                    recoveredPartialTail = true;
                    queueRecoveryEvent();
                    try { flushPending(); }
                    catch (IOException error) { writeFault = error.getClass().getSimpleName(); }
                }
            }
            if (recoveredPartialTail) {
                throw new IOException("lifecycle journal partial tail recovered");
            }
            try (EventIterator events = new EventIterator(file)) {
                return LifecycleJournalBatch.build(events, true, acknowledgedSequence,
                        acknowledgedPartialSequence, acknowledgedPartialChunk);
            }
        }
    }

    int pendingEventCount() { synchronized (lock) { return pendingEvents.size(); } }
    String writeFault() { synchronized (lock) { return writeFault; } }

    private void enqueue(JSONObject entry) throws IOException {
        long bytes = entry.toString().getBytes(StandardCharsets.UTF_8).length;
        if (pendingEvents.size() >= MAX_PENDING_EVENTS || pendingBytes + bytes > MAX_PENDING_BYTES) {
            writeFault = "retry_queue_full";
            throw new IOException("lifecycle journal retry queue full; event detail preserved in error log");
        }
        pendingEvents.addLast(entry);
        pendingBytes += bytes;
    }

    private void flushPending() throws IOException {
        if (pendingEvents.isEmpty()) return;
        Recovery recovery = scanAndRepairTail(null);
        if (recovery.maximumEventSequence > sequence) sequence = recovery.maximumEventSequence;
        if (recovery.recoveredPartialTail) {
            recoveredPartialTail = true;
        }
        while (!pendingEvents.isEmpty()) {
            JSONObject entry = pendingEvents.peekFirst();
            long pendingSequence = entry.optLong("sequence", -1L);
            if (pendingSequence <= sequence) {
                if (pendingSequence != sequence || !sameEvent(recovery.lastEvent, entry)) {
                    throw new IOException("lifecycle journal sequence conflict");
                }
                removePending();
                continue;
            }
            if (pendingSequence != sequence + 1L) {
                throw new IOException("lifecycle journal sequence gap");
            }
            try {
                writeLine(file, new JSONObject().put("kind", EVENT).put("entry", entry));
                sequence = pendingSequence;
                removePending();
                writeFault = "";
            } catch (IOException | JSONException error) {
                writeFault = error.getClass().getSimpleName();
                throw new IOException("cannot flush pending lifecycle event", error);
            }
        }
        if (recoveredPartialTail) queueRecoveryEvent();
    }

    private void removePending() {
        JSONObject removed = pendingEvents.removeFirst();
        pendingBytes -= removed.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private void queueRecoveryEvent() throws IOException {
        if (!recoveredPartialTail || !pendingEvents.isEmpty()) return;
        JSONObject marker = event(sequence + 1L,
                "lifecycle_journal_recovered", "journal", "partial_final_record_repaired",
                android.os.SystemClock.elapsedRealtimeNanos());
        try {
            writeLine(file, new JSONObject().put("kind", EVENT).put("entry", marker));
            sequence++;
            writeFault = "";
        } catch (IOException | JSONException error) {
            writeFault = error.getClass().getSimpleName();
            enqueue(marker);
        }
        recoveredPartialTail = false;
    }

    private static JSONObject event(long sequence, String name, String owner, String detail, long elapsedNs)
            throws IOException {
        try {
            return new JSONObject().put("sequence", sequence).put("elapsedNs", elapsedNs)
                    .put("event", name).put("owner", owner).put("detail", detail);
        } catch (JSONException error) {
            throw new IOException("cannot encode lifecycle event", error);
        }
    }

    private static boolean sameEvent(JSONObject first, JSONObject second) {
        return first != null && first.optLong("sequence", -1L) == second.optLong("sequence", -2L)
                && first.optLong("elapsedNs", -1L) == second.optLong("elapsedNs", -2L)
                && first.optString("event", "").equals(second.optString("event", "!"))
                && first.optString("owner", "").equals(second.optString("owner", "!"))
                && first.optString("detail", "").equals(second.optString("detail", "!"));
    }

    private interface RecordConsumer { void accept(JSONObject record) throws IOException; }

    private static final class Recovery {
        long maximumEventSequence;
        JSONObject lastEvent;
        boolean recoveredPartialTail;
        long truncateAt;
    }

    private static final class RestoreState {
        String session;
        long sequence;
        long complete;
        long partialSequence;
        int partialChunk;
        int cursorRecords;
    }

    private Recovery scanAndRepairTail(RecordConsumer consumer) throws IOException {
        Recovery result = new Recovery();
        if (!file.isFile()) return result;
        try (BufferedInputStream input = new BufferedInputStream(new AtomicFile(file).openRead())) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            long offset = 0L;
            long lineStart = 0L;
            int next;
            while ((next = input.read()) != -1) {
                offset++;
                if (next == '\n') {
                    if (!consumeLine(line.toByteArray(), true, input, lineStart, result, consumer)) break;
                    line.reset();
                    lineStart = offset;
                } else {
                    line.write(next);
                }
            }
            if (line.size() > 0 && !result.recoveredPartialTail) {
                if (!consumeLine(line.toByteArray(), false, input, lineStart, result, consumer)) {
                    // The invalid final record was removed by consumeLine.
                } else {
                    try (FileOutputStream output = new FileOutputStream(file, true)) {
                        output.write('\n');
                        output.getFD().sync();
                    }
                }
            }
        }
        if (result.recoveredPartialTail) {
            try (RandomAccessFile repair = new RandomAccessFile(file, "rw")) {
                repair.setLength(result.truncateAt);
                repair.getFD().sync();
            }
        }
        return result;
    }

    private boolean consumeLine(byte[] bytes, boolean newline, BufferedInputStream input,
            long lineStart, Recovery result, RecordConsumer consumer) throws IOException {
        if (bytes.length == 0) return true;
        final JSONObject record;
        try {
            record = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException malformed) {
            int following = newline ? input.read() : -1;
            if (following != -1) {
                throw new IOException("malformed lifecycle journal record before file tail", malformed);
            }
            result.recoveredPartialTail = true;
            result.truncateAt = lineStart;
            return false;
        }
        if (EVENT.equals(record.optString("kind", ""))) {
            JSONObject entry = record.optJSONObject("entry");
            if (entry == null) throw new IOException("invalid lifecycle event record");
            long eventSequence = entry.optLong("sequence", 0L);
            if (eventSequence >= result.maximumEventSequence) {
                result.maximumEventSequence = eventSequence;
                result.lastEvent = entry;
            }
        }
        if (consumer != null) consumer.accept(record);
        return true;
    }

    long acknowledgedSequence() {
        synchronized (lock) { return acknowledgedSequence; }
    }

    long acknowledgedPartialSequence() {
        synchronized (lock) { return acknowledgedPartialSequence; }
    }

    int acknowledgedPartialChunk() {
        synchronized (lock) { return acknowledgedPartialChunk; }
    }

    boolean acknowledge(long complete, long partialSequence, int partialChunk) throws IOException {
        synchronized (lock) {
            if (complete < acknowledgedSequence || complete > sequence
                    || partialSequence < 0L || partialChunk < 0 || partialSequence > sequence) return false;
            if (partialSequence > 0L && (partialSequence <= complete || partialChunk < 1)) return false;
            if (complete == acknowledgedSequence
                    && partialSequence == acknowledgedPartialSequence
                    && partialChunk == acknowledgedPartialChunk) return true;

            long previousComplete = acknowledgedSequence;
            long previousPartialSequence = acknowledgedPartialSequence;
            int previousPartialChunk = acknowledgedPartialChunk;
            acknowledgedSequence = complete;
            acknowledgedPartialSequence = partialSequence;
            acknowledgedPartialChunk = partialSequence == 0L ? 0 : partialChunk;
            try {
                writeLine(file, cursorRecord());
            } catch (IOException error) {
                writeFault = error.getClass().getSimpleName();
                acknowledgedSequence = previousComplete;
                acknowledgedPartialSequence = previousPartialSequence;
                acknowledgedPartialChunk = previousPartialChunk;
                throw error;
            }
            writeFault = "";
            cursorRecordsSinceCompaction++;
            boolean fullyDrained = acknowledgedSequence >= sequence && acknowledgedPartialSequence == 0L;
            boolean compactForSize = file.length() >= CURSOR_COMPACTION_BYTES
                    && cursorRecordsSinceCompaction >= CURSOR_COMPACTION_RECORDS;
            if (fullyDrained || compactForSize) {
                try { compact(); }
                catch (IOException error) {
                    // The synced cursor is authoritative; compaction only reclaims space.
                    Log.w("BydHudMapCapture", "lifecycle journal compaction deferred", error);
                }
            }
            return true;
        }
    }

    private void restore(String newSessionId) throws IOException {
        synchronized (lock) {
            RestoreState stored = new RestoreState();
            Recovery recovery = scanAndRepairTail(record -> {
                if (!CURSOR.equals(record.optString("kind", ""))) return;
                stored.cursorRecords++;
                stored.session = record.optString("session", stored.session);
                stored.sequence = Math.max(stored.sequence, record.optLong("sequence", 0L));
                stored.complete = Math.max(stored.complete, record.optLong("complete", 0L));
                long partial = record.optLong("partialSequence", 0L);
                int chunk = record.optInt("partialChunk", 0);
                if (stored.partialSequence <= stored.complete) {
                    stored.partialSequence = 0L;
                    stored.partialChunk = 0;
                }
                if (partial > stored.complete && (partial > stored.partialSequence
                        || (partial == stored.partialSequence && chunk > stored.partialChunk))) {
                    stored.partialSequence = partial;
                    stored.partialChunk = chunk;
                }
            });
            boolean hasPending = recovery.maximumEventSequence > stored.complete;
            if (hasPending && stored.session != null && !stored.session.isEmpty()) {
                sessionId = stored.session;
                sequence = Math.max(recovery.maximumEventSequence, stored.sequence);
                acknowledgedSequence = stored.complete;
                acknowledgedPartialSequence = stored.partialSequence;
                acknowledgedPartialChunk = stored.partialChunk;
                cursorRecordsSinceCompaction = stored.cursorRecords;
                if (acknowledgedPartialSequence > sequence) {
                    throw new IOException("invalid lifecycle journal cursor");
                }
            } else if (recovery.maximumEventSequence > 0L) {
                // If the cursor row was lost, retain every event rather than
                // resetting the sequence and allowing new events to collide.
                sessionId = newSessionId;
                sequence = recovery.maximumEventSequence;
                acknowledgedSequence = 0L;
                acknowledgedPartialSequence = 0L;
                acknowledgedPartialChunk = 0;
                cursorRecordsSinceCompaction = 1;
                // Add a cursor without rewriting the event-only prefix. This
                // preserves every recoverable event if its original cursor row
                // was lost and avoids making recovery depend on compaction.
                writeLine(file, cursorRecord());
            } else {
                sessionId = newSessionId;
                sequence = 0L;
                acknowledgedSequence = 0L;
                acknowledgedPartialSequence = 0L;
                acknowledgedPartialChunk = 0;
                compact();
            }
            if (recovery.recoveredPartialTail) {
                recoveredPartialTail = true;
                queueRecoveryEvent();
                flushPending();
                Log.w("BydHudMapCapture", "repaired malformed final lifecycle journal record");
            }
        }
    }

    private void compact() throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try (BufferedReader old = file.isFile() ? new BufferedReader(new InputStreamReader(
                atomic.openRead(), StandardCharsets.UTF_8)) : null) {
            output = atomic.startWrite();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            if (old != null) {
                String line;
                while ((line = old.readLine()) != null) {
                    JSONObject record = new JSONObject(line);
                    if (!EVENT.equals(record.optString("kind", ""))) continue;
                    JSONObject entry = record.optJSONObject("entry");
                    if (entry == null) throw new IOException("invalid lifecycle event record");
                    if (entry.optLong("sequence", 0L) > acknowledgedSequence) {
                        writer.write(record.toString());
                        writer.newLine();
                    }
                }
            }
            writer.write(cursorRecord().toString());
            writer.newLine();
            writer.flush();
            atomic.finishWrite(output);
            cursorRecordsSinceCompaction = 1;
        } catch (JSONException error) {
            if (output != null) atomic.failWrite(output);
            throw new IOException("cannot compact lifecycle journal", error);
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("cannot compact lifecycle journal", error);
        }
    }

    private JSONObject cursorRecord() throws IOException {
        try {
            return new JSONObject().put("kind", CURSOR).put("session", sessionId)
                    .put("pid", processId).put("sequence", sequence)
                    .put("complete", acknowledgedSequence)
                    .put("partialSequence", acknowledgedPartialSequence)
                    .put("partialChunk", acknowledgedPartialChunk);
        } catch (JSONException error) {
            throw new IOException("cannot encode lifecycle cursor", error);
        }
    }

    private static void writeLine(File file, JSONObject record) throws IOException {
        byte[] bytes = (record.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static final class EventIterator implements Iterator<JSONObject>, AutoCloseable {
        private final BufferedReader reader;
        private JSONObject next;
        private boolean finished;

        EventIterator(File file) throws IOException {
            reader = new BufferedReader(new InputStreamReader(
                    new AtomicFile(file).openRead(), StandardCharsets.UTF_8));
        }

        @Override public boolean hasNext() {
            if (next != null) return true;
            if (finished) return false;
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject record = new JSONObject(line);
                    if (!EVENT.equals(record.optString("kind", ""))) continue;
                    next = record.optJSONObject("entry");
                    if (next == null) throw new IOException("invalid lifecycle event record");
                    return true;
                }
                finished = true;
                return false;
            } catch (IOException | JSONException error) {
                throw new IllegalStateException("cannot read lifecycle journal", error);
            }
        }

        @Override public JSONObject next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            JSONObject result = next;
            next = null;
            return result;
        }

        @Override public void close() throws IOException { reader.close(); }
    }
}
