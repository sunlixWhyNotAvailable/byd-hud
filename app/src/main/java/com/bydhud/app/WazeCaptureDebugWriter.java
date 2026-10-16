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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static volatile WazeCaptureDebugWriter routeInstance;
    private static final ThreadLocal<WazeCaptureDebugWriter> CURRENT = new ThreadLocal<>();

    private final HandlerThread thread;
    private final Handler handler;
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicInteger pendingBitmaps = new AtomicInteger();
    private final AtomicInteger droppedBitmaps = new AtomicInteger();
    private final AtomicInteger droppedRouteArtifacts = new AtomicInteger();
    private final NavigatorRouteArtifactQueue routeArtifactQueue =
            new NavigatorRouteArtifactQueue();
    private final Object routeSubmissionLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong completedTasks = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> queuedAt = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final long startedAt = System.currentTimeMillis();
    private volatile long lastSuccessfulWriteAt;
    private volatile long lastCompletedTaskAt;
    private volatile long taskStartedAt;
    private volatile String currentTask = "";
    private volatile String lastError = "";
    private volatile long lastCompletedElapsedMs;
    private volatile long taskStartedElapsedMs;
    private final RuntimeIncidentRecorder.Watch incidentWatch = new RuntimeIncidentRecorder.Watch("writer", 5_000L) {
        @Override boolean pending() { return pendingTasks.get() > 0; }
        @Override long token() { return completedTasks.get(); }
        @Override long since() {
            Long oldest = queuedAt.peek();
            return oldest == null ? -1L : Math.max(oldest, lastCompletedElapsedMs);
        }
        @Override JSONObject details() throws Exception {
            return health().put("thread", RuntimeIncidentRecorder.thread(thread))
                    .put("storageLockOwners", NavigationLogStorage.lockDiagnostics());
        }
    };

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
        RuntimeIncidentRecorder.loss(thread.getName(), lastError);
    }

    private JSONObject health() throws JSONException {
        Long oldest = queuedAt.peek();
        return new JSONObject().put("writer", thread.getName()).put("startedAtWallMs", startedAt)
                .put("completedTasks", completedTasks.get())
                .put("oldestQueuedAgeMs", oldest == null ? 0 : Math.max(0L, android.os.SystemClock.elapsedRealtime() - oldest))
                .put("currentTaskAgeMs", currentTask.isEmpty() ? 0 : Math.max(0L, android.os.SystemClock.elapsedRealtime() - taskStartedElapsedMs))
                .put("alive", thread.isAlive()).put("pendingTasks", pendingTasks.get())
                .put("pendingBitmaps", pendingBitmaps.get()).put("droppedBitmaps", droppedBitmaps.get())
                .put("pendingRouteArtifacts", routeArtifactQueue.retainedRoutes())
                .put("retainedRouteArtifactBytes", routeArtifactQueue.retainedBytes())
                .put("maxPendingRouteArtifacts",
                        NavigatorRouteArtifactQueue.MAX_RETAINED_ROUTES)
                .put("maxRetainedRouteArtifactBytes",
                        NavigatorRouteArtifactQueue.MAX_RETAINED_BYTES)
                .put("droppedRouteArtifacts", droppedRouteArtifacts.get())
                .put("failures", failures.get()).put("lastError", lastError)
                .put("lastSuccessfulWriteAtWallMs", lastSuccessfulWriteAt)
                .put("lastCompletedTaskAtWallMs", lastCompletedTaskAt)
                .put("currentTask", currentTask).put("taskStartedAtWallMs", taskStartedAt);
    }

    // Called directly by the export worker, never queued behind a stalled writer.
    static JSONObject healthSnapshot() throws JSONException {
        WazeCaptureDebugWriter journal = get(), images = mapInstance, routes = routeInstance;
        boolean loss = journal.failures.get() > 0 || journal.droppedBitmaps.get() > 0
                || journal.droppedRouteArtifacts.get() > 0
                || (images != null && (images.failures.get() > 0 || images.droppedBitmaps.get() > 0))
                || (routes != null && (routes.failures.get() > 0
                || routes.droppedRouteArtifacts.get() > 0));
        return new JSONObject().put("scope", "current_process")
                .put("incidentPersistenceUncertain", RuntimeIncidentRecorder.persistenceUncertain())
                .put("sampledAtWallMs", System.currentTimeMillis()).put("lossObserved", loss)
                .put("journal", journal.health())
                .put("mapImages", images == null ? JSONObject.NULL : images.health())
                .put("routeArtifacts", routes == null ? JSONObject.NULL : routes.health())
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

    private static WazeCaptureDebugWriter routeArtifacts() {
        synchronized (INSTANCE_LOCK) {
            if (routeInstance == null) {
                routeInstance = new WazeCaptureDebugWriter("BydHudNavigatorRouteWriter");
            }
            return routeInstance;
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
        return rawEvent(context, channel, packageName, payload,
                android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis());
    }

    boolean rawEvent(Context context, String channel, String packageName, String payload,
            long eventElapsedMs, long eventWallClockMs) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            return false;
        }
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

    boolean routeArtifact(Context context, String targetDay, NavigatorRouteData route) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null || route == null) return false;
        return routeArtifacts().enqueueRouteArtifact(app, targetDay, route);
    }

    private boolean enqueueRouteArtifact(Context context, String targetDay,
            NavigatorRouteData route) {
        NavigatorRouteArtifactQueue.Offer offer;
        java.util.List<NavigatorRouteArtifactQueue.Entry> failedEntries = null;
        synchronized (routeSubmissionLock) {
            offer = routeArtifactQueue.offer(context, targetDay, null, route);
            if (offer.entry != null) {
                if (post("navigator_route_artifact", this::writeNextRouteArtifact)) return true;
                failedEntries = routeArtifactQueue.failPending();
            }
        }
        if (failedEntries != null) {
            for (NavigatorRouteArtifactQueue.Entry entry : failedEntries) {
                recordRouteDrop(entry.context, entry.route, "writer_stopped");
            }
            return false;
        }
        if (!offer.accepted()) {
            recordRouteDrop(context, route, offer.rejectionReason);
            return false;
        }
        return offer.duplicate;
    }

    private void writeNextRouteArtifact() {
        NavigatorRouteArtifactQueue.Entry writing = routeArtifactQueue.take();
        if (writing == null) return;
        long waitMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - writing.enqueuedAtNanos));
        long writeStarted = System.nanoTime();
        boolean written = false;
        try {
            try {
                written = NavigationLogStorage.withReadLock(() ->
                        NavigatorRouteArtifact.write(writing.logsDir(), writing.route));
                if (written) {
                    recordWriteSuccess();
                } else {
                    routeArtifactFailed(writing.context, writing.route);
                }
            } catch (RuntimeException error) {
                routeArtifactFailed(writing.context, writing.route);
            }
        } finally {
            long writeMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - writeStarted));
            try {
                NavigatorRouteLog.artifactWrite(writing.context, writing.route,
                        waitMs, writeMs, written);
            } finally {
                routeArtifactQueue.complete(writing);
            }
        }
    }

    private void routeArtifactFailed(Context context, NavigatorRouteData route) {
        recordWriteFailure("navigator_route_artifact: write_failed");
        NavigatorRouteData.Header header = route.header();
        NavigatorRouteLog.artifactError(context, header.navigator,
                header.producerEpoch, header.routeRevision);
    }

    private void recordRouteDrop(Context context, NavigatorRouteData route, String reason) {
        droppedRouteArtifacts.incrementAndGet();
        RuntimeIncidentRecorder.loss(thread.getName(),
                "navigator_route_artifact: " + (reason == null ? "queue_rejected" : reason));
        NavigatorRouteLog.artifactRejected(context, route,
                reason == null ? "queue_rejected" : reason, droppedRouteArtifacts.get());
    }

    boolean directEvent(Runnable work) {
        if (work == null) return false;
        if (!tryReserveBitmap()) {
            droppedBitmaps.incrementAndGet();
            RuntimeIncidentRecorder.loss(thread.getName(), "direct_event: bitmap_queue_full");
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

    boolean settingsTask(Runnable work) {
        return work != null && runOrPost("user_settings", work);
    }

    boolean userSettings(Context context, String day, String line) {
        return settingsTask(() -> NavCaptureStore.writeUserSettings(context, day, line));
    }

    //Waits for work queued before this call; share/retirement invoke it from background threads.
    boolean awaitIdle() {
        if (android.os.Looper.myLooper() == thread.getLooper()) {
            return true;
        }
        CountDownLatch idle = new CountDownLatch(1);
        AtomicBoolean posted = new AtomicBoolean(true);
        if (!postOrderedBarrier(idle, posted)) return false;
        try {
            idle.await();
            return posted.get();
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
        CountDownLatch checkpoint = new CountDownLatch(1);
        AtomicBoolean posted = new AtomicBoolean(true);
        if (!postOrderedBarrier(checkpoint, posted)) return false;
        try {
            boolean completed = checkpoint.await(Math.max(0L, timeoutMs
                    - (android.os.SystemClock.elapsedRealtime() - started)),
                    TimeUnit.MILLISECONDS);
            return completed && posted.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean postRouteBarrier(Runnable barrier) {
        synchronized (routeSubmissionLock) {
            return handler.post(barrier);
        }
    }

    private boolean postOrderedBarrier(CountDownLatch completed, AtomicBoolean posted) {
        if (this != instance) {
            Runnable barrier = () -> completed.countDown();
            return this == routeInstance ? postRouteBarrier(barrier) : handler.post(barrier);
        }

        synchronized (INSTANCE_LOCK) {
            WazeCaptureDebugWriter images = mapInstance;
            WazeCaptureDebugWriter routes = routeInstance;
            Runnable journalBarrier = () -> {
                if (!handler.post(completed::countDown)) {
                    posted.set(false);
                    completed.countDown();
                }
            };
            Runnable imageBarrier = () -> {
                if (images == null) {
                    journalBarrier.run();
                } else if (!images.handler.post(journalBarrier)) {
                    posted.set(false);
                    completed.countDown();
                }
            };
            if (routes != null) return routes.postRouteBarrier(imageBarrier);
            if (images != null) return images.handler.post(journalBarrier);
            if (handler.post(completed::countDown)) return true;
            posted.set(false);
            completed.countDown();
            return false;
        }
    }

    static long completedTaskCount() {
        WazeCaptureDebugWriter images = mapInstance, routes = routeInstance;
        return get().completedTasks.get() + (images == null ? 0 : images.completedTasks.get())
                + (routes == null ? 0 : routes.completedTasks.get());
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
        Long enqueuedAt = android.os.SystemClock.elapsedRealtime();
        queuedAt.add(enqueuedAt);
        pendingTasks.incrementAndGet();
        RuntimeIncidentRecorder.watch(incidentWatch);
        boolean posted = handler.post(() -> {
            CURRENT.set(this);
            currentTask = type;
            taskStartedAt = System.currentTimeMillis();
            taskStartedElapsedMs = android.os.SystemClock.elapsedRealtime();
            try {
                work.run();
            } catch (RuntimeException e) {
                failed(type + ": " + e);
                Log.w(TAG, "debug_writer_failed type=" + type, e);
            } finally {
                lastCompletedTaskAt = System.currentTimeMillis();
                lastCompletedElapsedMs = android.os.SystemClock.elapsedRealtime();
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
