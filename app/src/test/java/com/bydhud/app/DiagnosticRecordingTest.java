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

    @Test public void actualLossSurvivesFreshWriterAndBothExportPathsUseFrozenHistory() throws Exception {
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

        LogShareZip.Result result = LogShareZip.create(context, Collections.singletonList(day));
        assertTrue(result.detail, result.ok);
        assertEquals(LogShareZip.CollectionOutcome.PARTIAL, result.outcome);
        try (ZipFile zip = new ZipFile(result.file)) {
            assertNotNull(zip.getEntry(day + "/diagnostics/" + RuntimeIncidentRecorder.FILE_NAME));
            assertTrue(status(zip).getJSONObject("incidentHistory").getBoolean("lossObserved"));
        }
        File job = temporaryFolder.newFolder("shell-job");
        org.json.JSONArray sources = new org.json.JSONArray();
        LogShareZip.freezeIncidentSources(context, Collections.singletonList(day), job, sources);
        File frozen = new File(sources.getJSONObject(0).getString("path"));
        assertTrue(frozen.getCanonicalPath().startsWith(job.getCanonicalPath() + File.separator));
        assertTrue(read(frozen).contains("disk write failed"));
        Files.delete(new File(new File(new File(context.getFilesDir(), "runtime-incidents"), day),
                RuntimeIncidentRecorder.FILE_NAME).toPath());
        assertTrue("shell metadata must not depend on the live private source", read(frozen).contains("disk write failed"));
    }

    @Test public void failedStallAppendRetriesAndRemainsUncertainAfterRecoveryAndRestart() throws Exception {
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
        LogShareZip.Result result = LogShareZip.create(context, Collections.singletonList(day));
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
        // A completed worker reply isolates real request preparation from ADB/archiving.
        ShellWorkFiles.write(new File(job, "result.json"), new JSONObject().put("ok", true)
                .put("outcome", "PARTIAL").put("file", new File(job, "unused.zip").getPath()));
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
        assertTrue(result.get().detail, result.get().ok);
        JSONObject input = ShellWorkFiles.read(new File(job, "request.json")).getJSONObject("input");
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
        for (byte[] file : RuntimeIncidentRecorder.snapshot(context, Collections.singletonList(day)).values()) {
            text.append(new String(file, StandardCharsets.UTF_8));
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
            LogShareZip.Result result = LogShareZip.create(context, Collections.singletonList(day));
            assertFalse(result.detail, result.ok);
            assertEquals(LogShareZip.CollectionOutcome.FAILED, result.outcome);
            assertNull(result.file);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(8));
        } finally { release.countDown(); reader.join(2000); }
        assertEquals("preserve-me\n", read(new File(NavigationLogStorage.logsDir(context, day), "someip_tx.jsonl")));
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
