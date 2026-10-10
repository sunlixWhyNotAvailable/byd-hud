package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.car.app.model.DateTimeWithZone;
import androidx.car.app.model.Distance;
import androidx.car.app.navigation.INavigationHost;
import androidx.car.app.navigation.model.Destination;
import androidx.car.app.navigation.model.Maneuver;
import androidx.car.app.navigation.model.Step;
import androidx.car.app.navigation.model.TravelEstimate;
import androidx.car.app.navigation.model.Trip;
import androidx.car.app.serialization.Bundleable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises AndroidX Trip callbacks through the Waze ingress and drain path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WazeIngressBehaviorTest {
    private static final long WAIT_SECONDS = 5L;
    private static final String BARRIER_LOG = "voice assistant capabilities received";

    private WazeDirectChannel channel;
    private RecordingListener listener;
    private WazeDirectChannel.IngressTestHosts ingressHosts;
    private INavigationHost navigationHost;

    @Before
    public void setUp() throws Exception {
        IncidentTestSupport.restartProcessDiagnostics();
        Context context = RuntimeEnvironment.getApplication();
        listener = new RecordingListener();
        channel = new WazeDirectChannel(context, listener);
        useHosts(WazeDirectChannel.Mode.CLUSTER);
    }

    @After
    public void tearDown() {
        if (listener != null) listener.releaseBlockedFrame();
        if (channel != null) channel.clearIngressForTest();
        if (channel != null) channel.shutdown("waze_ingress_test_cleanup");
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .removePackage(WazeRouteLifecycleStore.WAZE_PACKAGE);
        WazeStartAdmission.PROCESS.updateRuntime(
                false, System.currentTimeMillis(), false);
        UserRuntimeSession.PROCESS.shutdown();
    }

    @Test
    public void oldHostCannotWriteIntoFreshConnectionGeneration() throws Exception {
        startRoute(routeTrip("Old session", Maneuver.TYPE_TURN_NORMAL_LEFT,
                650, 75L, 4500L));
        INavigationHost oldHost = navigationHost;

        CountDownLatch stopped = listener.armLog("hard stopped reason=test_session_change");
        channel.hardStop("test_session_change");
        assertTrue("hard stop completes the old session",
                stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));

        useHosts(WazeDirectChannel.Mode.CLUSTER);
        CountDownLatch started = listener.armNavigationStarted();
        navigationHost.navigationStarted();
        assertTrue("fresh connection starts", started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        oldHost.updateTrip(Bundleable.create(routeTrip(
                "Late old session", Maneuver.TYPE_TURN_NORMAL_RIGHT, 200, 22L, 1600L)));
        awaitHandlerBarrier(navigationHost);
        assertEquals("old Binder host cannot publish into the new generation",
                1, listener.frameSnapshot().size());

        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "Fresh session", Maneuver.TYPE_TURN_NORMAL_LEFT, 420, 44L, 3200L)));
        awaitHandlerBarrier(navigationHost);
        List<DirectTbtFrame> frames = listener.frameSnapshot();
        assertEquals(2, frames.size());
        assertEquals("Fresh session", frames.get(1).getRoadText());
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test public void busyChannelKeepsOldestDeadlineAndDeliversLatestTrip() throws Exception {
        startRoute(routeTrip("Initial", Maneuver.TYPE_TURN_NORMAL_LEFT, 600, 75L, 4500L));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ((android.os.Handler) field(channel, "channelHandler")).post(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            navigationHost.updateTrip(Bundleable.create(routeTrip("Older", Maneuver.TYPE_TURN_NORMAL_LEFT, 550, 70, 4000)));
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2100));
            for (int i = 0; i < 20; i++) {
                navigationHost.updateTrip(Bundleable.create(routeTrip("Latest " + i,
                        Maneuver.TYPE_TURN_NORMAL_RIGHT, 300, 40, 2500)));
            }
            JSONObjectRecord incident = awaitIncident();
            assertEquals("ready_queue", incident.details.getString("stage"));
            assertEquals("Trip", incident.details.getString("source"));
            assertTrue(incident.value.getLong("ageMs") >= 2000);
            assertTrue(incident.details.getJSONObject("channelThread").getJSONArray("stack").length() > 0);
        } finally { release.countDown(); }
        awaitHandlerBarrier(navigationHost);
        List<DirectTbtFrame> frames = listener.frameSnapshot();
        assertEquals("Latest 19", frames.get(frames.size() - 1).getRoadText());
        assertEquals("coalescing must not replay the older 20 snapshots", 2, frames.size());
    }

    @Test public void queuedTemplateIsAttributedSeparatelyFromTripNormalization() throws Exception {
        startRoute(routeTrip("Initial", Maneuver.TYPE_TURN_NORMAL_LEFT, 600, 75, 4500));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ((android.os.Handler) field(channel, "channelHandler")).post(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            channel.templateCallbackForTest().onSuccess(null);
            navigationHost.updateTrip(Bundleable.create(routeTrip("After template", Maneuver.TYPE_TURN_NORMAL_RIGHT, 300, 40, 2500)));
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2100));
            JSONObjectRecord incident = awaitIncident();
            assertEquals("template", incident.details.getString("source"));
            assertEquals("handler_queue", incident.details.getString("stage"));
            assertEquals("older_control", incident.details.getJSONObject("latestIngress").getString("stage"));
        } finally { release.countDown(); }
        awaitHandlerBarrier(navigationHost);
        assertEquals("After template", listener.frameSnapshot().get(1).getRoadText());
        String timing = listener.timings.get(listener.timings.size() - 1).directLine("test");
        assertTrue(timing, timing.contains("ingressSource=Trip"));
        assertTrue(timing, timing.contains("preNormalizationQueueMs=-1"));
        assertTrue(timing, timing.contains("normalizationStartElapsedMs="));
    }

    @Test @Config(shadows = DelayedBundle.class)
    public void blockedNormalizationIsIdentifiedAndCannotReviveATerminatedRoute() throws Exception {
        startRoute(routeTrip("Initial", Maneuver.TYPE_TURN_NORMAL_LEFT, 600, 75, 4500));
        DelayedBundle.target = Bundleable.create(routeTrip("Blocked old trip", Maneuver.TYPE_TURN_NORMAL_RIGHT, 300, 40, 2500));
        DelayedBundle.entered = new CountDownLatch(1);
        DelayedBundle.release = new CountDownLatch(1);
        DelayedBundle.decoded = null;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread binder = new Thread(() -> {
            try { navigationHost.updateTrip(DelayedBundle.target); }
            catch (Throwable error) { failure.set(error); }
        }, "test-trip-normalizer");
        binder.start();
        try {
            assertTrue(DelayedBundle.entered.await(2, TimeUnit.SECONDS));
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2100));
            JSONObjectRecord incident = awaitIncident();
            assertEquals("normalization", incident.details.getString("stage"));
            assertEquals("Trip", incident.details.getString("source"));
            assertEquals("test-trip-normalizer", incident.details.getJSONObject("normalizationThread").getString("name"));
            channel.noteRouteTerminalGeneration("test_terminal", 1000);
            awaitHandlerBarrier(navigationHost);
        } finally { DelayedBundle.release.countDown(); binder.join(2000); }
        assertFalse("normalization callback must finish", binder.isAlive());
        if (failure.get() != null) throw new AssertionError("normalization callback failed", failure.get());
        assertTrue("delayed payload must deserialize successfully", DelayedBundle.decoded instanceof Trip);
        assertEquals("Blocked old trip", ((Trip) DelayedBundle.decoded).getSteps().get(0).getRoad().toString());
        assertNull("a parse failure must not masquerade as route-generation rejection", listener.tripParseFailure);
        awaitHandlerBarrier(navigationHost);
        assertTrue(listener.frameSnapshot().stream().noneMatch(frame -> "Blocked old trip".equals(frame.getRoadText())));
    }

    @org.robolectric.annotation.Implements(value = Bundleable.class, isInAndroidSdk = false)
    public static final class DelayedBundle {
        static Bundleable target;
        static CountDownLatch entered, release;
        static volatile Object decoded;
        @org.robolectric.annotation.RealObject Bundleable value;
        @org.robolectric.annotation.Implementation protected Object get() throws Exception {
            if (value == target) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("normalizer release timeout");
            }
            Object result = org.robolectric.shadow.api.Shadow.directlyOn(value, Bundleable.class, "get");
            if (value == target) decoded = result;
            return result;
        }
    }

    private JSONObjectRecord awaitIncident() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            for (byte[] bytes : RuntimeIncidentRecorder.snapshot(RuntimeEnvironment.getApplication(),
                    java.util.Collections.singletonList(NavCaptureStore.todayDir())).values()) {
                for (String line : new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    org.json.JSONObject record = new org.json.JSONObject(line);
                    if ("stall".equals(record.optString("event")) && "waze_ingress".equals(record.optString("source"))) {
                        return new JSONObjectRecord(record);
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("missing Waze incident");
    }

    private static final class JSONObjectRecord {
        final org.json.JSONObject value, details;
        JSONObjectRecord(org.json.JSONObject value) throws Exception {
            this.value = value; details = value.getJSONObject("details");
        }
    }

    private void startRoute(Trip trip) throws Exception {
        CountDownLatch started = listener.armNavigationStarted();
        navigationHost.navigationStarted();
        assertTrue("navigation start callback", started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        navigationHost.updateTrip(Bundleable.create(trip));
        awaitHandlerBarrier(navigationHost);
    }

    private void useHosts(WazeDirectChannel.Mode mode) {
        ingressHosts = channel.ingressHostsForTest(mode);
        navigationHost = ingressHosts.navigationHost;
    }

    private void awaitHandlerBarrier(INavigationHost host) throws Exception {
        // The first barrier drains any already-posted callback; the second also covers a
        // drain that the first callback may have scheduled behind itself.
        for (int i = 0; i < 2; i++) {
            CountDownLatch barrier = listener.armBarrier();
            host.setVoiceAssistantCapabilities(Bundleable.create(metricsOnlyTrip(1L, 1)));
            assertTrue("channel callback barrier", barrier.await(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private static Trip routeTrip(String road, int maneuverType, int distanceMeters,
            long stepSeconds, long destinationSeconds) {
        Step step = new Step.Builder()
                .setCue("Continue")
                .setRoad(road)
                .setManeuver(new Maneuver.Builder(maneuverType).build())
                .build();
        return new Trip.Builder()
                .addStep(step, estimate(distanceMeters, stepSeconds))
                .addDestination(destination("Destination"),
                        estimate(distanceMeters * 10, destinationSeconds))
                .build();
    }

    private static Trip metricsOnlyTrip(long remainingSeconds, int remainingMeters) {
        return new Trip.Builder()
                .addDestination(destination("Destination"),
                        estimate(remainingMeters, remainingSeconds))
                .build();
    }

    private static Destination destination(String name) {
        return new Destination.Builder().setName(name).build();
    }

    private static TravelEstimate estimate(int remainingMeters, long remainingSeconds) {
        long arrivalTimeMs = System.currentTimeMillis() + remainingSeconds * 1000L;
        return new TravelEstimate.Builder(
                Distance.create(remainingMeters / 1000.0,
                        Distance.UNIT_KILOMETERS_P1),
                DateTimeWithZone.create(arrivalTimeMs, TimeZone.getTimeZone("UTC")))
                .setRemainingTimeSeconds(remainingSeconds)
                .build();
    }

    private static final class RecordingListener implements WazeDirectChannel.Listener {
        final List<DirectTbtFrame> frames = new CopyOnWriteArrayList<>();
        final List<WazeRouteTiming.Frame> timings = new CopyOnWriteArrayList<>();
        final AtomicBoolean blockNextFrame = new AtomicBoolean();
        volatile CountDownLatch blockedFrameEntered = new CountDownLatch(0);
        volatile CountDownLatch releaseBlockedFrame = new CountDownLatch(0);
        volatile CountDownLatch navigationStarted = new CountDownLatch(0);
        volatile CountDownLatch navigationEnded = new CountDownLatch(0);
        volatile CountDownLatch handshakeUnavailable = new CountDownLatch(0);
        volatile CountDownLatch barrier = new CountDownLatch(0);
        volatile String handshakeUnavailableReason;
        volatile String tripParseFailure;
        volatile String expectedLog;
        volatile CountDownLatch expectedLogLatch = new CountDownLatch(0);
        volatile int navigationStartedCount;
        volatile int navigationEndedCount;

        void blockNextFrame() {
            blockedFrameEntered = new CountDownLatch(1);
            releaseBlockedFrame = new CountDownLatch(1);
            blockNextFrame.set(true);
        }

        void releaseBlockedFrame() {
            releaseBlockedFrame.countDown();
        }

        CountDownLatch armNavigationStarted() {
            navigationStarted = new CountDownLatch(1);
            return navigationStarted;
        }

        CountDownLatch armNavigationEnded() {
            navigationEnded = new CountDownLatch(1);
            return navigationEnded;
        }

        CountDownLatch armHandshakeUnavailable() {
            handshakeUnavailable = new CountDownLatch(1);
            return handshakeUnavailable;
        }

        CountDownLatch armBarrier() {
            barrier = new CountDownLatch(1);
            return barrier;
        }

        CountDownLatch armLog(String message) {
            expectedLog = message;
            expectedLogLatch = new CountDownLatch(1);
            return expectedLogLatch;
        }

        List<DirectTbtFrame> frameSnapshot() {
            return new ArrayList<>(frames);
        }

        @Override
        public void onHandshakeAvailable(String ownerPackage, int sessionGeneration, String reason) {}

        @Override
        public void onHandshakeUnavailable(String ownerPackage, int sessionGeneration,
                String reason) {
            handshakeUnavailableReason = reason;
            handshakeUnavailable.countDown();
        }

        @Override
        public void onNavigationStarted(String ownerPackage, int sessionGeneration, String reason) {
            navigationStartedCount++;
            navigationStarted.countDown();
        }

        @Override
        public void onFrame(String ownerPackage, int sessionGeneration,
                DirectTbtFrame frame, String reason, WazeRouteTiming.Frame timing) {
            frames.add(frame);
            if (timing != null) timings.add(timing);
            if (blockNextFrame.compareAndSet(true, false)) {
                blockedFrameEntered.countDown();
                try {
                    releaseBlockedFrame.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void onAlertCleared(String ownerPackage, int sessionGeneration,
                DirectTbtFrame frame, String reason) {}

        @Override
        public void onNavigationEnded(String ownerPackage, int routeGeneration,
                int callbackGeneration, String reason) {
            navigationEndedCount++;
            navigationEnded.countDown();
        }

        @Override
        public void onLiveness(String ownerPackage, int sessionGeneration, String reason) {}

        @Override
        public void onSurfaceReady(String ownerPackage, int sessionGeneration,
                long activityInstanceId, int displayId, long surfaceEpoch) {}

        @Override
        public void onSurfaceUnavailable(String ownerPackage, int sessionGeneration, String reason) {}

        @Override
        public void onLog(String message) {
            if (message.startsWith("trip parse failed:")) tripParseFailure = message;
            if (BARRIER_LOG.equals(message)) barrier.countDown();
            if (message.equals(expectedLog)) expectedLogLatch.countDown();
        }
    }
}
