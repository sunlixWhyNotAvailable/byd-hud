package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class LogcatCaptureFileTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recordingBeyond64MiBRetainsStartAndStopInExactlyOneFile() throws Exception {
        File directory = temporaryFolder.newFolder("large-capture");
        LogcatCaptureFile capture = new LogcatCaptureFile(directory);
        byte[] start = utf8("START capture\n");
        byte[] stop = utf8("\nSTOP capture: кінець\n");
        byte[] chunk = new byte[256 * 1024];
        Arrays.fill(chunk, (byte) 'x');
        capture.append(start);
        for (int i = 0; i < 260; i++) capture.append(chunk);
        long bodyBytes = 260L * chunk.length;
        assertTrue(capture.bytes() > 64L * 1024L * 1024L);
        assertEquals(start.length + bodyBytes, capture.bytes());
        assertArrayEquals(new String[]{"logcat.log.part"}, directory.list());

        // The recorder's final Stop poll appends before it finalizes the file.
        capture.append(stop);
        capture.finish();
        capture.finish();
        long expectedBytes = start.length + bodyBytes + stop.length;
        assertEquals(expectedBytes, capture.bytes());
        assertEquals(expectedBytes, capture.file().length());
        assertArrayEquals(new String[]{"logcat.log"}, directory.list());
        try (RandomAccessFile input = new RandomAccessFile(capture.file(), "r")) {
            byte[] actualStart = new byte[start.length];
            input.readFully(actualStart);
            assertArrayEquals(start, actualStart);
            input.seek(expectedBytes - stop.length);
            byte[] actualStop = new byte[stop.length];
            input.readFully(actualStop);
            assertArrayEquals(stop, actualStop);
        }
    }

    @Test
    public void stopClosesTheFirstCaptureAndSecondCaptureStartsSeparately() throws Exception {
        LogcatCaptureFile first = new LogcatCaptureFile(temporaryFolder.newFolder("first"));
        first.append(utf8("first capture\n"));
        first.finish();
        assertThrows(IOException.class, () -> first.append(utf8("late event\n")));

        File secondDirectory = temporaryFolder.newFolder("second");
        LogcatCaptureFile second = new LogcatCaptureFile(secondDirectory);
        assertEquals(0L, second.bytes());
        second.append(utf8("second capture\n"));
        second.append(utf8("second Stop poll\n"));
        second.finish();
        assertArrayEquals(utf8("first capture\n"), Files.readAllBytes(first.file().toPath()));
        assertArrayEquals(utf8("second capture\nsecond Stop poll\n"),
                Files.readAllBytes(second.file().toPath()));
        assertArrayEquals(new String[]{"logcat.log"}, secondDirectory.list());
    }

    @Test
    public void finalizationFailureKeepsPartialFileAndItsActualSize() throws Exception {
        File directory = temporaryFolder.newFolder("failed-finish");
        LogcatCaptureFile capture = new LogcatCaptureFile(directory);
        byte[] prefix = utf8("captured before failure\n");
        capture.append(prefix);
        File occupiedTarget = new File(directory, "logcat.log");
        byte[] existing = utf8("do not replace existing evidence\n");
        Files.write(occupiedTarget.toPath(), existing);

        assertThrows(IOException.class, capture::finish);
        assertEquals("logcat.log.part", capture.file().getName());
        assertEquals(prefix.length, capture.bytes());
        assertArrayEquals(prefix, Files.readAllBytes(capture.file().toPath()));
        assertArrayEquals(existing, Files.readAllBytes(occupiedTarget.toPath()));
    }

    @Test
    public void aNewWriterNeverOverwritesAnInterruptedCapture() throws Exception {
        File directory = temporaryFolder.newFolder("interrupted");
        LogcatCaptureFile original = new LogcatCaptureFile(directory);
        byte[] prefix = utf8("partial recording\n");
        original.append(prefix);
        LogcatCaptureFile replacement = new LogcatCaptureFile(directory);

        assertThrows(IOException.class, () -> replacement.append(utf8("replacement")));
        assertArrayEquals(prefix, Files.readAllBytes(original.file().toPath()));
        assertEquals(prefix.length, original.bytes());
        original.finish();
        assertArrayEquals(new String[]{"logcat.log"}, directory.list());
    }

    @Test
    public void writeFailureReportsNoInventedBytesOrFile() throws Exception {
        File notADirectory = temporaryFolder.newFile("blocked-directory");
        LogcatCaptureFile capture = new LogcatCaptureFile(notADirectory);
        assertThrows(IOException.class, () -> capture.append(utf8("not written")));
        assertEquals(0L, capture.bytes());
        assertFalse(capture.file().exists());
        capture.finish();
        assertFalse(capture.file().exists());
        assertThrows(IOException.class, () -> capture.append(utf8("after Stop")));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
