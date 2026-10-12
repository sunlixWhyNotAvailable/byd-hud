package com.bydhud.mapcapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public final class LifecycleJournalSpoolTest {
    private Context context;
    private File journalFile;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        journalFile = new File(context.getFilesDir(), "bydhud-mapcapture-lifecycle.jsonl");
        new File(journalFile.getPath() + ".bak").delete();
        journalFile.delete();
    }

    @Test public void failedAppendStaysQueuedAndRetriesWhenFileIsWritableAgain() throws Exception {
        LifecycleJournalSpool spool = new LifecycleJournalSpool(context, 8123, "session-retry");
        spool.append("state", "renderer", "first", 1L);
        File held = new File(journalFile.getPath() + ".held");
        assertTrue(journalFile.renameTo(held));
        assertTrue(journalFile.mkdir());
        try {
            spool.append("state", "renderer", "retry-this-event", 2L);
            assertEquals(1, spool.pendingEventCount());
            assertFalse(spool.writeFault().isEmpty());
        } finally {
            assertTrue(journalFile.delete());
            assertTrue(held.renameTo(journalFile));
        }

        LifecycleJournalBatch batch = spool.batch(true);
        assertEquals(2, batch.events.length());
        assertEquals(0, spool.pendingEventCount());
        assertEquals("retry-this-event", batch.events.getJSONObject(1).getString("detail"));
    }

    @Test public void repairedPartialTailFlushesPendingEventBeforeRecoveryMarker() throws Exception {
        LifecycleJournalSpool spool = new LifecycleJournalSpool(context, 8123, "session-partial-pending");
        spool.append("state", "renderer", "first", 1L);
        File held = new File(journalFile.getPath() + ".held");
        assertTrue(journalFile.renameTo(held));
        assertTrue(journalFile.mkdir());
        try {
            spool.append("state", "renderer", "retry-this-event", 2L);
            assertEquals(1, spool.pendingEventCount());
        } finally {
            assertTrue(journalFile.delete());
            assertTrue(held.renameTo(journalFile));
        }

        // Model a process-level partial append after the failed write, while
        // the complete original event remains in the in-memory retry queue.
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(journalFile, true)) {
            output.write("{\"kind\":\"event\",\"entry\":{\"sequence\":2".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }

        LifecycleJournalBatch batch = spool.batch(true);

        assertEquals(0, spool.pendingEventCount());
        assertTrue(spool.writeFault().isEmpty());
        assertEquals(3, batch.events.length());
        assertEquals(1L, batch.events.getJSONObject(0).getLong("sequence"));
        assertEquals(2L, batch.events.getJSONObject(1).getLong("sequence"));
        assertEquals("retry-this-event", batch.events.getJSONObject(1).getString("detail"));
        assertEquals(3L, batch.events.getJSONObject(2).getLong("sequence"));
        assertEquals("lifecycle_journal_recovered", batch.events.getJSONObject(2).getString("event"));
    }

    @Test public void partialFinalJsonlWriteRepairsPrefixAndLeavesRecoveryDiagnostic() throws Exception {
        LifecycleJournalSpool first = new LifecycleJournalSpool(context, 8123, "session-corrupt-tail");
        first.append("state", "renderer", "preserve-me", 1L);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(journalFile, true)) {
            output.write("{\"kind\":\"event\",\"entry\":{\"sequence\":2".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }

        LifecycleJournalSpool recovered = new LifecycleJournalSpool(context, 9123, "session-new");
        assertEquals("session-corrupt-tail", recovered.sessionId());
        LifecycleJournalBatch batch = recovered.batch(true);
        assertEquals(2, batch.events.length());
        assertEquals("preserve-me", batch.events.getJSONObject(0).getString("detail"));
        assertEquals("lifecycle_journal_recovered", batch.events.getJSONObject(1).getString("event"));
    }

    @Test public void missingCursorPreservesEventsAndContinuesSequence() throws Exception {
        LifecycleJournalSpool first = new LifecycleJournalSpool(context, 8123, "session-before-loss");
        first.append("state", "renderer", "keep-this", 1L);

        String eventLine = null;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(journalFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"kind\":\"event\"")) eventLine = line;
            }
        }
        assertTrue(eventLine != null);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(journalFile, false)) {
            output.write((eventLine + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }

        LifecycleJournalSpool restarted = new LifecycleJournalSpool(context, 9123, "session-after-loss");
        assertEquals("session-after-loss", restarted.sessionId());
        restarted.append("state", "renderer", "new-event", 2L);
        LifecycleJournalBatch batch = restarted.batch(true);
        assertEquals(2, batch.events.length());
        assertEquals(1L, batch.events.getJSONObject(0).getLong("sequence"));
        assertEquals(2L, batch.events.getJSONObject(1).getLong("sequence"));
        assertEquals("keep-this", batch.events.getJSONObject(0).getString("detail"));
        assertEquals("new-event", batch.events.getJSONObject(1).getString("detail"));
    }
}
