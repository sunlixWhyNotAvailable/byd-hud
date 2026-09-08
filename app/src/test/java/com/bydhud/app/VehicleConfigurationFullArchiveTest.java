package com.bydhud.app;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class VehicleConfigurationFullArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void copyCountsRealBytesAndRejectsChangedSources() throws Exception {
        byte[] source = new byte[150_001];
        List<Long> progress = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(source.length, VehicleConfigurationZip.copyExact(new ByteArrayInputStream(source),
                out, source.length, new VehicleConfigurationZip.Control(), progress::add));
        assertArrayEquals(source, out.toByteArray());
        assertEquals(Long.valueOf(source.length), progress.get(progress.size() - 1));
        assertThrows(IOException.class, () -> VehicleConfigurationZip.copyExact(
                new ByteArrayInputStream(source), new ByteArrayOutputStream(), source.length - 1,
                new VehicleConfigurationZip.Control(), ignored -> { }));
        assertThrows(IOException.class, () -> VehicleConfigurationZip.copyExact(
                new ByteArrayInputStream(source), new ByteArrayOutputStream(), source.length + 1,
                new VehicleConfigurationZip.Control(), ignored -> { }));
        VehicleConfigurationZip.Control stopped = new VehicleConfigurationZip.Control();
        assertThrows(InterruptedIOException.class, () -> VehicleConfigurationZip.copyExact(
                new ByteArrayInputStream(source), new ByteArrayOutputStream(), source.length,
                stopped, bytes -> stopped.cancel()));
    }

    @Test public void archiveStreamsLargerThanOldCapAndVerifiesContents() throws Exception {
        File source = temp.newFile("large.apk");
        try (RandomAccessFile file = new RandomAccessFile(source, "rw")) {
            file.setLength(VehicleConfigurationZip.MAX_TOTAL_BYTES + 3L * 1024 * 1024);
        }
        File archive = temp.newFile("full.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            VehicleConfigurationZip.appendFile(zip, "files/system/app/large.apk", source,
                    new VehicleConfigurationZip.Control());
        }
        JSONArray expected = new JSONArray().put(new JSONObject()
                .put("path", "files/system/app/large.apk").put("size", source.length())
                .put("sha256", sha(source)));
        VehicleConfigurationZip.verifyFullZip(archive, expected, new VehicleConfigurationZip.Control());
        expected.getJSONObject(0).put("sha256", "corrupt");
        assertThrows(IOException.class, () -> VehicleConfigurationZip.verifyFullZip(
                archive, expected, new VehicleConfigurationZip.Control()));
    }

    @Test public void missingFileKeepsUsablePartialArchiveAndStableInventory() throws Exception {
        File output = new File(temp.getRoot(), "partial.zip");
        VehicleConfigurationZip.Collector collector = new VehicleConfigurationZip.Collector(null);
        collector.addJson("app/test.json", "test", new JSONObject().put("readOnly", true));
        VehicleConfigurationFiles.Inventory inventory = new VehicleConfigurationFiles.Inventory();
        inventory.add(new VehicleConfigurationFiles.Entry(
                "/system/lib64/libBydCluster-192.168.1.10-not-installed.so", "files/system/lib64/missing.so",
                500, 0, 0, 0, "regular", "native"), null);
        List<Long> totals = new ArrayList<>();
        List<String> progress = new ArrayList<>();
        VehicleConfigurationZip.Result result = VehicleConfigurationZip.writeFullArchive(
                output, collector, inventory, null, new VehicleConfigurationZip.Control(),
                (phase, file, bytes, total, files, count, unavailable) -> {
                    totals.add(total);
                    progress.add(phase + "|" + file);
                });
        assertTrue(result.detail, result.ok);
        assertEquals(1, result.unavailableFiles);
        assertEquals(1, totals.stream().distinct().count());
        assertTrue(progress.contains("COPYING|/system/lib64/libBydCluster-192.168.1.10-not-installed.so"));
        assertTrue(progress.contains("ARCHIVING|manifest.json"));
        assertTrue(progress.contains("VERIFYING|app/test.json"));
        assertTrue(progress.contains("VERIFYING|manifest.json"));
        try (ZipFile zip = new ZipFile(output)) {
            assertNull(zip.getEntry("files/system/lib64/missing.so"));
            JSONObject manifest = new JSONObject(new String(zip.getInputStream(
                    zip.getEntry("manifest.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("partial", manifest.getString("status"));
            assertEquals(2, manifest.getInt("inventoryFiles"));
            assertEquals(1, manifest.getInt("copiedFiles"));
            assertEquals(1, manifest.getJSONArray("unavailable").length());
            assertFalse(manifest.toString().contains("192.168.1.10"));
            assertTrue(manifest.getJSONArray("unavailable").getJSONObject(0).getString("path").contains("<IP_1>"));
            assertFalse(manifest.has("maxTotalBytes"));
        }
        assertFalse(new File(output.getPath() + ".part").exists());
        assertFalse(new File(output.getPath() + ".source.part").exists());
    }

    @Test public void rawPathsAndAliasesUseArchiveLocalMaskingWithoutChangingBinary() throws Exception {
        File source = temp.newFile("source.bin");
        Files.write(source.toPath(), new byte[]{0, 1, 2, 3});
        VehicleConfigurationZip.Collector collector = new VehicleConfigurationZip.Collector(null);
        VehicleConfigurationFiles.Entry entry = new VehicleConfigurationFiles.Entry(
                "/vendor/cluster/192.168.1.10.so", "files/vendor/cluster/192.168.1.10.so",
                source.length(), 0, 0, 0, "regular", "native");
        entry.aliases.add("/vendor/cluster/aa:bb:cc:dd:ee:ff.so");
        entry.aliases.add(entry.sourcePath);
        String sourceHash = sha(source);
        JSONObject metadata = VehicleConfigurationZip.fileMetadata(entry, source, false, sourceHash,
                new VehicleConfigurationZip.Control(), collector);
        assertEquals("files/vendor/cluster/[IP_1].so", metadata.getString("path"));
        assertEquals("/vendor/cluster/<IP_1>.so", metadata.getString("source"));
        assertFalse(metadata.toString().contains("192.168.1.10"));
        assertFalse(metadata.toString().contains("aa:bb:cc:dd:ee:ff"));
        assertTrue(metadata.getJSONArray("aliases").toString().contains("<MAC_1>"));
        assertEquals(sourceHash, metadata.getString("sourceSha256"));
        assertEquals(sourceHash, metadata.getString("sha256"));
        File output = temp.newFile("masked.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            VehicleConfigurationZip.appendFile(zip, metadata.getString("path"), source,
                    new VehicleConfigurationZip.Control());
        }
        VehicleConfigurationZip.verifyFullZip(output, new JSONArray().put(metadata),
                new VehicleConfigurationZip.Control());
        assertEquals(sourceHash, sha(source));
    }

    @Test public void cancellationRemovesPartialAndDoesNotPublishZip() throws Exception {
        File output = new File(temp.getRoot(), "cancelled.zip");
        VehicleConfigurationZip.Control cancelled = new VehicleConfigurationZip.Control();
        VehicleConfigurationZip.Result result = VehicleConfigurationZip.writeFullArchive(output,
                new VehicleConfigurationZip.Collector(null), new VehicleConfigurationFiles.Inventory(),
                null, cancelled, (phase, file, bytes, total, files, count, unavailable) -> cancelled.cancel());
        assertFalse(result.ok);
        assertFalse(output.exists());
        assertFalse(new File(output.getPath() + ".part").exists());
    }

    @Test public void statAndFreeSpaceGuardsRejectInconsistentInventory() throws Exception {
        VehicleConfigurationFiles.Entry entry = new VehicleConfigurationFiles.Entry(
                "/system/lib64/example.so", "files/system/lib64/example.so", 3, 1000, 2, 4, "regular", "native");
        VehicleConfigurationZip.ensureUnchanged(entry,
                new VehicleConfigurationFiles.FileStat(0100644, 3, 1000, 2, 4));
        assertThrows(IOException.class, () -> VehicleConfigurationZip.ensureUnchanged(entry,
                new VehicleConfigurationFiles.FileStat(0100644, 3, 1001, 2, 4)));
        assertThrows(IOException.class, () -> VehicleConfigurationZip.ensureUnchanged(entry,
                new VehicleConfigurationFiles.FileStat(0100644, 3, 1000, 2, 5)));
        assertThrows(IOException.class, () -> VehicleConfigurationZip.requireFreeSpace(100, 100, 100));
        assertThrows(IOException.class, () -> VehicleConfigurationZip.requireFreeSpace(Long.MAX_VALUE,
                Long.MAX_VALUE, 500));
    }

    private static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }
}
