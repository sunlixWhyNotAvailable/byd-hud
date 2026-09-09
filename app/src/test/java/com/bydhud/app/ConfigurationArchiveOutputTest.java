package com.bydhud.app;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class ConfigurationArchiveOutputTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void oneEntryLargerThanVolumeRoundTripsAcrossAllParts() throws Exception {
        byte[] original = new byte[256_123];
        new Random(123).nextBytes(original);
        File base = new File(temp.getRoot(), "archive.zip");
        ConfigurationArchiveOutput output = new ConfigurationArchiveOutput(base, 10_000);
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("CarSettingsPlugins.apk"));
            zip.write(original);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{}".getBytes());
            zip.closeEntry();
        }
        List<File> volumes = output.publish();
        assertTrue(volumes.size() > 2);
        assertFalse(base.exists());
        List<InputStream> streams = new ArrayList<>();
        for (File volume : volumes) {
            assertTrue(volume.length() > 0 && volume.length() <= 10_000);
            streams.add(new FileInputStream(volume));
        }
        try (ZipInputStream zip = new ZipInputStream(new SequenceInputStream(Collections.enumeration(streams)))) {
            assertEquals("CarSettingsPlugins.apk", zip.getNextEntry().getName());
            assertArrayEquals(original, zip.readAllBytes());
            assertEquals("manifest.json", zip.getNextEntry().getName());
            assertArrayEquals("{}".getBytes(), zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
    }

    @Test public void exactBoundaryHasNoEmptyTrailingVolumeAndSmallArchiveKeepsZipName() throws Exception {
        File base = new File(temp.getRoot(), "exact.zip");
        ConfigurationArchiveOutput output = new ConfigurationArchiveOutput(base, 16);
        output.write(new byte[16]);
        List<File> volumes = output.publish();
        assertEquals(Collections.singletonList(base), volumes);
        assertEquals(16, base.length());
        assertEquals(1, temp.getRoot().list().length);
    }

    @Test public void failedPublishRemovesOnlyItsOwnFilesOnAbort() throws Exception {
        File base = new File(temp.getRoot(), "collision.zip");
        ConfigurationArchiveOutput output = new ConfigurationArchiveOutput(base, 10);
        output.write(new byte[25]);
        File other = new File(base.getPath() + ".002");
        Files.write(other.toPath(), new byte[]{42});
        assertThrows(IOException.class, output::publish);
        output.abort();
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(other.toPath()));
        assertEquals(1, temp.getRoot().list().length);
    }
}
