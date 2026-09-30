package com.bydhud.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/** Receives bounded live map frames from the selected patched navigator. */
public final class NavigatorMapCapture {
    static final String WAZE_PACKAGE = "com.waze";
    static final String MAPS_PACKAGE = GMapsDirectChannel.PACKAGE_NAME;

    private static final Object LOCK = new Object();
    private static final long MIN_REQUEST_INTERVAL_MS = 1000L;
    private static final long POST_STOP_JOURNAL_MS = 10000L;
    private static final long MAX_BITMAP_BYTES = 512L * 1024L;
    private static final int MAX_BITMAP_SIDE = 320;
    private static final byte[] EMPTY_PNG = new byte[0];
    private static final NavigatorMapSessionState FRAME = new NavigatorMapSessionState();

    private static Context appContext;
    private static HandlerThread workerThread;
    private static Handler worker;
    private static Runnable expiryRunnable;
    private static Runnable onFrameChanged;
    private static Snapshot cachedSnapshot;
    private static String ownerPackage = "";
    private static long ownerGeneration = -1L;
    private static String session = "";
    private static String stoppedOwnerPackage = "";
    private static String stoppedSession = "";
    private static long stoppedAtElapsedMs;
    private static long requestSerial;
    private static long pendingId;
    private static long pendingAtElapsedMs;
    private static long nextRequestAtElapsedMs;
    private static long processingId;
    private static long lastRejectedLogAtElapsedMs;
    private static String lastProducerState = "";

    private NavigatorMapCapture() {
    }

    /** Immutable receiver view. PNG access returns a defensive copy. */
    public static final class Snapshot {
        private final byte[] png;
        private final long revision;
        private final long receivedAtElapsedMs;

        Snapshot(byte[] png, long revision, long receivedAtElapsedMs) {
            this.png = png == null || png.length == 0
                    ? EMPTY_PNG
                    : Arrays.copyOf(png, png.length);
            this.revision = revision;
            this.receivedAtElapsedMs = receivedAtElapsedMs;
        }

        public byte[] png() {
            return png.length == 0 ? EMPTY_PNG : Arrays.copyOf(png, png.length);
        }

        public long revision() {
            return revision;
        }

        public long receivedAtElapsedMs() {
            return receivedAtElapsedMs;
        }
    }

    public static void activate(
            Context context,
            String selectedOwnerPackage,
            long selectedOwnerGeneration,
            Runnable frameChanged) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null || !isSupportedPackage(selectedOwnerPackage)) {
            stop("invalid_owner");
            log(app, "navigator_map_capture activation_rejected owner="
                    + field(selectedOwnerPackage, 96));
            return;
        }

        String nextSession = UUID.randomUUID().toString();
        String replacedOwner = "";
        String replacedSession = "";
        boolean changed;
        Runnable notify;
        synchronized (LOCK) {
            appContext = app;
            if (ownerPackage.equals(selectedOwnerPackage)
                    && ownerGeneration == selectedOwnerGeneration
                    && !session.isEmpty()) {
                onFrameChanged = frameChanged;
                ensureWorkerLocked();
                return;
            }
            if (!session.isEmpty()) {
                replacedOwner = ownerPackage;
                replacedSession = session;
                rememberStoppedLocked();
            }
            ownerPackage = selectedOwnerPackage;
            ownerGeneration = selectedOwnerGeneration;
            session = nextSession;
            pendingId = 0L;
            pendingAtElapsedMs = 0L;
            nextRequestAtElapsedMs = 0L;
            processingId = 0L;
            lastProducerState = "";
            onFrameChanged = frameChanged;
            changed = FRAME.activate(ownerPackage, ownerGeneration, session);
            cachedSnapshot = null;
            cancelExpiryLocked();
            ensureWorkerLocked();
            notify = changed ? onFrameChanged : null;
        }
        if (!replacedSession.isEmpty()) {
            log(app, "navigator_map_capture replaced owner=" + field(replacedOwner, 96)
                    + " session=" + replacedSession);
        }
        log(app, "navigator_map_capture started owner=" + field(selectedOwnerPackage, 96)
                + " generation=" + selectedOwnerGeneration + " session=" + nextSession);
        runCallback(notify, app);
    }

    public static void stop(String reason) {
        Context app;
        HandlerThread threadToStop;
        Runnable notify;
        String stoppedPackage;
        String stoppedToken;
        long stoppedGeneration;
        boolean changed;
        synchronized (LOCK) {
            app = appContext;
            if (session.isEmpty()) {
                return;
            }
            stoppedPackage = ownerPackage;
            stoppedToken = session;
            stoppedGeneration = ownerGeneration;
            rememberStoppedLocked();
            changed = FRAME.stop();
            ownerPackage = "";
            ownerGeneration = -1L;
            session = "";
            pendingId = 0L;
            pendingAtElapsedMs = 0L;
            nextRequestAtElapsedMs = 0L;
            processingId = 0L;
            lastProducerState = "";
            notify = changed ? onFrameChanged : null;
            onFrameChanged = null;
            cancelExpiryLocked();
            threadToStop = workerThread;
            workerThread = null;
            worker = null;
        }
        if (threadToStop != null) {
            threadToStop.quitSafely();
        }
        log(app, "navigator_map_capture stopped owner=" + field(stoppedPackage, 96)
                + " generation=" + stoppedGeneration + " session=" + stoppedToken
                + " reason=" + field(reason, 96));
        runCallback(notify, app);
    }

    public static Snapshot snapshot() {
        Runnable notify = null;
        Context app = null;
        boolean expired;
        Snapshot result;
        long revision;
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            expired = FRAME.expireIfStale(now);
            if (expired) {
                cancelExpiryLocked();
                cachedSnapshot = null;
                notify = onFrameChanged;
                app = appContext;
            }
            revision = FRAME.revision();
            long receivedAt = FRAME.receivedAtElapsedMs();
            if (cachedSnapshot == null
                    || cachedSnapshot.revision() != revision
                    || cachedSnapshot.receivedAtElapsedMs() != receivedAt) {
                cachedSnapshot = new Snapshot(FRAME.pngForSnapshot(), revision, receivedAt);
            }
            result = cachedSnapshot;
        }
        if (expired) {
            log(app, "navigator_map_capture frame_expired revision=" + revision);
            runCallback(notify, app);
        }
        return result;
    }

    static void initialize(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app != null) {
            synchronized (LOCK) {
                if (appContext == null) {
                    appContext = app;
                }
            }
        }
    }

    static Bundle providerCall(Context context, String method, Bundle data, int callingUid) {
        Bundle answer = new Bundle();
        if (context == null || data == null
                || (!"poll".equals(method) && !"result".equals(method))) {
            return answer;
        }
        Context app = context.getApplicationContext();
        String callerPackage = string(data, "package", "");
        if (!isSupportedPackage(callerPackage) || !uidOwnsPackage(app, callingUid, callerPackage)) {
            logRejectedCaller(app, callingUid, callerPackage);
            return answer;
        }
        if ("poll".equals(method)) {
            return poll(app, data, callerPackage, answer);
        }
        return receiveResult(app, data, callerPackage, answer);
    }

    private static Bundle poll(Context app, Bundle data, String callerPackage, Bundle answer) {
        long now = SystemClock.elapsedRealtime();
        String lifeEvents = string(data, "lifecycleEvents", "[]");
        String logRequest = null;
        String timeoutLog = null;
        String producerStateLog = null;
        String lifeSession = "";
        boolean acceptLifecycle = false;
        synchronized (LOCK) {
            if (!session.isEmpty()
                    && callerPackage.equals(ownerPackage)
                    && FRAME.isCurrent(ownerPackage, ownerGeneration, session)) {
                answer.putBoolean("active", true);
                answer.putString("session", session);
                if (!"[]".equals(lifeEvents) && !lifeEvents.isEmpty()) {
                    answer.putBoolean("journalAccepted", true);
                    acceptLifecycle = true;
                    lifeSession = session;
                }
                String producerState = "build=" + field(string(data, "build", ""), 96)
                        + " revision=" + field(string(data, "revision", ""), 64)
                        + " pid=" + integer(data, "pid", 0)
                        + " controller=" + field(string(data, "controller", ""), 160)
                        + " producerError=" + field(string(data, "producerError", ""), 384)
                        + " backgroundState=" + field(string(data, "backgroundState", ""), 96)
                        + " carReadiness=" + field(string(data, "carReadiness", ""), 192);
                if (!producerState.equals(lastProducerState)) {
                    lastProducerState = producerState;
                    producerStateLog = "navigator_map_capture producer_state owner="
                            + field(ownerPackage, 96) + " session=" + session + " " + producerState;
                }
                if (pendingId != 0L && now - pendingAtElapsedMs >= NavigatorMapSessionState.FRESHNESS_MS) {
                    timeoutLog = "navigator_map_capture request_timeout owner="
                            + field(ownerPackage, 96) + " id=" + pendingId;
                    pendingId = 0L;
                    pendingAtElapsedMs = 0L;
                }
                boolean busy = booleanValue(data, "busy", false);
                if (pendingId == 0L && processingId == 0L && !busy
                        && now >= nextRequestAtElapsedMs) {
                    pendingId = nextRequestIdLocked();
                    pendingAtElapsedMs = now;
                    nextRequestAtElapsedMs = now + MIN_REQUEST_INTERVAL_MS;
                    answer.putLong("id", pendingId);
                    answer.putLong("requestNs", SystemClock.elapsedRealtimeNanos());
                    logRequest = "navigator_map_capture request owner="
                            + field(ownerPackage, 96) + " session=" + session + " id=" + pendingId;
                }
            } else if (callerPackage.equals(stoppedOwnerPackage)
                    && string(data, "captureSession", "").equals(stoppedSession)
                    && !stoppedSession.isEmpty()
                    && now - stoppedAtElapsedMs < POST_STOP_JOURNAL_MS) {
                answer.putBoolean("journalAccepted", true);
                acceptLifecycle = true;
                lifeSession = stoppedSession;
            }
        }
        if (timeoutLog != null) {
            log(app, timeoutLog);
        }
        if (producerStateLog != null) {
            log(app, producerStateLog);
        }
        if (logRequest != null) {
            log(app, logRequest);
        }
        if (acceptLifecycle) {
            logLifecycleEvents(app, callerPackage, lifeSession, lifeEvents);
        }
        return answer;
    }

    private static Bundle receiveResult(
            Context app,
            Bundle data,
            String callerPackage,
            Bundle answer) {
        long receivedAt = SystemClock.elapsedRealtime();
        long id = longValue(data, "id", 0L);
        String resultSession = string(data, "session", "");
        long generation;
        Handler receiverWorker;
        boolean rejected;
        synchronized (LOCK) {
            rejected = session.isEmpty()
                    || !callerPackage.equals(ownerPackage)
                    || !FRAME.isCurrent(ownerPackage, ownerGeneration, session)
                    || !session.equals(resultSession)
                    || id == 0L || id != pendingId || processingId != 0L;
            if (!rejected) {
                pendingId = 0L;
                pendingAtElapsedMs = 0L;
                processingId = id;
                generation = ownerGeneration;
                receiverWorker = worker;
            } else {
                generation = -1L;
                receiverWorker = null;
            }
        }
        if (rejected) {
            log(app, "navigator_map_capture result_rejected owner="
                    + field(callerPackage, 96) + " session=" + field(resultSession, 96)
                    + " id=" + id + " reason=stale_session_or_request");
            return answer;
        }

        String status = string(data, "status", "missing_status");
        Bitmap bitmap = bitmap(data);
        String invalidReason = validateBitmap(bitmap, status);
        answer.putBoolean("accepted", true);
        if (invalidReason != null) {
            recycle(bitmap);
            finishProcessing(resultSession, id);
            logResultError(app, callerPackage, resultSession, id, status, invalidReason, data);
            return answer;
        }
        Bitmap acceptedBitmap = bitmap;
        if (receiverWorker == null || !receiverWorker.post(() -> processFrame(
                app, callerPackage, generation, resultSession, id, receivedAt,
                status, data, acceptedBitmap))) {
            recycle(acceptedBitmap);
            finishProcessing(resultSession, id);
            logResultError(app, callerPackage, resultSession, id, status, "receiver_stopped", data);
        }
        return answer;
    }

    private static void processFrame(
            Context app,
            String callerPackage,
            long generation,
            String resultSession,
            long id,
            long receivedAt,
            String status,
            Bundle data,
            Bitmap bitmap) {
        try {
            String inputHash = pixelHash(bitmap);
            NavigatorMapSessionState.FrameUpdate update = NavigatorMapSessionState.FrameUpdate.REJECTED;
            long sequence = 0L;
            long revision = 0L;
            Runnable notify = null;
            boolean sameInput = false;
            synchronized (LOCK) {
                if (processingId == id
                        && FRAME.isCurrent(callerPackage, generation, resultSession)
                        && FRAME.hasSameInputPixels(callerPackage, generation, resultSession, inputHash)) {
                    update = FRAME.refreshSameInput(
                            callerPackage, generation, resultSession, inputHash,
                            receivedAt, SystemClock.elapsedRealtime());
                    sameInput = update == NavigatorMapSessionState.FrameUpdate.SAME;
                }
                if (sameInput) {
                    processingId = 0L;
                    sequence = FRAME.frameSequence();
                    revision = FRAME.revision();
                    scheduleExpiryLocked(callerPackage, generation, resultSession, sequence, receivedAt);
                }
            }
            if (sameInput) {
                logFrame(app, callerPackage, resultSession, id, status, "same", bitmap, receivedAt, data);
                return;
            }

            byte[] png = HudMapImage.centerCropPng(bitmap);
            if (png == null || png.length == 0) {
                finishProcessing(resultSession, id);
                logResultError(app, callerPackage, resultSession, id, status, "empty_cropped_png", data);
                return;
            }
            String outputHash = sha256(png);
            synchronized (LOCK) {
                if (processingId == id) {
                    update = FRAME.publish(
                            callerPackage, generation, resultSession, inputHash, outputHash,
                            png, receivedAt, SystemClock.elapsedRealtime());
                    processingId = 0L;
                    sequence = FRAME.frameSequence();
                    revision = FRAME.revision();
                    if (update != NavigatorMapSessionState.FrameUpdate.REJECTED) {
                        scheduleExpiryLocked(callerPackage, generation, resultSession, sequence, receivedAt);
                    }
                    if (update == NavigatorMapSessionState.FrameUpdate.CHANGED) {
                        cachedSnapshot = null;
                        notify = onFrameChanged;
                    }
                }
            }
            if (update == NavigatorMapSessionState.FrameUpdate.REJECTED) {
                logResultError(app, callerPackage, resultSession, id, status,
                        "late_crop_or_stale_session", data);
                return;
            }
            logFrame(app, callerPackage, resultSession, id, status,
                    update == NavigatorMapSessionState.FrameUpdate.SAME ? "same_crop" : "changed",
                    bitmap, receivedAt, data);
            runCallback(notify, app);
        } catch (RuntimeException | NoSuchAlgorithmException error) {
            finishProcessing(resultSession, id);
            logResultError(app, callerPackage, resultSession, id, status,
                    "processing_" + error.getClass().getSimpleName(), data);
        } finally {
            recycle(bitmap);
            finishProcessing(resultSession, id);
        }
    }

    private static void onExpiry(
            String expectedOwner,
            long expectedGeneration,
            String expectedSession,
            long expectedFrameSequence) {
        Runnable notify = null;
        Context app = null;
        boolean expired;
        long revision;
        synchronized (LOCK) {
            expiryRunnable = null;
            long now = SystemClock.elapsedRealtime();
            expired = FRAME.isCurrent(expectedOwner, expectedGeneration, expectedSession)
                    && FRAME.expireIfDue(expectedSession, expectedFrameSequence, now);
            if (expired) {
                cachedSnapshot = null;
                notify = onFrameChanged;
                app = appContext;
            } else if (FRAME.isCurrent(expectedOwner, expectedGeneration, expectedSession)
                    && FRAME.hasFrame()) {
                scheduleExpiryLocked(expectedOwner, expectedGeneration, expectedSession,
                        FRAME.frameSequence(), FRAME.receivedAtElapsedMs());
            }
            revision = FRAME.revision();
        }
        if (expired) {
            log(app, "navigator_map_capture frame_expired revision=" + revision);
            runCallback(notify, app);
        }
    }

    private static void scheduleExpiryLocked(
            String expectedOwner,
            long expectedGeneration,
            String expectedSession,
            long frameSequence,
            long receivedAt) {
        if (worker == null) {
            return;
        }
        cancelExpiryLocked();
        long age = Math.max(0L, SystemClock.elapsedRealtime() - receivedAt);
        long delay = Math.max(0L, NavigatorMapSessionState.FRESHNESS_MS - age);
        Runnable task = () -> onExpiry(
                expectedOwner, expectedGeneration, expectedSession, frameSequence);
        expiryRunnable = task;
        worker.postDelayed(task, delay);
    }

    private static void cancelExpiryLocked() {
        if (worker != null && expiryRunnable != null) {
            worker.removeCallbacks(expiryRunnable);
        }
        expiryRunnable = null;
    }

    private static void finishProcessing(String expectedSession, long id) {
        synchronized (LOCK) {
            if (session.equals(expectedSession) && processingId == id) {
                processingId = 0L;
            }
        }
    }

    private static long nextRequestIdLocked() {
        requestSerial++;
        if (requestSerial == 0L) {
            requestSerial++;
        }
        return requestSerial;
    }

    private static void rememberStoppedLocked() {
        stoppedOwnerPackage = ownerPackage;
        stoppedSession = session;
        stoppedAtElapsedMs = SystemClock.elapsedRealtime();
    }

    private static void ensureWorkerLocked() {
        if (workerThread != null && worker != null) {
            return;
        }
        workerThread = new HandlerThread(
                "BydHudNavigatorMapReceiver", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    private static boolean isSupportedPackage(String packageName) {
        return WAZE_PACKAGE.equals(packageName) || MAPS_PACKAGE.equals(packageName);
    }

    private static boolean uidOwnsPackage(Context context, int uid, String packageName) {
        if (context == null) {
            return false;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String candidate : packages) {
            if (packageName.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Bitmap bitmap(Bundle data) {
        try {
            data.setClassLoader(Bitmap.class.getClassLoader());
            Object value = data.getParcelable("bitmap");
            return value instanceof Bitmap ? (Bitmap) value : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String validateBitmap(Bitmap bitmap, String status) {
        if (!"ok".equals(status)) {
            return "producer_status";
        }
        if (bitmap == null || bitmap.isRecycled()) {
            return "bitmap_missing";
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        long byteCount = bitmap.getByteCount();
        Bitmap.Config config = bitmap.getConfig();
        if (width < 1 || height < 1 || width > MAX_BITMAP_SIDE || height > MAX_BITMAP_SIDE
                || (long) width * height > (long) MAX_BITMAP_SIDE * MAX_BITMAP_SIDE
                || byteCount < 1L || byteCount > MAX_BITMAP_BYTES
                || config == null || config == Bitmap.Config.HARDWARE) {
            return "bitmap_bounds";
        }
        return null;
    }

    private static String pixelHash(Bitmap bitmap) throws NoSuchAlgorithmException {
        byte[] config = bitmap.getConfig().name().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(bitmap.getByteCount() + config.length + 12);
        buffer.putInt(bitmap.getWidth());
        buffer.putInt(bitmap.getHeight());
        buffer.putInt(config.length);
        buffer.put(config);
        bitmap.copyPixelsToBuffer(buffer);
        return sha256(buffer.array());
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static void logFrame(
            Context app,
            String callerPackage,
            String frameSession,
            long id,
            String status,
            String pixelChange,
            Bitmap bitmap,
            long receivedAt,
            Bundle data) {
        long age = Math.max(0L, SystemClock.elapsedRealtime() - receivedAt);
        log(app, "navigator_map_capture frame owner=" + field(callerPackage, 96)
                + " session=" + frameSession + " id=" + id
                + " status=" + field(status, 64) + " source=" + field(string(data, "source", ""), 96)
                + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " receivedAgeMs=" + age + " pixels=" + pixelChange
                + timing(data));
    }

    private static void logResultError(
            Context app,
            String callerPackage,
            String frameSession,
            long id,
            String status,
            String reason,
            Bundle data) {
        log(app, "navigator_map_capture result_error owner=" + field(callerPackage, 96)
                + " session=" + field(frameSession, 96) + " id=" + id
                + " status=" + field(status, 96) + " reason=" + field(reason, 96)
                + " source=" + field(string(data, "source", ""), 96)
                + " producerError=" + field(string(data, "producerError", ""), 384)
                + timing(data));
    }

    private static void logRejectedCaller(Context app, int uid, String callerPackage) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (session.isEmpty() || now - lastRejectedLogAtElapsedMs < 1000L) {
                return;
            }
            lastRejectedLogAtElapsedMs = now;
        }
        log(app, "navigator_map_capture uid_rejected uid=" + uid
                + " package=" + field(callerPackage, 96));
    }

    private static void logLifecycleEvents(
            Context app,
            String callerPackage,
            String frameSession,
            String rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty() || "[]".equals(rawEvents)) {
            return;
        }
        try {
            JSONArray events = new JSONArray(rawEvents);
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) {
                    continue;
                }
                log(app, "navigator_map_capture producer_lifecycle owner="
                        + field(callerPackage, 96) + " session=" + field(frameSession, 96)
                        + " sequence=" + event.optLong("sequence")
                        + " event=" + field(event.optString("event", ""), 96)
                        + " ownerId=" + field(event.optString("owner", ""), 160)
                        + " detail=" + field(event.optString("detail", ""), 512));
            }
        } catch (JSONException error) {
            log(app, "navigator_map_capture producer_lifecycle_parse_error owner="
                    + field(callerPackage, 96) + " chars=" + rawEvents.length());
        }
    }

    private static String string(Bundle data, String key, String fallback) {
        try {
            String value = data.getString(key);
            return value == null ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long longValue(Bundle data, String key, long fallback) {
        try {
            return data.getLong(key, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(Bundle data, String key, int fallback) {
        try {
            return data.getInt(key, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(Bundle data, String key, boolean fallback) {
        try {
            return data.getBoolean(key, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String timing(Bundle data) {
        return " requestNs=" + longValue(data, "requestNs", 0L)
                + " producerRequestNs=" + longValue(data, "producerRequestNs", 0L)
                + " captureStartNs=" + longValue(data, "captureStartNs", 0L)
                + " callbackNs=" + longValue(data, "callbackNs", 0L)
                + " sendNs=" + longValue(data, "sendNs", 0L)
                + " sourceSize=" + integer(data, "sourceWidth", 0) + "x"
                + integer(data, "sourceHeight", 0)
                + " mapsState=" + field(string(data, "mapsState", ""), 512);
    }

    private static String field(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private static void log(Context context, String line) {
        if (context != null) {
            AppEventLogger.event(context, line);
        }
    }

    private static void runCallback(Runnable callback, Context context) {
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (RuntimeException error) {
            log(context, "navigator_map_capture callback_error type="
                    + error.getClass().getSimpleName());
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
