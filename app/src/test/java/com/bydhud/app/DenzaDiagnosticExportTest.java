package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DenzaDiagnosticExportTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Context context;

    @Before public void setUp() throws Exception {
        context = new IsolatedStorageContext(RuntimeEnvironment.getApplication(),
                temporaryFolder.newFolder("storage"));
        var reset = NavigationLogStorage.class.getDeclaredMethod("invalidatePublicRootCache");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @Test public void completeSnapshotAndCommandBytesStayInsideDatedCapture() throws Exception {
        String day = NavCaptureStore.todayDir(System.currentTimeMillis());
        File operation = operation(day, "original-capture", "transfer-a");
        write(operation, "status.json", "{\"status\":\"complete\",\"captureId\":\"original-capture\"}");
        write(operation, "surfaceflinger.txt", "full output\nlast line\n");
        LogShareZip.Result result = LogShareZip.create(context, Collections.singletonList(day));
        assertTrue(result.detail, result.ok);
        try (ZipFile zip = new ZipFile(result.file)) {
            JSONObject health = recordingStatus(zip);
            assertEquals("COMPLETE", health.getJSONObject("transferDiagnostics").getString("outcome"));
            assertEquals(1, health.getJSONObject("transferDiagnostics").getInt("operationCount"));
            assertTrue(health.getJSONObject("transferDiagnostics").getBoolean("separateFromLogcatCompleteness"));
            ZipEntry command = find(zip, "/diagnostics/denza-transfer/transfer-a/surfaceflinger.txt");
            assertNotNull(command);
            assertTrue(command.getName().startsWith(day + "/"));
            assertEquals("full output\nlast line\n", text(zip, command));
            assertNull(find(zip, "/INCOMPLETE-TRANSFER-DIAGNOSTICS.txt"));
        }
    }

    @Test public void inProgressAndHistoricalPartialSnapshotsDoNotClaimLogcatLoss() throws Exception {
        String day = NavCaptureStore.todayDir(System.currentTimeMillis());
        String priorDay = NavCaptureStore.todayDir(System.currentTimeMillis() - 86_400_000L);
        write(operation(priorDay, "old-capture", "old-transfer"), "status.json",
                "{\"status\":\"partial\",\"captureId\":\"old-capture\",\"error\":\"timeout\"}");
        File current = operation(day, "new-capture", "new-transfer");
        write(current, "status.json", "{\"status\":\"collecting\",\"captureId\":\"new-capture\"}");
        write(current, "display.txt", "partial display bytes");
        LogShareZip.Result result = LogShareZip.create(context, Arrays.asList(priorDay, day));
        assertTrue(result.detail, result.ok);
        try (ZipFile zip = new ZipFile(result.file)) {
            JSONObject health = recordingStatus(zip);
            JSONObject transfer = health.getJSONObject("transferDiagnostics");
            assertEquals("IN_PROGRESS", transfer.getString("outcome"));
            assertEquals(2, transfer.getInt("operationCount"));
            assertEquals(1, transfer.getJSONObject("statusCounts").getInt("partial"));
            assertEquals("FULL", health.getString("collectionOutcome"));
            assertNotNull(find(zip, "/INCOMPLETE-TRANSFER-DIAGNOSTICS.txt"));
            assertEquals("partial display bytes", text(zip,
                    find(zip, "/diagnostics/denza-transfer/new-transfer/display.txt")));
            assertTrue(text(zip, find(zip, "/diagnostics/denza-transfer/old-transfer/status.json"))
                    .contains("old-capture"));
        }
    }

    @Test public void malformedTransferMetadataIsReportedInsteadOfOmitted() throws Exception {
        String day = NavCaptureStore.todayDir(System.currentTimeMillis());
        write(operation(day, "capture", "bad"), "status.json", "not-json");
        LogShareZip.Result result = LogShareZip.create(context, Collections.singletonList(day));
        assertTrue(result.detail, result.ok);
        try (ZipFile zip = new ZipFile(result.file)) {
            JSONObject diagnostics = recordingStatus(zip).getJSONObject("transferDiagnostics");
            assertEquals("PARTIAL", diagnostics.getString("outcome"));
            assertEquals(1, diagnostics.getJSONArray("unreadableMetadata").length());
            assertEquals("not-json", text(zip,
                    find(zip, "/diagnostics/denza-transfer/bad/status.json")));
        }
    }

    private File operation(String day, String capture, String operation) {
        return new File(NavigationLogStorage.logcatDir(context, day),
                capture + "/diagnostics/denza-transfer/" + operation);
    }

    private static void write(File directory, String name, String value) throws Exception {
        Files.createDirectories(directory.toPath());
        Files.write(new File(directory, name).toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject recordingStatus(ZipFile zip) throws Exception {
        return new JSONObject(text(zip, find(zip, "/diagnostics/recording-status.json")));
    }

    private static ZipEntry find(ZipFile zip, String suffix) {
        return Collections.list(zip.entries()).stream()
                .filter(entry -> entry.getName().endsWith(suffix)).findFirst().orElse(null);
    }

    private static String text(ZipFile zip, ZipEntry entry) throws Exception {
        assertNotNull(entry);
        return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static final class IsolatedStorageContext extends ContextWrapper {
        private final File root;

        IsolatedStorageContext(Context base, File root) {
            super(base);
            this.root = root;
        }

        @Override public Context getApplicationContext() { return this; }

        @Override public File getExternalFilesDir(String type) {
            return directory("external-files", type);
        }

        @Override public File getExternalCacheDir() {
            return directory("external-cache", null);
        }

        @Override public File getFilesDir() {
            return directory("files", null);
        }

        @Override public File getCacheDir() {
            return directory("cache", null);
        }

        @Override public int checkSelfPermission(String permission) {
            return PackageManager.PERMISSION_DENIED;
        }

        private File directory(String name, String child) {
            File result = new File(root, name);
            if (child != null && !child.isEmpty()) result = new File(result, child);
            if (!result.isDirectory() && !result.mkdirs() && !result.isDirectory()) {
                throw new IllegalStateException("Unable to create test storage " + result);
            }
            return result;
        }
    }
}
