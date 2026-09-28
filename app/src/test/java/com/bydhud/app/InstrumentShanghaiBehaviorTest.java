package com.bydhud.app;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.*;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** Executes the real manager; only shell I/O, logging and the executor clock are replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class,
        shadows = {InstrumentShanghaiBehaviorTest.Adb.class, InstrumentShanghaiBehaviorTest.Events.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class InstrumentShanghaiBehaviorTest {
    private InstrumentProxyManager manager;
    private ManualWorker worker;
    private Proxy proxy;

    @Before public void setUp() throws Exception {
        Adb.cleanups = 0;
        Adb.uncertainLaunch = false;
        Adb.launches = 0;
        manager = callConstructor(InstrumentProxyManager.class,
                ClassParameter.from(Context.class, RuntimeEnvironment.getApplication()));
        stop(getField(manager, "worker"));
        worker = new ManualWorker();
        setField(manager, "worker", worker);
        proxy = new Proxy();
        setField(manager, "generation", 7L);
        setField(manager, "nonce", "old");
        setField(manager, "launchToken", "old-token");
    }

    @After public void tearDown() throws Exception {
        stop(worker);
        stop(getField(manager, "calls"));
        stop(getField(manager, "shanghaiTransitions"));
    }

    @Test public void readySuspendCancelsRetryAndIsIdempotent() throws Exception {
        ready();
        InstrumentProxyManager.OutputRetry retry = getField(manager, "outputRetry");
        retry.setActive(true);
        List<Long> attempts = new ArrayList<>();
        retry.schedule(worker, 30_000, 7, attempts::add);
        Task pending = worker.tasks.get(0);
        manager.suspendForShanghai();
        manager.suspendForShanghai();
        manager.ensureStarted("during-shanghai");
        pending.command.run(); // Simulate work dequeued just before cancellation.
        assertTrue(pending.isCancelled());
        assertTrue(attempts.isEmpty());
        assertEquals(List.of("suspend:7"), proxy.events);
        assertEquals(0, Adb.cleanups);
        assertTrue((Boolean) getField(manager, "shanghaiSuspended"));
    }

    @Test public void startingSuspendFencesLaunchConnectAndTimeoutFromOldGeneration() throws Exception {
        state("STARTING");
        setField(manager, "runtimeActive", true);
        setField(manager, "connectingProxy", proxy);
        setField(manager, "connectingBinder", proxy.asBinder());
        manager.suspendForShanghai();
        assertEquals(List.of("shutdown:7"), proxy.events);
        assertEquals(1, Adb.cleanups);
        assertEquals("IDLE", getField(manager, "state").toString());
        assertNotEquals(7L, (long) getField(manager, "generation"));
        callInstanceMethod(manager, "launch", ClassParameter.from(long.class, 7L),
                ClassParameter.from(String.class, "old"), ClassParameter.from(String.class, "old-token"));
        callInstanceMethod(manager, "beginConnect", ClassParameter.from(long.class, 7L),
                ClassParameter.from(String.class, "old"), ClassParameter.from(IBinder.class, proxy.asBinder()));
        callInstanceMethod(manager, "completeConnect", ClassParameter.from(long.class, 7L),
                ClassParameter.from(Bundle.class, new Bundle()));
        callInstanceMethod(manager, "handleStartTimeout", ClassParameter.from(long.class, 7L));
        assertEquals("IDLE", getField(manager, "state").toString());
        assertEquals(List.of("shutdown:7"), proxy.events);
        assertEquals(1, Adb.cleanups);
        assertTrue(worker.tasks.isEmpty());
    }

    @Test public void resumeRestartsOnlyWhileOutputDemandRemains() throws Exception {
        ready();
        setField(manager, "runtimeActive", true);
        InstrumentProxyManager.OutputRetry retry = getField(manager, "outputRetry");
        retry.setActive(true);
        manager.suspendForShanghai();
        manager.resumeAfterShanghai();
        assertEquals(List.of("suspend:7", "resume:7", "ping:7"), proxy.events);
        assertFalse((Boolean) getField(manager, "shanghaiSuspended"));
        setField(manager, "pingInFlight", false);
        manager.suspendForShanghai();
        manager.setOutputDemand(false, "route-ended-during-shanghai");
        manager.resumeAfterShanghai();
        assertEquals(List.of("suspend:7", "resume:7", "ping:7", "suspend:7", "resume:7"), proxy.events);
        assertFalse(retry.isActive());
        assertFalse((Boolean) getField(manager, "shanghaiSuspended"));
    }

    @Test public void failedResumeStillReleasesSuspensionWithoutRevivingIdleOutput() throws Exception {
        ready();
        manager.suspendForShanghai();
        proxy.failResume = true;
        assertThrows(java.io.IOException.class, manager::resumeAfterShanghai);
        assertFalse((Boolean) getField(manager, "shanghaiSuspended"));
        assertFalse((Boolean) getField(manager, "shanghaiHelperAcknowledged"));
        assertEquals("IDLE", getField(manager, "state").toString());
        assertEquals(List.of("suspend:7", "resume:7"), proxy.events);
        assertEquals(1, Adb.cleanups);
    }

    @Test public void uncertainLaunchAllowsAuthenticatedHandoffWithoutReplay() throws Exception {
        startWithLostShellReply();
        assertEquals("STARTING", getField(manager, "state").toString());
        assertEquals("launch-transport-uncertain", getField(manager, "startStage"));
        InstrumentProxyStore.Identity pending = getField(manager, "helperIdentity");
        assertEquals(-1, pending.pid);
        manager.acceptHandoff(pending.generation, pending.nonce, proxy.asBinder());
        Bundle result = InstrumentProxyContract.connectionResult(true, "", pending.generation,
                pending.nonce, 14207, 2000, BuildConfig.VERSION_CODE, pending.token, 4242,
                InstrumentProxyContract.CAP_SYSTEM_CONTEXT | InstrumentProxyContract.CAP_DIRECT_FID);
        callInstanceMethod(manager, "completeConnect", ClassParameter.from(long.class, pending.generation),
                ClassParameter.from(Bundle.class, result));
        assertEquals("READY", getField(manager, "state").toString());
        assertEquals(14207, ((InstrumentProxyStore.Identity) getField(manager, "helperIdentity")).pid);
        callInstanceMethod(manager, "handleStartTimeout", ClassParameter.from(long.class, pending.generation));
        assertEquals("READY", getField(manager, "state").toString());
        assertEquals(1, Adb.launches);
    }

    @Test public void uncertainLaunchWithoutHandoffStillTimesOutAndCleansUp() throws Exception {
        startWithLostShellReply();
        long generation = getField(manager, "generation");
        // Exhaust the existing immediate-retry budget to isolate this launch's deadline.
        setField(manager, "rapidFailureCount", 1);
        Task timeout = worker.tasks.stream().filter(t -> t.delay == 5000).findFirst().orElseThrow();
        timeout.run();
        assertEquals("IDLE", getField(manager, "state").toString());
        assertEquals(1, Adb.launches);
        assertEquals(2, Adb.cleanups); // Pre-launch stale cleanup and failed-launch cleanup.
        manager.acceptHandoff(generation, getField(manager, "nonce"), proxy.asBinder());
        assertTrue(proxy.events.isEmpty());
    }

    private void startWithLostShellReply() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().uid = 10123;
        assertTrue(InstrumentProxyStore.markLegacyMigrationComplete(context, 10123));
        Adb.uncertainLaunch = true;
        manager.ensureStarted("test-lost-launch-reply");
    }

    private void ready() throws Exception {
        state("READY");
        setField(manager, "proxy", proxy);
        setField(manager, "proxyBinder", proxy.asBinder());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void state(String name) throws Exception {
        Class type = Class.forName("com.bydhud.app.InstrumentProxyManager$State");
        setField(manager, "state", Enum.valueOf(type, name));
    }

    private static void stop(ExecutorService executor) throws Exception {
        if (executor == null) return;
        executor.shutdownNow();
        assertTrue("worker did not stop", executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    private static class Proxy extends IInstrumentNavigationProxy.Stub {
        final List<String> events = new CopyOnWriteArrayList<>();
        boolean failResume;
        public void connect(long g, String n, IInstrumentNavigationClient c) { events.add("connect:" + g); }
        public void ping(long g, long token) { events.add("ping:" + g); }
        public void shutdown(long g) { events.add("shutdown:" + g); }
        public Bundle suspendOutput(long g) { events.add("suspend:" + g); return new Bundle(); }
        public Bundle resumeOutput(long g) throws android.os.RemoteException {
            events.add("resume:" + g);
            if (failResume) throw new android.os.RemoteException("test failure");
            return new Bundle();
        }
        public Bundle sendNavigationStatus(long g, int s) { return new Bundle(); }
        public Bundle sendGuidance(long g, int i, int d, String r, int[] l, int[] a) { return new Bundle(); }
        public Bundle sendHudCheckTrafficLight(long g, int s) { return new Bundle(); }
        public Bundle nativeSpeedOperation(long g, int o, int v) { return new Bundle(); }
    }

    @Implements(LocalAdbBridge.class)
    public static class Adb {
        static int cleanups;
        static boolean uncertainLaunch;
        static int launches;
        @Implementation protected static LocalAdbBridge.ShellResult launchInstrumentProxy(
                Context context, String apkPath, long generation, String nonce, int appUid,
                String launchToken, int appVersionCode) throws java.io.IOException {
            launches++;
            throw new java.net.SocketTimeoutException("test lost shell reply");
        }
        @Implementation protected static String instrumentProxyStartupDiagnostic(
                Context context, InstrumentProxyStore.Identity identity) { return "test diagnostic"; }
        @Implementation protected static void clearInstrumentProxyStartupDiagnostic(
                Context context, InstrumentProxyStore.Identity identity) { }
        @Implementation protected static LocalAdbBridge.ShellResult stopInstrumentProxy(
                Context context, InstrumentProxyStore.Identity identity) {
            cleanups++;
            return callConstructor(LocalAdbBridge.ShellResult.class,
                    ClassParameter.from(String.class, ""), ClassParameter.from(int.class, 0),
                    ClassParameter.from(String.class, ""));
        }
        @Implementation protected static boolean isCurrentKeyKnownAuthorized(Context context) {
            if (uncertainLaunch) return true;
            throw new AssertionError("cancelled Shanghai launch reached ADB");
        }
    }

    @Implements(AppEventLogger.class)
    public static class Events {
        @Implementation protected static void event(Context context, String message) { }
    }

    /** Immediate submitted work and manually retained deadlines; no wall-clock sleeps. */
    private static class ManualWorker extends ScheduledThreadPoolExecutor {
        final List<Task> tasks = new ArrayList<>();
        ManualWorker() { super(1); }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public Future<?> submit(Runnable task) {
            FutureTask<Void> result = new FutureTask<>(task, null); result.run(); return result;
        }
        @Override public <T> Future<T> submit(Callable<T> task) {
            FutureTask<T> result = new FutureTask<>(task); result.run(); return result;
        }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Task task = new Task(command, unit.toMillis(delay)); tasks.add(task); return task;
        }
    }

    private static class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
        final Runnable command;
        final long delay;
        Task(Runnable command, long delay) { super(command, null); this.command = command; this.delay = delay; }
        public long getDelay(TimeUnit unit) { return unit.convert(delay, TimeUnit.MILLISECONDS); }
        public int compareTo(Delayed other) { return Long.compare(delay, other.getDelay(TimeUnit.MILLISECONDS)); }
    }
}
