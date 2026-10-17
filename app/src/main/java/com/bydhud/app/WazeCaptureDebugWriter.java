package com.bydhud.app;

//guards Waze parser freshness by moving debug disk writes off the capture/parser thread.

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONException;
import org.json.JSONObject;

final class WazeCaptureDebugWriter {
    private static final String TAG = "BydHudWazeDebugWriter";
    private static final int MAX_PENDING_BITMAPS = 4;
    private static final int MAX_TEXT_BATCH_BYTES = 64 * 1024;
    private static final long MAX_TEXT_BATCH_FORMATION_MS = 20L;
    private static final String SESSION_LOG = "session.jsonl";
    private static final Object INSTANCE_LOCK = new Object();

    private static WazeCaptureDebugWriter instance;
    private static volatile WazeCaptureDebugWriter mapInstance;
    private static volatile WazeCaptureDebugWriter routeInstance;
    private static final ThreadLocal<WazeCaptureDebugWriter> CURRENT = new ThreadLocal<>();

    private final HandlerThread thread;
    private final Handler handler;
    private final Object taskQueueLock = new Object();
    private final ArrayDeque<WriterTask> taskQueue = new ArrayDeque<>();
    private boolean drainPosted;
    private final List<TextWrite> activeTextBatch = new ArrayList<>();
    private final List<WriterTask> activeTextTasks = new ArrayList<>();
    private int activeTextBatchBytes;
    private long activeTextBatchStartedElapsedMs;
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicInteger pendingBitmaps = new AtomicInteger();
    private final AtomicInteger droppedBitmaps = new AtomicInteger();
    private final AtomicInteger droppedRouteArtifacts = new AtomicInteger();
    private final NavigatorRouteArtifactQueue routeArtifactQueue =
            new NavigatorRouteArtifactQueue();
    private final Object routeSubmissionLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong completedTasks = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong completedTextBatches = new java.util.concurrent.atomic.AtomicLong();
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
    private volatile long lastTextBatchDurationMs;
    private volatile int lastTextBatchRecords;
    private volatile long lastTextBatchBytes;
    private volatile int lastTextBatchErrors;
    private volatile String lastTextBatchError = "";
    private List<TextWrite> collectingText;
    private WriterTask currentWriterTask;
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
    private final RuntimeIncidentRecorder.Watch backlogWatch = new RuntimeIncidentRecorder.Watch("writer_queue_age", 5_000L) {
        @Override boolean pending() { return pendingTasks.get() > 0; }
        @Override long token() {
            Long oldest = queuedAt.peek();
            return oldest != null && android.os.SystemClock.elapsedRealtime() - oldest >= 5_000L
                    ? 1L : 0L;
        }
        @Override long since() {
            Long oldest = queuedAt.peek();
            return oldest == null ? -1L : oldest;
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
                .put("completedTextBatches", completedTextBatches.get())
                .put("lastTextBatchRecords", lastTextBatchRecords)
                .put("lastTextBatchBytes", lastTextBatchBytes)
                .put("lastTextBatchDurationMs", lastTextBatchDurationMs)
                .put("lastTextBatchErrors", lastTextBatchErrors)
                .put("lastTextBatchError", lastTextBatchError)
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

    private enum TextKind { APP_EVENT, NAV_CAPTURE, DIRECT }

    private static final class TextWrite {
        final TextKind kind;
        final Context context;
        final String day;
        final File directory;
        final String fileName;
        final String line;
        final int byteCount;
        WriterTask owner;

        TextWrite(TextKind kind, Context context, String day, File directory,
                String fileName, String line) {
            this.kind = kind;
            this.context = context;
            this.day = day;
            this.directory = directory;
            this.fileName = fileName;
            this.line = line;
            this.byteCount = JournalTextBatch.byteCount(line);
        }
    }

    private static final class WriterTask {
        final String type;
        final Runnable work;
        final TextWrite text;
        final Runnable after;
        final boolean batchable;
        final boolean tracked;
        final boolean flushAfter;
        final long enqueuedAt;
        final CountDownLatch completed;
        final AtomicBoolean result;
        int remainingText;
        boolean failed;

        WriterTask(String type, Runnable work, TextWrite text, Runnable after,
                boolean batchable, boolean tracked, boolean flushAfter,
                long enqueuedAt, CountDownLatch completed, AtomicBoolean result) {
            this.type = type;
            this.work = work;
            this.text = text;
            this.after = after;
            this.batchable = batchable;
            this.tracked = tracked;
            this.flushAfter = flushAfter;
            this.enqueuedAt = enqueuedAt;
            this.completed = completed;
            this.result = result;
        }

        boolean isTextTask() { return text != null || batchable; }
    }

    private static final class TextGroupKey {
        final TextKind kind;
        final Context context;
        final String day;
        final File directory;
        final String fileName;

        TextGroupKey(TextWrite write) {
            kind = write.kind;
            context = write.context;
            day = write.day;
            directory = write.directory;
            fileName = write.fileName;
        }

        @Override public int hashCode() {
            int result = kind.hashCode();
            result = 31 * result + System.identityHashCode(context);
            result = 31 * result + (day == null ? 0 : day.hashCode());
            result = 31 * result + (directory == null ? 0 : directory.hashCode());
            result = 31 * result + fileName.hashCode();
            return result;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof TextGroupKey)) return false;
            TextGroupKey that = (TextGroupKey) other;
            return kind == that.kind && context == that.context
                    && equal(day, that.day) && equal(directory, that.directory)
                    && fileName.equals(that.fileName);
        }

        private static boolean equal(Object left, Object right) {
            return left == null ? right == null : left.equals(right);
        }
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
        return postText("session_jsonl", new TextWrite(
                TextKind.DIRECT, null, null, dir, SESSION_LOG, line + '\n'), null, false);
    }

    boolean appendDirectLine(File dir, String fileName, String line) {
        if (dir == null || fileName == null || line == null) {
            return false;
        }
        return postText("direct_session_event", new TextWrite(
                TextKind.DIRECT, null, null, dir, fileName, line + '\n'), null, false);
    }

    boolean appendDirectRaw(File dir, String fileName, String line) {
        if (dir == null || fileName == null || line == null) {
            return false;
        }
        return postText("direct_session_raw", new TextWrite(
                TextKind.DIRECT, null, null, dir, fileName, line + '\n'), null, false);
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
        return postText("direct_session_end", new TextWrite(
                TextKind.DIRECT, null, null, dir, fileName, line + '\n'),
                () -> NavigationLogStorage.closeDirectSession(dir), true);
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
        return postBatchable("raw_nav_event",
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
        return postBatchable("nav_snapshot", () -> NavCaptureStore.writeSnapshot(
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
        return postText("app_event", new TextWrite(TextKind.APP_EVENT, app,
                targetDay, null, AppEventLogger.EVENTS_FILE,
                AppEventLogger.queuedEvent(line, eventWallClockMs)), null, false);
    }

    boolean someIpTx(Runnable work) {
        return work != null && postBatchable("someip_tx", work);
    }

    boolean settingsTask(Runnable work) {
        return work != null && runOrPost("user_settings", work);
    }

    boolean userSettings(Context context, String day, String line) {
        Context app = context == null ? null : context.getApplicationContext();
        return app != null && day != null && line != null
                && postText("user_settings", new TextWrite(TextKind.NAV_CAPTURE,
                app, day, null, UserSettingsDiagnostics.FILE_NAME, line + '\n'), null, false);
    }

    boolean writeEventSynchronously(Context context, String line, long wallClockMs,
            String targetDay) {
        if (context == null || line == null || targetDay == null) return false;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        if (android.os.Looper.myLooper() == thread.getLooper()) {
            flushBeforeSynchronousEvent();
            boolean written = AppEventLogger.writeEventNow(app, line, wallClockMs, targetDay);
            if (!written && currentWriterTask != null) currentWriterTask.failed = true;
            return written;
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        TextWrite text = new TextWrite(TextKind.APP_EVENT, app, targetDay,
                null, AppEventLogger.EVENTS_FILE,
                AppEventLogger.queuedEvent(line, wallClockMs));
        if (!postText("app_event_sync", text, null, true, completed, result)) return false;
        boolean interrupted = false;
        try {
            // The queued write must finish before its lifecycle cursor can be acknowledged.
            while (true) {
                try {
                    completed.await();
                    return result.get();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
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

    private boolean postRouteBarrier(Runnable barrier, CountDownLatch completed,
            AtomicBoolean posted, long failureBaseline) {
        synchronized (routeSubmissionLock) {
            return postControlAtBaseline("route_barrier", barrier,
                    completed, posted, failureBaseline);
        }
    }

    private boolean postOrderedBarrier(CountDownLatch completed, AtomicBoolean posted) {
        if (this != instance) {
            Runnable barrier = () -> {
                completed.countDown();
            };
            return this == routeInstance
                    ? postRouteBarrier(barrier, completed, posted, failures.get())
                    : postControl("writer_barrier", barrier, completed, posted);
        }

        synchronized (INSTANCE_LOCK) {
            WazeCaptureDebugWriter images = mapInstance;
            WazeCaptureDebugWriter routes = routeInstance;
            long journalFailureBaseline = failures.get();
            long imageFailureBaseline = images == null ? -1L : images.failures.get();
            long routeFailureBaseline = routes == null ? -1L : routes.failures.get();
            Runnable journalBarrier = () -> {
                if (!postControlAtBaseline("journal_barrier", completed::countDown,
                        completed, posted, journalFailureBaseline)) {
                    posted.set(false);
                    completed.countDown();
                }
            };
            Runnable imageBarrier = () -> {
                if (images == null) {
                    journalBarrier.run();
                } else if (!images.postControlAtBaseline("image_barrier", journalBarrier,
                        completed, posted, imageFailureBaseline)) {
                    posted.set(false);
                    completed.countDown();
                }
            };
            if (routes != null) return routes.postRouteBarrier(
                    imageBarrier, completed, posted, routeFailureBaseline);
            if (images != null) return images.postControlAtBaseline(
                    "image_barrier", journalBarrier, completed, posted, imageFailureBaseline);
            if (postControlAtBaseline("journal_barrier", completed::countDown,
                    completed, posted, journalFailureBaseline)) return true;
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
        return enqueue(new WriterTask(type, work, null, null,
                false, true, false, android.os.SystemClock.elapsedRealtime(),
                null, null));
    }

    private boolean postBatchable(String type, Runnable work) {
        return enqueue(new WriterTask(type, work, null, null,
                true, true, false, android.os.SystemClock.elapsedRealtime(),
                null, null));
    }

    private boolean postText(String type, TextWrite text, Runnable after, boolean flushAfter) {
        return postText(type, text, after, flushAfter, null, null);
    }

    private boolean postText(String type, TextWrite text, Runnable after, boolean flushAfter,
            CountDownLatch completed, AtomicBoolean result) {
        return enqueue(new WriterTask(type, null, text, after,
                false, true, flushAfter, android.os.SystemClock.elapsedRealtime(),
                completed, result));
    }

    private boolean postControl(String type, Runnable work, CountDownLatch completed,
            AtomicBoolean result) {
        return postControlAtBaseline(type, work, completed, result, failures.get());
    }

    private boolean postControlAtBaseline(String type, Runnable work,
            CountDownLatch completed, AtomicBoolean result, long baseline) {
        Runnable check = () -> {
            if (result != null && failures.get() != baseline) result.set(false);
            try {
                if (work != null) work.run();
            } catch (RuntimeException error) {
                if (result != null) result.set(false);
                failed(type + ": " + error);
                Log.w(TAG, "debug_writer_failed type=" + type, error);
                if (completed != null) completed.countDown();
            }
        };
        boolean accepted = enqueue(new WriterTask(type, check, null, null,
                false, false, false, android.os.SystemClock.elapsedRealtime(),
                null, null));
        if (!accepted) {
            if (result != null) result.set(false);
            if (completed != null) completed.countDown();
        }
        return accepted;
    }

    private boolean enqueue(WriterTask task) {
        List<WriterTask> abandoned = null;
        synchronized (taskQueueLock) {
            if (task.tracked) {
                queuedAt.add(task.enqueuedAt);
                pendingTasks.incrementAndGet();
                RuntimeIncidentRecorder.watch(incidentWatch);
                RuntimeIncidentRecorder.watch(backlogWatch);
            }
            taskQueue.addLast(task);
            if (!drainPosted) {
                drainPosted = true;
                if (!handler.post(this::drainQueue)) {
                    drainPosted = false;
                    abandoned = new ArrayList<>(taskQueue);
                    taskQueue.clear();
                }
            }
        }
        if (abandoned == null) return true;
        for (WriterTask queued : abandoned) abandon(queued);
        return false;
    }

    private void abandon(WriterTask task) {
        failed(task.type + ": handler_stopped");
        if (task.tracked) {
            queuedAt.remove(task.enqueuedAt);
            pendingTasks.decrementAndGet();
        }
        if (task.result != null) task.result.set(false);
        if (task.completed != null) task.completed.countDown();
        Log.w(TAG, "debug_writer_drop type=" + task.type + " reason=handler_stopped");
    }

    private void drainQueue() {
        CURRENT.set(this);
        try {
            while (true) {
                WriterTask next;
                synchronized (taskQueueLock) {
                    next = taskQueue.peekFirst();
                    if (next == null && activeTextTasks.isEmpty()) {
                        drainPosted = false;
                        return;
                    }
                }
                if (next == null) {
                    flushTextBatch();
                    continue;
                }
                long now = android.os.SystemClock.elapsedRealtime();
                boolean formationExpired = !activeTextTasks.isEmpty()
                        && now - activeTextBatchStartedElapsedMs >= MAX_TEXT_BATCH_FORMATION_MS;
                if (!activeTextTasks.isEmpty()
                        && (!next.isTextTask() || formationExpired
                        || activeTextBatchBytes >= MAX_TEXT_BATCH_BYTES)) {
                    flushTextBatch();
                    continue;
                }
                synchronized (taskQueueLock) {
                    next = taskQueue.pollFirst();
                }
                if (next == null) continue;
                if (next.isTextTask()) {
                    processTextTask(next);
                    if (next.flushAfter) flushTextBatch();
                } else {
                    executeStandalone(next);
                }
            }
        } finally {
            collectingText = null;
            currentWriterTask = null;
            currentTask = "";
            taskStartedAt = 0L;
            taskStartedElapsedMs = 0L;
            CURRENT.remove();
        }
    }

    private void processTextTask(WriterTask task) {
        if (activeTextTasks.isEmpty() || activeTextBatchStartedElapsedMs == 0L) {
            activeTextBatchStartedElapsedMs = android.os.SystemClock.elapsedRealtime();
        }
        activeTextTasks.add(task);
        task.remainingText = task.text == null ? -1 : 1;
        List<TextWrite> produced = null;
        setCurrentTask(task.type);
        currentWriterTask = task;
        try {
            if (task.text != null) {
                addTextWrite(task.text, task);
            } else {
                produced = new ArrayList<>();
                collectingText = produced;
                if (task.work != null) task.work.run();
            }
        } catch (RuntimeException error) {
            task.failed = true;
            failed(task.type + ": " + error);
            Log.w(TAG, "debug_writer_failed type=" + task.type, error);
        } finally {
            collectingText = null;
            currentWriterTask = null;
            clearCurrentTask();
        }
        if (produced != null) {
            task.remainingText = produced.size();
            for (TextWrite write : produced) addTextWrite(write, task);
        }
    }

    private void addTextWrite(TextWrite write, WriterTask owner) {
        write.owner = owner;
        if (!activeTextBatch.isEmpty()
                && activeTextBatchBytes + write.byteCount > MAX_TEXT_BATCH_BYTES) {
            flushTextBatch();
            if (activeTextBatchStartedElapsedMs == 0L) {
                activeTextBatchStartedElapsedMs = android.os.SystemClock.elapsedRealtime();
            }
        }
        activeTextBatch.add(write);
        activeTextBatchBytes += write.byteCount;
        if (activeTextBatchBytes >= MAX_TEXT_BATCH_BYTES) flushTextBatch();
    }

    private void executeStandalone(WriterTask task) {
        setCurrentTask(task.type);
        boolean success = true;
        try {
            if (task.work != null) task.work.run();
        } catch (RuntimeException error) {
            success = false;
            task.failed = true;
            failed(task.type + ": " + error);
            Log.w(TAG, "debug_writer_failed type=" + task.type, error);
        } finally {
            clearCurrentTask();
            finishTask(task, success);
        }
    }

    private void flushTextBatch() {
        if (activeTextBatch.isEmpty()) {
            finishReadyTextTasks();
            if (activeTextTasks.isEmpty()) activeTextBatchStartedElapsedMs = 0L;
            return;
        }
        long startedNanos = System.nanoTime();
        int records = activeTextBatch.size();
        long bytes = activeTextBatchBytes;
        int errors = 0;
        String batchError = "";
        LinkedHashMap<TextGroupKey, List<TextWrite>> groups = new LinkedHashMap<>();
        for (TextWrite write : activeTextBatch) {
            TextGroupKey key = new TextGroupKey(write);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(write);
        }
        setCurrentTask("journal_text_batch");
        try {
            for (Map.Entry<TextGroupKey, List<TextWrite>> entry : groups.entrySet()) {
                int failuresBefore = failures.get();
                boolean written = false;
                try {
                    written = appendTextGroup(entry.getKey(), entry.getValue());
                } catch (RuntimeException error) {
                    recordWriteFailure("journal batch " + entry.getKey().fileName + ": " + error);
                    Log.e(TAG, "journal batch failed " + entry.getKey().fileName, error);
                }
                if (!written) {
                    errors++;
                    batchError = lastError.isEmpty()
                            ? "journal batch failed: " + entry.getKey().fileName : lastError;
                    if (failures.get() == failuresBefore) {
                        recordWriteFailure("journal batch failed: " + entry.getKey().fileName);
                    }
                }
                for (TextWrite write : entry.getValue()) {
                    if (!written) write.owner.failed = true;
                    write.owner.remainingText--;
                }
            }
        } finally {
            activeTextBatch.clear();
            activeTextBatchBytes = 0;
            activeTextBatchStartedElapsedMs = 0L;
            lastTextBatchDurationMs = Math.max(0L,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            lastTextBatchRecords = records;
            lastTextBatchBytes = bytes;
            lastTextBatchErrors = errors;
            lastTextBatchError = batchError;
            completedTextBatches.incrementAndGet();
            clearCurrentTask();
            finishReadyTextTasks();
        }
    }

    private boolean appendTextGroup(TextGroupKey key, List<TextWrite> writes) {
        List<String> lines = new ArrayList<>(writes.size());
        for (TextWrite write : writes) lines.add(write.line);
        switch (key.kind) {
            case APP_EVENT:
                // Batch writers split at the first record that crosses the existing file limit.
                return AppEventLogger.writeBatch(key.context, key.day, lines);
            case NAV_CAPTURE:
                return NavCaptureStore.writeBatch(key.context, key.day, key.fileName, lines);
            case DIRECT:
                boolean appended = NavigationLogStorage.withReadLock(() -> {
                    try {
                        JournalTextBatch.append(new File(key.directory, key.fileName), lines);
                        return true;
                    } catch (IOException error) {
                        recordWriteFailure("direct session append: " + error);
                        Log.e(TAG, "direct session append failed " + key.fileName, error);
                        return false;
                    }
                });
                if (appended) recordWriteSuccess();
                return appended;
            default:
                return false;
        }
    }

    private void finishReadyTextTasks() {
        java.util.Iterator<WriterTask> iterator = activeTextTasks.iterator();
        while (iterator.hasNext()) {
            WriterTask task = iterator.next();
            if (task.remainingText != 0) continue;
            iterator.remove();
            finishTask(task, !task.failed);
        }
    }

    private void finishTask(WriterTask task, boolean success) {
        if (task.after != null) {
            try {
                task.after.run();
            } catch (RuntimeException error) {
                success = false;
                task.failed = true;
                failed(task.type + " completion: " + error);
                Log.w(TAG, "debug_writer_failed type=" + task.type + " completion", error);
            }
        }
        success &= !task.failed;
        lastCompletedTaskAt = System.currentTimeMillis();
        lastCompletedElapsedMs = android.os.SystemClock.elapsedRealtime();
        if (task.tracked) {
            pendingTasks.decrementAndGet();
            queuedAt.remove(task.enqueuedAt);
            if (success) completedTasks.incrementAndGet();
        }
        if (task.result != null) {
            if (task.tracked || !success) task.result.set(success);
        }
        if (task.completed != null) task.completed.countDown();
    }

    private void setCurrentTask(String type) {
        currentTask = type;
        taskStartedAt = System.currentTimeMillis();
        taskStartedElapsedMs = android.os.SystemClock.elapsedRealtime();
    }

    private void clearCurrentTask() {
        currentTask = "";
        taskStartedAt = 0L;
        taskStartedElapsedMs = 0L;
    }

    static boolean captureNavigationText(Context context, String day,
            String fileName, String line) {
        WazeCaptureDebugWriter writer = CURRENT.get();
        if (writer == null || writer.collectingText == null
                || context == null || day == null || fileName == null || line == null) {
            return false;
        }
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        writer.collectingText.add(new TextWrite(TextKind.NAV_CAPTURE,
                app, day, null, fileName, line + '\n'));
        return true;
    }

    private void flushBeforeSynchronousEvent() {
        WriterTask owner = currentWriterTask;
        flushTextBatch();
        if (collectingText == null || collectingText.isEmpty()) return;
        List<TextWrite> prefix = new ArrayList<>(collectingText);
        collectingText.clear();
        if (owner != null) owner.remainingText = prefix.size() + 1;
        for (TextWrite write : prefix) addTextWrite(write, owner);
        flushTextBatch();
        if (owner != null) owner.remainingText = -1;
    }

    private boolean runOrPost(String type, Runnable work) {
        if (android.os.Looper.myLooper() == thread.getLooper()) {
            work.run();
            return true;
        }
        return post(type, work);
    }

}
