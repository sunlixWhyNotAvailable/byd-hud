package com.bydhud.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NavigatorMapLifecycleJournalTest {
    private JSONObject fragment(long sequence, int index, int count) throws Exception {
        return new JSONObject().put("sequence", sequence)
                .put("detailChunkIndex", index).put("detailChunkCount", count);
    }

    @Test public void resumesPartialAndSkipsReplayedFragments() throws Exception {
        JSONArray input = new JSONArray().put(fragment(1, 1, 1)).put(fragment(2, 1, 3))
                .put(fragment(2, 2, 3)).put(fragment(2, 3, 3)).put(fragment(3, 1, 2));
        NavigatorMapLifecycleJournal.Batch batch = NavigatorMapLifecycleJournal.select(input,
                1, 2, 1, 2, 3, 1);
        assertTrue(batch.accepted);
        assertEquals(3, batch.fragments.size());
        assertEquals(2, batch.complete);
        assertEquals(3, batch.partial);
        assertEquals(1, batch.chunk);
        assertEquals(2, batch.fragments.get(0).getInt("detailChunkIndex"));
        NavigatorMapLifecycleJournal.Batch replay = NavigatorMapLifecycleJournal.select(input,
                batch.complete, batch.partial, batch.chunk, 2, 3, 1);
        assertTrue(replay.accepted);
        assertTrue(replay.fragments.isEmpty());
    }

    @Test public void stopsAtGapAndAtUnsentChunk() throws Exception {
        JSONArray gap = new JSONArray().put(fragment(1, 1, 1)).put(fragment(3, 1, 1));
        NavigatorMapLifecycleJournal.Batch prefix = NavigatorMapLifecycleJournal.select(gap, 0, 0, 0, 3, 0, 0);
        assertEquals(1, prefix.complete);
        assertEquals(1, prefix.fragments.size());
        JSONArray chunks = new JSONArray().put(fragment(2, 1, 3)).put(fragment(2, 2, 3));
        prefix = NavigatorMapLifecycleJournal.select(chunks, 1, 0, 0, 1, 2, 1);
        assertEquals(1, prefix.complete);
        assertEquals(1, prefix.chunk);
        assertEquals(1, prefix.fragments.size());
    }
}
