package com.bydhud.app;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.*;
import android.app.Application;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class InstrumentProxyRetryPolicyTest {
    @Test public void shortHandshakesBackOffAndStableConnectionRestoresImmediateRetry() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().uid = 10056;
        InstrumentProxyManager manager = callConstructor(InstrumentProxyManager.class,
                ClassParameter.from(Context.class, context));
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ((ExecutorService) getField(manager, "worker")).shutdownNow();
        setField(manager, "worker", worker);
        worker.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        InstrumentProxyManager.OutputRetry demand = getField(manager, "outputRetry");
        demand.setActive(true);
        try {
            manager.ensureStarted("test-demand");
            connect(manager, context);
            die(manager);
            assertEquals("STARTING", getField(manager, "state").toString());
            connect(manager, context);
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            die(manager);
            assertEquals("IDLE", getField(manager, "state").toString());
            assertTrue((long) getField(manager, "nextRetryAtMs") > SystemClock.elapsedRealtime());
            assertTrue(worker.getQueue().stream().anyMatch(task -> task instanceof Delayed
                    && ((Delayed) task).getDelay(TimeUnit.MILLISECONDS) > 0));
            manager.ensureStarted("still-inside-backoff");
            assertEquals("IDLE", getField(manager, "state").toString());
            ShadowSystemClock.advanceBy(Duration.ofMinutes(1));
            manager.ensureStarted("after-backoff");
            connect(manager, context);
            ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
            die(manager);
            assertEquals("STARTING", getField(manager, "state").toString());
            demand.setActive(false);
            setField(manager, "runtimeActive", false);
            assertFalse(demand.isActive());
        } finally {
            worker.shutdownNow(); release.countDown();
            ((ExecutorService) getField(manager, "calls")).shutdownNow();
            ((ExecutorService) getField(manager, "shanghaiTransitions")).shutdownNow();
            callInstanceMethod(manager, "unregisterHandoffReceiver");
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static void connect(InstrumentProxyManager manager, Context context) {
        long generation = getField(manager, "generation");
        String nonce = getField(manager, "nonce"), token = getField(manager, "launchToken");
        Binder binder = new Binder();
        IInstrumentNavigationProxy proxy = (IInstrumentNavigationProxy) Proxy.newProxyInstance(
                InstrumentProxyRetryPolicyTest.class.getClassLoader(), new Class<?>[]{IInstrumentNavigationProxy.class},
                (object, method, args) -> method.getName().equals("asBinder") ? binder : null);
        setField(manager, "connectingProxy", proxy);
        setField(manager, "connectingBinder", binder);
        setField(manager, "helperIdentity", InstrumentProxyStore.Identity.pending(
                context.getApplicationInfo().uid, generation, nonce, token, BuildConfig.VERSION_CODE));
        setField(manager, "helperIdentityLoaded", true);
        Bundle result = InstrumentProxyContract.connectionResult(true, "", generation, nonce,
                1234, 2000, BuildConfig.VERSION_CODE, token, 99L,
                InstrumentProxyContract.CAP_SYSTEM_CONTEXT | InstrumentProxyContract.CAP_SHELL_RUNTIME);
        callInstanceMethod(manager, "completeConnect", ClassParameter.from(long.class, generation),
                ClassParameter.from(Bundle.class, result));
        assertEquals("READY", getField(manager, "state").toString());
    }
    private static void die(InstrumentProxyManager manager) {
        callInstanceMethod(manager, "handleBinderDeath", ClassParameter.from(long.class,
                (long) getField(manager, "generation")), ClassParameter.from(IBinder.class,
                (IBinder) getField(manager, "proxyBinder")));
    }
}
