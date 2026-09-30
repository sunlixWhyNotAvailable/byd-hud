package com.bydhud.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SharedMemory;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
    private static final long MAX_BITMAP_BYTES = 1920L * 1920L * 4L;
    private static final int MAX_BITMAP_SIDE = 1920;
    private static final java.util.Set<String> PENDING_ARTIFACTS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final byte[] EMPTY_PNG = new byte[0];
    private static final NavigatorMapSessionState FRAME = new NavigatorMapSessionState();

    private static Context appContext;
    private static HandlerThread workerThread;
    private static Handler worker;
    private static Runnable expiryRunnable;
    private static Runnable recropRunnable;
    private static long recropSerial;
    private static Runnable onFrameChanged;
    private static Snapshot cachedSnapshot;
    private static Bitmap rawBitmap;
    private static long rawFrameReceiptElapsedMs;
    private static long rawFrameSequence;
    private static HudMapProfile.Source rawSource;
    private static String rawInputPixelHash = "";
    private static String rawSourceMetadata = "";
    private static String rawBackgroundState = "";
    private static String ownerPackage = "";
    private static long ownerGeneration = -1L;
    private static HudMapProfile.Source expectedSource;
    private static HudMapProfile.Source activeSourceMode;
    private static long activeSourceModeRevision;
    private static long requestSourceModeRevision = -1L;
    private static HudMapProfile.Source requestSourceMode;
    private static HudMapProfile overrideProfile;
    private static long profileEpoch;
    private static long catalogRevision = -1L;
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
        private final HudMapProfile.Source source;

        Snapshot(byte[] png, long revision, long receivedAtElapsedMs) {
            this(png, revision, receivedAtElapsedMs, null);
        }

        Snapshot(byte[] png, long revision, long receivedAtElapsedMs,
                 HudMapProfile.Source source) {
            this.png = png == null || png.length == 0
                    ? EMPTY_PNG
                    : Arrays.copyOf(png, png.length);
            this.revision = revision;
            this.receivedAtElapsedMs = receivedAtElapsedMs;
            this.source = png == null || png.length == 0 ? null : source;
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

        public HudMapProfile.Source source() {
            return source;
        }
    }

    public static void activate(
            Context context,
            String selectedOwnerPackage,
            long selectedOwnerGeneration,
            Runnable frameChanged) {
        activate(context, selectedOwnerPackage, selectedOwnerGeneration, null, frameChanged);
    }

    public static void activate(
            Context context,
            String selectedOwnerPackage,
            long selectedOwnerGeneration,
            HudMapProfile.Source selectedSource,
            Runnable frameChanged) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null || !isSupportedPackage(selectedOwnerPackage)
                || (selectedSource != null
                && !selectedOwnerPackage.equals(selectedSource.packageName()))) {
            stop("invalid_owner");
            log(app, "navigator_map_capture activation_rejected owner="
                    + field(selectedOwnerPackage, 96)
                    + " expectedSource=" + sourceName(selectedSource));
            return;
        }

        String nextSession = UUID.randomUUID().toString();
        String replacedOwner = "";
        String replacedSession = "";
        Bitmap replacedRaw;
        Handler cleanupWorker;
        boolean changed;
        Runnable notify;
        synchronized (LOCK) {
            appContext = app;
            if (ownerPackage.equals(selectedOwnerPackage)
                    && ownerGeneration == selectedOwnerGeneration
                    && expectedSource == selectedSource
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
            replacedRaw = detachRawLocked();
            cleanupWorker = worker;
            ownerPackage = selectedOwnerPackage;
            ownerGeneration = selectedOwnerGeneration;
            expectedSource = selectedSource;
            session = nextSession;
            pendingId = 0L;
            pendingAtElapsedMs = 0L;
            requestSourceMode = null;
            requestSourceModeRevision = -1L;
            nextRequestAtElapsedMs = 0L;
            processingId = 0L;
            lastProducerState = "";
            overrideProfile = null;
            profileEpoch++;
            catalogRevision = -1L;
            onFrameChanged = frameChanged;
            changed = FRAME.activate(ownerPackage, ownerGeneration, session);
            cachedSnapshot = null;
            cancelExpiryLocked();
            cancelRecropLocked();
            ensureWorkerLocked();
            notify = changed ? onFrameChanged : null;
        }
        recycleAfterPendingWork(cleanupWorker, replacedRaw);
        if (!replacedSession.isEmpty()) {
            log(app, "navigator_map_capture replaced owner=" + field(replacedOwner, 96)
                    + " session=" + replacedSession);
        }
        log(app, "navigator_map_capture started owner=" + field(selectedOwnerPackage, 96)
                + " generation=" + selectedOwnerGeneration + " session=" + nextSession
                + " expectedSource=" + sourceName(selectedSource));
        runCallback(notify, app);
    }

    /** Supplies the current Waze mode from the live navigation runtime, or null when unknown. */
    public static void setActiveSourceMode(HudMapProfile.Source source) {
        if (source != HudMapProfile.Source.WAZE
                && source != HudMapProfile.Source.WAZE_SURFACE) {
            source = null;
        }
        synchronized (LOCK) {
            if (activeSourceMode == source) return;
            activeSourceMode = source;
            activeSourceModeRevision++;
        }
    }

    /** Applies an unsaved calibration value to the latest frame; null restores saved settings. */
    public static void setProfileOverride(HudMapProfile profile) {
        Context app;
        synchronized (LOCK) {
            if (profile != null && expectedSource != null && profile.source != expectedSource) return;
            if (profile == null ? overrideProfile == null : profile.equals(overrideProfile)) return;
            overrideProfile = profile;
            profileEpoch++;
            app = appContext;
            scheduleRecropLocked();
        }
        if (app != null) log(app, "navigator_map_capture profile_override source="
                + sourceName(profile == null ? null : profile.source));
    }

    /** Reloads saved profiles, clears any edit override, and crops the retained source again. */
    public static void refreshProfiles() {
        Context app;
        long nextCatalogRevision;
        synchronized (LOCK) {
            app = appContext;
        }
        if (app == null) return;
        nextCatalogRevision = HudMapProfiles.revision(app);
        synchronized (LOCK) {
            if (catalogRevision != nextCatalogRevision) {
                catalogRevision = nextCatalogRevision;
            }
            overrideProfile = null;
            profileEpoch++;
            scheduleRecropLocked();
        }
    }

    public static void stop(String reason) {
        Context app;
        HandlerThread threadToStop;
        Handler cleanupWorker;
        Bitmap rawToRecycle;
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
            rawToRecycle = detachRawLocked();
            cleanupWorker = worker;
            ownerPackage = "";
            ownerGeneration = -1L;
            expectedSource = null;
            session = "";
            pendingId = 0L;
            pendingAtElapsedMs = 0L;
            requestSourceMode = null;
            requestSourceModeRevision = -1L;
            nextRequestAtElapsedMs = 0L;
            processingId = 0L;
            lastProducerState = "";
            notify = changed ? onFrameChanged : null;
            onFrameChanged = null;
            cancelExpiryLocked();
            cancelRecropLocked();
            threadToStop = workerThread;
            workerThread = null;
            worker = null;
        }
        recycleAfterPendingWork(cleanupWorker, rawToRecycle);
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
        Bitmap expiredRaw = null;
        Handler cleanupWorker = null;
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            expired = FRAME.expireIfStale(now);
            if (expired) {
                cancelExpiryLocked();
                cachedSnapshot = null;
                expiredRaw = detachRawLocked();
                cleanupWorker = worker;
                notify = onFrameChanged;
                app = appContext;
            }
            revision = FRAME.revision();
            long receivedAt = FRAME.receivedAtElapsedMs();
            if (cachedSnapshot == null
                    || cachedSnapshot.revision() != revision
                    || cachedSnapshot.receivedAtElapsedMs() != receivedAt) {
                cachedSnapshot = new Snapshot(FRAME.pngForSnapshot(), revision, receivedAt,
                        FRAME.source());
            }
            result = cachedSnapshot;
        }
        recycleAfterPendingWork(cleanupWorker, expiredRaw);
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
        try {
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
        } finally {
            // Close the receiver's descriptor even for rejected/stale requests.
            if (data != null) try {
                SharedMemory memory = data.getParcelable("sourceMemory");
                if (memory != null) memory.close();
            } catch (RuntimeException ignored) { }
        }
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
                answer.putBoolean("fullSourceMemory", true);
                answer.putString("session", session);
                if (!"[]".equals(lifeEvents) && !lifeEvents.isEmpty()) {
                    answer.putBoolean("journalAccepted", true);
                    acceptLifecycle = true;
                    lifeSession = session;
                }
                String producerState = "build=" + field(string(data, "build", ""), 96)
                        + " fullSourceMemory=" + booleanValue(data, "fullSourceMemory", false)
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
                    requestSourceMode = null;
                    requestSourceModeRevision = -1L;
                }
                boolean busy = booleanValue(data, "busy", false);
                if (pendingId == 0L && processingId == 0L && !busy
                        && now >= nextRequestAtElapsedMs) {
                    pendingId = nextRequestIdLocked();
                    pendingAtElapsedMs = now;
                    requestSourceMode = activeSourceMode;
                    requestSourceModeRevision = activeSourceModeRevision;
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
        long modeRevision;
        HudMapProfile.Source modeAtRequest;
        HudMapProfile.Source expectedAtRequest;
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
                modeAtRequest = requestSourceMode;
                modeRevision = requestSourceModeRevision;
                requestSourceMode = null;
                requestSourceModeRevision = -1L;
                expectedAtRequest = expectedSource;
                processingId = id;
                generation = ownerGeneration;
                receiverWorker = worker;
            } else {
                generation = -1L;
                modeRevision = -1L;
                modeAtRequest = null;
                expectedAtRequest = null;
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
                modeAtRequest, modeRevision, expectedAtRequest,
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
            HudMapProfile.Source modeAtRequest,
            long modeRevisionAtRequest,
            HudMapProfile.Source expectedAtRequest,
            String status,
            Bundle data,
            Bitmap bitmap) {
        boolean retained = false;
        try {
            String sourceMetadata = string(data, "source", "");
            String backgroundState = string(data, "backgroundState", "");
            HudMapProfile.Source frameSource = classifySource(
                    callerPackage, sourceMetadata, backgroundState, modeAtRequest);
            if (expectedAtRequest != null && frameSource != expectedAtRequest) {
                log(app, "navigator_map_capture result_rejected owner="
                        + field(callerPackage, 96) + " id=" + id + " reason=wrong_source"
                        + " expectedSource=" + sourceName(expectedAtRequest)
                        + " actualSource=" + sourceName(frameSource)
                        + " sourceMetadata=" + field(sourceMetadata, 160)
                        + " backgroundState=" + field(backgroundState, 96));
                return;
            }
            synchronized (LOCK) {
                if (WAZE_PACKAGE.equals(callerPackage)
                        && activeSourceModeRevision != modeRevisionAtRequest) {
                    log(app, "navigator_map_capture result_rejected owner="
                            + field(callerPackage, 96) + " id=" + id
                            + " reason=source_mode_changed");
                    return;
                }
            }

            ProfileSelection selection = profileSelection(app, frameSource);
            String inputHash = pixelHash(bitmap);
            NavigatorMapSessionState.FrameUpdate update = NavigatorMapSessionState.FrameUpdate.REJECTED;
            long sequence = 0L;
            long revision = 0L;
            Runnable notify = null;
            boolean sameInput = false;
            byte[] samePng = EMPTY_PNG;
            Bitmap replacedRaw = null;
            synchronized (LOCK) {
                if (processingId == id
                        && FRAME.isCurrent(callerPackage, generation, resultSession)
                        && selection.epoch == profileEpoch
                        && (!WAZE_PACKAGE.equals(callerPackage)
                        || activeSourceModeRevision == modeRevisionAtRequest)
                        && rawBitmap != null && !rawBitmap.isRecycled()
                        && FRAME.hasSameInputPixels(callerPackage, generation, resultSession,
                                inputHash, frameSource, selection.epoch)) {
                    update = FRAME.refreshSameInput(
                            callerPackage, generation, resultSession, inputHash,
                            frameSource, selection.epoch,
                            receivedAt, SystemClock.elapsedRealtime());
                    sameInput = update == NavigatorMapSessionState.FrameUpdate.SAME;
                }
                if (sameInput) {
                    replacedRaw = retainRawLocked(bitmap, receivedAt, frameSource,
                            inputHash, sourceMetadata, backgroundState);
                    retained = true;
                    samePng = FRAME.pngForSnapshot();
                    processingId = 0L;
                    sequence = FRAME.frameSequence();
                    rawFrameSequence = sequence;
                    revision = FRAME.revision();
                    scheduleExpiryLocked(callerPackage, generation, resultSession, sequence, receivedAt);
                }
            }
            if (sameInput) {
                recycle(replacedRaw);
                logFrame(app, callerPackage, resultSession, id, status, "same", bitmap,
                        receivedAt, data, inputHash, samePng, frameSource, selection);
                return;
            }

            byte[] png = HudMapImage.profileCropPng(bitmap, selection.profile);
            if (png == null || png.length == 0) {
                finishProcessing(resultSession, id);
                logResultError(app, callerPackage, resultSession, id, status, "empty_cropped_png", data);
                return;
            }
            String outputHash = sha256(png);
            long latestCatalogRevision = HudMapProfiles.revision(app);
            String publishFailure = null;
            replacedRaw = null;
            synchronized (LOCK) {
                if (processingId == id) {
                    processingId = 0L;
                    if (WAZE_PACKAGE.equals(callerPackage)
                            && activeSourceModeRevision != modeRevisionAtRequest) {
                        publishFailure = "stale_source_mode";
                    } else if (latestCatalogRevision != selection.catalogRevision) {
                        if (catalogRevision != latestCatalogRevision) {
                            catalogRevision = latestCatalogRevision;
                            profileEpoch++;
                        }
                        if (FRAME.isCurrent(callerPackage, generation, resultSession)) {
                            replacedRaw = retainRawLocked(bitmap, receivedAt, frameSource,
                                    inputHash, sourceMetadata, backgroundState);
                            retained = true;
                            scheduleRecropLocked();
                        }
                        publishFailure = "stale_profile_revision";
                    } else if (selection.epoch != profileEpoch) {
                        if (FRAME.isCurrent(callerPackage, generation, resultSession)) {
                            replacedRaw = retainRawLocked(bitmap, receivedAt, frameSource,
                                    inputHash, sourceMetadata, backgroundState);
                            retained = true;
                            scheduleRecropLocked();
                        }
                        publishFailure = "stale_profile_epoch";
                    } else {
                        update = FRAME.publish(
                                callerPackage, generation, resultSession, inputHash, outputHash,
                                png, frameSource, selection.epoch,
                                receivedAt, SystemClock.elapsedRealtime());
                    }
                    sequence = FRAME.frameSequence();
                    revision = FRAME.revision();
                    if (update != NavigatorMapSessionState.FrameUpdate.REJECTED) {
                        replacedRaw = retainRawLocked(bitmap, receivedAt, frameSource,
                                inputHash, sourceMetadata, backgroundState);
                        retained = true;
                        rawFrameSequence = sequence;
                        scheduleExpiryLocked(callerPackage, generation, resultSession, sequence, receivedAt);
                    }
                    if (update == NavigatorMapSessionState.FrameUpdate.CHANGED) {
                        cachedSnapshot = null;
                        notify = onFrameChanged;
                    }
                }
            }
            recycle(replacedRaw);
            if (publishFailure != null) {
                log(app, "navigator_map_capture frame_discarded owner="
                        + field(callerPackage, 96) + " id=" + id + " reason=" + publishFailure
                        + " profile=" + profileName(selection.profile)
                        + " profileRevision=" + selection.catalogRevision
                        + " source=" + sourceName(frameSource));
                return;
            }
            if (update == NavigatorMapSessionState.FrameUpdate.REJECTED) {
                logResultError(app, callerPackage, resultSession, id, status,
                        "late_crop_or_stale_session", data);
                return;
            }
            logFrame(app, callerPackage, resultSession, id, status,
                    update == NavigatorMapSessionState.FrameUpdate.SAME ? "same_crop" : "changed",
                    bitmap, receivedAt, data, inputHash, png, frameSource, selection);
            runCallback(notify, app);
        } catch (RuntimeException | NoSuchAlgorithmException error) {
            finishProcessing(resultSession, id);
            logResultError(app, callerPackage, resultSession, id, status,
                    "processing_" + error.getClass().getSimpleName(), data);
        } finally {
            if (!retained) recycle(bitmap);
            finishProcessing(resultSession, id);
        }
    }

    private static ProfileSelection profileSelection(
            Context app,
            HudMapProfile.Source source) {
        long savedRevision = HudMapProfiles.revision(app);
        HudMapProfile saved = source == null ? null : HudMapProfiles.profiles(app).get(source);
        synchronized (LOCK) {
            if (catalogRevision != savedRevision) {
                catalogRevision = savedRevision;
                profileEpoch++;
            }
            HudMapProfile selected = source != null && overrideProfile != null
                    && overrideProfile.source == source ? overrideProfile : saved;
            return new ProfileSelection(selected, savedRevision, profileEpoch);
        }
    }

    private static HudMapProfile.Source classifySource(
            String owner,
            String sourceMetadata,
            String backgroundState,
            HudMapProfile.Source activeMode) {
        String className = rendererClassName(sourceMetadata);
        if (MAPS_PACKAGE.equals(owner)) {
            return className.isEmpty() ? null : HudMapProfile.Source.GOOGLE_MAPS;
        }
        if (!WAZE_PACKAGE.equals(owner) || !"com.waze.map.opengl.w".equals(className)) {
            return null;
        }
        if ("waze_car_frame_received".equals(backgroundState)) {
            return HudMapProfile.Source.WAZE_SURFACE;
        }
        if ("waze_frame_received".equals(backgroundState)) {
            return HudMapProfile.Source.WAZE;
        }
        if (activeMode == HudMapProfile.Source.WAZE
                || activeMode == HudMapProfile.Source.WAZE_SURFACE) {
            return activeMode;
        }
        return null;
    }

    private static String rendererClassName(String sourceMetadata) {
        int at = sourceMetadata == null ? -1 : sourceMetadata.lastIndexOf('@');
        if (at <= 0 || at == sourceMetadata.length() - 1) return "";
        for (int i = at + 1; i < sourceMetadata.length(); i++) {
            char value = sourceMetadata.charAt(i);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')
                    || (value >= 'A' && value <= 'F'))) return "";
        }
        return sourceMetadata.substring(0, at);
    }

    private static Bitmap retainRawLocked(
            Bitmap bitmap,
            long receivedAt,
            HudMapProfile.Source source,
            String inputPixelHash,
            String sourceMetadata,
            String backgroundState) {
        Bitmap previous = rawBitmap;
        rawBitmap = bitmap;
        rawFrameReceiptElapsedMs = receivedAt;
        rawFrameSequence = FRAME.frameSequence();
        rawSource = source;
        rawInputPixelHash = inputPixelHash;
        rawSourceMetadata = field(sourceMetadata, 160);
        rawBackgroundState = field(backgroundState, 96);
        return previous == bitmap ? null : previous;
    }

    private static void scheduleRecropLocked() {
        if (worker == null || session.isEmpty() || rawBitmap == null) return;
        cancelRecropLocked();
        long serial = ++recropSerial;
        String expectedOwner = ownerPackage;
        long expectedGeneration = ownerGeneration;
        String expectedSession = session;
        long expectedRawSequence = rawFrameSequence;
        Runnable task = () -> recropLatest(
                expectedOwner, expectedGeneration, expectedSession, expectedRawSequence, serial);
        recropRunnable = task;
        worker.post(task);
    }

    private static void cancelRecropLocked() {
        if (worker != null && recropRunnable != null) worker.removeCallbacks(recropRunnable);
        recropRunnable = null;
        recropSerial++;
    }

    private static void recropLatest(
            String expectedOwner,
            long expectedGeneration,
            String expectedSession,
            long expectedRawSequence,
            long serial) {
        Bitmap sourceBitmap;
        HudMapProfile.Source source;
        long receivedAt;
        String inputHash;
        String sourceMetadata;
        String backgroundState;
        synchronized (LOCK) {
            if (serial != recropSerial || rawBitmap == null
                    || rawFrameSequence != expectedRawSequence
                    || !FRAME.isCurrent(expectedOwner, expectedGeneration, expectedSession)) return;
            recropRunnable = null;
            sourceBitmap = rawBitmap;
            source = rawSource;
            receivedAt = rawFrameReceiptElapsedMs;
            inputHash = rawInputPixelHash;
            sourceMetadata = rawSourceMetadata;
            backgroundState = rawBackgroundState;
        }
        Context app = appContext;
        if (app == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - receivedAt >= NavigatorMapSessionState.FRESHNESS_MS) {
            expireRetainedFrame(expectedOwner, expectedGeneration, expectedSession, source,
                    sourceMetadata, backgroundState);
            return;
        }
        ProfileSelection selection = profileSelection(app, source);
        byte[] png = HudMapImage.profileCropPng(sourceBitmap, selection.profile);
        if (png.length == 0) return;
        String outputHash;
        try {
            outputHash = sha256(png);
        } catch (NoSuchAlgorithmException impossible) {
            return;
        }
        long latestCatalogRevision = HudMapProfiles.revision(app);
        Runnable notify = null;
        long revision = 0L;
        long sequence = 0L;
        NavigatorMapSessionState.FrameUpdate update = NavigatorMapSessionState.FrameUpdate.REJECTED;
        boolean expired = false;
        Bitmap expiredRaw = null;
        synchronized (LOCK) {
            if (serial != recropSerial || rawBitmap != sourceBitmap
                    || rawFrameSequence != expectedRawSequence
                    || !FRAME.isCurrent(expectedOwner, expectedGeneration, expectedSession)) return;
            if (latestCatalogRevision != selection.catalogRevision) {
                if (catalogRevision != latestCatalogRevision) {
                    catalogRevision = latestCatalogRevision;
                    profileEpoch++;
                }
                scheduleRecropLocked();
                return;
            }
            if (selection.epoch != profileEpoch) {
                scheduleRecropLocked();
                return;
            }
            update = FRAME.publish(expectedOwner, expectedGeneration, expectedSession,
                    inputHash, outputHash, png, source, selection.epoch,
                    receivedAt, SystemClock.elapsedRealtime());
            if (update == NavigatorMapSessionState.FrameUpdate.REJECTED) {
                expired = FRAME.expireIfStale(SystemClock.elapsedRealtime());
                if (expired) {
                    cancelExpiryLocked();
                    cachedSnapshot = null;
                    expiredRaw = detachRawLocked();
                    notify = onFrameChanged;
                }
            } else {
                sequence = FRAME.frameSequence();
                rawFrameSequence = sequence;
                revision = FRAME.revision();
                scheduleExpiryLocked(expectedOwner, expectedGeneration, expectedSession,
                        sequence, receivedAt);
                if (update == NavigatorMapSessionState.FrameUpdate.CHANGED) {
                    cachedSnapshot = null;
                    notify = onFrameChanged;
                }
            }
        }
        if (expiredRaw != null) recycle(expiredRaw);
        log(app, "navigator_map_capture frame_recropped owner=" + field(expectedOwner, 96)
                + " session=" + expectedSession + " source=" + sourceName(source)
                + " sourceMetadata=" + field(sourceMetadata, 160)
                + " backgroundState=" + field(backgroundState, 96)
                + " profile=" + profileName(selection.profile)
                + " profileRevision=" + selection.catalogRevision
                + " profileEpoch=" + selection.epoch
                + " revision=" + revision
                + " result=" + update.name().toLowerCase());
        if (update != NavigatorMapSessionState.FrameUpdate.REJECTED) {
            saveFrameArtifacts(app, expectedOwner, expectedSession, sequence,
                    sourceBitmap, png, inputHash, source, selection);
        }
        runCallback(notify, app);
    }

    private static void expireRetainedFrame(
            String expectedOwner,
            long expectedGeneration,
            String expectedSession,
            HudMapProfile.Source source,
            String sourceMetadata,
            String backgroundState) {
        Context app = appContext;
        Runnable notify = null;
        Bitmap expiredRaw = null;
        long revision;
        boolean expired;
        synchronized (LOCK) {
            expired = FRAME.isCurrent(expectedOwner, expectedGeneration, expectedSession)
                    && FRAME.expireIfStale(SystemClock.elapsedRealtime());
            if (expired) {
                cancelExpiryLocked();
                cachedSnapshot = null;
                expiredRaw = detachRawLocked();
                notify = onFrameChanged;
            }
            revision = FRAME.revision();
        }
        if (expiredRaw != null) recycle(expiredRaw);
        if (expired) {
            log(app, "navigator_map_capture frame_expired owner=" + field(expectedOwner, 96)
                    + " session=" + expectedSession + " source=" + sourceName(source)
                    + " sourceMetadata=" + field(sourceMetadata, 160)
                    + " backgroundState=" + field(backgroundState, 96)
                    + " revision=" + revision);
            runCallback(notify, app);
        }
    }

    private static Bitmap detachRawLocked() {
        Bitmap detached = rawBitmap;
        rawBitmap = null;
        rawFrameReceiptElapsedMs = 0L;
        rawFrameSequence = 0L;
        rawSource = null;
        rawInputPixelHash = "";
        rawSourceMetadata = "";
        rawBackgroundState = "";
        return detached;
    }

    private static void recycleAfterPendingWork(Handler handler, Bitmap bitmap) {
        if (bitmap == null) return;
        if (handler == null || !handler.post(() -> recycle(bitmap))) recycle(bitmap);
    }

    private static final class ProfileSelection {
        final HudMapProfile profile;
        final long catalogRevision;
        final long epoch;

        ProfileSelection(HudMapProfile profile, long catalogRevision, long epoch) {
            this.profile = profile;
            this.catalogRevision = catalogRevision;
            this.epoch = epoch;
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
        HudMapProfile.Source source;
        Bitmap expiredRaw = null;
        synchronized (LOCK) {
            expiryRunnable = null;
            long now = SystemClock.elapsedRealtime();
            source = FRAME.source();
            expired = FRAME.isCurrent(expectedOwner, expectedGeneration, expectedSession)
                    && FRAME.expireIfDue(expectedSession, expectedFrameSequence, now);
            if (expired) {
                cachedSnapshot = null;
                expiredRaw = detachRawLocked();
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
            recycle(expiredRaw);
            log(app, "navigator_map_capture frame_expired owner=" + field(expectedOwner, 96)
                    + " session=" + expectedSession + " source=" + sourceName(source)
                    + " revision=" + revision);
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
            SharedMemory shared = data.getParcelable("sourceMemory");
            if (shared != null) {
                Bitmap source = null;
                try {
                    int width = data.getInt("bitmapWidth"), height = data.getInt("bitmapHeight");
                    long bytes = (long) width * height * 4;
                    if (width < 1 || height < 1 || width > MAX_BITMAP_SIDE || height > MAX_BITMAP_SIDE
                            || bytes != shared.getSize()) return null;
                    source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    ByteBuffer pixels = shared.mapReadOnly();
                    try { source.copyPixelsFromBuffer(pixels); }
                    finally { SharedMemory.unmap(pixels); }
                    Bitmap result = source;
                    source = null;
                    return result;
                } catch (android.system.ErrnoException failed) {
                    return null;
                } finally {
                    recycle(source);
                    shared.close();
                }
            }
            Object value = data.getParcelable("bitmap");
            return value instanceof Bitmap ? (Bitmap) value : null;
        } catch (RuntimeException | OutOfMemoryError ignored) {
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
            Bundle data,
            String inputHash,
            byte[] png,
            HudMapProfile.Source source,
            ProfileSelection selection) {
        long age = Math.max(0L, SystemClock.elapsedRealtime() - receivedAt);
        log(app, "navigator_map_capture frame owner=" + field(callerPackage, 96)
                + " session=" + frameSession + " id=" + id
                + " status=" + field(status, 64) + " source=" + field(string(data, "source", ""), 96)
                + " frameSource=" + sourceName(source)
                + " profile=" + profileName(selection.profile)
                + " profileRevision=" + selection.catalogRevision
                + " profileEpoch=" + selection.epoch
                + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " receivedAgeMs=" + age + " pixels=" + pixelChange
                + " inputHash=" + inputHash
                + timing(data));
        saveFrameArtifacts(app, callerPackage, frameSession, id, bitmap, png, inputHash,
                source, selection);
    }

    private static void saveFrameArtifacts(Context app, String owner, String frameSession,
            long id, Bitmap bitmap, byte[] croppedPng, String inputHash,
            HudMapProfile.Source source, ProfileSelection selection) {
        if (!HudPrefs.isDetailedDebugArtifactsEnabled(app)) return;
        String artifactKey;
        try { artifactKey = NavCaptureStore.todayDir() + ":" + inputHash + ":" + sha256(croppedPng); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (!PENDING_ARTIFACTS.add(artifactKey)) return; // The same PNG pair is already being saved.
        Bitmap copy = null;
        try {
            copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("bitmap_copy_failed");
            Bitmap sourceImage = copy;
            byte[] crop = croppedPng.clone();
            String day = NavCaptureStore.todayDir();
            boolean queued = WazeCaptureDebugWriter.mapFrames().directEvent(() -> {
                try {
                    if (!HudPrefs.isDetailedDebugArtifactsEnabled(app)) return;
                    NavigationLogStorage.withReadLock(() -> {
                        File dir = new File(NavigationLogStorage.logsDir(app, day), "map-frames");
                        // The source filename uses its pixel hash; unchanged frames reuse the file.
                        String sourceName = "map-source-" + inputHash + ".png";
                        if (!new File(dir, sourceName).isFile()) {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            if (!sourceImage.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                                throw new IllegalStateException("source_png_failed");
                            }
                            sourceName = NavCaptureStore.writeDirectArtifactFileIfAbsent(
                                    dir, sourceName, bytes.toByteArray());
                        }
                        String cropName = NavCaptureStore.writeDirectArtifactIfAbsent(dir, "map-hud", crop);
                        log(app, "navigator_map_capture artifacts owner=" + field(owner, 96)
                                + " session=" + frameSession + " id=" + id + " day=" + day
                                + " source=" + sourceName + " hud=" + cropName
                                + " frameSource=" + NavigatorMapCapture.sourceName(source)
                                + " profile=" + profileName(selection.profile)
                                + " profileRevision=" + selection.catalogRevision
                                + " profileEpoch=" + selection.epoch
                                + " result=" + (sourceName.isEmpty() || cropName.isEmpty() ? "write_failed" : "saved"));
                    });
                } catch (RuntimeException | OutOfMemoryError error) {
                    log(app, "navigator_map_capture artifacts_error id=" + id
                            + " reason=" + error.getClass().getSimpleName());
                } finally {
                    recycle(sourceImage);
                    PENDING_ARTIFACTS.remove(artifactKey);
                }
            });
            if (queued) return; // The bounded writer now owns the bitmap copy.
            log(app, "navigator_map_capture artifacts_dropped id=" + id + " reason=writer_queue_full");
        } catch (RuntimeException | OutOfMemoryError error) {
            log(app, "navigator_map_capture artifacts_error id=" + id
                    + " reason=" + error.getClass().getSimpleName());
        }
        recycle(copy);
        PENDING_ARTIFACTS.remove(artifactKey);
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

    private static String sourceName(HudMapProfile.Source source) {
        return source == null ? "unknown" : source.name();
    }

    private static String profileName(HudMapProfile profile) {
        return profile == null ? "none" : profile.source.name() + ":"
                + profile.x + "," + profile.y + "," + profile.scale;
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
