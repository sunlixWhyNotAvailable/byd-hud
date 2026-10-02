package com.bydhud.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DiagnosticRecordingTest {
    private Context context;
    private String day;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        var reset = NavigationLogStorage.class.getDeclaredMethod("invalidatePublicRootCache");
        reset.setAccessible(true);
        reset.invoke(null);
        day = NavCaptureStore.todayDir(System.currentTimeMillis());
    }

    @Test public void rotationAndConcurrentImageCompleteWithoutLosingJournal() throws Exception {
        File dir = NavigationLogStorage.logsDir(context, day);
        File log = new File(dir, "someip_tx.jsonl");
        String payload = "x".repeat((int) NavCaptureStore.MAX_LOG_BYTES + 1);
        CountDownLatch readHeld = new CountDownLatch(1), saveImage = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread image = daemon(() -> NavigationLogStorage.withReadLock(() -> {
            readHeld.countDown();
            await(saveImage);
            if (NavCaptureStore.writeDirectArtifactIfAbsent(dir, "map", new byte[]{1,2,3}).isEmpty())
                throw new AssertionError("image missing");
        }), failure);
        assertTrue(readHeld.await(2, TimeUnit.SECONDS));
        Thread journal = daemon(() -> NavCaptureStore.writeSomeIpTx(context, day, payload), failure);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (log.length() <= NavCaptureStore.MAX_LOG_BYTES && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue("journal reached rollover", log.length() > NavCaptureStore.MAX_LOG_BYTES);
        } finally {
            saveImage.countDown();
        }
        image.join(3000);
        journal.join(3000);
        assertFalse("image blocked behind journal monitor", image.isAlive());
        assertFalse("rotation blocked behind image reader", journal.isAlive());
        assertNull(failure.get());
        assertEquals(payload + "\n", read(new File(dir, "someip_tx.jsonl.1")));
        NavCaptureStore.writeSomeIpTx(context, day, "after-rollover");
        assertEquals("after-rollover\n", read(log));
    }

    @Test public void stalledWriterExportsExistingFilesAndIndependentHealth() throws Exception {
        NavCaptureStore.writeSomeIpTx(context, day, "before-stall");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        WazeCaptureDebugWriter writer = WazeCaptureDebugWriter.get();
        writer.someIpTx(() -> { entered.countDown(); await(release); });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            long start = System.nanoTime();
            LogShareZip.Result result = LogShareZip.create(context, Collections.singletonList(day));
            assertTrue(result.detail, result.ok);
            assertTrue("export bounded", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(6));
            try (ZipFile zip = new ZipFile(result.file)) {
                JSONObject status = status(zip);
                assertEquals("INCOMPLETE", status.getString("recordingStatus"));
                assertFalse(status.getBoolean("writerCheckpointReady"));
                assertTrue(status.getBoolean("storageSnapshotReady"));
                assertEquals("someip_tx", status.getJSONObject("journal").getString("currentTask"));
                assertTrue(status.getJSONObject("journal").getInt("pendingTasks") > 0);
                assertNotNull(zip.getEntry(day + "/diagnostics/INCOMPLETE-RECORDING.txt"));
                assertTrue(new String(zip.getInputStream(zip.getEntry(day + "/diagnostics/INCOMPLETE-RECORDING.txt"))
                        .readAllBytes(), StandardCharsets.UTF_8).contains("See " + day + "/diagnostics/recording-status.json"));
                assertTrue(Collections.list(zip.entries()).stream().anyMatch(e -> e.getName().endsWith("someip_tx.jsonl")));
            }
        } finally { release.countDown(); assertTrue(writer.awaitCheckpoint(2000)); }
    }

    @Test public void busyStorageExportsDiagnosticStatusWithoutWaitingForever() throws Exception {
        NavCaptureStore.writeSomeIpTx(context, day, "preserve-me");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread reader = daemon(() -> NavigationLogStorage.withReadLock(() -> {
            entered.countDown(); await(release);
        }), new AtomicReference<>());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            long started = System.nanoTime();
            LogShareZip.SelectionSummary summary = LogShareZip.summarize(context, Collections.singletonList(day));
            assertTrue(summary.detail, summary.ok);
            assertTrue(summary.detail.contains("diagnostic status only"));
            LogShareZip.Result result = LogShareZip.create(context, Collections.singletonList(day));
            assertTrue(result.detail, result.ok);
            assertTrue("summary and export bounded", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(7));
            try (ZipFile zip = new ZipFile(result.file)) {
                assertFalse(status(zip).getBoolean("storageSnapshotReady"));
                assertEquals(3, zip.size());
                assertNotNull(zip.getEntry(day + "/diagnostics/navigator-patch-reports.json"));
                assertNotNull(zip.getEntry(day + "/diagnostics/INCOMPLETE-RECORDING.txt"));
            }
        } finally { release.countDown(); reader.join(2000); }
        assertEquals("preserve-me\n", read(new File(NavigationLogStorage.logsDir(context, day), "someip_tx.jsonl")));
    }

    @Test public void ioFailureIsReportedAndLaterSuccessfulWriteHasTimestamp() throws Exception {
        File invalid = new File(context.getCacheDir(), "not-a-directory");
        Files.write(invalid.toPath(), "file".getBytes(StandardCharsets.UTF_8));
        WazeCaptureDebugWriter writer = WazeCaptureDebugWriter.get();
        writer.appendDirectLine(invalid, "events.jsonl", "cannot-write");
        writer.appendDirectLine(context.getCacheDir(), "health-check.jsonl", "written");
        assertTrue(writer.awaitCheckpoint(2000));
        JSONObject health = WazeCaptureDebugWriter.healthSnapshot();
        assertTrue(health.getBoolean("lossObserved"));
        assertTrue(health.getJSONObject("journal").getInt("failures") > 0);
        assertFalse(health.getJSONObject("journal").getString("lastError").isEmpty());
        assertTrue(health.getJSONObject("journal").getLong("lastSuccessfulWriteAtWallMs") > 0);
    }

    @Test
    @Config(sdk = 29, application = Application.class)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void croppedMapWriteFailureMarksArchiveIncomplete() throws Exception {
        HudPrefs.setDetailedDebugArtifactsEnabled(context, true);
        File invalid = new File(NavigationLogStorage.logsDir(context, day), "map-frames");
        Files.write(invalid.toPath(), new byte[]{1});
        Class<?> selectionType = Class.forName("com.bydhud.app.NavigatorMapCapture$ProfileSelection");
        java.lang.reflect.Constructor<?> constructor = selectionType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object selection = constructor.newInstance(null, 0L, 0L);
        java.lang.reflect.Method save = java.util.Arrays.stream(NavigatorMapCapture.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("saveFrameArtifacts")).findFirst().get();
        save.setAccessible(true);
        save.invoke(null, context, NavigatorMapCapture.MAPS_PACKAGE, "failed-write", 1L,
                new byte[]{1,2,3}, HudMapProfile.Source.GOOGLE_MAPS, selection);
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        LogShareZip.Result result = LogShareZip.create(context, Collections.singletonList(day));
        assertTrue(result.detail, result.ok);
        try (ZipFile zip = new ZipFile(result.file)) {
            JSONObject status = status(zip);
            assertTrue(status.getBoolean("lossObserved"));
            assertTrue(status.getJSONObject("mapImages").getInt("failures") > 0);
            assertTrue(status.getJSONObject("mapImages").getString("lastError").contains("artifact directory"));
            assertNotNull(zip.getEntry(day + "/diagnostics/INCOMPLETE-RECORDING.txt"));
        }
    }

    @Test public void multipleDaysShareOneRecordingStatusUnderExportDate() throws Exception {
        String earlierDay = "20200101";
        NavCaptureStore.writeSomeIpTx(context, earlierDay, "earlier");
        NavCaptureStore.writeSomeIpTx(context, day, "current");
        LogShareZip.Result result = LogShareZip.create(context, java.util.Arrays.asList(earlierDay, day));
        assertTrue(result.detail, result.ok);
        try (ZipFile zip = new ZipFile(result.file)) {
            JSONObject status = status(zip);
            assertEquals(2, status.getJSONArray("selectedDays").length());
            assertEquals(1L, Collections.list(zip.entries()).stream()
                    .filter(entry -> entry.getName().endsWith("recording-status.json")).count());
            assertTrue(Collections.list(zip.entries()).stream()
                    .anyMatch(entry -> entry.getName().endsWith(earlierDay + "/logs/someip_tx.jsonl")));
            assertTrue(Collections.list(zip.entries()).stream()
                    .anyMatch(entry -> entry.getName().endsWith(day + "/logs/someip_tx.jsonl")));
        }
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
}
