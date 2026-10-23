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

    @Test public void rotationPreservesAllSameDayShardsAndCreatesEmptyActiveFile() throws Exception {
        File file = new File(temporaryFolder.newFolder(), AppEventLogger.EVENTS_FILE);
        String first = repeat('r', (int) AppEventLogger.MAX_FILE_BYTES + 1);
        assertTrue(JournalTextBatch.append(file, Collections.singletonList(first)));
        assertTrue(NavCaptureStore.rotate(file));

        String second = "second\n";
        assertTrue(JournalTextBatch.append(file, Collections.singletonList(second)));
        assertTrue(NavCaptureStore.rotate(file));

        String third = "third\n";
        assertTrue(JournalTextBatch.append(file, Collections.singletonList(third)));
        assertTrue(NavCaptureStore.rotate(file));

        assertArrayEquals(third.getBytes(Charset.defaultCharset()),
                Files.readAllBytes(new File(file.getParentFile(), file.getName() + ".1").toPath()));
        assertArrayEquals(second.getBytes(Charset.defaultCharset()),
                Files.readAllBytes(new File(file.getParentFile(), file.getName() + ".2").toPath()));
        assertArrayEquals(first.getBytes(Charset.defaultCharset()),
                Files.readAllBytes(new File(file.getParentFile(), file.getName() + ".3").toPath()));
        assertTrue(file.isFile());
        assertEquals(0L, file.length());
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
