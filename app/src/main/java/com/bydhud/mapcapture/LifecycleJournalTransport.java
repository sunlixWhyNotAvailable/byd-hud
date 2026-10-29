package com.bydhud.mapcapture;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Journal I/O and its Binder exchange never run on the frame, main or GL workers. */
public final class LifecycleJournalTransport {
    private static final Uri ENDPOINT = Uri.parse("content://com.bydhud.app.mapframes");
    private static final ScheduledExecutorService DISK = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "MapJournalDisk"));
    private static final ScheduledExecutorService WIRE = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "MapJournalTransfer"));
    private static final ArrayDeque<Entry> QUEUE = new ArrayDeque<>();
    private static long queuedBytes;
    private static boolean drainPosted, started;
    private static volatile Context app;
    private static volatile String captureSession = "", fault = "";
    private static volatile boolean detailed, transferAllowed;
    private static volatile int storagePending;
    private static volatile int unacknowledgedBatchCount;
    private static volatile String storageFault = "";
    private static long overflowCount;
    // Owned exclusively by DISK; restored sequences and ACK cursors remain durable.
    private static LifecycleJournalSpool spool;

    private static final class Entry {
        final String name, owner, detail;
        final long elapsedNs, bytes;
        Entry(String name, String owner, String detail) {
            this.name = name; this.owner = owner; this.detail = detail;
            elapsedNs = SystemClock.elapsedRealtimeNanos();
            bytes = 32L + 2L * (name.length() + owner.length() + detail.length());
        }
    }

    public static synchronized void start(Context context) {
        if (started || context == null) return;
        app = context.getApplicationContext();
        if (app == null) return;
        started = true;
        WIRE.scheduleWithFixedDelay(LifecycleJournalTransport.transfer, 0, 500, TimeUnit.MILLISECONDS);
        synchronized (QUEUE) { scheduleDrain(0L); }
    }

    public static void policy(Bundle reply) {
        detailed = reply.getBoolean("detailedLogs", false);
        transferAllowed = reply.getBoolean("active", false) || reply.getBoolean("journalEnabled", false);
        if (reply.getBoolean("active", false)) captureSession = reply.getString("session", "");
    }

    public static boolean detailed() { return detailed; }

    public static boolean shouldLog(String name) {
        return LifecycleJournalPolicy.shouldLog(detailed, name);
    }

    public static void append(String name, String owner, String detail) {
        if (!shouldLog(name)) return;
        Entry entry = new Entry(name, owner, detail == null ? "" : detail);
        synchronized (QUEUE) {
            if (QUEUE.size() >= 128 || queuedBytes + entry.bytes > 512L * 1024L) {
                // Same bounded retry budget as the durable spool. Never acknowledge an unpersisted event.
                overflowCount++;
                Log.e("BydHudMapCapture", "unpersisted event=" + name + " owner=" + owner + " detail=" + entry.detail);
                failure("enqueue", new IllegalStateException("lifecycle journal pending queue full"));
                return;
            }
            QUEUE.addLast(entry); queuedBytes += entry.bytes;
            scheduleDrain(0L);
        }
    }

    private static void scheduleDrain(long delayMs) {
        if (app == null || drainPosted || (QUEUE.isEmpty() && storagePending == 0)) return;
        drainPosted = true;
        DISK.schedule(() -> {
            try { drain(); }
            catch (Exception error) { failure("persist", error); }
            finally { synchronized (QUEUE) {
                drainPosted = false;
                // Arrivals during this drain and failed writes must progress even after the lease ends.
                scheduleDrain(500L);
            } }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static LifecycleJournalSpool storage() throws Exception {
        if (spool == null) {
            int pid = android.os.Process.myPid();
            spool = new LifecycleJournalSpool(app, pid, pid + "-" + UUID.randomUUID());
        }
        return spool;
    }

    private static void drain() throws Exception {
        LifecycleJournalSpool store = storage();
        // Bound each drain so an active renderer cannot starve ACKs and batch snapshots.
        int remaining;
        synchronized (QUEUE) { remaining = QUEUE.size(); }
        while (remaining-- > 0) {
            Entry entry;
            synchronized (QUEUE) { entry = QUEUE.peekFirst(); }
            if (entry == null) break;
            store.append(entry.name, entry.owner, entry.detail, entry.elapsedNs);
            synchronized (QUEUE) { QUEUE.removeFirst(); queuedBytes -= entry.bytes; }
        }
        store.batch(false); // Retry already enqueued failed writes without a collector or a new event.
        storagePending = store.pendingEventCount();
        storageFault = store.writeFault();
    }

    private static Bundle batch() throws Exception {
        drain();
        LifecycleJournalSpool store = storage();
        LifecycleJournalBatch batch = store.batch(true);
        storagePending = store.pendingEventCount();
        storageFault = store.writeFault();
        Bundle data = new Bundle();
        data.putString("package", app.getPackageName());
        data.putString("captureSession", captureSession);
        data.putString("lifecycleOwner", app.getPackageName());
        data.putString("lifecycleSessionId", store.sessionId());
        data.putString("lifecycleEvents", batch.events.toString());
        data.putLong("lifecycleCursorSequence", batch.completeThrough);
        data.putLong("lifecycleChunkSequence", batch.partialSequence);
        data.putInt("lifecycleChunkIndex", batch.partialChunkIndex);
        if (!store.writeFault().isEmpty()) data.putString("lifecycleJournalError", store.writeFault());
        return data;
    }

    private static final Runnable transfer = () -> {
        if (!transferAllowed) return;
        try {
            Bundle sent = DISK.submit(LifecycleJournalTransport::batch).get();
            unacknowledgedBatchCount = new org.json.JSONArray(sent.getString("lifecycleEvents")).length();
            Bundle reply = app.getContentResolver().call(ENDPOINT, "journal", null, sent);
            if (reply == null || !reply.getBoolean("journalTransport", false))
                throw new IllegalStateException("collector does not support separate journal transport; backlog retained");
            if (reply.getBoolean("journalAccepted", false)) {
                if (!app.getPackageName().equals(reply.getString("journalOwner"))
                        || !sent.getString("lifecycleSessionId").equals(reply.getString("journalSessionId")))
                    throw new IllegalStateException("journal ACK owner/session mismatch");
                long complete = reply.getLong("journalAckSequence", -1L);
                long partial = reply.getLong("journalAckChunkSequence", 0L);
                int chunk = reply.getInt("journalAckChunkIndex", 0);
                if (!LifecycleJournalPolicy.ackWithinBatch(complete, partial, chunk,
                        sent.getLong("lifecycleCursorSequence"), sent.getLong("lifecycleChunkSequence"),
                        sent.getInt("lifecycleChunkIndex")))
                    throw new IllegalStateException("journal ACK outside sent range");
                DISK.submit(() -> {
                    if (!storage().acknowledge(complete, partial, chunk))
                        throw new IllegalStateException("journal ACK rejected by durable cursor");
                    return null;
                }).get();
                if (complete == sent.getLong("lifecycleCursorSequence")
                        && partial == sent.getLong("lifecycleChunkSequence")
                        && chunk == sent.getInt("lifecycleChunkIndex")) unacknowledgedBatchCount = 0;
            }
            String collectorError = reply.getString("journalError", "");
            if (!collectorError.isEmpty()) throw new IllegalStateException("collector: " + collectorError);
            if (unacknowledgedBatchCount > 0 && !reply.getBoolean("journalAccepted", false))
                throw new IllegalStateException("collector returned no durable ACK for a nonempty batch; cause unavailable");
            fault = "";
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failure("transfer", error);
        }
    };

    public static Bundle health() {
        Bundle data = new Bundle();
        String problem = fault.isEmpty() ? storageFault : fault;
        // Full failures remain in logcat; frame metadata must stay bounded even on queue overflow.
        data.putString("lifecycleJournalError", problem.substring(0, Math.min(4096, problem.length())));
        data.putInt("lifecycleUnackedBatchCount", unacknowledgedBatchCount);
        synchronized (QUEUE) {
            data.putInt("lifecyclePendingCount", QUEUE.size() + storagePending);
            data.putLong("lifecycleUnpersistedCount", overflowCount);
        }
        return data;
    }

    private static void failure(String stage, Throwable error) {
        String message = stage + ": " + Log.getStackTraceString(error);
        if (!message.equals(fault)) Log.e("BydHudMapCapture", stage, error);
        fault = message;
    }
}
