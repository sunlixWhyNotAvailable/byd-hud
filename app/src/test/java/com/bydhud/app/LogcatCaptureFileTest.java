package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = android.app.Application.class)
public final class LogcatCaptureFileTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void aNewWriterNeverOverwritesAnInterruptedCapture() throws Exception {
        File directory = temporaryFolder.newFolder("interrupted");
        LogcatCaptureFile original = new LogcatCaptureFile(directory);
        byte[] prefix = utf8("partial recording\n");
        original.append(prefix);
        LogcatCaptureFile replacement = new LogcatCaptureFile(directory);

        assertThrows(IOException.class, () -> replacement.append(utf8("replacement")));
        assertArrayEquals(prefix, Files.readAllBytes(original.file().toPath()));
        assertEquals(prefix.length, original.bytes());
        original.finish();
        assertArrayEquals(new String[]{"logcat.log"}, directory.list());
    }

    @Test
    @org.robolectric.annotation.Config(sdk = 29, shadows = {FullDisk.class, CaptureEndpoint.class})
    public void terminalDiskFailuresPreserveEvidenceAndDoNotRestartCapture() throws Exception {
        long reserveWithWriteInterval = 32L * 1024 * 1024 + 256L * 1024;
        assertTrue(ShellLogcatCapture.shouldStopForAvailableBytes(reserveWithWriteInterval));
        assertFalse(ShellLogcatCapture.shouldStopForAvailableBytes(reserveWithWriteInterval + 1));
        assertFalse(ShellLogcatCapture.shouldStopForAvailableBytes(-1));
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, 42);
        try {
            for (String reason : new String[]{"low_space", "write_failure", "shell writer unavailable"}) {
                File directory = temporaryFolder.newFolder();
                String id = "capture-" + reason.replace(' ', '-');
                File part = new File(directory, "logcat.log.part");
                Files.write(part.toPath(), utf8("preserved-prefix\n"));
                if (reason.equals("low_space")) {
                    try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(part,
                            android.os.ParcelFileDescriptor.MODE_WRITE_ONLY | android.os.ParcelFileDescriptor.MODE_APPEND)) {
                        CaptureEndpoint.state = new ShellLogcatCapture().start(id, "", fd);
                    }
                    assertFalse(CaptureEndpoint.state.getBoolean("running"));
                    assertTrue(new String(Files.readAllBytes(part.toPath()), StandardCharsets.UTF_8).contains("BYD-HUD-CAPTURE-INCOMPLETE reason=low_space"));
                } else {
                    CaptureEndpoint.state = new android.os.Bundle();
                    CaptureEndpoint.state.putString("id", id);
                    CaptureEndpoint.state.putString("error", reason + ": writer stopped");
                }
                CaptureEndpoint.starts = 0;
                File manifest = new File(directory, "manifest.json");
                Files.write(manifest.toPath(), utf8(new org.json.JSONObject().put("status", "recording").toString()));
                assertTrue(ShellRuntimeSession.arm(context));
                assertTrue("capture recovery must be admitted", ShellRuntimeSession.mayRestore(context));
                ShellRuntimeSession.prefs(context).edit().putString("capture_id", id)
                        .putString("capture_day", NavCaptureStore.todayDir())
                        .putString("capture_directory", directory.getPath()).commit();
                org.robolectric.util.ReflectionHelpers.callStaticMethod(LogcatRecorder.class, "recoverShellCaptureNow",
                        org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.content.Context.class, context));
                java.util.concurrent.ExecutorService worker = org.robolectric.util.ReflectionHelpers.getStaticField(
                        LogcatRecorder.class, "WORKER");
                worker.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!reason.equals("shell writer unavailable")) {
                    assertEquals("terminal errors must never reopen a writer", 0, CaptureEndpoint.starts);
                    org.json.JSONObject saved = new org.json.JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
                    assertEquals(reason, "failed", saved.getString("status"));
                    assertFalse(saved.getBoolean("streamComplete"));
                    assertTrue(saved.getString("failure").contains(reason));
                    assertTrue(new String(Files.readAllBytes(new File(directory, "logcat.log").toPath()), StandardCharsets.UTF_8).startsWith("preserved-prefix"));
                } else {
                    assertEquals("ordinary writer loss should still recover", 1, CaptureEndpoint.starts);
                    LogcatRecorder.Session session = org.robolectric.util.ReflectionHelpers.getStaticField(LogcatRecorder.class, "activeSession");
                    assertTrue(session.readerStarted);
                    session.logFile.finish();
                    org.robolectric.util.ReflectionHelpers.setStaticField(LogcatRecorder.class, "activeSession", null);
                }
                ShellRuntimeSession.prefs(context).edit().clear().commit();
            }
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(LogcatRecorder.class, "activeStartDay", "");
            ShellRuntimeSession.prefs(context).edit().clear().commit();
        }
    }

    @org.robolectric.annotation.Implements(android.system.Os.class)
    public static class FullDisk {
        @org.robolectric.annotation.Implementation
        protected static android.system.StructStatVfs fstatvfs(java.io.FileDescriptor fd) {
            return new android.system.StructStatVfs(4096, 4096, 1000, 0, 0, 1000, 0, 0, 1, 0, 255);
        }
        @org.robolectric.annotation.Implementation
        protected static void fsync(java.io.FileDescriptor fd) { }
    }

    @org.robolectric.annotation.Implements(InstrumentProxyManager.class)
    public static class CaptureEndpoint {
        static android.os.Bundle state;
        static int starts;
        @org.robolectric.annotation.Implementation
        protected android.os.Bundle systemCaptureState(String id, boolean stop) { return state; }
        @org.robolectric.annotation.Implementation
        protected android.os.Bundle startSystemCapture(String id, String cursor, android.os.ParcelFileDescriptor fd) {
            starts++;
            state.putBoolean("running", true);
            state.putString("error", "");
            return state;
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
