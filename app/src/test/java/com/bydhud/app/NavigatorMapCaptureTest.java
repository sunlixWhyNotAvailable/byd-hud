package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.CountDownLatch;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
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
        installPackage(NavigatorMapCapture.WAZE_PACKAGE, WAZE_UID);
        installPackage(NavigatorMapCapture.MAPS_PACKAGE, MAPS_UID);
    }

    @After
    public void tearDown() {
        NavigatorMapCapture.stop("test_cleanup");
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
        assertTrue(NavigatorMapCapture.providerCall(context, "result", result, MAPS_UID)
                .getBoolean("accepted"));
        Handler receiver = ReflectionHelpers.getStaticField(NavigatorMapCapture.class, "worker");
        CountDownLatch received = new CountDownLatch(1);
        receiver.post(received::countDown);
        assertTrue(received.await(5L, TimeUnit.SECONDS));
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(5000L));
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
                Bitmap.createBitmap(321, 1, Bitmap.Config.ARGB_8888));
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
