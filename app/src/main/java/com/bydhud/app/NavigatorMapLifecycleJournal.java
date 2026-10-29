package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Persists authenticated producer journal fragments before returning an acknowledgement. */
final class NavigatorMapLifecycleJournal {
    private static final Object LOCK = new Object();
    private static final String PREFS = "navigator_map_lifecycle_journal";
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "MapJournalCollector"));
    private static final java.util.Map<String, Pending> LEGACY = new java.util.HashMap<>();

    private static final class Pending {
        final String session;
        boolean busy;
        Result saved = new Result(false, 0L, 0L, 0);
        Pending(String session) { this.session = session; }
    }

    /** Old producers retry the unacknowledged batch; poll returns only an already durable cursor. */
    static Result acceptLater(Context context, String owner, String session, JSONArray fragments,
            long through, long partial, int chunk, Store store) {
        if (!safeOwner(owner) || !safeSession(session)) return new Result(false, 0L, 0L, 0);
        synchronized (LEGACY) {
            Pending pending = LEGACY.get(owner);
            if (pending == null || !pending.session.equals(session)) {
                if (pending != null && pending.busy) return new Result(false, 0L, 0L, 0);
                pending = new Pending(session);
                LEGACY.put(owner, pending);
            }
            Pending current = pending;
            Result saved = current.saved;
            if (!current.busy) {
                current.busy = true;
                WRITER.execute(() -> {
                    try {
                        Result result = accept(context, owner, session, fragments, through, partial, chunk, store);
                        boolean changedError;
                        synchronized (LEGACY) {
                            changedError = !result.error.isEmpty() && !result.error.equals(current.saved.error);
                            current.saved = result;
                        }
                        if (changedError) AppEventLogger.event(context,
                                "navigator_map_capture legacy_journal_error owner=" + owner
                                        + " producerSession=" + session + " cause=" + result.error);
                    } catch (RuntimeException error) {
                        android.util.Log.e("BydHudMapCapture", "collector journal persistence", error);
                    } finally { synchronized (LEGACY) { current.busy = false; } }
                });
            }
            long complete = Math.min(saved.ackSequence, through);
            boolean samePartial = partial > complete && partial == saved.ackChunkSequence;
            return new Result(saved.accepted, complete, samePartial ? partial : 0L,
                    samePartial ? Math.min(chunk, saved.ackChunkIndex) : 0, saved.error);
        }
    }

    interface Store {
        boolean store(String owner, String producerSession, java.util.List<JSONObject> fragments);
    }

    /** A contiguous validated prefix; selecting it does not acknowledge or persist anything. */
    static final class Batch {
        final java.util.List<JSONObject> fragments = new java.util.ArrayList<>();
        long complete, partial;
        int chunk;
        boolean accepted;
        Batch(long complete, long partial, int chunk) {
            this.complete = complete; this.partial = partial; this.chunk = chunk;
        }
    }

    static Batch select(JSONArray fragments, long complete, long partial, int chunk,
            long sentThrough, long sentPartial, int sentChunk) {
        Batch batch = new Batch(complete, partial, chunk);
        for (int i = 0; i < fragments.length(); i++) {
            JSONObject fragment = fragments.optJSONObject(i);
            if (fragment == null) break;
            long sequence = fragment.optLong("sequence", -1L);
            int index = fragment.optInt("detailChunkIndex", 1);
            int count = fragment.optInt("detailChunkCount", 1);
            if (sequence <= 0L || index < 1 || count < index
                    || !(sequence <= sentThrough || (sequence == sentPartial && index <= sentChunk))) break;
            if (sequence <= batch.complete
                    || (sequence == batch.partial && index <= batch.chunk)) {
                batch.accepted = true;
                continue;
            }
            if (!((batch.partial == 0L && sequence == batch.complete + 1L && index == 1)
                    || (batch.partial > 0L && sequence == batch.partial && index == batch.chunk + 1))) break;
            batch.fragments.add(fragment);
            if (index == count) {
                batch.complete = sequence; batch.partial = 0L; batch.chunk = 0;
            } else {
                batch.partial = sequence; batch.chunk = index;
            }
            batch.accepted = true;
        }
        return batch;
    }

    static final class Result {
        final boolean accepted;
        final long ackSequence;
        final long ackChunkSequence;
        final int ackChunkIndex;
        final String error;

        Result(boolean accepted, long ackSequence, long ackChunkSequence, int ackChunkIndex) {
            this(accepted, ackSequence, ackChunkSequence, ackChunkIndex, "");
        }

        Result(boolean accepted, long ackSequence, long ackChunkSequence, int ackChunkIndex, String error) {
            this.accepted = accepted;
            this.ackSequence = ackSequence;
            this.ackChunkSequence = ackChunkSequence;
            this.ackChunkIndex = ackChunkIndex;
            this.error = error;
        }
    }

    private NavigatorMapLifecycleJournal() { }

    static Result accept(Context context, String owner, String producerSession,
            JSONArray fragments, long sentThrough, long sentPartialSequence,
            int sentPartialChunk, Store store) {
        if (context == null || !safeOwner(owner) || !safeSession(producerSession)
                || fragments == null || store == null || fragments.length() == 0
                || sentThrough < 0L || sentPartialSequence < 0L || sentPartialChunk < 0) {
            return new Result(false, 0L, 0L, 0);
        }
        synchronized (LOCK) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String prefix = "cursor." + owner + "." + producerSession;
            long complete = Math.max(0L, prefs.getLong(prefix + ".sequence", 0L));
            long partialSequence = Math.max(0L, prefs.getLong(prefix + ".partialSequence", 0L));
            int partialChunk = Math.max(0, prefs.getInt(prefix + ".partialChunk", 0));
            Batch batch = select(fragments, complete, partialSequence, partialChunk,
                    sentThrough, sentPartialSequence, sentPartialChunk);
            if (!batch.accepted) return new Result(false, 0L, 0L, 0);
            if (!batch.fragments.isEmpty() && !store.store(owner, producerSession, batch.fragments)) {
                return new Result(false, 0L, 0L, 0,
                        "event_batch_write_unconfirmed: writer returned false; underlying cause is in collector diagnostics");
            }
            // Commit once, after every affected event file is synced. Also retry duplicate-only
            // cursors: a failed SharedPreferences commit can still update its in-memory values.
            if (!prefs.edit().putLong(prefix + ".sequence", batch.complete)
                    .putLong(prefix + ".partialSequence", batch.partial)
                    .putInt(prefix + ".partialChunk", batch.chunk).commit()) {
                return new Result(false, 0L, 0L, 0,
                        "cursor_commit_unconfirmed: SharedPreferences returned false; platform cause unavailable");
            }
            complete = batch.complete;
            partialSequence = batch.partial;
            partialChunk = batch.chunk;

            long ackComplete = Math.min(complete, sentThrough);
            long ackChunkSequence = 0L;
            int ackChunkIndex = 0;
            if (sentPartialSequence > ackComplete && sentPartialSequence == partialSequence) {
                ackChunkSequence = sentPartialSequence;
                ackChunkIndex = Math.min(partialChunk, sentPartialChunk);
            }
            return new Result(true, ackComplete, ackChunkSequence, ackChunkIndex);
        }
    }

    private static boolean safeOwner(String value) {
        return value != null && (value.equals(NavigatorMapCapture.MAPS_PACKAGE)
                || value.equals(NavigatorMapCapture.WAZE_PACKAGE));
    }

    private static boolean safeSession(String value) {
        if (value == null || value.isEmpty() || value.length() > 96) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) return false;
        }
        return true;
    }
}
