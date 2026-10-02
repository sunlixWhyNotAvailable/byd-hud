package com.bydhud.app;

//guards Waze parser freshness by moving debug disk writes off the capture/parser thread.

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONException;
import org.json.JSONObject;

final class WazeCaptureDebugWriter {
    private static final String TAG = "BydHudWazeDebugWriter";
    private static final int MAX_PENDING_BITMAPS = 4;
    private static final String SESSION_LOG = "session.jsonl";
    private static final Object INSTANCE_LOCK = new Object();

    private static WazeCaptureDebugWriter instance;
    private static volatile WazeCaptureDebugWriter mapInstance;
    private static final ThreadLocal<WazeCaptureDebugWriter> CURRENT = new ThreadLocal<>();

    private final HandlerThread thread;
    private final Handler handler;
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicInteger pendingBitmaps = new AtomicInteger();
    private final AtomicInteger droppedBitmaps = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong completedTasks = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> queuedAt = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final long startedAt = System.currentTimeMillis();
    private volatile long lastSuccessfulWriteAt;
    private volatile long lastCompletedTaskAt;
    private volatile long taskStartedAt;
    private volatile String currentTask = "";
    private volatile String lastError = "";

    static String currentOperation() {
        WazeCaptureDebugWriter writer = CURRENT.get();
        return writer == null ? Thread.currentThread().getName() : writer.currentTask;
    }

    static void recordWriteSuccess() {
        WazeCaptureDebugWriter writer = CURRENT.get();
        if (writer != null) writer.lastSuccessfulWriteAt = System.currentTimeMillis();
    }

    static void recordWriteFailure(String error) {
        WazeCaptureDebugWriter writer = CURRENT.get();
        if (writer != null) writer.failed(error);
    }

    static void recordMapArtifactFailure(String error) {
        // Bitmap copying can fail on the capture thread, before a writer task exists.
        mapFrames().failed(error);
    }

    private void failed(String error) {
        failures.incrementAndGet();
        lastError = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 512));
    }

    private JSONObject health() throws JSONException {
        Long oldest = queuedAt.peek();
        return new JSONObject().put("writer", thread.getName()).put("startedAtWallMs", startedAt)
                .put("completedTasks", completedTasks.get())
                .put("oldestQueuedAgeMs", oldest == null ? 0 : Math.max(0L, System.currentTimeMillis() - oldest))
                .put("alive", thread.isAlive()).put("pendingTasks", pendingTasks.get())
                .put("pendingBitmaps", pendingBitmaps.get()).put("droppedBitmaps", droppedBitmaps.get())
                .put("failures", failures.get()).put("lastError", lastError)
                .put("lastSuccessfulWriteAtWallMs", lastSuccessfulWriteAt)
                .put("lastCompletedTaskAtWallMs", lastCompletedTaskAt)
                .put("currentTask", currentTask).put("taskStartedAtWallMs", taskStartedAt);
    }

    // Called directly by the export worker, never queued behind a stalled writer.
    static JSONObject healthSnapshot() throws JSONException {
        WazeCaptureDebugWriter journal = get(), images = mapInstance;
        boolean loss = journal.failures.get() > 0 || journal.droppedBitmaps.get() > 0
                || (images != null && (images.failures.get() > 0 || images.droppedBitmaps.get() > 0));
        return new JSONObject().put("scope", "current_process")
                .put("sampledAtWallMs", System.currentTimeMillis()).put("lossObserved", loss)
                .put("journal", journal.health())
                .put("mapImages", images == null ? JSONObject.NULL : images.health())
                .put("storageLockOwners", NavigationLogStorage.lockDiagnostics())
                .put("completedStorageOperations", NavigationLogStorage.completedOperations());
    }

    private WazeCaptureDebugWriter(String name) {
        thread = new HandlerThread(name, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    static WazeCaptureDebugWriter get() {
        synchronized (INSTANCE_LOCK) {
            if (instance == null) {
                instance = new WazeCaptureDebugWriter("BydHudWazeDebugWriter");
            }
            return instance;
        }
    }

    static WazeCaptureDebugWriter mapFrames() {
        synchronized (INSTANCE_LOCK) {
            if (mapInstance == null) mapInstance = new WazeCaptureDebugWriter("BydHudMapImages");
            return mapInstance;
        }
    }

    int pendingTasks() {
        return pendingTasks.get();
    }

    int pendingBitmaps() {
        return pendingBitmaps.get();
    }

    boolean appendSessionLine(File dir, String line) {
        if (dir == null || line == null) {
            return false;
        }
        return post("session_jsonl", () -> NavigationLogStorage.withReadLock(
                () -> appendLine(dir, SESSION_LOG, line)));
    }

    boolean appendDirectLine(File dir, String fileName, String line) {
        if (dir == null || fileName == null || line == null) {
            return false;
        }
        return post("direct_session_event", () -> NavigationLogStorage.withReadLock(
                () -> appendLine(dir, fileName, line)));
    }

    boolean appendDirectRaw(File dir, String fileName, String line) {
        if (dir == null || fileName == null || line == null) {
            return false;
        }
        return post("direct_session_raw", () -> NavigationLogStorage.withReadLock(
                () -> appendLine(dir, fileName, line)));
    }

    boolean saveDirectArtifact(File dir, String fileName, byte[] bytes) {
        if (dir == null
                || fileName == null || fileName.isEmpty()
                || bytes == null || bytes.length == 0) {
            return false;
        }
        byte[] copy = bytes.clone();
        return directEvent(() -> NavigationLogStorage.withReadLock(
                () -> NavCaptureStore.writeDirectArtifactFileIfAbsent(
                        dir, fileName, copy)));
    }

    boolean endDirectSession(File dir, String fileName, String line) {
        if (dir == null || fileName == null || line == null) {
            return false;
        }
        return post("direct_session_end", () -> NavigationLogStorage.withReadLock(() -> {
            appendLine(dir, fileName, line);
            NavigationLogStorage.closeDirectSession(dir);
        }));
    }

    boolean rawEvent(Context context, String channel, String packageName, String payload) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            return false;
        }
        long eventElapsedMs = android.os.SystemClock.elapsedRealtime();
        long eventWallClockMs = System.currentTimeMillis();
        String targetDay = NavCaptureStore.todayDir(eventWallClockMs);
        return runOrPost("raw_nav_event",
                () -> NavCaptureStore.writeRawEvent(
                        app, channel, packageName, payload,
                        eventElapsedMs, eventWallClockMs, targetDay));
    }

    boolean snapshot(Context context, NavSnapshot snapshot) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null || snapshot == null) {
            return false;
        }
        long eventElapsedMs = android.os.SystemClock.elapsedRealtime();
        long eventWallClockMs = System.currentTimeMillis();
        String targetDay = NavCaptureStore.todayDir(eventWallClockMs);
        return runOrPost("nav_snapshot", () -> NavCaptureStore.writeSnapshot(
                app, snapshot, eventElapsedMs, eventWallClockMs, targetDay));
    }

    boolean directEvent(Runnable work) {
        if (work == null) return false;
        if (!tryReserveBitmap()) {
            droppedBitmaps.incrementAndGet();
            Log.w(TAG, "debug_writer_drop type=direct_event reason=bitmap_queue_full");
            return false;
        }
        boolean posted = post("direct_event", () -> {
            try {
                work.run();
            } finally {
                pendingBitmaps.decrementAndGet();
            }
        });
        if (!posted) {
            pendingBitmaps.decrementAndGet();
        }
        return posted;
    }

    boolean appEvent(Context context, String line) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null || line == null) return false;
        long eventWallClockMs = System.currentTimeMillis();
        String targetDay = NavCaptureStore.todayDir(eventWallClockMs);
        return runOrPost("app_event",
                () -> AppEventLogger.writeEvent(app, line, eventWallClockMs, targetDay));
    }

    boolean someIpTx(Runnable work) {
        return work != null && post("someip_tx", work);
    }

    //Waits for work queued before this call; share/retirement invoke it from background threads.
    boolean awaitIdle() {
        if (android.os.Looper.myLooper() == thread.getLooper()) {
            return true;
        }
        WazeCaptureDebugWriter images = mapInstance;
        if (this == instance && images != null && !images.awaitIdle()) return false;
        CountDownLatch idle = new CountDownLatch(1);
        if (!handler.post(idle::countDown)) {
            return false;
        }
        try {
            idle.await();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    //Waits only for work queued before this call, but lets cancellable share preparation move on.
    boolean awaitCheckpoint(long timeoutMs) {
        if (android.os.Looper.myLooper() == thread.getLooper()) {
            return true;
        }
        long started = android.os.SystemClock.elapsedRealtime();
        WazeCaptureDebugWriter images = mapInstance;
        CountDownLatch idle = new CountDownLatch(this == instance && images != null ? 2 : 1);
        // Enqueue BOTH markers now: new work arriving during the wait is not part of this checkpoint.
        if (!handler.post(idle::countDown)) return false;
        if (this == instance && images != null && !images.handler.post(idle::countDown)) return false;
        try { return idle.await(Math.max(0L, timeoutMs - (android.os.SystemClock.elapsedRealtime() - started)), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    static long completedTaskCount() {
        WazeCaptureDebugWriter images = mapInstance;
        return get().completedTasks.get() + (images == null ? 0 : images.completedTasks.get());
    }

    private boolean tryReserveBitmap() {
        while (true) {
            int current = pendingBitmaps.get();
            if (current >= MAX_PENDING_BITMAPS) {
                return false;
            }
            if (pendingBitmaps.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private boolean post(String type, Runnable work) {
        Long enqueuedAt = System.currentTimeMillis();
        queuedAt.add(enqueuedAt);
        pendingTasks.incrementAndGet();
        boolean posted = handler.post(() -> {
            CURRENT.set(this);
            currentTask = type;
            taskStartedAt = System.currentTimeMillis();
            try {
                work.run();
            } catch (RuntimeException e) {
                failed(type + ": " + e);
                Log.w(TAG, "debug_writer_failed type=" + type, e);
            } finally {
                lastCompletedTaskAt = System.currentTimeMillis();
                currentTask = "";
                taskStartedAt = 0L;
                CURRENT.remove();
                pendingTasks.decrementAndGet();
                queuedAt.remove(enqueuedAt);
                completedTasks.incrementAndGet();
            }
        });
        if (!posted) {
            queuedAt.remove(enqueuedAt);
            failed(type + ": handler_stopped");
            pendingTasks.decrementAndGet();
            Log.w(TAG, "debug_writer_drop type=" + type + " reason=handler_stopped");
        }
        return posted;
    }

    private boolean runOrPost(String type, Runnable work) {
        if (android.os.Looper.myLooper() == thread.getLooper()) {
            work.run();
            return true;
        }
        return post(type, work);
    }

    private static void appendLine(File dir, String fileName, String line) {
        if (dir == null || fileName == null || line == null) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            recordWriteFailure("session directory unavailable: " + dir);
            return;
        }
        File file = new File(dir, fileName);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException error) {
            recordWriteFailure("session append: " + error);
            return;
        }
        recordWriteSuccess();
    }

}
