package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises journal batching and rotation against ordinary temporary files. */
public final class JournalTextBatchTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void durableAppendFlushesFullRecordsAndPreservesExistingFile() throws Exception {
        File file = new File(temporaryFolder.newFolder(), "durable.jsonl");
        assertTrue(JournalTextBatch.append(file, Collections.singletonList("earlier\n")));
        String large = repeat('d', 70 * 1024) + "\n";
        assertTrue(JournalTextBatch.append(file, Arrays.asList(large, "last\n"), true));
        assertArrayEquals(("earlier\n" + large + "last\n").getBytes(Charset.defaultCharset()),
                Files.readAllBytes(file.toPath()));
    }

    @Test(expected = java.io.IOException.class)
    public void durableAppendPropagatesRealOpenFailure() throws Exception {
        JournalTextBatch.append(temporaryFolder.newFolder(),
                Collections.singletonList("not acknowledged\n"), true);
    }

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

    @Test public void exportPreservesNulDamagedLogsAndMarksRecordingIncomplete() throws Exception {
        File source = temporaryFolder.newFile("events.log.1");
        byte[] bytes = (repeat('a', 65_534) + "\0\0\0\0\nlast record").getBytes(Charset.defaultCharset());
        Files.write(source.toPath(), bytes);
        File binary = temporaryFolder.newFile("capture.png");
        Files.write(binary.toPath(), new byte[]{1, 0, 2, 0});
        LogShareZip.SnapshotFile captured = new LogShareZip.SnapshotFile(
                new File(source.getParentFile(), "raw.jsonl"), "day/logs/raw.jsonl", 0);
        captured.captured = "before\0after\n".getBytes(Charset.defaultCharset());
        File staging = temporaryFolder.newFolder();
        List<String> omissions = new ArrayList<>(), corruptions = new ArrayList<>();
        List<LogShareZip.SnapshotFile> copied = LogShareZip.copySnapshotToStaging(staging,
                Arrays.asList(new LogShareZip.SnapshotFile(source, "day/logs/events.log.1", source.length()),
                        new LogShareZip.SnapshotFile(binary, "day/capture.png", binary.length()), captured),
                omissions, corruptions);

        assertArrayEquals(bytes, Files.readAllBytes(new File(staging, "day/logs/events.log.1").toPath()));
        assertArrayEquals(bytes, Files.readAllBytes(source.toPath()));
        assertArrayEquals(new byte[]{1, 0, 2, 0}, Files.readAllBytes(new File(staging, "day/capture.png").toPath()));
        assertArrayEquals(captured.captured, Files.readAllBytes(new File(staging, "day/logs/raw.jsonl").toPath()));
        assertTrue(omissions.isEmpty());
        assertEquals(2, corruptions.size());
        assertTrue(corruptions.get(0).contains("day/logs/events.log.1: NUL bytes=4 firstByteOffset=65534"));
        assertTrue(corruptions.get(1).contains("day/logs/raw.jsonl: NUL bytes=1 firstByteOffset=6"));
        JSONObject report = new JSONObject().put("generatedAt", 1_700_000_000_000L);
        assertTrue(LogShareZip.addWriterStatus(staging, copied, Collections.singletonList("day"),
                true, true, report, omissions, corruptions,
                new JSONObject().put("lossObserved", false)));
        File statusFile = new File(staging, NavigatorPatchReportStore.exportDirectory(report) + "recording-status.json");
        JSONObject status = new JSONObject(new String(Files.readAllBytes(statusFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("PARTIAL", status.getString("collectionOutcome"));
        assertEquals("INCOMPLETE", status.getString("recordingStatus"));
        assertEquals(2, status.getJSONArray("corruptedFiles").length());
        assertEquals(0, status.getJSONArray("omittedOrTruncatedFiles").length());
        assertTrue(new File(statusFile.getParentFile(), "INCOMPLETE-RECORDING.txt").isFile());
    }

    @Test public void exportChecksOnlySnapshotBytesAndStillTrimsIncompleteCleanRecords() throws Exception {
        File source = temporaryFolder.newFile("events.log");
        byte[] original = "complete\npartial\0later\n".getBytes(Charset.defaultCharset());
        Files.write(source.toPath(), original);
        File staging = temporaryFolder.newFolder();
        List<String> omissions = new ArrayList<>(), corruptions = new ArrayList<>();
        LogShareZip.copySnapshotToStaging(staging,
                Collections.singletonList(new LogShareZip.SnapshotFile(source, "events.log", 16)),
                omissions, corruptions);
        assertArrayEquals("complete\n".getBytes(Charset.defaultCharset()),
                Files.readAllBytes(new File(staging, "events.log").toPath()));
        assertArrayEquals(original, Files.readAllBytes(source.toPath()));
        assertTrue(corruptions.isEmpty());
        assertEquals(Collections.singletonList("events.log: incomplete trailing record omitted"), omissions);
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
