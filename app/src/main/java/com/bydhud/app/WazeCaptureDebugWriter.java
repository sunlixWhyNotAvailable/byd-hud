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

final class WazeCaptureDebugWriter {
    private static final String TAG = "BydHudWazeDebugWriter";
    private static final int MAX_PENDING_BITMAPS = 4;
    private static final String SESSION_LOG = "session.jsonl";
    private static final Object INSTANCE_LOCK = new Object();

    private static WazeCaptureDebugWriter instance;

    private final HandlerThread thread;
    private final Handler handler;
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicInteger pendingBitmaps = new AtomicInteger();

    private WazeCaptureDebugWriter() {
        thread = new HandlerThread("BydHudWazeDebugWriter", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    static WazeCaptureDebugWriter get() {
        synchronized (INSTANCE_LOCK) {
            if (instance == null) {
                instance = new WazeCaptureDebugWriter();
            }
            return instance;
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
        if (work == null || !tryReserveBitmap()) {
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
        CountDownLatch idle = new CountDownLatch(1);
        if (!handler.post(idle::countDown)) {
            return false;
        }
        try {
            return idle.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        pendingTasks.incrementAndGet();
        boolean posted = handler.post(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "debug_writer_failed type=" + type, e);
            } finally {
                pendingTasks.decrementAndGet();
            }
        });
        if (!posted) {
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
            return;
        }
        File file = new File(dir, fileName);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException ignored) {
            //debug evidence must never block live navigation.
        }
    }

}
