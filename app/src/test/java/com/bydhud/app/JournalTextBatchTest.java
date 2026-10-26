package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises journal batching and rotation against ordinary temporary files. */
public final class JournalTextBatchTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void appendPreservesOrderAndDoesNotTruncateOversizedRecord() throws Exception {
        File file = new File(temporaryFolder.newFolder(), "records.jsonl");
        String oversized = repeat('x', 70 * 1024) + "\n";
        String expected = "first\n" + oversized + "last\n";

        assertTrue(JournalTextBatch.append(file,
                Arrays.asList("first\n", oversized, "last\n")));

        assertArrayEquals(expected.getBytes(Charset.defaultCharset()), Files.readAllBytes(file.toPath()));
    }

    @Test public void rotationKeepsLegacyShardsAndUsesRestartSafePartSequence() throws Exception {
        File directory = temporaryFolder.newFolder();
        File file = new File(directory, AppEventLogger.EVENTS_FILE);
        File legacyOne = new File(directory, file.getName() + ".1");
        File legacyThree = new File(directory, file.getName() + ".3");
        Files.write(legacyOne.toPath(), "legacy one\n".getBytes(Charset.defaultCharset()));
        Files.write(legacyThree.toPath(), "legacy three\n".getBytes(Charset.defaultCharset()));
        String first = repeat('r', (int) AppEventLogger.MAX_FILE_BYTES + 1);
        assertTrue(JournalTextBatch.append(file, Collections.singletonList(first)));
        assertTrue(NavCaptureStore.rotate(file));

        String second = "second\n";
        assertTrue(JournalTextBatch.append(file, Collections.singletonList(second)));
        assertTrue(NavCaptureStore.rotate(file));

        File partSeven = new File(directory, file.getName() + ".part-7");
        Files.write(partSeven.toPath(), "previous process\n".getBytes(Charset.defaultCharset()));
        String third = "third\n";
        assertTrue(JournalTextBatch.append(file, Collections.singletonList(third)));
        assertTrue(NavCaptureStore.rotate(file));

        assertArrayEquals(first.getBytes(Charset.defaultCharset()),
                Files.readAllBytes(new File(directory, file.getName() + ".part-1").toPath()));
        assertArrayEquals(second.getBytes(Charset.defaultCharset()),
                Files.readAllBytes(new File(directory, file.getName() + ".part-2").toPath()));
        assertArrayEquals("previous process\n".getBytes(Charset.defaultCharset()),
                Files.readAllBytes(partSeven.toPath()));
        assertArrayEquals(third.getBytes(Charset.defaultCharset()),
                Files.readAllBytes(new File(directory, file.getName() + ".part-8").toPath()));
        assertArrayEquals("legacy one\n".getBytes(Charset.defaultCharset()),
                Files.readAllBytes(legacyOne.toPath()));
        assertArrayEquals("legacy three\n".getBytes(Charset.defaultCharset()),
                Files.readAllBytes(legacyThree.toPath()));
        assertTrue(file.isFile());
        assertEquals(0L, file.length());
    }

    @Test public void exportCollectorKeepsNumericAndPartJsonlShards() throws Exception {
        File day = temporaryFolder.newFolder("20261026");
        File logs = new File(day, NavigationLogStorage.LOGS_DIR);
        assertTrue(logs.mkdir());
        File active = new File(logs, "raw_nav_events.jsonl");
        File oldShard = new File(logs, "raw_nav_events.jsonl.12");
        File partShard = new File(logs, "raw_nav_events.jsonl.part-13");
        Files.write(active.toPath(), "active\n".getBytes(Charset.defaultCharset()));
        Files.write(oldShard.toPath(), "old\n".getBytes(Charset.defaultCharset()));
        Files.write(partShard.toPath(), "part\n".getBytes(Charset.defaultCharset()));

        java.util.List<LogShareZip.SnapshotFile> files = new java.util.ArrayList<>();
        LogShareZip.collectFiles(day, day.getName(), files,
                new java.util.HashSet<>(), new java.util.HashSet<>());

        assertEquals(3, files.size());
        assertTrue(files.stream().anyMatch(file -> file.entryName.endsWith("raw_nav_events.jsonl")));
        assertTrue(files.stream().anyMatch(file -> file.entryName.endsWith("raw_nav_events.jsonl.12")));
        assertTrue(files.stream().anyMatch(file -> file.entryName.endsWith(
                "raw_nav_events.jsonl.part-13")));
        assertTrue(LogShareZip.appendLog(partShard));
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
