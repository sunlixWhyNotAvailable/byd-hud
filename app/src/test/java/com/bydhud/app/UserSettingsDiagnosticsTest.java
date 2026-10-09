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

    @Test public void capturesEffectiveDefaultsWithoutWritingPreferencesOrPrivateState() throws Exception {
        context.getSharedPreferences("byd_hud_prefs", 0).edit()
                .putString("unrelated_private_token", "must-not-export").commit();
        JSONObject settings = UserSettingsDiagnostics.snapshot(context);
        JSONObject output = settings.getJSONObject("output");
        assertTrue(output.getBoolean("autoStart"));
        assertEquals(1, output.getJSONObject("mapLayout").getInt("updateRateHz"));
        assertEquals(3, output.getJSONArray("effectiveMapImageProfiles").length());
        assertFalse(output.getJSONArray("effectiveMapImageProfiles").getJSONObject(0).getBoolean("saved"));
        assertTrue(settings.getJSONObject("updates").getBoolean("autoCheck"));
        assertFalse(settings.getJSONObject("logging").getBoolean("detailedArtifacts"));
        assertFalse(settings.toString().contains("must-not-export"));
        assertEquals(1, context.getSharedPreferences("byd_hud_prefs", 0).getAll().size());
        for (String name : UserSettingsDiagnostics.PREFERENCE_FILES) {
            if (!name.equals("byd_hud_prefs")) assertTrue(name, context.getSharedPreferences(name, 0).getAll().isEmpty());
        }
    }

    @Test public void preservesMapAndTransferProfilesAndSelection() throws Exception {
        HudMapProfiles.save(context, null, new HudMapProfile(HudMapProfile.Source.WAZE_SURFACE, -3.5f, -80.2f, 35.6f));
        context.getSharedPreferences("bydhud_steering_transfer", 0).edit().putString("profiles_v2",
                "[{\"id\":\"star\",\"keyCode\":305,\"pressMode\":\"double\",\"packageName\":\"com.waze\",\"windowProfile\":\"full\"}]").commit();
        context.getSharedPreferences("byd_hud_nav_capture_prefs", 0).edit()
                .putString("hud_package", "com.waze")
                .putStringSet("log_only_packages", Collections.singleton("com.example.navigator")).commit();
        JSONObject snapshot = UserSettingsDiagnostics.snapshot(context).getJSONObject("output");
        JSONObject profile = snapshot.getJSONArray("mapImageProfiles").getJSONObject(0);
        assertEquals(-3.5, profile.getDouble("x"), 0.001);
        assertEquals(-80.2, profile.getDouble("y"), 0.001);
        assertEquals(35.6, profile.getDouble("scale"), 0.001);
        assertEquals("double", snapshot.getJSONArray("steeringTransferProfiles").getJSONObject(0).getString("pressMode"));
        assertEquals("com.waze", snapshot.getString("selectedHudPackage"));
        assertEquals("com.example.navigator", snapshot.getJSONArray("logOnlyPackages").getString(0));
        HudMapProfiles.delete(context, HudMapProfile.Source.WAZE_SURFACE);
        assertEquals(1, snapshot.getJSONArray("mapImageProfiles").length());
        assertEquals(0, UserSettingsDiagnostics.snapshot(context).getJSONObject("output").getJSONArray("mapImageProfiles").length());
    }

    @Test public void queuedWritesKeepEachObservedChangeAndNormalExportIncludesHistory() throws Exception {
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
        LogShareZip.Result result = LogShareZip.create(context, Collections.singletonList(day));
        assertTrue(result.detail, result.ok);
        try (ZipFile zip = new ZipFile(result.file)) {
            var entry = zip.getEntry(day + "/logs/" + UserSettingsDiagnostics.FILE_NAME);
            assertNotNull(entry);
            assertEquals(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
                    new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test public void ignoresRuntimeOnlyChangesAndRecordsFreshBaselineAfterRestart() throws Exception {
        UserSettingsDiagnostics.start(context);
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        JSONObject before = new JSONObject(Files.readAllLines(file.toPath()).get(0));
        context.getSharedPreferences("byd_hud_prefs", 0).edit().putBoolean("runtime_service_running", true).commit();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        assertEquals(1, Files.readAllLines(file.toPath()).size());
        UserSettingsDiagnostics.resetForTests();
        UserSettingsDiagnostics.start(context);
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        JSONObject after = new JSONObject(Files.readAllLines(file.toPath()).get(1));
        assertNotEquals(before.getString("processSession"), after.getString("processSession"));
        assertEquals(before.getJSONObject("settings").toString(), after.getJSONObject("settings").toString());
    }
}
