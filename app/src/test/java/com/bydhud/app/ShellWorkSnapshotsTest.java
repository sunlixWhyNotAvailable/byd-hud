package com.bydhud.app;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ShellWorkSnapshotsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void allSelectedFilesSurviveBatchedRegistrationAndReopen() throws Exception {
        File control = temporary.newFolder();
        File inputs = temporary.newFolder();
        JSONArray entries = new JSONArray().put(new JSONObject().put("entry", "generated.json"));
        for (int i = 0; i < 356; i++) {
            File source = new File(inputs, i + ".log");
            byte[] contents = ("selected-log-" + i).getBytes(StandardCharsets.UTF_8);
            Files.write(source.toPath(), contents);
            entries.put(new JSONObject().put("path", source.getAbsolutePath())
                    .put("entry", i + ".log").put("length", contents.length));
        }
        JSONObject input = new JSONObject().put("sources", entries);
        JSONObject manifest = start(control, "log", input);
        JSONArray paths = manifest.getJSONArray("snapshotPaths");
        assertEquals(356, paths.length());
        // The live recorder may append; the chosen snapshot ends at the selected length.
        Files.write(new File(paths.getString(0)).toPath(), "later-data".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        for (int offset = 0; offset < paths.length();) {
            manifest = ShellWorkFiles.readManifest(new File(control, "request.json"));
            int cursor = ShellWorkSnapshots.progress(control, manifest).getInt("nextSnapshot");
            assertEquals(offset, cursor);
            int count = Math.min(ShellWorkSnapshots.BATCH_SIZE, paths.length() - cursor);
            List<ShellWorkSnapshots.Source> sources = new ArrayList<>();
            try {
                for (int i = 0; i < count; i++) {
                    File file = new File(paths.getString(cursor + i));
                    sources.add(new ShellWorkSnapshots.Source(new FileInputStream(file), file.length()));
                }
                ShellWorkSnapshots.append(control, manifest, cursor, sources, cursor + count == paths.length());
            } finally {
                for (ShellWorkSnapshots.Source source : sources) source.input.close();
            }
            offset += count;
        }
        JSONObject result = ShellWorkFiles.readManifest(new File(control, "request.json"));
        assertEquals("REGISTERED", result.getString("state"));
        assertEquals(356, result.getInt("nextSnapshot"));
        JSONArray snapshots = result.getJSONObject("workerInput").getJSONArray("sources");
        assertEquals(357, snapshots.length());
        assertFalse(snapshots.getJSONObject(0).has("path"));
        JSONArray fingerprints = result.getJSONArray("inputFingerprints");
        assertEquals(356, fingerprints.length());
        for (int i = 0; i < 356; i++) {
            byte[] expected = ("selected-log-" + i).getBytes(StandardCharsets.UTF_8);
            File snapshot = new File(snapshots.getJSONObject(i + 1).getString("path"));
            assertArrayEquals(expected, Files.readAllBytes(snapshot.toPath()));
            assertEquals(expected.length, fingerprints.getJSONObject(i).getLong("length"));
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)),
                    fingerprints.getJSONObject(i).getString("sha256"));
        }
    }

    @Test public void truncatedBatchCannotAdvanceAndZeroInputConfigurationCanSeal() throws Exception {
        File source = temporary.newFile();
        Files.write(source.toPath(), "complete".getBytes(StandardCharsets.UTF_8));
        File control = temporary.newFolder();
        JSONObject manifest = start(control, "log", new JSONObject().put("sources", new JSONArray()
                .put(new JSONObject().put("path", source.getAbsolutePath()).put("length", source.length()))));
        // Actual source shrinkage after selection must fail, never certify a partial export.
        Files.write(source.toPath(), "cut".getBytes(StandardCharsets.UTF_8));
        try (FileInputStream stream = new FileInputStream(source)) {
            List<ShellWorkSnapshots.Source> sources = List.of(new ShellWorkSnapshots.Source(stream, source.length()));
            assertThrows(IOException.class, () -> ShellWorkSnapshots.append(control, manifest, 0, sources, true));
        }
        assertEquals(0, ShellWorkSnapshots.progress(control,
                ShellWorkFiles.readManifest(new File(control, "request.json"))).getInt("nextSnapshot"));
        File configuration = temporary.newFolder();
        JSONObject request = start(configuration, "configuration", new JSONObject());
        ShellWorkSnapshots.append(configuration, request, 0, List.of(), true);
        assertEquals("REGISTERED", ShellWorkFiles.readManifest(new File(configuration, "request.json")).getString("state"));
    }

    private static JSONObject start(File control, String type, JSONObject input) throws Exception {
        JSONObject request = new JSONObject().put("state", "REGISTERING").put("type", type)
                .put("input", input).put("workerInput", new JSONObject(input.toString()))
                .put("snapshotPaths", ShellWorkSnapshots.paths(type, input));
        ShellWorkFiles.write(new File(control, "request.json"), request);
        return request;
    }
}
