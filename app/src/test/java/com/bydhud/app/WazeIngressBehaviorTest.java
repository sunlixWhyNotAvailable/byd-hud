package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.car.app.IOnDoneCallback;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.DateTimeWithZone;
import androidx.car.app.model.Distance;
import androidx.car.app.model.TemplateWrapper;
import androidx.car.app.navigation.INavigationHost;
import androidx.car.app.navigation.model.Destination;
import androidx.car.app.navigation.model.Maneuver;
import androidx.car.app.navigation.model.NavigationTemplate;
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
import java.lang.reflect.Method;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    public void setUp() {
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
    public void tripBurstCoalescesStepAndMetricsOnlyUpdatesThroughOneDrain() throws Exception {
        CountDownLatch started = listener.armNavigationStarted();
        navigationHost.navigationStarted();
        assertTrue("navigation start callback", started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        listener.blockNextFrame();
        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "Road A", Maneuver.TYPE_TURN_NORMAL_LEFT, 900, 95L, 4000L)));
        assertTrue("first route frame entered listener",
                listener.blockedFrameEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

        // The first rendered callback is deliberately held while the real Binder host
        // receives a burst. A no-step Trip must contribute its metrics without replacing C.
        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "Road B", Maneuver.TYPE_TURN_NORMAL_RIGHT, 610, 70L, 3500L)));
        navigationHost.updateTrip(Bundleable.create(metricsOnlyTrip(53L, 5300)));
        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "Road C", Maneuver.TYPE_TURN_NORMAL_LEFT, 325, 29L, 2800L)));
        navigationHost.updateTrip(Bundleable.create(metricsOnlyTrip(17L, 1700)));

        CountDownLatch firstBarrier = listener.armBarrier();
        navigationHost.setVoiceAssistantCapabilities(Bundleable.create(metricsOnlyTrip(1L, 1)));
        listener.releaseBlockedFrame();
        assertTrue("queued callbacks crossed Handler barrier",
                firstBarrier.await(WAIT_SECONDS, TimeUnit.SECONDS));
        awaitHandlerBarrier(navigationHost);

        List<DirectTbtFrame> frames = listener.frameSnapshot();
        assertEquals("one in-flight frame plus one coalesced frame", 2, frames.size());
        DirectTbtFrame latest = frames.get(1);
        assertEquals("Road C", latest.getRoadText());
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, latest.getRawManeuverType());
        assertEquals(325, latest.getDistanceMeters());
        assertEquals(17L,
                latest.getTripMetrics().getNextStop().getRemainingTimeSeconds());
        assertEquals(1700L,
                latest.getTripMetrics().getNextStop().getRemainingDistanceMeters());
    }

    @Test
    public void terminalNavigationRejectsLateMetricsAndStepCallbacks() throws Exception {
        startRoute(routeTrip("Active road", Maneuver.TYPE_TURN_NORMAL_LEFT,
                700, 80L, 5000L));
        assertEquals(1, listener.frameSnapshot().size());

        CountDownLatch ended = listener.armNavigationEnded();
        navigationHost.navigationEnded();
        assertTrue("terminal callback", ended.await(WAIT_SECONDS, TimeUnit.SECONDS));

        // A late metrics-only Trip must not emit through the previously accepted route,
        // and a stale step callback must not restart navigation after the terminal fence.
        navigationHost.updateTrip(Bundleable.create(metricsOnlyTrip(12L, 1200)));
        awaitHandlerBarrier(navigationHost);
        assertEquals(1, listener.frameSnapshot().size());

        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "Stale road", Maneuver.TYPE_TURN_NORMAL_RIGHT, 300, 40L, 2400L)));
        awaitHandlerBarrier(navigationHost);
        assertEquals(1, listener.frameSnapshot().size());

        CountDownLatch rejectedStart = listener.armNavigationStarted();
        navigationHost.navigationStarted();
        awaitHandlerBarrier(navigationHost);
        assertFalse("terminal latch rejects a late navigationStarted",
                rejectedStart.await(0L, TimeUnit.MILLISECONDS));
        assertEquals(1, listener.navigationStartedCount);
    }

    @Test
    public void explicitCarHostFinishFencesLateTripCallbacks() throws Exception {
        startRoute(routeTrip("Active road", Maneuver.TYPE_TURN_NORMAL_LEFT,
                700, 80L, 5000L));
        assertEquals(1, listener.frameSnapshot().size());

        CountDownLatch ended = listener.armNavigationEnded();
        ingressHosts.carHost.finish();
        assertTrue("explicit car host finish callback",
                ended.await(WAIT_SECONDS, TimeUnit.SECONDS));

        navigationHost.updateTrip(Bundleable.create(metricsOnlyTrip(11L, 1100)));
        awaitHandlerBarrier(navigationHost);
        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "Finished route", Maneuver.TYPE_TURN_NORMAL_RIGHT, 275, 35L, 2100L)));
        awaitHandlerBarrier(navigationHost);

        assertEquals("finish fences both late metrics and step updates",
                1, listener.frameSnapshot().size());
    }

    @Test
    public void suspendedSessionDropsOldHostTripCallbacks() throws Exception {
        startRoute(routeTrip("Before suspend", Maneuver.TYPE_TURN_NORMAL_LEFT,
                800, 90L, 6000L));
        assertEquals(1, listener.frameSnapshot().size());

        channel.stop("test_suspend");
        awaitHandlerBarrier(navigationHost);
        assertFalse("stop leaves the channel suspended", channel.isActive());

        navigationHost.updateTrip(Bundleable.create(metricsOnlyTrip(8L, 800)));
        awaitHandlerBarrier(navigationHost);
        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "After suspend", Maneuver.TYPE_TURN_NORMAL_RIGHT, 250, 30L, 2000L)));
        awaitHandlerBarrier(navigationHost);

        assertEquals("old session callbacks cannot publish while suspended",
                1, listener.frameSnapshot().size());
        assertEquals(1, listener.navigationStartedCount);
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

    @Test
    public void bridgeSupportedNavigationHintsStayNonterminalInClusterAndSurfaceModes()
            throws Exception {
        installV2BridgePackage();
        for (WazeDirectChannel.Mode mode : new WazeDirectChannel.Mode[]{
                WazeDirectChannel.Mode.CLUSTER, WazeDirectChannel.Mode.MAIN_SURFACE}) {
            useHosts(mode);
            int priorFrames = listener.frameSnapshot().size();
            int priorEnds = listener.navigationEndedCount;
            startRoute(routeTrip("Before hints " + mode,
                    Maneuver.TYPE_TURN_NORMAL_LEFT, 750, 80L, 5200L));

            navigationHost.navigationEnded();
            awaitHandlerBarrier(navigationHost);

            IOnDoneCallback templateCallback = channel.templateCallbackForTest();
            templateCallback.onSuccess(Bundleable.create(
                    TemplateWrapper.wrap(navigationTemplateWithNullInfo())));
            awaitHandlerBarrier(navigationHost);

            navigationHost.updateTrip(Bundleable.create(routeTrip(
                    "After hints " + mode, Maneuver.TYPE_TURN_NORMAL_RIGHT,
                    360, 40L, 2600L)));
            awaitHandlerBarrier(navigationHost);

            List<DirectTbtFrame> frames = listener.frameSnapshot();
            assertEquals("hints preserve the accepted route in " + mode,
                    priorFrames + 2, frames.size());
            assertEquals("After hints " + mode, frames.get(frames.size() - 1).getRoadText());
            assertEquals("bridge hints do not end navigation in " + mode,
                    priorEnds, listener.navigationEndedCount);
        }
    }

    @Test
    public void getTemplateFailureUsesSessionFailureAndRecoversOnFreshGeneration()
            throws Exception {
        CountDownLatch unavailable = listener.armHandshakeUnavailable();
        IOnDoneCallback templateCallback = channel.templateCallbackForTest();
        templateCallback.onFailure(Bundleable.create(
                TemplateWrapper.wrap(navigationTemplateWithNullInfo())));

        assertTrue("failed getTemplate reports the session failure",
                unavailable.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("callback_failure_getTemplate", listener.handshakeUnavailableReason);
        assertTrue("failed template callback leaves the channel recoverable",
                channel.isActive());

        useHosts(WazeDirectChannel.Mode.CLUSTER);
        startRoute(routeTrip("Recovered session", Maneuver.TYPE_TURN_NORMAL_LEFT,
                410, 45L, 3100L));
        assertEquals("Recovered session", listener.frameSnapshot().get(0).getRoadText());
    }

    @Test
    public void redundantActiveStartKeepsLiveHostTripAndControlDelivery() throws Exception {
        allowRealStartWithCurrentPackage();
        startRoute(routeTrip("Before ensure", Maneuver.TYPE_TURN_NORMAL_LEFT,
                700, 80L, 5000L));
        channel.start("route-lifecycle-ensure", WazeDirectChannel.Mode.CLUSTER);
        awaitHandlerBarrier(navigationHost);

        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "After ensure", Maneuver.TYPE_TURN_NORMAL_RIGHT, 320, 30L, 2500L)));
        awaitHandlerBarrier(navigationHost);
        assertEquals(2, listener.frameSnapshot().size());
        assertEquals("After ensure", listener.frameSnapshot().get(1).getRoadText());
        CountDownLatch ended = listener.armNavigationEnded();
        navigationHost.navigationEnded();
        assertTrue("same live host still delivers controls",
                ended.await(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void retainedBindingResumeKeepsLiveHostWhileFencingSuspendedData() throws Exception {
        allowRealStartWithCurrentPackage();
        // Model the established binding without connecting an external Waze service.
        setField(channel, "bound", true);
        startRoute(routeTrip("Before suspension", Maneuver.TYPE_TURN_NORMAL_LEFT,
                700, 80L, 5000L));
        channel.stop("retained-resume-test");
        awaitHandlerBarrier(navigationHost);
        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "During suspension", Maneuver.TYPE_TURN_NORMAL_RIGHT, 610, 60L, 4000L)));
        awaitHandlerBarrier(navigationHost);
        assertEquals(1, listener.frameSnapshot().size());

        channel.start("retained-resume-test", WazeDirectChannel.Mode.CLUSTER);
        awaitHandlerBarrier(navigationHost);
        startRoute(routeTrip("After resume", Maneuver.TYPE_TURN_NORMAL_RIGHT,
                320, 30L, 2500L));
        assertEquals(2, listener.frameSnapshot().size());
        assertEquals("After resume", listener.frameSnapshot().get(1).getRoadText());
    }

    @Test
    public void invalidAdmissionRetiresBoundHostWithoutStartingWaze() throws Exception {
        allowRealStartWithCurrentPackage();
        setField(channel, "bound", true);
        startRoute(routeTrip("Before admission loss", Maneuver.TYPE_TURN_NORMAL_LEFT,
                700, 80L, 5000L));
        WazeStartAdmission.PROCESS.invalidate();
        CountDownLatch waiting = listener.armLog(
                "start waiting for evidence reason=admission-lost");
        channel.start("admission-lost", WazeDirectChannel.Mode.CLUSTER);
        assertTrue(waiting.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(channel.isActive());
        assertEquals(false, field(channel, "bound"));
        assertEquals(false, field(channel, "binding"));
        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "Stale host", Maneuver.TYPE_TURN_NORMAL_RIGHT, 250, 25L, 2000L)));
        CountDownLatch drained = new CountDownLatch(1);
        ((android.os.Handler) field(channel, "channelHandler")).post(drained::countDown);
        assertTrue(drained.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, listener.frameSnapshot().size());
    }

    @Test
    public void resetDoesNotWaitForDiscardedNormalization() throws Exception {
        startRoute(routeTrip("Old route", Maneuver.TYPE_TURN_NORMAL_LEFT,
                700, 80L, 5000L));
        Object oldIngress = beginUnfinishedNormalization();
        CountDownLatch stopped = listener.armLog("hard stopped reason=normalization-reset");
        channel.hardStop("normalization-reset");
        assertTrue(stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        useHosts(WazeDirectChannel.Mode.CLUSTER);
        startRoute(routeTrip("New route", Maneuver.TYPE_TURN_NORMAL_RIGHT,
                250, 25L, 2000L));
        assertEquals("new route drains before the old decode finishes",
                2, listener.frameSnapshot().size());
        finishObsoleteNormalization(oldIngress);
        awaitHandlerBarrier(navigationHost);
        assertEquals(2, listener.frameSnapshot().size());
    }

    @Test
    public void freshRouteDiscardDoesNotWaitForDiscardedNormalization() throws Exception {
        startRoute(routeTrip("Old route", Maneuver.TYPE_TURN_NORMAL_LEFT,
                700, 80L, 5000L));
        Object oldIngress = beginUnfinishedNormalization();
        channel.openAcceptedFreshRoute("normalization-discard", 10L);
        awaitHandlerBarrier(navigationHost);
        navigationHost.updateTrip(Bundleable.create(routeTrip(
                "Fresh route", Maneuver.TYPE_TURN_NORMAL_RIGHT, 250, 25L, 2000L)));
        awaitHandlerBarrier(navigationHost);
        assertEquals("fresh route drains before the old decode finishes",
                2, listener.frameSnapshot().size());
        finishObsoleteNormalization(oldIngress);
        awaitHandlerBarrier(navigationHost);
        assertEquals("late discarded result cannot republish the old route",
                "Fresh route", listener.frameSnapshot().get(1).getRoadText());
        assertEquals(2, listener.frameSnapshot().size());
    }

    @Test
    public void staleTemplateResultCompletesOnceWithoutRevivingOldSession() throws Exception {
        AtomicInteger completions = new AtomicInteger();
        WazeDirectChannel.TemplateRefreshGate gate = new WazeDirectChannel.TemplateRefreshGate();
        WazeDirectChannel.TemplateRefreshGate.Request request = gate.begin();
        IOnDoneCallback oldCallback = channel.templateCallbackForTest(() -> {
            gate.complete(request);
            completions.incrementAndGet();
        });
        AtomicInteger failureCompletions = new AtomicInteger();
        IOnDoneCallback oldFailure = channel.templateCallbackForTest(failureCompletions::incrementAndGet);
        CountDownLatch stopped = listener.armLog("hard stopped reason=stale-template");
        channel.hardStop("stale-template");
        assertTrue(stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        useHosts(WazeDirectChannel.Mode.CLUSTER);
        oldCallback.onSuccess(Bundleable.create(
                TemplateWrapper.wrap(navigationTemplateWithNullInfo())));
        oldFailure.onFailure(null);
        awaitHandlerBarrier(navigationHost);
        assertEquals(1, completions.get());
        assertEquals(1, failureCompletions.get());
        assertTrue("stale request released its gate", gate.begin() != null);
        assertEquals(0, listener.frameSnapshot().size());
        assertEquals(0, listener.navigationEndedCount);
    }

    private void allowRealStartWithCurrentPackage() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo waze = new PackageInfo();
        waze.packageName = WazeRouteLifecycleStore.WAZE_PACKAGE;
        waze.lastUpdateTime = (long) field(WazeStartAdmission.PROCESS, "packageUpdateMs");
        waze.applicationInfo = new ApplicationInfo();
        waze.applicationInfo.packageName = waze.packageName;
        Shadows.shadowOf(context.getPackageManager()).installPackage(waze);
        UserRuntimeSession.PROCESS.activate();
        HudPrefs.setUserShutdownActive(context, false);
        NavCapturePrefs.setHudEnabled(context, waze.packageName, true);
        WazeStartAdmission.PROCESS.acceptedRoute(
                true, true, false, SystemClock.elapsedRealtime());
    }

    private Object beginUnfinishedNormalization() throws Exception {
        Method begin = WazeDirectChannel.class.getDeclaredMethod(
                "beginTripIngress", int.class, WazeStartAdmission.Permit.class);
        begin.setAccessible(true);
        // Pause after the real ingress registration, as if bundle.get() is still running.
        return begin.invoke(channel, field(channel, "generation"), field(channel, "connectionPermit"));
    }

    private void finishObsoleteNormalization(Object ingress) throws Exception {
        Method finish = WazeDirectChannel.class.getDeclaredMethod("finishTripIngress",
                int.class, WazeStartAdmission.Permit.class, ingress.getClass(),
                WazeTripStateQueue.Update.class);
        finish.setAccessible(true);
        long sequence = (long) field(ingress, "sequence");
        WazeTripStateQueue.Update<Object, DirectTbtFrame.TripMetrics> old =
                new WazeTripStateQueue.Update<>(sequence, 0L, false, null,
                        false, 0, false, true, DirectTbtFrame.TripMetrics.empty());
        // Even with the current identity, the old sequence must fail the discard floor.
        finish.invoke(channel, field(channel, "generation"),
                field(channel, "connectionPermit"), ingress, old);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
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

    private void installV2BridgePackage() {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo waze = new PackageInfo();
        waze.packageName = WazeRouteLifecycleStore.WAZE_PACKAGE;
        waze.setLongVersionCode(31L);
        waze.versionCode = 31;
        waze.lastUpdateTime = System.currentTimeMillis();
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = WazeRouteLifecycleStore.WAZE_PACKAGE;
        appInfo.metaData = new Bundle();
        appInfo.metaData.putInt(WazeRouteLifecycleStore.CAPABILITY_META_DATA,
                WazeRouteLifecycleStore.V2_PROTOCOL_VERSION);
        waze.applicationInfo = appInfo;
        Shadows.shadowOf(context.getPackageManager()).installPackage(waze);
    }

    private static NavigationTemplate navigationTemplateWithNullInfo() {
        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder().addAction(Action.APP_ICON).build())
                .build();
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
        final AtomicBoolean blockNextFrame = new AtomicBoolean();
        volatile CountDownLatch blockedFrameEntered = new CountDownLatch(0);
        volatile CountDownLatch releaseBlockedFrame = new CountDownLatch(0);
        volatile CountDownLatch navigationStarted = new CountDownLatch(0);
        volatile CountDownLatch navigationEnded = new CountDownLatch(0);
        volatile CountDownLatch handshakeUnavailable = new CountDownLatch(0);
        volatile CountDownLatch barrier = new CountDownLatch(0);
        volatile String handshakeUnavailableReason;
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
            if (BARRIER_LOG.equals(message)) barrier.countDown();
            if (message.equals(expectedLog)) expectedLogLatch.countDown();
        }
    }
}
