package com.bydhud.mapcapture;

import static org.junit.Assert.*;
import android.app.Application;
import android.os.Bundle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class WazeRenderSchedulerTest {
    static final class Manager {
        final Object a = new Object();
        final Queue<Object> d = new ConcurrentLinkedQueue<>(), f = new ConcurrentLinkedQueue<>();
        boolean g = true;
    }
    static final class RenderThread {
        boolean d;
        final Object h = new Object();
        final Queue<Object> g = new ConcurrentLinkedQueue<>();
        final List<Manager> e = new ArrayList<>();
    }
    private Manager manager;
    private RenderThread thread;
    @Before public void attach() {
        manager = new Manager(); thread = new RenderThread(); thread.e.add(manager);
        WazeRenderScheduler.policy(true, 1000, 6000);
        WazeRenderScheduler.attach(manager, thread);
    }
    @After public void release() {
        ReflectionHelpers.setStaticField(CaptureBridge.class, "wazeRequest", null);
        WazeRenderScheduler.policy(false, 1000, 6000);
    }
    private JSONObject stats() throws Exception {
        Bundle data = new Bundle(); WazeRenderScheduler.metadata(data);
        return new JSONObject(data.getString("renderStats"));
    }
    @Test public void onlyOwnedHiddenTargetIsGatedAndPendingSurvivesRetry() throws Exception {
        assertTrue(WazeRenderScheduler.allow(manager.a)); // Initial Surface frame.
        WazeRenderScheduler.frame(manager.a);
        assertFalse(WazeRenderScheduler.allow(manager.a));
        assertTrue(WazeRenderScheduler.allow(new Object())); // Foreground/native owner.
        Bundle request = new Bundle();
        ReflectionHelpers.setStaticField(CaptureBridge.class, "wazeRequest", request);
        WazeRenderScheduler.request();
        assertTrue(WazeRenderScheduler.allow(manager.a));
        assertFalse(WazeRenderScheduler.allow(manager.a));
        ShadowSystemClock.advanceBy(Duration.ofMillis(50));
        assertTrue(WazeRenderScheduler.allow(manager.a)); // Native false must not consume request.
        assertTrue(CaptureBridge.hasWazeRequest());
        assertEquals(2, stats().getLong("skips"));
        WazeRenderScheduler.detach();
        assertTrue(WazeRenderScheduler.allow(manager.a));
    }
    @Test public void callbacksAndSurfaceWorkWakeWithoutCapture() {
        WazeRenderScheduler.allow(manager.a); WazeRenderScheduler.frame(manager.a);
        assertFalse(WazeRenderScheduler.allow(manager.a));
        manager.f.add(new Object()); WazeRenderScheduler.callbackQueued(new Object());
        assertTrue(WazeRenderScheduler.allow(manager.a));
        manager.f.clear();
        manager.d.add(new Object()); WazeRenderScheduler.surfaceQueued(manager);
        assertTrue(WazeRenderScheduler.allow(manager.a));
    }
    @Test public void leaseExpiryAndUnknownLayoutRestoreNative() throws Exception {
        WazeRenderScheduler.allow(manager.a); WazeRenderScheduler.frame(manager.a);
        ShadowSystemClock.advanceBy(Duration.ofMillis(6001));
        assertTrue(WazeRenderScheduler.allow(manager.a));
        assertFalse(stats().getBoolean("active"));
        WazeRenderScheduler.attach(new Object(), thread);
        assertTrue(WazeRenderScheduler.allow(manager.a));
        assertEquals("attach_failed", stats().getString("reason"));
        assertFalse(stats().getString("error").isEmpty());
    }
    @Test public void queuedWorkAndOtherVisibleTargetsDoNotUseLongIdleWait() throws Exception {
        WazeRenderScheduler.frame(manager.a);
        thread.g.add(new Object());
        synchronized (thread.h) { WazeRenderScheduler.await(thread.h, thread); }
        thread.g.clear();
        Manager other = new Manager(); other.g = false; other.d.add(new Object()); thread.e.add(other);
        synchronized (thread.h) { WazeRenderScheduler.await(thread.h, thread); }
        other.g = true; other.d.clear();
        synchronized (thread.h) { WazeRenderScheduler.await(thread.h, thread); }
        other.g = false; thread.d = true;
        WazeRenderScheduler.threadStopping(thread);
        synchronized (thread.h) { WazeRenderScheduler.await(thread.h, thread); }
        assertEquals(0, stats().getLong("waits"));
    }
    @Test public void newlyVisibleOtherSurfaceInterruptsHiddenWait() throws Exception {
        WazeRenderScheduler.frame(manager.a);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sleeper = new Thread(() -> {
            synchronized (thread.h) {
                entered.countDown();
                try { WazeRenderScheduler.await(thread.h, thread); }
                catch (Throwable error) { failure.set(error); }
            }
        });
        sleeper.start(); assertTrue(entered.await(1, TimeUnit.SECONDS));
        WazeRenderScheduler.surfaceQueued(new Manager());
        sleeper.join(500);
        boolean woke = !sleeper.isAlive();
        if (!woke) { sleeper.interrupt(); sleeper.join(500); }
        assertTrue("Surface event must wake the shared executor immediately", woke);
        assertNull(failure.get());
    }
    @Test public void demandPolicySupportsBothRatesWithoutBusySpinOrPermanentCap() {
        for (long interval : new long[]{1000, 200, 10}) {
            long retry = WazeRenderPolicy.retryDelay(interval);
            assertTrue(retry > 0 && retry <= interval);
            assertFalse(WazeRenderPolicy.admit(1000, 0, false, false, false));
            assertTrue(WazeRenderPolicy.admit(1000, 0, false, true, false));
            assertFalse(WazeRenderPolicy.admit(1000, 1000 + retry, false, true, false));
            assertEquals(retry, WazeRenderPolicy.idleDelay(1000, 1000 + retry, true));
        }
        assertEquals(1000, WazeRenderPolicy.idleDelay(1000, 0, false));
        assertEquals(1, WazeRenderPolicy.idleDelay(1000, 0, true));
    }
}
