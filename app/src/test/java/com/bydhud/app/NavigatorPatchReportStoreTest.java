package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class NavigatorPatchReportStoreTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void persistsFullAttemptAndRequiresVerifiedPreparedFingerprint() throws Exception {
        JSONObject source = new JSONObject()
                .put("apkSetSha256", hash('a'))
                .put("package", "com.google.android.apps.maps")
                .put("versionCode", 123L)
                .put("signerSha256", hash('b'));
        JSONObject initial = new JSONObject()
                .put("source", source)
                .put("patchRevision", "gmaps-26.30-r4");
        NavigatorPatchReportStore.begin(context, "operation-one", "gmaps", "PATCH",
                dayTime(2026, 1, 5), initial);
        assertEquals("STARTED", NavigatorPatchReportStore.snapshot(context, "operation-one")
                .getString("currentStage"));
        NavigatorPatchReportStore.recordComponent(context, "operation-one", "Direct",
                "PATCHABLE", "PATCH", "PATCHED", "verified", new JSONObject()
                        .put("beforeRevision", "stock")
                        .put("outputRevision", "r4"));
        assertEquals(1, NavigatorPatchReportStore.snapshot(context, "operation-one")
                .getJSONArray("components").length());
        NavigatorPatchReportStore.recordStage(context, "operation-one", "OUTPUT_VERIFIED",
                "SUCCESS", "package, signer and DEX verified", new JSONObject()
                        .put("output", new JSONObject()
                                .put("sha256", hash('c'))
                                .put("package", "com.google.android.apps.maps")
                                .put("versionCode", 123L)
                                .put("signerSha256", hash('d'))));
        assertEquals("OUTPUT_VERIFIED", NavigatorPatchReportStore.snapshot(context,
                "operation-one").getString("currentStage"));

        JSONObject prepared = new JSONObject()
                .put("output", new JSONObject()
                        .put("sha256", hash('c'))
                        .put("package", "com.google.android.apps.maps")
                        .put("versionCode", 123L)
                        .put("signerSha256", hash('d')));
        NavigatorPatchReportStore.recordStage(context, "operation-one", "PREPARED",
                "SUCCESS", "ready for Android install", prepared);
        assertEquals(hash('c'), NavigatorPatchReportStore.requirePrepared(context, "operation-one"));

        NavigatorPatchReportStore.recordStage(context, "operation-one", "INSTALL_REQUESTED",
                "SUCCESS", "Android confirmation opened", new JSONObject()
                        .put("sessionId", "session-8"));
        NavigatorPatchReportStore.recordStage(context, "operation-one", "POSTVERIFY",
                "SUCCESS", "installed identity and component results match", new JSONObject()
                        .put("installedSha256", hash('c')));
        NavigatorPatchReportStore.finish(context, "operation-one", "SUCCESS", "complete");
        NavigatorPatchReportStore.begin(context, "operation-one", "gmaps", "PATCH",
                dayTime(2026, 1, 5), new JSONObject());

        JSONObject report = NavigatorPatchReportStore.snapshot(context, "operation-one");
        assertEquals("COMPLETE", report.getString("status"));
        assertFalse(report.getBoolean("incomplete"));
        assertEquals(hash('a'), report.getJSONObject("metadata").getJSONObject("source")
                .getString("apkSetSha256"));
        assertEquals("gmaps-26.30-r4", report.getJSONObject("metadata")
                .getString("patchRevision"));
        assertEquals(1, report.getJSONArray("components").length());
        assertEquals("PATCHED", report.getJSONArray("components").getJSONObject(0)
                .getString("outcome"));
        assertTrue(report.getJSONArray("stages").length() >= 4);
    }

    @Test
    public void reportOnlyDaysAreDiscoverableAndNormalZipExportsAllHistory() throws Exception {
        long firstTime = dayTime(2026, 1, 5);
        long secondTime = dayTime(2026, 1, 6);
        String firstDay = NavCaptureStore.todayDir(firstTime);
        String secondDay = NavCaptureStore.todayDir(secondTime);
        beginMinimal("operation-day-one", firstTime);
        NavigatorPatchReportStore.finish(context, "operation-day-one", "COMPLETE", "done");
        beginMinimal("operation-day-two", secondTime);
        NavigatorPatchReportStore.finish(context, "operation-day-two", "COMPLETE", "done");

        Map<String, Long> reportDays = NavigatorPatchReportStore.reportDays(context);
        assertTrue(reportDays.containsKey(firstDay));
        assertTrue(reportDays.containsKey(secondDay));
        NavigationLogStorage.StorageSnapshot storage =
                NavigationLogStorage.snapshotAccessibleStorage(context);
        assertStorageDay(storage.days, firstDay);
        assertStorageDay(storage.days, secondDay);

        LogShareZip.SelectionSummary selection =
                LogShareZip.summarize(context, Arrays.asList(firstDay));
        assertTrue(selection.ok);
        assertTrue(selection.fileCount >= 1);

        LogShareZip.Result archive = LogShareZip.create(context, Arrays.asList(firstDay));
        assertTrue(archive.detail, archive.ok);
        try (ZipFile zip = new ZipFile(archive.file)) {
            ZipEntry entry = zip.getEntry(NavCaptureStore.todayDir() + "/diagnostics/navigator-patch-reports.json");
            assertNotNull(entry);
            JSONObject history = new JSONObject(new String(
                    zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(2, history.getInt("reportCount"));
            assertTrue(java.util.Collections.list(zip.entries()).stream()
                    .allMatch(item -> item.getName().matches("[0-9]{8}/.+")));
            assertEquals("operation-day-one", history.getJSONArray("reports")
                    .getJSONObject(0).getString("operationId"));
            assertEquals("operation-day-two", history.getJSONArray("reports")
                    .getJSONObject(1).getString("operationId"));
        }
    }

    @Test
    public void cancellationIsRetainedAndLateLifecycleWritesCannotReopenTerminalReport()
            throws Exception {
        beginMinimal("operation-cancelled", dayTime(2026, 2, 1));
        NavigatorPatchReportStore.recordComponent(context, "operation-cancelled", "Audio",
                "PATCHABLE", "PATCH", "FAILED", "missing guarded target", new JSONObject());
        NavigatorPatchReportStore.recordStage(context, "operation-cancelled", "CANCEL_REQUESTED",
                "SUCCESS", "user cancelled before install", null);
        NavigatorPatchReportStore.recordStage(context, "operation-cancelled", "CANCELLED",
                "SUCCESS", "cancellation completed", null);
        NavigatorPatchReportStore.finish(context, "operation-cancelled", "CANCELLED",
                "cancel acknowledged; staged APK removed");
        NavigatorPatchReportStore.recordStageAsync(context, "operation-cancelled",
                "LATE_WORKER_CALLBACK", "SUCCESS", "late callback observed", null);
        NavigatorPatchReportStore.awaitPendingWrites();

        JSONObject snapshot = NavigatorPatchReportStore.snapshot(context, "operation-cancelled");
        assertEquals("CANCELLED", snapshot.getString("status"));
        assertEquals("TERMINAL", snapshot.getString("currentStage"));
        assertFalse(snapshot.getBoolean("incomplete"));
        assertEquals(1, snapshot.getJSONArray("components").length());
        assertEquals("LATE_WORKER_CALLBACK", snapshot.getJSONArray("stages")
                .getJSONObject(snapshot.getJSONArray("stages").length() - 1)
                .getString("stage"));
    }

    @Test
    public void interruptedSnapshotAndPrivacyAreExplicitInExport() throws Exception {
        JSONObject metadata = new JSONObject()
                .put("vin", "VIN-PRIVATE-987")
                .put("authorization", "Bearer secret-value")
                .put("diagnosticEndpoint", "192.168.1.8:5100")
                .put("source", new JSONObject().put("sha256", hash('e')));
        NavigatorPatchReportStore.begin(context, "operation-active", "waze", "PATCH",
                dayTime(2026, 3, 1), metadata);
        NavigatorPatchReportStore.recordStage(context, "operation-active", "PATCHING",
                "IN_PROGRESS", "map component running", new JSONObject());
        JSONObject current = NavigatorPatchReportStore.snapshot(context, "operation-active");
        assertTrue(current.getBoolean("incomplete"));
        assertEquals("PATCHING", current.getString("currentStage"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NavigatorPatchReportStore.ExportResult result;
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            result = NavigatorPatchReportStore.writeReports(context, zip);
        }
        assertEquals(1, result.reportCount);
        assertTrue(result.incomplete);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry entry = zip.getNextEntry();
            assertEquals(result.entry, entry.getName());
            assertEquals(NavCaptureStore.todayDir() + "/diagnostics/navigator-patch-reports.json", entry.getName());
            String exported = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(exported.contains("VIN-PRIVATE-987"));
            assertFalse(exported.contains("secret-value"));
            assertFalse(exported.contains("192.168.1.8"));
            assertTrue(exported.contains(hash('e')));
            JSONObject history = new JSONObject(exported);
            assertTrue(history.getJSONArray("reports").getJSONObject(0)
                    .getBoolean("incomplete"));
            assertEquals(NavCaptureStore.todayDir() + "/diagnostics/INCOMPLETE-NAVIGATOR-PATCH-REPORTS.txt",
                    zip.getNextEntry().getName());
            assertTrue(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains(result.entry));
        }
    }

    @Test
    public void configurationZipIncludesFullHistoryAndIncompleteStatus() throws Exception {
        beginMinimal("config-history-one", dayTime(2026, 4, 1));
        NavigatorPatchReportStore.finish(context, "config-history-one", "COMPLETE", "done");
        beginMinimal("config-history-active", dayTime(2026, 4, 2));

        File output = new File(context.getCacheDir(), "patch-report-config.zip");
        VehicleConfigurationZip.Control control = new VehicleConfigurationZip.Control();
        try (VehicleConfigurationZip.Collector collector =
                     new VehicleConfigurationZip.Collector(context, control)) {
            VehicleConfigurationZip.Result result = VehicleConfigurationZip.writeFullArchive(
                    output, collector, new VehicleConfigurationFiles.Inventory(), null, control,
                    (phase, file, copied, total, files, count, unavailable) -> { });
            assertTrue(result.detail, result.ok);
            try (ZipFile zip = new ZipFile(result.file)) {
                assertNotNull(zip.getEntry(NavCaptureStore.todayDir() + "/diagnostics/navigator-patch-reports.json"));
                assertNotNull(zip.getEntry(NavCaptureStore.todayDir() + "/diagnostics/INCOMPLETE-NAVIGATOR-PATCH-REPORTS.txt"));
                JSONObject history = new JSONObject(new String(zip.getInputStream(
                        zip.getEntry(NavCaptureStore.todayDir() + "/diagnostics/navigator-patch-reports.json")).readAllBytes(),
                        StandardCharsets.UTF_8));
                assertEquals(2, history.getInt("reportCount"));
                JSONObject manifest = new JSONObject(new String(zip.getInputStream(
                        zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("INCOMPLETE", manifest.getJSONObject("navigatorPatchReports")
                        .getString("status"));
                assertEquals(NavCaptureStore.todayDir() + "/diagnostics/navigator-patch-reports.json",
                        manifest.getJSONObject("navigatorPatchReports").getString("entry"));
                assertEquals("partial", manifest.getString("status"));
            }
        } finally {
            control.close();
        }
    }

    @Test
    public void corruptReportFailsSnapshotInsteadOfDroppingHistory() throws Exception {
        beginMinimal("operation-corrupt", dayTime(2026, 5, 1));
        File directory = new File(context.getFilesDir(), "navigator-patch-reports");
        File[] reports = directory.listFiles((parent, name) -> name.endsWith(".json"));
        assertNotNull(reports);
        assertEquals(1, reports.length);
        Files.write(reports[0].toPath(), "{truncated".getBytes(StandardCharsets.UTF_8));
        try {
            NavigatorPatchReportStore.snapshotAll(context);
            fail("corrupt history must fail the snapshot");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("corrupt"));
        }
    }

    private void beginMinimal(String id, long startedAt) throws Exception {
        NavigatorPatchReportStore.begin(context, id, "gmaps", "PATCH", startedAt,
                new JSONObject().put("patchRevision", "test-r1"));
    }

    private static void assertStorageDay(List<NavigationLogStorage.StorageDay> days, String name) {
        for (NavigationLogStorage.StorageDay day : days) {
            if (!name.equals(day.name)) continue;
            assertEquals(0, day.cropSessions);
            assertTrue(day.hasPrivateStorage);
            return;
        }
        fail("missing report-only storage day " + name);
    }

    private static long dayTime(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(year, month - 1, day, 12, 0, 0);
        return calendar.getTimeInMillis();
    }

    private static String hash(char digit) {
        char[] value = new char[64];
        Arrays.fill(value, digit);
        return new String(value);
    }
}
