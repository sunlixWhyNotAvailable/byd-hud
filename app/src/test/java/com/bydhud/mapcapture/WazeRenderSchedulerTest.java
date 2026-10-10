package com.bydhud.mapcapture;

import static org.junit.Assert.*;
import android.app.Application;
import android.os.Bundle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

}
