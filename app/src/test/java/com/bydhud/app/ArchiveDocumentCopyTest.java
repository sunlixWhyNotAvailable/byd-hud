package com.bydhud.app;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ArchiveDocumentCopyTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    @Test public void singleAndSplitExportsSaveAsOneReadableZipWithoutChangingContents() throws Exception {
        byte[] log = new byte[150_000];
        new Random(42).nextBytes(log);
        byte[] manifest = "{\"files\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (long limit : new long[]{1_000_000, 32_768}) {
            File source = new File(files.newFolder(), "BYD-HUD-configuration.zip");
            ConfigurationArchiveOutput parts = new ConfigurationArchiveOutput(source, limit);
            try (ZipOutputStream zip = new ZipOutputStream(parts)) {
                zip.putNextEntry(new ZipEntry("navigation.log"));
                zip.write(log);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(manifest);
                zip.closeEntry();
            }
            List<File> volumes = parts.publish();
            assertEquals(limit > log.length, volumes.size() == 1);
            File saved = new File(files.newFolder(), "saved.zip");
            long copied;
            try (FileOutputStream destination = new FileOutputStream(saved)) {
                copied = ArchiveDocumentCopy.copy(ArchiveDocumentCopy.prepare(volumes),
                        destination, new AtomicBoolean(), ignored -> { });
            }
            assertEquals(volumes.stream().mapToLong(File::length).sum(), copied);
            assertEquals(copied, saved.length());
            try (ZipFile zip = new ZipFile(saved)) {
                assertEquals(2, zip.size());
                assertArrayEquals(log, zip.getInputStream(zip.getEntry("navigation.log")).readAllBytes());
                assertArrayEquals(manifest, zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
            }
        }
    }

    @Test public void missingOrTruncatedVolumeCannotCompleteTheSave() throws Exception {
        File first = files.newFile("configuration.zip.001");
        File second = files.newFile("configuration.zip.002");
        Files.write(first.toPath(), new byte[70_000]);
        Files.write(second.toPath(), new byte[70_000]);
        ArchiveDocumentCopy.Plan plan = ArchiveDocumentCopy.prepare(List.of(first, second));
        Files.write(second.toPath(), new byte[5]);
        File output = files.newFile("incomplete.zip");
        try (FileOutputStream destination = new FileOutputStream(output)) {
            assertThrows(IOException.class, () -> ArchiveDocumentCopy.copy(plan, destination,
                    new AtomicBoolean(), ignored -> { }));
        }
        Files.delete(second.toPath());
        assertThrows(IOException.class, () -> ArchiveDocumentCopy.prepare(List.of(first, second)));
    }
}
