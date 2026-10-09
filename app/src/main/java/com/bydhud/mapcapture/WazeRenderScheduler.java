package com.bydhud.mapcapture;

import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Queue;
import org.json.JSONObject;

/** Only our owned hidden target is eligible; unknown/visible hosts use Waze's loop. */
public final class WazeRenderScheduler {
    private static volatile State current;
    private static volatile String lastStats = "";
    private static volatile long intervalMs = 1000;
    private static volatile long leaseUntil;
    private static final class State {
        Object manager, renderer, thread, monitor;
        Queue<?> commands, events, callbacks;
        List<?> managers;
        Field ready, eventQueue, stopped;
        volatile boolean enabled = true, bootstrap = true;
        volatile long retryAt, checks, skips, attempts, frames, idleChecks, waits, wakes;
        volatile long renderNs, renderCpuNs, readbackNs;
        long beginNs, beginCpuNs;
        String id;
        volatile String reason = "attach", error = "";
    }
    private static Object get(Object owner, String name) throws Exception {
        return MapsFollowing.get(owner, null, name);
    }
    public static void attach(Object manager, Object thread) {
        detach();
        State s = new State();
        s.id = Integer.toHexString(System.identityHashCode(manager)) + "-" + SystemClock.elapsedRealtime();
        try {
            s.manager = manager; s.thread = thread; s.renderer = get(manager, "a");
            s.monitor = get(thread, "h"); s.commands = (Queue<?>)get(thread, "g");
            s.managers = (List<?>)get(thread, "e");
            s.events = (Queue<?>)get(manager, "d"); s.callbacks = (Queue<?>)get(manager, "f");
            s.ready = manager.getClass().getDeclaredField("g"); s.ready.setAccessible(true);
            s.eventQueue = manager.getClass().getDeclaredField("d"); s.eventQueue.setAccessible(true);
            s.stopped = thread.getClass().getDeclaredField("d"); s.stopped.setAccessible(true);
            current = s;
            wake(s, "attach");
        } catch (Exception error) {
            s.enabled = false; s.error = error.getClass().getSimpleName(); s.reason = "attach_failed";
            lastStats = summary(s);
            android.util.Log.w("BYDMapCapture", "Render scheduling unavailable", error);
        }
    }
    public static void detach() {
        State s = current; current = null;
        if (s != null) { s.enabled = false; wake(s, "detach"); lastStats = summary(s); }
    }
    public static void policy(boolean active, long requestedIntervalMs, long leaseMs) {
        long previous = intervalMs;
        intervalMs = Math.max(1, requestedIntervalMs);
        leaseUntil = active ? SystemClock.elapsedRealtime() + leaseMs : 0;
        if (!active) detach();
        else if (previous != intervalMs) wake(current, "policy");
    }
    public static void request() { wake(current, "capture"); }
    private static boolean eligible(State s) {
        return s != null && s.enabled && current == s && SystemClock.elapsedRealtime() < leaseUntil;
    }
    private static void wake(State s, String reason) {
        if (s == null) return;
        synchronized (s.monitor) {
            s.retryAt = 0; s.reason = reason; s.wakes++;
            s.monitor.notifyAll();
        }
    }
    public static void surfaceQueued(Object manager) {
        State s = current;
        if (s != null) {
            if (s.manager == manager) s.bootstrap = true;
            wake(s, "surface"); // Another manager can become visible on this render thread.
        }
    }
    public static void threadStopping(Object thread) {
        State s = current;
        if (s != null && s.thread == thread) wake(s, "thread_state");
    }
    public static void callbackQueued(Object callback) {
        State s = current;
        wake(s, "drawing_callback");
    }
    public static boolean allow(Object renderer) {
        State s = current;
        if (!eligible(s) || s.renderer != renderer) return true;
        s.checks++;
        long now = SystemClock.elapsedRealtime();
        if (!WazeRenderPolicy.admit(now, s.retryAt, s.bootstrap,
                CaptureBridge.hasWazeRequest(), !s.callbacks.isEmpty())) { s.skips++; return false; }
        s.retryAt = now + WazeRenderPolicy.retryDelay(intervalMs);
        return true;
    }
    public static void begin(Object renderer) {
        State s = current;
        if (!eligible(s) || s.renderer != renderer) return;
        s.attempts++; s.beginNs = SystemClock.elapsedRealtimeNanos();
        s.beginCpuNs = Debug.threadCpuTimeNanos();
    }
    public static void end(Object renderer) {
        State s = current;
        if (s == null || s.renderer != renderer || s.beginNs == 0) return;
        s.renderNs += SystemClock.elapsedRealtimeNanos() - s.beginNs;
        s.renderCpuNs += Math.max(0, Debug.threadCpuTimeNanos() - s.beginCpuNs);
        s.beginNs = 0;
    }
    public static void frame(Object renderer) {
        State s = current;
        if (s != null && s.renderer == renderer) { s.frames++; s.bootstrap = false; }
    }
    public static void readback(Object renderer, long ns) {
        State s = current;
        if (s != null && s.renderer == renderer) s.readbackNs += Math.max(0, ns);
    }
    /** Called inside Waze's existing monitor, replacing only its idle wait. */
    public static void await(Object monitor, Object thread) throws InterruptedException {
        State s = current;
        if (!eligible(s) || s.thread != thread || s.monitor != monitor) { monitor.wait(1); return; }
        try {
            s.idleChecks++;
            if (s.stopped.getBoolean(thread)) return;
            for (Object manager : s.managers) {
                if (manager == s.manager) continue;
                if (!s.ready.getDeclaringClass().isInstance(manager) || s.ready.getBoolean(manager)) {
                    monitor.wait(1); return;
                }
                if (!((Queue<?>)s.eventQueue.get(manager)).isEmpty()) return;
            }
            // Enqueuers notify this same monitor; recheck while holding it.
            if (!s.commands.isEmpty() || !s.events.isEmpty()) return;
            boolean due = s.bootstrap || CaptureBridge.hasWazeRequest() || !s.callbacks.isEmpty();
            long delay = WazeRenderPolicy.idleDelay(SystemClock.elapsedRealtime(), s.retryAt, due);
            s.waits++;
            monitor.wait(delay);
        } catch (IllegalAccessException | RuntimeException error) {
            disable(s, error); monitor.wait(1);
        }
    }
    private static void disable(State s, Exception error) {
        s.error = error.getClass().getSimpleName(); s.enabled = false;
        wake(s, "native_fallback");
        android.util.Log.w("BYDMapCapture", "Render scheduling reverted to native", error);
    }
    public static void metadata(Bundle data) {
        State s = current;
        String stats = s == null ? lastStats : summary(s);
        if (!stats.isEmpty()) data.putString("renderStats", stats);
    }
    private static String summary(State s) {
        try {
            return new JSONObject().put("id", s.id)
                    .put("active", eligible(s)).put("intervalMs", intervalMs)
                    .put("checks", s.checks).put("skips", s.skips).put("attempts", s.attempts)
                    .put("frames", s.frames).put("idleChecks", s.idleChecks)
                    .put("waits", s.waits).put("wakes", s.wakes)
                    .put("renderNs", s.renderNs).put("renderCpuNs", s.renderCpuNs)
                    .put("readbackNs", s.readbackNs).put("reason", s.reason).put("error", s.error).toString();
        } catch (org.json.JSONException ignored) { return ""; }
    }
}
