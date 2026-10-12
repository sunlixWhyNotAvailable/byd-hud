package com.bydhud.mapcapture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Iterator;

/** Builds a byte-bounded, resumable prefix of the producer lifecycle journal. */
final class LifecycleJournalBatch {
    static final int MAX_BYTES = 24 * 1024;
    // JSON escaping can expand control characters sixfold; leave ample room for metadata.
    private static final int MAX_DETAIL_CHUNK_BYTES = 1024;

    final JSONArray events;
    final long completeThrough;
    final long partialSequence;
    final int partialChunkIndex;
    final long firstSequence;

    private LifecycleJournalBatch(
            JSONArray events,
            long completeThrough,
            long partialSequence,
            int partialChunkIndex,
            long firstSequence) {
        this.events = events;
        this.completeThrough = completeThrough;
        this.partialSequence = partialSequence;
        this.partialChunkIndex = partialChunkIndex;
        this.firstSequence = firstSequence;
    }

    static LifecycleJournalBatch build(
            Collection<JSONObject> source,
            boolean include,
            long acknowledgedThrough,
            long acknowledgedPartialSequence,
            int acknowledgedPartialChunk) {
        return build(source.iterator(), include, acknowledgedThrough,
                acknowledgedPartialSequence, acknowledgedPartialChunk);
    }

    static LifecycleJournalBatch build(
            Iterator<JSONObject> source,
            boolean include,
            long acknowledgedThrough,
            long acknowledgedPartialSequence,
            int acknowledgedPartialChunk) {
        JSONArray batch = new JSONArray();
        long complete = acknowledgedThrough;
        long partial = acknowledgedPartialSequence;
        int partialChunk = acknowledgedPartialChunk;
        long first = 0L;
        if (!include) return new LifecycleJournalBatch(batch, complete, partial, partialChunk, first);

        int used = 2; // []
        while (source.hasNext()) {
            JSONObject event = source.next();
            long sequence = event.optLong("sequence", -1L);
            if (sequence <= acknowledgedThrough) continue;
            if (sequence != complete + 1L && !(sequence == partial && partial > complete)) break;

            DetailChunks details = new DetailChunks(event.optString("detail", ""));
            int startChunk = sequence == partial ? partialChunk + 1 : 1;
            while (details.nextIndex < startChunk - 1) details.next();
            for (int i = startChunk; i <= details.count; i++) {
                JSONObject fragment;
                try {
                    fragment = new JSONObject()
                            .put("sequence", sequence)
                            .put("elapsedNs", event.optLong("elapsedNs", 0L))
                            .put("event", event.optString("event", ""))
                            .put("owner", event.optString("owner", ""))
                            .put("detail", details.next())
                            .put("detailChunkIndex", i)
                            .put("detailChunkCount", details.count);
                } catch (JSONException error) {
                    throw new IllegalStateException("cannot encode lifecycle event", error);
                }
                int entryBytes = fragment.toString().getBytes(StandardCharsets.UTF_8).length;
                int next = used + (batch.length() == 0 ? 0 : 1) + entryBytes;
                if (next > MAX_BYTES) {
                    return new LifecycleJournalBatch(batch, complete, partial, partialChunk, first);
                }
                if (first == 0L) first = sequence;
                batch.put(fragment);
                used = next;
                if (i == details.count) {
                    complete = sequence;
                    partial = 0L;
                    partialChunk = 0;
                } else {
                    partial = sequence;
                    partialChunk = i;
                }
            }
        }
        if (batch.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalStateException("lifecycle journal batch exceeded byte limit");
        }
        return new LifecycleJournalBatch(batch, complete, partial, partialChunk, first);
    }

    private static final class DetailChunks {
        final String detail;
        final int count;
        int nextIndex;
        int offset;

        DetailChunks(String detail) {
            this.detail = detail == null ? "" : detail;
            this.count = countChunks(this.detail);
        }

        String next() {
            if (nextIndex >= count) throw new IllegalStateException("no lifecycle detail chunk remains");
            int start = offset;
            int bytes = 0;
            while (offset < detail.length()) {
                int codePoint = detail.codePointAt(offset);
                int unitBytes = utf8Length(codePoint);
                if (bytes > 0 && bytes + unitBytes > MAX_DETAIL_CHUNK_BYTES) break;
                bytes += unitBytes;
                offset += Character.charCount(codePoint);
            }
            nextIndex++;
            return detail.substring(start, offset);
        }

        private static int countChunks(String detail) {
            if (detail.isEmpty()) return 1;
            int count = 0;
            int offset = 0;
            while (offset < detail.length()) {
                int bytes = 0;
                while (offset < detail.length()) {
                    int codePoint = detail.codePointAt(offset);
                    int unitBytes = utf8Length(codePoint);
                    if (bytes > 0 && bytes + unitBytes > MAX_DETAIL_CHUNK_BYTES) break;
                    bytes += unitBytes;
                    offset += Character.charCount(codePoint);
                }
                count++;
            }
            return count;
        }

        private static int utf8Length(int codePoint) {
            if (codePoint <= 0x7f) return 1;
            if (codePoint <= 0x7ff) return 2;
            return codePoint <= 0xffff ? 3 : 4;
        }
    }
}
