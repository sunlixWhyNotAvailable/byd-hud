package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ShanghaiDiagnosticsJournalTest {
    @Test public void topicCatalogExactlyMatchesSavedStockNavigationFixture() throws Exception {
        Set<Long> fixture = new LinkedHashSet<>();
        try (InputStream input = getClass().getResourceAsStream("/shanghai-stock-navigation-topics.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) fixture.add(Long.parseUnsignedLong(line.substring(2), 16));
        }
        assertEquals(26, ShanghaiTopics.ALL.size());
        assertEquals(fixture, new LinkedHashSet<>(ShanghaiTopics.ALL));
    }

    @Test public void duplicatePayloadsRemainSeparateAndRawBytesRoundTrip() throws Exception {
        File directory = Files.createTempDirectory("shanghai-journal").toFile();
        File output = new File(directory, "events.jsonl");
        byte[] payload = new byte[]{0, 1, 2, 3, (byte) 0xff, 0, 9};
        ShanghaiEventJournal journal = new ShanghaiEventJournal(
                output, ShanghaiTopics.ALL, System::nanoTime);
        journal.offer(ShanghaiTopics.ALL.get(0), 101L, payload.length, payload);
        journal.offer(ShanghaiTopics.ALL.get(0), 102L, payload.length, payload);
        journal.close();

        List<String> lines = Files.readAllLines(output.toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        JSONObject first = new JSONObject(lines.get(0));
        JSONObject second = new JSONObject(lines.get(1));
        assertNotEquals(first.getLong("sequence"), second.getLong("sequence"));
        assertEquals(101L, first.getLong("oemTimestamp"));
        assertEquals(102L, second.getLong("oemTimestamp"));
        assertEquals(first.getString("sha256"), second.getString("sha256"));
        assertArrayEquals(payload, Base64.getDecoder().decode(first.getString("payloadBase64")));
        assertArrayEquals(payload, Base64.getDecoder().decode(second.getString("payloadBase64")));
        assertEquals(2L, journal.persistedCount());
        assertEquals(0L, journal.droppedCount());
    }

    @Test public void oversizedRawPayloadIsDroppedAndCounted() throws Exception {
        File directory = Files.createTempDirectory("shanghai-journal-bound").toFile();
        ShanghaiEventJournal journal = new ShanghaiEventJournal(
                new File(directory, "events.jsonl"), ShanghaiTopics.ALL, System::nanoTime);
        journal.offer(ShanghaiTopics.ALL.get(0), 1L, 4 * 1024 * 1024 + 1,
                new byte[4 * 1024 * 1024 + 1]);
        journal.close();
        assertEquals(1L, journal.receivedCount());
        assertEquals(0L, journal.persistedCount());
        assertEquals(1L, journal.droppedCount());
        assertTrue(journal.topicSummary().getJSONObject(0).getLong("dropped") > 0L);
    }

}
