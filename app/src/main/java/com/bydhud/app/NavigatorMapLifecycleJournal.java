package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Persists authenticated producer journal fragments before returning an acknowledgement. */
final class NavigatorMapLifecycleJournal {
    private static final Object LOCK = new Object();
    private static final String PREFS = "navigator_map_lifecycle_journal";

    interface Store {
        boolean store(String owner, String producerSession, JSONObject fragment);
    }

    static final class Result {
        final boolean accepted;
        final long ackSequence;
        final long ackChunkSequence;
        final int ackChunkIndex;

        Result(boolean accepted, long ackSequence, long ackChunkSequence, int ackChunkIndex) {
            this.accepted = accepted;
            this.ackSequence = ackSequence;
            this.ackChunkSequence = ackChunkSequence;
            this.ackChunkIndex = ackChunkIndex;
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
            boolean accepted = false;

            for (int i = 0; i < fragments.length(); i++) {
                JSONObject fragment = fragments.optJSONObject(i);
                if (fragment == null) break;
                long sequence = fragment.optLong("sequence", -1L);
                int chunkIndex = fragment.optInt("detailChunkIndex", 1);
                int chunkCount = fragment.optInt("detailChunkCount", 1);
                if (sequence <= 0L || chunkIndex < 1 || chunkCount < chunkIndex) break;
                boolean inSentRange = sequence <= sentThrough
                        || (sequence == sentPartialSequence && chunkIndex <= sentPartialChunk);
                if (!inSentRange) break;

                if (sequence < complete || (sequence == complete && partialSequence == 0L)) {
                    accepted = true; // Retry after an accepted acknowledgement was lost.
                    continue;
                }
                if (partialSequence > 0L && sequence == partialSequence && chunkIndex <= partialChunk) {
                    accepted = true;
                    continue;
                }

                boolean startsNext = partialSequence == 0L
                        && sequence == complete + 1L && chunkIndex == 1;
                boolean continuesPartial = partialSequence > 0L
                        && sequence == partialSequence && chunkIndex == partialChunk + 1;
                if (!startsNext && !continuesPartial) break;
                if (!store.store(owner, producerSession, fragment)) break;

                long nextComplete = chunkIndex == chunkCount ? sequence : complete;
                long nextPartialSequence = chunkIndex == chunkCount ? 0L : sequence;
                int nextPartialChunk = chunkIndex == chunkCount ? 0 : chunkIndex;
                boolean saved = prefs.edit()
                        .putLong(prefix + ".sequence", nextComplete)
                        .putLong(prefix + ".partialSequence", nextPartialSequence)
                        .putInt(prefix + ".partialChunk", nextPartialChunk)
                        .commit();
                if (!saved) break;
                complete = nextComplete;
                partialSequence = nextPartialSequence;
                partialChunk = nextPartialChunk;
                accepted = true;
            }

            long ackComplete = Math.min(complete, sentThrough);
            long ackChunkSequence = 0L;
            int ackChunkIndex = 0;
            if (sentPartialSequence > ackComplete && sentPartialSequence == partialSequence) {
                ackChunkSequence = sentPartialSequence;
                ackChunkIndex = Math.min(partialChunk, sentPartialChunk);
            }
            return new Result(accepted, ackComplete, ackChunkSequence, ackChunkIndex);
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
