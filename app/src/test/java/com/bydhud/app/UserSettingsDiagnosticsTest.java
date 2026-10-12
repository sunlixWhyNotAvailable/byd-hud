package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class UserSettingsDiagnosticsTest {
    private Context context;
    private File file;
    private String day;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        UserSettingsDiagnostics.resetForTests();
        for (String name : UserSettingsDiagnostics.PREFERENCE_FILES) {
            context.getSharedPreferences(name, 0).edit().clear().commit();
        }
        var reset = NavigationLogStorage.class.getDeclaredMethod("invalidatePublicRootCache");
        reset.setAccessible(true);
        reset.invoke(null);
        day = NavCaptureStore.todayDir();
        file = new File(NavigationLogStorage.logsDir(context, day), UserSettingsDiagnostics.FILE_NAME);
        if (file.exists()) assertTrue(file.delete());
    }

    @After public void tearDown() {
        UserSettingsDiagnostics.resetForTests();
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
    }

    @Test @Config(shadows = LogShareShellTestSupport.class)
    public void queuedWritesKeepEachObservedChangeAndNormalExportIncludesHistory() throws Exception {
        UserSettingsDiagnostics.start(context);
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        UserSettingsDiagnostics.sessionStarted(context, "waze", 7);
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        WazeCaptureDebugWriter.get().settingsTask(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            context.getSharedPreferences("byd_hud_prefs", 0).edit().putBoolean("output_png", false).commit();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            context.getSharedPreferences("byd_hud_prefs", 0).edit().putBoolean("output_png", true).commit();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        } finally { release.countDown(); }
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        List<String> lines = Files.readAllLines(file.toPath());
        assertEquals(4, lines.size());
        assertEquals("process_start", new JSONObject(lines.get(0)).getString("event"));
        assertEquals("7", new JSONObject(lines.get(1)).getString("sessionGeneration"));
        assertFalse(new JSONObject(lines.get(2)).getJSONObject("settings").getJSONObject("output").getBoolean("png"));
        assertTrue(new JSONObject(lines.get(3)).getJSONObject("settings").getJSONObject("output").getBoolean("png"));
        assertEquals("not_collected", new JSONObject(lines.get(3)).getJSONObject("navigatorSettings").getString("status"));
        LogShareZip.Result result = LogShareShellTestSupport.export(
                context, Collections.singletonList(day));
        assertTrue(result.detail, result.ok);
        try (ZipFile zip = new ZipFile(result.file)) {
            var entry = zip.getEntry(day + "/logs/" + UserSettingsDiagnostics.FILE_NAME);
            assertNotNull(entry);
            assertEquals(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
                    new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

}
