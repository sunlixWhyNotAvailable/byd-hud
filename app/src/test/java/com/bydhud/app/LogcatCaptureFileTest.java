package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class LogcatCaptureFileTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
