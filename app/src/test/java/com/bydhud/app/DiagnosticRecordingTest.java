package com.bydhud.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
public final class DiagnosticRecordingTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Context context;
    private String day;

    @Before public void setUp() throws Exception {
        IncidentTestSupport.restartProcessDiagnostics();
        context = new IsolatedStorageContext(RuntimeEnvironment.getApplication(),
                temporaryFolder.newFolder("storage"));
        var reset = NavigationLogStorage.class.getDeclaredMethod("invalidatePublicRootCache");
        reset.setAccessible(true);
        reset.invoke(null);
        day = NavCaptureStore.todayDir();
        RuntimeIncidentRecorder.initialize(context);
    }

    @org.junit.After public void tearDown() throws Exception {
        IncidentTestSupport.restartProcessDiagnostics();
    }

    @Test public void blockedWriterRecordsStallAndRecoveryOutsideStorageLockWithoutClaimingLoss() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        assertTrue(WazeCaptureDebugWriter.get().directEvent(() -> NavigationLogStorage.withReadLock(() -> {
            entered.countDown(); await(release);
        })));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
            awaitHistory("stall");
            String history = incidentText();
            assertTrue(history.contains("direct_event"));
            assertTrue(history.contains("storageLockOwners"));
            JSONObject summary = RuntimeIncidentRecorder.summarize(RuntimeIncidentRecorder.snapshot(context,
                    Collections.singletonList(day)).values());
            assertTrue(summary.getBoolean("coverageUncertain"));
            assertFalse(summary.getBoolean("lossObserved"));
        } finally { release.countDown(); }
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        awaitHistory("recovery");
        JSONObject summary = RuntimeIncidentRecorder.summarize(RuntimeIncidentRecorder.snapshot(context,
                Collections.singletonList(day)).values());
        assertFalse(summary.getBoolean("coverageUncertain"));
        assertFalse(summary.getBoolean("lossObserved"));
    }

    @Test @Config(shadows = LogShareShellTestSupport.class)
    public void actualLossSurvivesFreshWriterAndShellExportUsesFrozenHistory() throws Exception {
        NavCaptureStore.writeSomeIpTx(context, day, "preserve-me");
        File blocked = blockIncidentFile();
        WazeCaptureDebugWriter writer = WazeCaptureDebugWriter.get();
        assertTrue(writer.directEvent(() -> { throw new IllegalStateException("disk write failed"); }));
        assertTrue(writer.awaitCheckpoint(2000));
        awaitHistory("persistence_uncertain");
        assertTrue(blocked.delete());
        awaitHistory("loss");
        // Replace volatile writer/recorder state while preserving its files, as after process death.
        IncidentTestSupport.restartProcessDiagnostics();
        RuntimeIncidentRecorder.initialize(context);
        assertFalse(WazeCaptureDebugWriter.healthSnapshot().getBoolean("lossObserved"));

        LogShareZip.Result result = LogShareShellTestSupport.export(context, Collections.singletonList(day));
        assertTrue(result.detail, result.ok);
        assertEquals(LogShareZip.CollectionOutcome.PARTIAL, result.outcome);
        try (ZipFile zip = new ZipFile(result.file)) {
            assertNotNull(zip.getEntry(day + "/diagnostics/" + RuntimeIncidentRecorder.FILE_NAME));
            assertTrue(status(zip).getJSONObject("incidentHistory").getBoolean("lossObserved"));
        }
        try (ZipFile zip = new ZipFile(result.file)) {
            var entry = zip.getEntry(day + "/diagnostics/" + RuntimeIncidentRecorder.FILE_NAME);
            assertNotNull(entry);
            assertTrue(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)
                    .contains("disk write failed"));
        }
    }

    @Test @Config(shadows = LogShareShellTestSupport.class)
    public void failedStallAppendRetriesAndRemainsUncertainAfterRecoveryAndRestart() throws Exception {
        NavCaptureStore.writeSomeIpTx(context, day, "preserve-me");
        File blocked = blockIncidentFile();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        assertTrue(WazeCaptureDebugWriter.get().directEvent(() -> { entered.countDown(); await(release); }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
            awaitHistory("persistence_uncertain");
            assertTrue(blocked.delete());
        } finally { release.countDown(); }
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        awaitHistory("recovery");
        awaitHistory("stall");
        IncidentTestSupport.restartProcessDiagnostics();
        RuntimeIncidentRecorder.initialize(context);
        LogShareZip.Result result = LogShareShellTestSupport.export(context, Collections.singletonList(day));
        assertTrue(result.detail, result.ok);
        assertEquals(LogShareZip.CollectionOutcome.PARTIAL, result.outcome);
        try (ZipFile zip = new ZipFile(result.file)) {
            JSONObject status = status(zip), history = status.getJSONObject("incidentHistory");
            assertEquals("UNCERTAIN", status.getString("recordingStatus"));
            assertFalse(history.getBoolean("lossObserved"));
            assertTrue(history.getBoolean("persistenceUncertain"));
            assertEquals(0, history.getInt("unresolvedStalls"));
            assertEquals(0, history.getInt("unmatchedRecoveries"));
        }
        JSONObject unpaired = RuntimeIncidentRecorder.summarize(Collections.singletonList(
                "{\"event\":\"recovery\",\"incident\":\"missing-onset\"}\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(unpaired.getBoolean("coverageUncertain"));
        assertFalse(unpaired.getBoolean("lossObserved"));
    }

    private File blockIncidentFile() {
        File blocked = new File(new File(new File(context.getFilesDir(), "runtime-incidents"), day),
                RuntimeIncidentRecorder.FILE_NAME);
        assertTrue(blocked.mkdirs());
        return blocked;
    }

    @Test public void shellRequestIncludesLateUncertaintyWhenNeitherPrivateJournalCanBeWritten() throws Exception {
        NavCaptureStore.writeSomeIpTx(context, day, "preserve-me");
        assertFalse(WazeCaptureDebugWriter.healthSnapshot().getBoolean("incidentPersistenceUncertain"));
        File blocked = blockIncidentFile();
        assertTrue(new File(blocked.getParentFile(), "persistence-uncertain.jsonl").mkdir());
        String operation = "late-uncertainty";
        File job = ShellWorkClient.job(context, "log", operation);
        var gateField = NavigationLogStorage.class.getDeclaredField("TOPOLOGY_GATE");
        gateField.setAccessible(true);
        var gate = (java.util.concurrent.locks.ReentrantReadWriteLock) gateField.get(null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<LogShareZip.Result> result = new AtomicReference<>();
        NavigationLogStorage.lockTopologyRead();
        Thread exporter = daemon(() -> result.set(LogShareZip.createIndependent(context,
                Collections.singletonList(day), "", operation)), failure);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!gate.hasQueuedThread(exporter) && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue("export must reach the storage wait before the incident", gate.hasQueuedThread(exporter));
            assertFalse(RuntimeIncidentRecorder.persistenceUncertain());
            RuntimeIncidentRecorder.loss("late-writer", "observed during export storage wait");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!RuntimeIncidentRecorder.persistenceUncertain() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue("both incident journals must fail before export resumes", RuntimeIncidentRecorder.persistenceUncertain());
        } finally {
            NavigationLogStorage.unlockTopologyRead();
            exporter.join(2000);
        }
        assertFalse("export preparation must finish", exporter.isAlive());
        if (failure.get() != null) throw new AssertionError("export preparation failed", failure.get());
        assertNotNull(result.get());
        JSONObject input = ShellWorkClient.expected(context, job).getJSONObject("input");
        org.json.JSONArray sources = input.getJSONArray("sources");
        assertTrue("recording sources must be included", sources.length() > 0);
        for (int i = 0; i < sources.length(); i++) {
            assertFalse("no private incident journal could be frozen", sources.getJSONObject(i)
                    .getString("entry").endsWith("/" + RuntimeIncidentRecorder.FILE_NAME));
        }
        assertTrue("persisted shell request must carry the late uncertainty",
                input.getJSONObject("health").getBoolean("incidentPersistenceUncertain"));
    }

    private String incidentText() throws Exception {
        StringBuilder text = new StringBuilder();
        File incidentDay = new File(new File(context.getFilesDir(), "runtime-incidents"), day);
        for (String name : new String[]{RuntimeIncidentRecorder.FILE_NAME,
                "persistence-uncertain.jsonl"}) {
            File file = new File(incidentDay, name);
            // A directory at the journal path is the deliberate write-failure fixture. Read any
            // separate regular marker journal without treating the malformed primary as a source.
            if (file.isFile()) {
                text.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    private void awaitHistory(String event) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (incidentText().contains("\"event\":\"" + event + "\"")) return;
            Thread.sleep(20);
        }
        fail("missing incident " + event + ": " + incidentText());
    }

    @Test public void busyStorageFailsWithoutPretendingStatusesAreLogs() throws Exception {
        NavCaptureStore.writeSomeIpTx(context, day, "preserve-me");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread reader = daemon(() -> NavigationLogStorage.withReadLock(() -> {
            entered.countDown(); await(release);
        }), new AtomicReference<>());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            long started = System.nanoTime();
            LogShareZip.Result result = LogShareZip.createIndependent(context,
                    Collections.singletonList(day), "", "busy-" + java.util.UUID.randomUUID());
            assertFalse(result.detail, result.ok);
            assertEquals(LogShareZip.CollectionOutcome.FAILED, result.outcome);
            assertNull(result.file);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(8));
        } finally { release.countDown(); reader.join(2000); }
        assertEquals("preserve-me\n", read(new File(NavigationLogStorage.logsDir(context, day), "someip_tx.jsonl")));
    }

    @Test @Config(shadows = LogShareShellTestSupport.class)
    public void reportOnlyDayIsReadyAndProducesTheProductionShellArchive() throws Exception {
        String operationId = "report-only-" + java.util.UUID.randomUUID();
        NavigatorPatchReportStore.begin(context, operationId, "default", "inspection",
                System.currentTimeMillis(), new JSONObject());
        JSONObject report = NavigatorPatchReportStore.snapshot(context, operationId);
        String reportDay = report.getString("day");
        NavigationLogStorage.StorageSnapshot storage =
                NavigationLogStorage.snapshotAccessibleStorage(context);
        assertTrue(storage.days.stream().anyMatch(item -> reportDay.equals(item.name)));
        assertTrue(LogShareZip.summarize(context, Collections.singletonList(reportDay)).ok);

        LogShareZip.Result result = LogShareShellTestSupport.export(
                context, Collections.singletonList(reportDay));
        assertTrue(result.detail, result.ok);
        assertTrue(result.patchReportsIncomplete);
        try (ZipFile zip = new ZipFile(result.file)) {
            assertNotNull(zip.getEntry(reportDay + "/diagnostics/navigator-patch-reports.json"));
            JSONObject status = status(zip);
            assertEquals("FULL", status.getString("collectionOutcome"));
            assertEquals(1, status.getInt("navigatorPatchReportCount"));
        }
    }

    @Test @Config(shadows = LogShareShellTestSupport.class)
    public void unreadableIncidentJournalLeavesUsablePartialShellArchive() throws Exception {
        NavCaptureStore.writeSomeIpTx(context, day, "preserve-me");
        File blocked = blockIncidentFile();
        assertTrue(new File(blocked.getParentFile(), "persistence-uncertain.jsonl").mkdir());
        RuntimeIncidentRecorder.loss("blocked-journal", "cannot persist incident");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!RuntimeIncidentRecorder.persistenceUncertain() && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertTrue(RuntimeIncidentRecorder.persistenceUncertain());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        assertTrue(WazeCaptureDebugWriter.get().directEvent(() -> { entered.countDown(); await(release); }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        LogShareZip.Result result;
        try {
            result = LogShareShellTestSupport.export(context, Collections.singletonList(day));
        } finally {
            release.countDown();
            assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        }
        assertTrue(result.detail, result.ok);
        assertEquals(LogShareZip.CollectionOutcome.PARTIAL, result.outcome);
        try (ZipFile zip = new ZipFile(result.file)) {
            assertNotNull(zip.getEntry(day + "/logs/someip_tx.jsonl"));
            assertNull(zip.getEntry(day + "/diagnostics/" + RuntimeIncidentRecorder.FILE_NAME));
            JSONObject status = status(zip);
            assertEquals("INCOMPLETE", status.getString("recordingStatus"));
            assertFalse(status.getBoolean("writerCheckpointReady"));
            assertTrue(status.getBoolean("incidentPersistenceUncertain"));
            org.json.JSONArray omitted = status.getJSONArray("omittedOrTruncatedFiles");
            java.util.List<String> omissions = new java.util.ArrayList<>();
            for (int i = 0; i < omitted.length(); i++) {
                omissions.add(omitted.getString(i));
            }
            Collections.sort(omissions);
            java.util.List<String> expectedOmissions = java.util.Arrays.asList(
                    day + "/diagnostics/" + RuntimeIncidentRecorder.FILE_NAME + ": not a regular file",
                    day + "/diagnostics/persistence-uncertain.jsonl: not a regular file");
            Collections.sort(expectedOmissions);
            assertEquals(expectedOmissions, omissions);
            assertNotNull(zip.getEntry(day + "/diagnostics/INCOMPLETE-RECORDING.txt"));
        }
    }

    @Test @Config(shadows = LogShareShellTestSupport.class)
    public void busyAndSupersededTransferSnapshotsStaySeparateFromLogcatCompleteness() throws Exception {
        NavCaptureStore.writeSomeIpTx(context, day, "preserve-me");
        File diagnostics = new File(NavigationLogStorage.logsDir(context, day),
                "logcat/system_transfer/diagnostics/denza-transfer");
        assertTrue(diagnostics.mkdirs());
        File busy = new File(diagnostics, "busy/status.json");
        File superseded = new File(diagnostics, "superseded/status.json");
        assertTrue(busy.getParentFile().mkdirs());
        assertTrue(superseded.getParentFile().mkdirs());
        Files.write(busy.toPath(), new JSONObject().put("status", "busy").toString().getBytes(StandardCharsets.UTF_8));
        Files.write(superseded.toPath(), new JSONObject().put("status", "superseded").toString().getBytes(StandardCharsets.UTF_8));

        File logs = NavigationLogStorage.logsDir(context, day);
        Files.write(new File(logs, "logcat.log.part").toPath(), "active logcat\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(logs, "unrelated.part").toPath(), "unfinished file".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(logs, "pending.tmp").toPath(), "temporary file".getBytes(StandardCharsets.UTF_8));

        LogShareZip.Result result = LogShareShellTestSupport.export(context,
                Collections.singletonList(day), () -> {
                    try {
                        Files.write(busy.toPath(), "{\"status\":\"ready\"}".getBytes(StandardCharsets.UTF_8));
                        Files.write(superseded.toPath(), "{\"status\":\"ready\"}".getBytes(StandardCharsets.UTF_8));
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
        assertTrue(result.detail, result.ok);
        assertEquals(LogShareZip.CollectionOutcome.FULL, result.outcome);
        try (ZipFile zip = new ZipFile(result.file)) {
            JSONObject status = status(zip);
            JSONObject transfer = status.getJSONObject("transferDiagnostics");
            assertEquals("PARTIAL", transfer.getString("outcome"));
            assertTrue(transfer.getBoolean("separateFromLogcatCompleteness"));
            assertEquals("snapshot_ready", status.getString("recordingStatus"));
            assertNull(zip.getEntry(day + "/diagnostics/INCOMPLETE-RECORDING.txt"));
            assertNotNull(zip.getEntry(day + "/diagnostics/INCOMPLETE-TRANSFER-DIAGNOSTICS.txt"));
            assertNull(zip.getEntry(day + "/logs/unrelated.part"));
            assertNull(zip.getEntry(day + "/logs/pending.tmp"));
            assertEquals("active logcat\n", new String(zip.getInputStream(
                    zip.getEntry(day + "/logs/logcat.log.part")).readAllBytes(), StandardCharsets.UTF_8));
            String prefix = day + "/logs/logcat/system_transfer/diagnostics/denza-transfer/";
            assertEquals("busy", new JSONObject(new String(zip.getInputStream(
                    zip.getEntry(prefix + "busy/status.json")).readAllBytes(), StandardCharsets.UTF_8))
                    .getString("status"));
            assertEquals("superseded", new JSONObject(new String(zip.getInputStream(
                    zip.getEntry(prefix + "superseded/status.json")).readAllBytes(), StandardCharsets.UTF_8))
                    .getString("status"));
        }
    }

    @Test public void retentionDayLookupDoesNotWaitForRecorderMonitorUnderTopologyLock() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> observedDay = new AtomicReference<>();
        Thread getter = new Thread(() -> {
            observedDay.set(LogcatRecorder.retentionActiveStartDay());
            finished.countDown();
        }, "recorder-day-lock-regression");
        getter.setDaemon(true);
        boolean returnedWhileLocksHeld;
        NavigationLogStorage.lockTopologyWrite();
        try {
            synchronized (LogcatRecorder.class) {
                getter.start();
                returnedWhileLocksHeld = finished.await(2, TimeUnit.SECONDS);
            }
        } finally {
            NavigationLogStorage.unlockTopologyWrite();
        }
        getter.join(2_000);
        assertTrue("retention must read the published capture day without the recorder monitor",
                returnedWhileLocksHeld);
        assertFalse("lookup worker should complete after the protected snapshot",
                getter.isAlive());
        assertNotNull(observedDay.get());
    }

    @Test public void failedWatchDoesNotStopLaterIncidentRecording() throws Exception {
        java.util.concurrent.atomic.AtomicInteger failedPolls = new java.util.concurrent.atomic.AtomicInteger();
        RuntimeIncidentRecorder.watch(new RuntimeIncidentRecorder.Watch("broken-watch", 1L) {
            @Override boolean pending() { return true; }
            @Override long token() { return 1L; }
            @Override long since() {
                failedPolls.incrementAndGet();
                throw new IllegalStateException("sample unavailable");
            }
            @Override JSONObject details() { return new JSONObject(); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (failedPolls.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("a failed sample must be polled again without registering another watch",
                failedPolls.get() >= 2);
        RuntimeIncidentRecorder.watch(new RuntimeIncidentRecorder.Watch("healthy-watch", 1L) {
            @Override boolean pending() { return true; }
            @Override long token() { return 2L; }
            @Override long since() { return 0L; }
            @Override JSONObject details() { return new JSONObject(); }
        });
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
        awaitHistory("stall");
        assertTrue(incidentText().contains("\"source\":\"healthy-watch\""));
    }

    @Test public void retentionProtectsCurrentAndRecordingDaysAndDeletesCompletedEvidence() throws Exception {
        String oldDay = "20000101";
        File logFile = new File(NavigationLogStorage.logsDir(context, oldDay), "retired.txt");
        Files.write(logFile.toPath(), "retired".getBytes(StandardCharsets.UTF_8));
        File incidentDay = new File(new File(context.getFilesDir(), "runtime-incidents"), oldDay);
        assertTrue(incidentDay.mkdirs());
        File incident = new File(incidentDay, RuntimeIncidentRecorder.FILE_NAME);
        Files.write(incident.toPath(), "{\"event\":\"loss\"}\n".getBytes(StandardCharsets.UTF_8));
        File currentLog = new File(NavigationLogStorage.logsDir(context, day), "active.txt");
        Files.write(currentLog.toPath(), "active".getBytes(StandardCharsets.UTF_8));
        File currentIncident = new File(new File(new File(context.getFilesDir(), "runtime-incidents"), day),
                RuntimeIncidentRecorder.FILE_NAME);
        currentIncident.getParentFile().mkdirs();
        Files.write(currentIncident.toPath(), "{\"event\":\"loss\"}\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(NavigationLogStorage.retireStorageDay(context, day).ok);
        assertTrue(currentLog.isFile());
        assertTrue(currentIncident.isFile());

        java.lang.reflect.Field activeStartDay = LogcatRecorder.class.getDeclaredField("activeStartDay");
        activeStartDay.setAccessible(true);
        String previous = (String) activeStartDay.get(null);
        activeStartDay.set(null, oldDay);
        try {
            assertFalse(NavigationLogStorage.retireStorageDay(context, oldDay).ok);
            assertTrue(logFile.isFile());
            assertTrue(incident.isFile());
        } finally { activeStartDay.set(null, previous); }

        NavigationLogStorage.DayRetirement retirement = NavigationLogStorage.retireStorageDay(context, oldDay);
        assertTrue(retirement.detail, retirement.ok);
        CountDownLatch deleted = new CountDownLatch(1);
        assertTrue(NavigationLogStorage.deleteRetiredStorageDayAsync(retirement, deleted::countDown));
        assertTrue(deleted.await(4, TimeUnit.SECONDS));
        assertFalse(logFile.exists());
        assertFalse(incidentDay.exists());
        assertTrue(currentLog.isFile());
        assertTrue(currentIncident.isFile());
    }

    private static JSONObject status(ZipFile zip) throws Exception {
        assertNull("recording status must not remain at archive root", zip.getEntry("recording-status.json"));
        assertNull(zip.getEntry("diagnostics/recording-status.json"));
        assertTrue("all log archive files must be under a date", Collections.list(zip.entries()).stream()
                .allMatch(item -> item.getName().matches("[0-9]{8}/.+")));
        var entry = zip.getEntry(NavCaptureStore.todayDir() + "/diagnostics/recording-status.json");
        assertNotNull("recording status must be in diagnostics", entry);
        return new JSONObject(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private static Thread daemon(Runnable work, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> { try { work.run(); } catch (Throwable t) { failure.set(t); } });
        thread.setDaemon(true); thread.start(); return thread;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
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
