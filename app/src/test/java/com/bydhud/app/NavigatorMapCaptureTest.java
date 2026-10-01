package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.CountDownLatch;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NavigatorMapCaptureTest {
    private static final int WAZE_UID = 23001;
    private static final int MAPS_UID = 23002;
    private static final int FORGED_UID = 23003;

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        NavigatorMapCapture.stop("test_reset");
        NavigatorMapCapture.setActiveSourceMode(null);
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit()
                .remove("map_profiles")
                .remove("map_profiles_revision")
                .apply();
        installPackage(NavigatorMapCapture.WAZE_PACKAGE, WAZE_UID);
        installPackage(NavigatorMapCapture.MAPS_PACKAGE, MAPS_UID);
    }

    @After
    public void tearDown() {
        NavigatorMapCapture.stop("test_cleanup");
        NavigatorMapCapture.setActiveSourceMode(null);
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit()
                .remove("map_profiles")
                .remove("map_profiles_revision")
                .apply();
        Shadows.shadowOf(context.getPackageManager()).removePackage(NavigatorMapCapture.WAZE_PACKAGE);
        Shadows.shadowOf(context.getPackageManager()).removePackage(NavigatorMapCapture.MAPS_PACKAGE);
    }

    @Test
    public void providerChecksUidSelectionSessionCadenceAndPostStopJournal() {
        NavigatorMapCapture.activate(context, NavigatorMapCapture.WAZE_PACKAGE, 41L, null);
        Bundle poll = poll(NavigatorMapCapture.WAZE_PACKAGE, "", false);
        assertTrue(poll.getBoolean("active"));
        String session = poll.getString("session");
        assertNotNull(session);
        long id = poll.getLong("id");
        assertTrue(id > 0L);

        Bundle forged = NavigatorMapCapture.providerCall(
                context, "poll", request(NavigatorMapCapture.WAZE_PACKAGE, "", false), FORGED_UID);
        assertFalse(forged.getBoolean("active"));

        Bundle otherOwner = NavigatorMapCapture.providerCall(
                context, "poll", request(NavigatorMapCapture.MAPS_PACKAGE, "", false), MAPS_UID);
        assertFalse(otherOwner.getBoolean("active"));

        Bundle whilePending = poll(NavigatorMapCapture.WAZE_PACKAGE, "", false);
        assertTrue(whilePending.getBoolean("active"));
        assertEquals(0L, whilePending.getLong("id"));

        Bundle result = new Bundle();
        result.putString("package", NavigatorMapCapture.WAZE_PACKAGE);
        result.putString("session", session);
        result.putLong("id", id);
        result.putString("status", "no_gl_frame");
        assertTrue(NavigatorMapCapture.providerCall(context, "result", result, WAZE_UID)
                .getBoolean("accepted"));
        assertEquals(0L, poll(NavigatorMapCapture.WAZE_PACKAGE, "", false).getLong("id"));

        SystemClock.sleep(1010L);
        Bundle nextRequest = poll(NavigatorMapCapture.WAZE_PACKAGE, "", false);
        long nextId = nextRequest.getLong("id");
        assertTrue("one-second request floor has elapsed", nextId > id);
        String currentSession = nextRequest.getString("session");

        NavigatorMapCapture.stop("test_stop");
        Bundle late = new Bundle();
        late.putString("package", NavigatorMapCapture.WAZE_PACKAGE);
        late.putString("session", currentSession);
        late.putLong("id", nextId);
        late.putString("status", "no_gl_frame");
        assertFalse(NavigatorMapCapture.providerCall(context, "result", late, WAZE_UID)
                .getBoolean("accepted"));

        Bundle releasePoll = request(NavigatorMapCapture.WAZE_PACKAGE, currentSession, false);
        releasePoll.putString("lifecycleEvents",
                "[{\"sequence\":1,\"event\":\"offscreen_released\",\"owner\":\"map\",\"detail\":\"lease_end\"}]");
        Bundle journal = NavigatorMapCapture.providerCall(
                context, "poll", releasePoll, WAZE_UID);
        assertFalse(journal.getBoolean("active"));
        assertTrue(journal.getBoolean("journalAccepted"));
        assertArrayEquals(new byte[0], NavigatorMapCapture.snapshot().png());
    }

    @Test
    public void postStopJournalAcceptsZeroOriginUntilWindowExpires() {
        NavigatorMapCapture.activate(context, NavigatorMapCapture.WAZE_PACKAGE, 42L, null);
        String session = poll(NavigatorMapCapture.WAZE_PACKAGE, "", false).getString("session");
        NavigatorMapCapture.stop("test_zero_origin");
        ReflectionHelpers.setStaticField(NavigatorMapCapture.class, "stoppedAtElapsedMs", 0L);
        assertTrue(SystemClock.elapsedRealtime() < 10_000L);

        Bundle journal = poll(NavigatorMapCapture.WAZE_PACKAGE, session, false);
        assertFalse(journal.getBoolean("active"));
        assertTrue(journal.getBoolean("journalAccepted"));
        assertFalse(poll(NavigatorMapCapture.WAZE_PACKAGE, "wrong_session", false)
                .getBoolean("journalAccepted"));
        SystemClock.sleep(10_000L);
        assertFalse(poll(NavigatorMapCapture.WAZE_PACKAGE, session, false)
                .getBoolean("journalAccepted"));
    }

    @Test
    public void detailedLogsSaveSourceAndCropEvenWhenEnabledOnAnUnchangedFrame() throws Exception {
        HudPrefs.setDetailedDebugArtifactsEnabled(context, false);
        NavigatorMapCapture.activate(context, NavigatorMapCapture.MAPS_PACKAGE, 53L, null);
        File artifacts = new File(NavCaptureStore.logDir(context), "map-frames");
        submitMapFrame(0xff123456);
        assertFalse(artifacts.exists());

        HudPrefs.setDetailedDebugArtifactsEnabled(context, true);
        submitMapFrame(0xff123456);
        File[] files = artifacts.listFiles();
        assertNotNull(files);
        assertEquals(2, files.length);
        for (File file : files) {
            Bitmap image = BitmapFactory.decodeFile(file.getAbsolutePath());
            assertNotNull(image);
            assertEquals(file.getName().startsWith("map-source-") ? 320 : 300, image.getWidth());
            assertEquals(file.getName().startsWith("map-source-") ? 165 : 180, image.getHeight());
            image.recycle();
        }
        submitMapFrame(0xff123456);
        assertEquals("unchanged pixels reuse the pair", 2, artifacts.listFiles().length);
        HudPrefs.setDetailedDebugArtifactsEnabled(context, false);
        submitMapFrame(0xff654321);
        assertEquals("disabled diagnostics add no images", 2, artifacts.listFiles().length);
    }

    private void submitMapFrame(int color) throws Exception {
        SystemClock.sleep(1010L);
        Bundle poll = poll(NavigatorMapCapture.MAPS_PACKAGE, "", false);
        assertTrue(poll.getLong("id") > 0L);
        Bitmap bitmap = Bitmap.createBitmap(320, 165, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        Bundle result = result(NavigatorMapCapture.MAPS_PACKAGE, poll.getString("session"),
                poll.getLong("id"), bitmap);
        result.putString("source", "com.google.maps.Renderer@1a2b");
        assertTrue(NavigatorMapCapture.providerCall(context, "result", result, MAPS_UID)
                .getBoolean("accepted"));
        drainReceiver();
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(5000L));
    }

    private void submitFrame(String packageName, int ownerUid, String source,
                             String backgroundState, Bitmap bitmap) throws Exception {
        submitFrame(packageName, ownerUid, source, backgroundState, "", bitmap);
    }

    private void submitFrame(String packageName, int ownerUid, String source,
                             String backgroundState, String sourceMode, Bitmap bitmap) throws Exception {
        SystemClock.sleep(1010L);
        Bundle poll = poll(packageName, "", false);
        assertTrue(poll.getLong("id") > 0L);
        Bundle frame = result(packageName, poll.getString("session"), poll.getLong("id"), bitmap);
        frame.putString("source", source);
        frame.putString("backgroundState", backgroundState);
        frame.putString("frameSourceMode", sourceMode);
        assertTrue(NavigatorMapCapture.providerCall(context, "result", frame, ownerUid)
                .getBoolean("accepted"));
        drainReceiver();
    }

    private static Bitmap decode(byte[] png) {
        Bitmap image = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull(image);
        return image;
    }

    private static void drainReceiver() throws InterruptedException {
        Handler receiver = ReflectionHelpers.getStaticField(NavigatorMapCapture.class, "worker");
        CountDownLatch received = new CountDownLatch(1);
        receiver.post(received::countDown);
        assertTrue(received.await(5L, TimeUnit.SECONDS));
    }

    @Test public void fullSourceMemoryKeepsPixelsAndRejectsInvalidDimensions() throws Exception {
        Bitmap original = Bitmap.createBitmap(1500, 900, Bitmap.Config.ARGB_8888);
        original.eraseColor(0xff123456);
        android.os.SharedMemory shared = android.os.SharedMemory.create("test-map", original.getByteCount());
        java.nio.ByteBuffer buffer = shared.mapReadWrite();
        try { original.copyPixelsToBuffer(buffer); }
        finally { android.os.SharedMemory.unmap(buffer); }
        Bundle data = new Bundle();
        data.putParcelable("sourceMemory", shared);
        data.putInt("bitmapWidth", 1500);
        data.putInt("bitmapHeight", 900);
        Bitmap decoded = ReflectionHelpers.callStaticMethod(NavigatorMapCapture.class, "bitmap",
                ReflectionHelpers.ClassParameter.from(Bundle.class, data));
        try {
            assertNotNull(decoded);
            assertEquals(1500, decoded.getWidth());
            assertEquals(0xff123456, decoded.getPixel(1499, 899));
        } finally { if (decoded != null) decoded.recycle(); original.recycle(); shared.close(); }
        android.os.SharedMemory invalid = android.os.SharedMemory.create("invalid-map", 4);
        data.putParcelable("sourceMemory", invalid);
        data.putInt("bitmapWidth", Integer.MAX_VALUE);
        try {
            assertNull(ReflectionHelpers.callStaticMethod(NavigatorMapCapture.class, "bitmap",
                    ReflectionHelpers.ClassParameter.from(Bundle.class, data)));
        } finally { invalid.close(); }
    }

    @Test public void mapArtifactsDoNotCompeteWithBlockedNavigationWriter() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch imageSaved = new CountDownLatch(1);
        assertTrue(WazeCaptureDebugWriter.get().directEvent(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) assertTrue(WazeCaptureDebugWriter.get().directEvent(() -> {}));
            assertTrue(WazeCaptureDebugWriter.mapFrames().directEvent(imageSaved::countDown));
            assertTrue(imageSaved.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertTrue(WazeCaptureDebugWriter.get().awaitIdle());
    }

    @Test
    public void providerRejectsOversizedBitmapAndAcceptsBoundedFrameAsynchronously() throws Exception {
        AtomicInteger callbackCount = new AtomicInteger();
        CountDownLatch frameChanged = new CountDownLatch(1);
        NavigatorMapCapture.activate(context, NavigatorMapCapture.MAPS_PACKAGE, 52L, () -> {
            if (callbackCount.incrementAndGet() > 1) {
                frameChanged.countDown();
            }
        });
        Bundle firstPoll = poll(NavigatorMapCapture.MAPS_PACKAGE, "", false);
        String session = firstPoll.getString("session");
        long firstId = firstPoll.getLong("id");

        Bundle oversized = result(NavigatorMapCapture.MAPS_PACKAGE, session, firstId,
                Bitmap.createBitmap(1921, 1, Bitmap.Config.ARGB_8888));
        assertTrue(NavigatorMapCapture.providerCall(context, "result", oversized, MAPS_UID)
                .getBoolean("accepted"));
        assertTrue(waitForNoPendingFrame());
        assertEquals(0, NavigatorMapCapture.snapshot().png().length);

        SystemClock.sleep(1010L);
        Bundle nextPoll = poll(NavigatorMapCapture.MAPS_PACKAGE, "", false);
        long nextId = nextPoll.getLong("id");
        assertTrue(nextId > firstId);
        Bundle valid = result(NavigatorMapCapture.MAPS_PACKAGE, session, nextId,
                Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888));
        assertTrue(NavigatorMapCapture.providerCall(context, "result", valid, MAPS_UID)
                .getBoolean("accepted"));

        assertTrue("accepted pixels publish from the receiver worker",
                frameChanged.await(5L, TimeUnit.SECONDS));
        NavigatorMapCapture.Snapshot snapshot = NavigatorMapCapture.snapshot();
        assertEquals(300, BitmapFactory.decodeByteArray(
                snapshot.png(), 0, snapshot.png().length).getWidth());
        assertEquals(180, BitmapFactory.decodeByteArray(
                snapshot.png(), 0, snapshot.png().length).getHeight());
        assertTrue(snapshot.receivedAtElapsedMs() > 0L);
    }

    @Test
    public void calibrationRecropsCoalescedEditsWithoutExtendingFrameExpiry() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        NavigatorMapCapture.activate(context, NavigatorMapCapture.MAPS_PACKAGE, 54L,
                HudMapProfile.Source.GOOGLE_MAPS, callbacks::incrementAndGet);
        Bitmap input = Bitmap.createBitmap(600, 360, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(input);
        canvas.drawColor(Color.GREEN);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        canvas.drawRect(0, 0, 250, 360, paint);
        paint.setColor(Color.BLUE);
        canvas.drawRect(350, 0, 600, 360, paint);
        submitFrame(NavigatorMapCapture.MAPS_PACKAGE, MAPS_UID,
                "com.google.maps.Renderer@1a2b", "", input);

        NavigatorMapCapture.Snapshot initial = NavigatorMapCapture.snapshot();
        assertEquals(HudMapProfile.Source.GOOGLE_MAPS, initial.source());
        Bitmap initialCrop = decode(initial.png());
        try {
            assertEquals(Color.RED, initialCrop.getPixel(0, 90));
        } finally {
            initialCrop.recycle();
        }

        NavigatorMapCapture.setProfileOverride(
                HudMapProfile.defaults(HudMapProfile.Source.GOOGLE_MAPS).withX(-10));
        NavigatorMapCapture.setProfileOverride(
                HudMapProfile.defaults(HudMapProfile.Source.GOOGLE_MAPS).withX(10));
        drainReceiver();
        assertTrue("a changed preview recrop notifies the consumer", callbacks.get() >= 3);
        Bitmap finalCrop = decode(NavigatorMapCapture.snapshot().png());
        try {
            assertEquals(Color.RED, finalCrop.getPixel(0, 90));
            assertEquals(Color.BLUE, finalCrop.getPixel(270, 90));
        } finally {
            finalCrop.recycle();
        }
        Bitmap retained = ReflectionHelpers.getStaticField(NavigatorMapCapture.class, "rawBitmap");
        assertNotNull(retained);
        assertFalse(retained.isRecycled());

        SystemClock.sleep(2600L);
        NavigatorMapCapture.setProfileOverride(
                HudMapProfile.defaults(HudMapProfile.Source.GOOGLE_MAPS).withX(-5));
        drainReceiver();
        int callbacksBeforeExpiry = callbacks.get();
        SystemClock.sleep(1100L);
        assertEquals(0, NavigatorMapCapture.snapshot().png().length);
        assertNull(ReflectionHelpers.getStaticField(NavigatorMapCapture.class, "rawBitmap"));
        assertTrue("expiry notifies the consumer", callbacks.get() > callbacksBeforeExpiry);
        assertTrue("the detached raw image is recycled", retained.isRecycled());
    }

    @Test
    public void unknownWazeModeUsesTheUnmodifiedCenterCropOutsideCalibration() throws Exception {
        NavigatorMapCapture.activate(context, NavigatorMapCapture.WAZE_PACKAGE, 56L, null);
        Bitmap input = Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888);
        input.eraseColor(Color.MAGENTA);
        submitFrame(NavigatorMapCapture.WAZE_PACKAGE, WAZE_UID,
                "com.waze.map.opengl.w@ab12", "waze_resume", input);

        NavigatorMapCapture.Snapshot snapshot = NavigatorMapCapture.snapshot();
        assertNull(snapshot.source());
        Bitmap output = decode(snapshot.png());
        try {
            assertEquals(300, output.getWidth());
            assertEquals(180, output.getHeight());
            assertEquals(Color.MAGENTA, output.getPixel(150, 90));
        } finally {
            output.recycle();
        }
    }

    @Test
    public void firstForegroundWazeFrameUsesKnownRuntimeModeBeforeAnyResume() throws Exception {
        NavigatorMapCapture.setActiveSourceMode(HudMapProfile.Source.WAZE);
        NavigatorMapCapture.activate(context, NavigatorMapCapture.WAZE_PACKAGE, 57L,
                HudMapProfile.Source.WAZE, null);
        submitFrame(NavigatorMapCapture.WAZE_PACKAGE, WAZE_UID,
                "com.waze.map.opengl.w@ab12", "idle",
                Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888));
        assertEquals(HudMapProfile.Source.WAZE, NavigatorMapCapture.snapshot().source());
        assertTrue(NavigatorMapCapture.snapshot().png().length > 0);
    }

    @Test
    public void explicitCalibrationRejectsUnknownAndWrongWazeFrameModes() throws Exception {
        NavigatorMapCapture.activate(context, NavigatorMapCapture.WAZE_PACKAGE, 55L,
                HudMapProfile.Source.WAZE_SURFACE, null);
        submitFrame(NavigatorMapCapture.WAZE_PACKAGE, WAZE_UID,
                "com.waze.map.opengl.w@ab12", "waze_resume",
                Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888));
        assertEquals(0, NavigatorMapCapture.snapshot().png().length);

        NavigatorMapCapture.setActiveSourceMode(HudMapProfile.Source.WAZE);
        submitFrame(NavigatorMapCapture.WAZE_PACKAGE, WAZE_UID,
                "com.waze.map.opengl.w@ab12", "waze_frame_received",
                Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888));
        assertEquals(0, NavigatorMapCapture.snapshot().png().length);

        NavigatorMapCapture.setActiveSourceMode(HudMapProfile.Source.WAZE_SURFACE);
        submitFrame(NavigatorMapCapture.WAZE_PACKAGE, WAZE_UID,
                "com.waze.map.opengl.w@ab12", "waze_resume",
                Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888));
        assertEquals(HudMapProfile.Source.WAZE_SURFACE,
                NavigatorMapCapture.snapshot().source());
    }

    @Test public void rendererModeSurvivesResumeAndOverridesUnrelatedDirectMode() throws Exception {
        for (HudMapProfile.Source expected : new HudMapProfile.Source[]{
                HudMapProfile.Source.WAZE, HudMapProfile.Source.WAZE_SURFACE}) {
            NavigatorMapCapture.activate(context, NavigatorMapCapture.WAZE_PACKAGE, 56L, expected, null);
            NavigatorMapCapture.setActiveSourceMode(null);
            String explicit = expected == HudMapProfile.Source.WAZE ? "waze" : "waze_surface";
            submitFrame(NavigatorMapCapture.WAZE_PACKAGE, WAZE_UID,
                    "com.waze.map.opengl.w@cd34", "waze_resume", explicit,
                    Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888));
            assertEquals(expected, NavigatorMapCapture.snapshot().source());
            long revision = NavigatorMapCapture.snapshot().revision();
            NavigatorMapCapture.setActiveSourceMode(expected);
            for (String wrong : new String[]{"unknown", expected == HudMapProfile.Source.WAZE ? "waze_surface" : "waze"}) {
                submitFrame(NavigatorMapCapture.WAZE_PACKAGE, WAZE_UID,
                        "com.waze.map.opengl.w@ef56", "waze_frame_received", wrong,
                        Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888));
                assertEquals("explicit unknown/wrong renderer is never relabelled", revision,
                        NavigatorMapCapture.snapshot().revision());
                assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("BydHudEventLog").stream()
                        .anyMatch(log -> log.msg.contains("reason=wrong_source")
                                && log.msg.contains("frameSourceMode=" + wrong)));
            }
            NavigatorMapCapture.stop("mode-test-switch");
        }
    }

    private Bundle poll(String packageName, String captureSession, boolean busy) {
        return NavigatorMapCapture.providerCall(
                context, "poll", request(packageName, captureSession, busy), uid(packageName));
    }

    private static Bundle request(String packageName, String captureSession, boolean busy) {
        Bundle request = new Bundle();
        request.putString("package", packageName);
        request.putString("captureSession", captureSession);
        request.putBoolean("busy", busy);
        return request;
    }

    private static Bundle result(String packageName, String session, long id, Bitmap bitmap) {
        Bundle result = new Bundle();
        result.putString("package", packageName);
        result.putString("session", session);
        result.putLong("id", id);
        result.putString("status", "ok");
        result.putParcelable("bitmap", bitmap);
        return result;
    }

    private void installPackage(String packageName, int uid) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        info.applicationInfo.uid = uid;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }

    private static int uid(String packageName) {
        return NavigatorMapCapture.WAZE_PACKAGE.equals(packageName) ? WAZE_UID : MAPS_UID;
    }

    private static boolean waitForNoPendingFrame() {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (NavigatorMapCapture.snapshot().png().length == 0) {
                return true;
            }
            SystemClock.sleep(10L);
        }
        return false;
    }
}
