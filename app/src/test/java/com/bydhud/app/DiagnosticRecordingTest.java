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
        context = new IsolatedStorageContext(RuntimeEnvironment.getApplication(),
                temporaryFolder.newFolder("storage"));
        var reset = NavigationLogStorage.class.getDeclaredMethod("invalidatePublicRootCache");
        reset.setAccessible(true);
        reset.invoke(null);
        day = NavCaptureStore.todayDir();
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
