package com.bydhud.gmapsdiag.patcher;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import org.junit.Test;

public final class GmapsDiagnosticPatcherTest {
    @Test
    public void rejectsDuplicateManifestEntries() throws Exception {
        Path apk = Files.createTempFile("gmaps-duplicate-manifest-", ".apk");
        try {
            writeZip(apk, new Entry("AndroidManifest.xml", new byte[] {1}),
                    new Entry("AndroidManifest.xml", new byte[] {2}));
            IOException error = assertThrows(IOException.class,
                    () -> GmapsDiagnosticPatcher.readManifestEntry(apk.toFile()));
            assertTrue(error.getMessage().contains("Duplicate APK manifest entries"));
            IOException compositeError = assertThrows(IOException.class,
                    () -> GmapsDiagnosticPatcher.inspectComponents(apk.toFile(), "26.30"));
            assertTrue(compositeError.getMessage().contains("Duplicate APK manifest entries"));
        } finally {
            Files.deleteIfExists(apk);
        }
    }

    @Test
    public void boundsManifestEntrySizeBeforeParsing() throws Exception {
        Path apk = Files.createTempFile("gmaps-large-manifest-", ".apk");
        try {
            writeZip(apk, new Entry("AndroidManifest.xml", new byte[8 * 1024 * 1024 + 1]));
            IOException error = assertThrows(IOException.class,
                    () -> GmapsDiagnosticPatcher.readManifestEntry(apk.toFile()));
            assertTrue(error.getMessage().contains("APK entry exceeds limit"));
        } finally {
            Files.deleteIfExists(apk);
        }
    }

    @Test
    public void pipInspectionRejectsUnknownProfileBeforeManifestParsing() throws Exception {
        Path apk = Files.createTempFile("gmaps-unknown-profile-", ".apk");
        try {
            writeZip(apk, new Entry("AndroidManifest.xml", new byte[] {1}));
            IOException error = assertThrows(IOException.class,
                    () -> GmapsDiagnosticPatcher.inspectPipClassification(apk.toFile()));
            assertTrue(error.getMessage().contains("unsupported GMaps target profile"));
        } finally {
            Files.deleteIfExists(apk);
        }
    }

    private static void writeZip(Path path, Entry... entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int[] localOffsets = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            localOffsets[i] = output.size();
            writeLocal(output, entries[i]);
        }
        int centralOffset = output.size();
        for (int i = 0; i < entries.length; i++) {
            writeCentral(output, entries[i], localOffsets[i]);
        }
        int centralSize = output.size() - centralOffset;
        writeInt(output, 0x06054b50);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, entries.length);
        writeShort(output, entries.length);
        writeInt(output, centralSize);
        writeInt(output, centralOffset);
        writeShort(output, 0);
        Files.write(path, output.toByteArray());
    }

    private static void writeLocal(ByteArrayOutputStream output, Entry entry) {
        byte[] name = entry.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeInt(output, 0x04034b50);
        writeShort(output, 20);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, (int) entry.crc);
        writeInt(output, entry.data.length);
        writeInt(output, entry.data.length);
        writeShort(output, name.length);
        writeShort(output, 0);
        output.write(name, 0, name.length);
        output.write(entry.data, 0, entry.data.length);
    }

    private static void writeCentral(ByteArrayOutputStream output, Entry entry, int localOffset) {
        byte[] name = entry.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeInt(output, 0x02014b50);
        writeShort(output, 20);
        writeShort(output, 20);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, (int) entry.crc);
        writeInt(output, entry.data.length);
        writeInt(output, entry.data.length);
        writeShort(output, name.length);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, 0);
        writeInt(output, localOffset);
        output.write(name, 0, name.length);
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static final class Entry {
        final String name;
        final byte[] data;
        final long crc;

        Entry(String name, byte[] data) {
            this.name = name;
            this.data = data;
            CRC32 checksum = new CRC32();
            checksum.update(data);
            this.crc = checksum.getValue();
        }
    }
}
