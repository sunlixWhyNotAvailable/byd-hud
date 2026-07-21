package com.bydhud.app;

//captures Waze screen crops so template and geometry parsers have reproducible visual evidence.

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

//defines the WazeCropCapture module boundary so related behavior stays readable inside one unit.
final class WazeCropCapture {
    static final boolean PRODUCTION_DELETE_AFTER_PARSE = false;
    static final String BUCKET_KNOWN = "known";
    static final String BUCKET_IGNORED_IDLE = "ignored-idle";
    static final String BUCKET_MISSING_MANEUVERS = "missing-maneuvers";
    static final String BUCKET_MISSING_LANES = "missing-lanes";
    private static final long SLOW_FRAME_LOG_MS = 1000L;
    private static final String TAG = "BydHudWazeCrop";
    private static final String WAZE_PACKAGE = "com.waze";
    private static final String SESSION_DIR = "waze-crop";
    private static final long CAPTURE_INTERVAL_MS = 1500L;
    private static final long INACTIVE_ROUTE_PROBE_INTERVAL_MS = 5000L;
    private static final long DISPLAY_REFRESH_INTERVAL_MS = 5000L;
    private static final long ACCESSIBILITY_GEOMETRY_TTL_MS = 7000L;
    private static final long STORAGE_REBASE_STOP_TIMEOUT_MS = 10000L;
    static final long MAX_VISUAL_COMMIT_AGE_MS = 3000L;
    private static final SimpleDateFormat SESSION_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    private static volatile WazeCropCapture instance;

    static final class RetentionState {
        static final RetentionState INACTIVE = new RetentionState(false, "", "", "");

        final boolean active;
        final String day;
        final String sessionName;
        final String preserveScreenshotName;

        RetentionState(
                boolean active,
                String day,
                String sessionName,
                String preserveScreenshotName) {
            this.active = active;
            this.day = safe(day);
            this.sessionName = safe(sessionName);
            this.preserveScreenshotName = safe(preserveScreenshotName);
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static synchronized WazeCropCapture get(Context context) {
        if (instance == null) {
            instance = new WazeCropCapture(context.getApplicationContext());
        }
        return instance;
    }

    //Returns one lock-free immutable snapshot for retention while it owns the topology write lock.
    static RetentionState currentRetentionState() {
        WazeCropCapture current = instance;
        return current == null ? RetentionState.INACTIVE : current.retentionState;
    }

    private final Context context;
    private final Object lock = new Object();
    private int generation;
    private boolean active;
    private Thread workerThread;
    private boolean storageRebaseInProgress;
    private boolean storageRebaseTimedOut;
    private boolean startRequestedDuringStorageRebase;
    private boolean stopRequestedDuringStorageRebase;
    private File sessionDir;
    private String sessionShellDir = "";
    private boolean sessionShellWritable;
    private String sessionName = "";
    private int screenshotIndex;
    private volatile RetentionState retentionState = RetentionState.INACTIVE;
    private NavAppDisplayState lastDisplayState = new NavAppDisplayState(
            WAZE_PACKAGE, -1, NavAppDisplayState.DISPLAY_UNKNOWN, false, "not checked");
    private long lastDisplayCheckMs;
    private WazeAccessibilityGeometry latestAccessibilityGeometry = WazeAccessibilityGeometry.EMPTY;
    private long latestAccessibilityGeometryMs;
    private final WazeFrameCaptureBackend frameBackend;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private WazeCropCapture(Context context) {
        this.context = context.getApplicationContext();
        this.frameBackend = new WazeFrameCaptureBackend(this.context);
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    static String classifyBucketForTest(NavSnapshot snapshot) {
        return classifyBucket(snapshot, false);
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    static String classifyBucketForTest(NavSnapshot snapshot, boolean missingLaneGuidance) {
        return classifyBucket(
                snapshot,
                missingLaneGuidance
                        ? WazeVisualCueParser.LaneGuidanceAnalysis.unparsed()
                        : WazeVisualCueParser.LaneGuidanceAnalysis.none(),
                !isIdleSnapshot(snapshot));
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    static String classifyBucketForTest(
            NavSnapshot snapshot, boolean missingLaneGuidance, boolean activeInstructionPanel) {
        return classifyBucket(
                snapshot,
                missingLaneGuidance
                        ? WazeVisualCueParser.LaneGuidanceAnalysis.unparsed()
                        : WazeVisualCueParser.LaneGuidanceAnalysis.none(),
                activeInstructionPanel);
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    static String classifyBucketForTest(
            NavSnapshot snapshot,
            WazeVisualCueParser.LaneGuidanceStatus laneStatus,
            boolean activeInstructionPanel) {
        return classifyBucket(
                snapshot,
                laneAnalysisFromStatus(laneStatus, false),
                activeInstructionPanel);
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    static String classifyBucketForTest(
            NavSnapshot snapshot,
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis,
            boolean activeInstructionPanel) {
        return classifyBucket(snapshot, laneAnalysis, activeInstructionPanel);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String snapshotLanesForCropForTest(NavSnapshot snapshot) {
        return snapshotLanesForCrop(snapshot);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String trustedLanesForCropForTest(
            NavSnapshot snapshot,
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis) {
        return trustedLanesForCrop(snapshot, laneAnalysis);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String trustedLanesForCropForTest(
            NavSnapshot snapshot,
            WazeVisualCueParser.LaneGuidanceStatus laneStatus) {
        return trustedLanesForCrop(snapshot, laneStatus);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldCommitVisualFrameForTest(
            boolean activeGeneration,
            int frameId,
            int latestFrameId,
            long ageMs) {
        return visualCommitSkipReason(activeGeneration, frameId, latestFrameId, ageMs).isEmpty();
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String visualCommitSkipReasonForTest(
            boolean activeGeneration,
            int frameId,
            int latestFrameId,
            long ageMs) {
        return visualCommitSkipReason(activeGeneration, frameId, latestFrameId, ageMs);
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    void start(String reason) {
        int workerGeneration;
        File dir;
        String name;
        synchronized (lock) {
            if (storageRebaseInProgress) {
                startRequestedDuringStorageRebase = true;
                return;
            }
            if (active) {
                return;
            }
            active = true;
            generation++;
            workerGeneration = generation;
            sessionName = timestampForFile();
            NavigationLogStorage.SessionPath path =
                    NavigationLogStorage.navCaptureSessionDir(context, SESSION_DIR, sessionName);
            sessionDir = path.localDir;
            sessionShellDir = path.shellDir;
            sessionShellWritable = path.shellWritable;
            if (!sessionDir.exists() && !sessionDir.mkdirs()) {
                Log.w(TAG, "mkdir failed " + sessionDir.getAbsolutePath());
            }
            screenshotIndex = 0;
            retentionState = new RetentionState(
                    true,
                    sessionName.length() >= 8 ? sessionName.substring(0, 8) : "",
                    sessionName,
                    "");
            lastDisplayState = new NavAppDisplayState(
                    WAZE_PACKAGE, -1, NavAppDisplayState.DISPLAY_UNKNOWN, false, "not checked");
            lastDisplayCheckMs = 0L;
            latestAccessibilityGeometry = WazeAccessibilityGeometry.EMPTY;
            latestAccessibilityGeometryMs = 0L;
            dir = sessionDir;
            name = sessionName;
        }
        log(dir, "start reason=" + safe(reason)
                + " localDir=" + dir.getAbsolutePath()
                + " shellDir=" + safe(sessionShellDir)
                + " shellWritable=" + sessionShellWritable);
        Thread worker = new Thread(() -> {
            try {
                runLoop(workerGeneration, dir, name);
            } finally {
                onWorkerExit(Thread.currentThread());
            }
        },
                "BydHudWazeCrop");
        synchronized (lock) {
            if (!active || generation != workerGeneration || storageRebaseInProgress) {
                return;
            }
            workerThread = worker;
        }
        worker.start();
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    void stop(String reason) {
        File dir;
        Thread worker;
        synchronized (lock) {
            if (storageRebaseInProgress) {
                stopRequestedDuringStorageRebase = true;
                startRequestedDuringStorageRebase = false;
            }
            if (!active && workerThread == null) {
                return;
            }
            deactivateLocked();
            dir = sessionDir;
            worker = workerThread;
        }
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
        log(dir, "stop reason=" + safe(reason));
    }

    boolean beginStorageDayRebase(String reason) {
        Thread worker;
        File dir;
        synchronized (lock) {
            if (storageRebaseInProgress) {
                return false;
            }
            storageRebaseInProgress = true;
            storageRebaseTimedOut = false;
            startRequestedDuringStorageRebase = false;
            stopRequestedDuringStorageRebase = false;
            deactivateLocked();
            worker = workerThread;
            dir = sessionDir;
        }
        if (worker == null || worker == Thread.currentThread()) {
            return true;
        }
        worker.interrupt();
        try {
            worker.join(STORAGE_REBASE_STOP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean stillAlive;
        synchronized (lock) {
            stillAlive = worker == workerThread && worker.isAlive();
            if (stillAlive) {
                storageRebaseTimedOut = true;
            }
        }
        if (!stillAlive) {
            return true;
        }
        log(dir, "storage rebase aborted: crop worker still alive reason=" + safe(reason));
        return false;
    }

    void finishStorageDayRebase(boolean restart, String reason) {
        boolean shouldRestart;
        synchronized (lock) {
            if (!storageRebaseInProgress) {
                return;
            }
            if (storageRebaseTimedOut) {
                if (!stopRequestedDuringStorageRebase) {
                    startRequestedDuringStorageRebase |= restart;
                }
                return;
            }
            shouldRestart = !stopRequestedDuringStorageRebase
                    && (restart || startRequestedDuringStorageRebase);
            storageRebaseInProgress = false;
            startRequestedDuringStorageRebase = false;
            stopRequestedDuringStorageRebase = false;
        }
        if (shouldRestart) {
            start(reason);
        }
    }

    private void deactivateLocked() {
        if (active) {
            active = false;
            generation++;
        }
        retentionState = RetentionState.INACTIVE;
        lastDisplayState = new NavAppDisplayState(
                WAZE_PACKAGE, -1, NavAppDisplayState.DISPLAY_UNKNOWN, false, "not checked");
        lastDisplayCheckMs = 0L;
        latestAccessibilityGeometry = WazeAccessibilityGeometry.EMPTY;
        latestAccessibilityGeometryMs = 0L;
    }

    private void onWorkerExit(Thread worker) {
        boolean shouldRestart = false;
        synchronized (lock) {
            if (worker == workerThread) {
                workerThread = null;
            }
            if (storageRebaseTimedOut) {
                shouldRestart = !stopRequestedDuringStorageRebase
                        && startRequestedDuringStorageRebase;
                storageRebaseTimedOut = false;
                storageRebaseInProgress = false;
                startRequestedDuringStorageRebase = false;
                stopRequestedDuringStorageRebase = false;
            }
        }
        if (shouldRestart) {
            start("storage-day-rebase-after-timeout");
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isRunning() {
        synchronized (lock) {
            return active;
        }
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateAccessibilityGeometry(WazeAccessibilityGeometry geometry) {
        if (geometry == null || !geometry.hasAnyBounds()) {
            return;
        }
        synchronized (lock) {
            latestAccessibilityGeometry = geometry;
            latestAccessibilityGeometryMs = SystemClock.elapsedRealtime();
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void runLoop(int workerGeneration, File dir, String workerSessionName) {
        long lastInactiveRouteProbeMs = 0L;
        while (isActiveGeneration(workerGeneration)) {
            long now = SystemClock.elapsedRealtime();
            String shellDir = currentSessionShellDir();
            if (!NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE)) {
                log(dir, "crop stopped: Waze not active HUD");
                stop("not-active-hud");
                return;
            }
            boolean routeActive = WazeRouteTracker.get(context).isRouteActive(now);
            if (!routeActive
                    && now - lastInactiveRouteProbeMs < INACTIVE_ROUTE_PROBE_INTERVAL_MS) {
                log(dir, "crop idle: route inactive reason="
                        + WazeRouteTracker.get(context).reason());
                sleepQuietly(CAPTURE_INTERVAL_MS);
                continue;
            }
            if (!routeActive) {
                lastInactiveRouteProbeMs = now;
                log(dir, "crop inactive visual probe reason="
                        + WazeRouteTracker.get(context).reason());
            }
            NavAppDisplayState state = displayStateForCrop(dir, now);
            int displayId = state.displayId;
            String activeDashboardPackage =
                    NavAppDisplayController.get(context).activeDashboardPackage();
            boolean projectedSurface =
                    ClusterProjectionService.isProjectedPackageCurrent(WAZE_PACKAGE);
            boolean canCrop = isUsableWazeCropState(
                    state,
                    activeDashboardPackage,
                    projectedSurface);
            if (!canCrop) {
                log(dir, "waze_crop availability display=" + state.displayId
                        + " visible=" + state.visible
                        + " activeDashboard=" + safe(activeDashboardPackage)
                        + " projectedSurface=" + projectedSurface
                        + " decision=stop status=" + state.status);
                log(dir, "crop idle: waze background display=" + state.displayId
                        + " status=" + state.status);
                NavHudLiveSender.get(context).onWazeCropUnavailable(
                        "background display=" + state.displayId + " status=" + state.status);
                sleepQuietly(CAPTURE_INTERVAL_MS);
                continue;
            }
            if (!isActiveGeneration(workerGeneration)) {
                break;
            }
            captureFrame(
                    workerGeneration,
                    state,
                    dir,
                    shellDir,
                    workerSessionName,
                    WazeRouteTracker.get(context).evidenceAgeMs(now));
            sleepQuietly(CAPTURE_INTERVAL_MS);
        }
        log(dir, "loop exit");
    }

    //keeps projected Waze crop alive even when BYD HUD itself is no longer the foreground Activity.
    static boolean isUsableWazeCropState(
            NavAppDisplayState state,
            String activeDashboardPackage,
            boolean projectedSurface) {
        if (state == null) {
            return false;
        }
        if (state.isUsableForCrop()) {
            return true;
        }
        return state.taskId >= 0
                && state.displayId > 0
                && WAZE_PACKAGE.equals(safe(activeDashboardPackage))
                && projectedSurface;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private WazeAccessibilityGeometry freshAccessibilityGeometry(long now) {
        synchronized (lock) {
            if (latestAccessibilityGeometry == null
                    || !latestAccessibilityGeometry.hasAnyBounds()
                    || latestAccessibilityGeometryMs <= 0L
                    || now - latestAccessibilityGeometryMs > ACCESSIBILITY_GEOMETRY_TTL_MS) {
                return WazeAccessibilityGeometry.EMPTY;
            }
            return latestAccessibilityGeometry;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private NavAppDisplayState displayStateForCrop(File dir, long now) {
        NavAppDisplayController controller = NavAppDisplayController.get(context);
        boolean due = lastDisplayState == null
                || !lastDisplayState.isUsableForCrop()
                || now - lastDisplayCheckMs >= DISPLAY_REFRESH_INTERVAL_MS
                || controller.isMoveInProgress()
                || cachedDisplayConflictsWithActiveDashboard(
                        lastDisplayState,
                        controller.activeDashboardPackage());
        if (!due) {
            return lastDisplayState;
        }
        lastDisplayCheckMs = now;
        lastDisplayState = controller.checkDisplay(WAZE_PACKAGE, "waze-crop");
        log(dir, "display check display=" + lastDisplayState.displayId
                + " status=" + lastDisplayState.status);
        return lastDisplayState;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean cachedDisplayConflictsWithActiveDashboard(
            NavAppDisplayState state,
            String activeDashboardPackage) {
        if (state == null || !WAZE_PACKAGE.equals(safe(activeDashboardPackage))) {
            return false;
        }
        return state.displayId == 0;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void captureFrame(
            int workerGeneration,
            NavAppDisplayState state,
            File dir,
            String shellDir,
            String workerSessionName,
            long evidenceAgeMs) {
        if (!isActiveGeneration(workerGeneration)) {
            return;
        }
        int index;
        synchronized (lock) {
            screenshotIndex++;
            index = screenshotIndex;
        }
        int displayId = state == null ? NavAppDisplayState.DISPLAY_UNKNOWN : state.displayId;
        WazeFrameCaptureBackend.CaptureResult capture = frameBackend.capture(state);
        String sourceFileName = "";
        String backendUnavailableReason = "";
        boolean detailedDebugArtifacts =
                HudPrefs.isDetailedDebugArtifactsEnabled(context);
        try {
            if (capture.frame == null) {
                backendUnavailableReason = capture.reason;
                log(dir, "frame_dropped frameId=" + index
                        + " backend=" + capture.backend
                        + " reason=" + capture.reason
                        + " display=" + displayId
                        + " timeout_streak=" + capture.pixelCopyTimeoutStreak);
                return;
            }
            NavigationLogStorage.beginCaptureFrame();
            try {
                if (!isActiveGeneration(workerGeneration)) {
                    return;
                }
                NavSnapshot snapshot = WazeRouteTracker.get(context).latestSnapshot();
                sourceFileName = detailedDebugArtifacts
                        ? WazeCaptureDebugArtifacts.frameName("source_frame_", index)
                        : "";
                retentionState = new RetentionState(
                        true,
                        workerSessionName.length() >= 8
                                ? workerSessionName.substring(0, 8)
                                : "",
                        workerSessionName,
                        sourceFileName);
                long artifactQueueMs = 0L;
                long parseStartMs = SystemClock.elapsedRealtime();
                long geometryStartMs = parseStartMs;
                WazeAccessibilityGeometry geometry =
                        freshAccessibilityGeometry(geometryStartMs);
                long geometryEndMs = SystemClock.elapsedRealtime();
                String arrowInput = "";
                String lanesInput = "";
                long laneAnalysisStartMs = geometryEndMs;
                long laneAnalysisEndMs = laneAnalysisStartMs;
                long visualParseStartMs = laneAnalysisStartMs;
                long visualParseEndMs = visualParseStartMs;
                long panelStartMs = visualParseStartMs;
                long panelEndMs = panelStartMs;
                long candidateStartMs = panelStartMs;
                long candidateEndMs = candidateStartMs;
                WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis =
                        WazeVisualCueParser.LaneGuidanceAnalysis.none();
                NavParserResult visualResult = null;
                boolean activeInstructionPanel = false;
                boolean visualNavigationCandidate = false;
                laneAnalysis =
                        WazeVisualCueParser.analyzeLaneGuidanceWithCueFallback(capture.frame, geometry);
                laneAnalysisEndMs = SystemClock.elapsedRealtime();
                visualParseStartMs = laneAnalysisEndMs;
                visualResult = WazeVisualCueParser.parseFrame(
                        capture.frame,
                        snapshot,
                        laneAnalysis,
                        geometry,
                        HudPrefs.isRoundaboutLeftHandTraffic(context));
                visualParseEndMs = SystemClock.elapsedRealtime();
                panelStartMs = visualParseEndMs;
                activeInstructionPanel =
                        WazeVisualCueParser.hasActiveInstructionPanel(capture.frame);
                panelEndMs = SystemClock.elapsedRealtime();
                candidateStartMs = panelEndMs;
                visualNavigationCandidate =
                        WazeVisualCueParser.hasVisualNavigationCueCandidate(capture.frame);
                candidateEndMs = SystemClock.elapsedRealtime();
                long parseEndMs = candidateEndMs;
                if (!isActiveGeneration(workerGeneration)) {
                    return;
                }
                int latestFrameId = latestFrameId();
                String commitSkipReason = visualCommitSkipReason(
                        true,
                        index,
                        latestFrameId,
                        parseEndMs - capture.captureStartMs);
                boolean commitEligible = commitSkipReason.isEmpty();
                String parseTimingDetail = timingDetail(
                        index,
                        latestFrameId,
                        capture.captureStartMs,
                        capture.captureEndMs,
                        parseStartMs,
                        parseEndMs,
                        geometryEndMs - geometryStartMs,
                        laneAnalysisEndMs - laneAnalysisStartMs,
                        visualParseEndMs - visualParseStartMs,
                        panelEndMs - panelStartMs,
                        candidateEndMs - candidateStartMs,
                        artifactQueueMs,
                        commitEligible,
                        commitSkipReason);
                if (visualNavigationCandidate) {
                    String livenessReason = commitEligible
                            ? "visual navigation cue"
                            : "visual navigation cue liveness-only " + commitSkipReason;
                    NavHudLiveSender.get(context).onWazeVisualRouteEvidence(livenessReason);
                    log(dir, (commitEligible
                            ? "visual route evidence navigation cue file="
                            : "visual route liveness refreshed file=")
                            + sourceFileName + " " + parseTimingDetail);
                }
                if (visualResult != null) {
                    if (commitEligible) {
                        NavHudLiveSender.get(context)
                                .updateFromWazeVisualCue(
                                        WAZE_PACKAGE,
                                        visualResult.withSourceDisplayId(displayId));
                    } else {
                        NavSnapshot visualSnapshot = visualResult.snapshot;
                        log(dir, "visual commit skipped file=" + sourceFileName
                                + " maneuver=" + snapshotManeuver(visualSnapshot)
                                + " lanes=" + (visualSnapshot == null
                                ? "" : safe(visualSnapshot.laneString))
                                + " " + parseTimingDetail);
                    }
                } else if (blocksSingleFallback(laneAnalysis)) {
                    if (commitEligible) {
                        NavHudLiveSender.get(context).onWazeUnknownLaneRow(
                                "file=" + sourceFileName + " reason=" + laneAnalysis.reason.name());
                    } else {
                        log(dir, "unknown lane row skipped file=" + sourceFileName
                                + " reason=" + laneAnalysis.reason.name()
                                + " " + parseTimingDetail);
                    }
                } else if (!visualNavigationCandidate) {
                    if (commitEligible) {
                        if (displayId > 0) {
                            log(dir, "projected_no_cue_drop display=" + displayId
                                    + " file=" + sourceFileName
                                    + " " + parseTimingDetail);
                        } else {
                            NavHudLiveSender.get(context).onWazeCropUnavailable(
                                    "main-visible-no-cue file=" + sourceFileName);
                        }
                    } else {
                        log(dir, "crop unavailable skipped file=" + sourceFileName
                                + " " + parseTimingDetail);
                    }
                }
                NavSnapshot effectiveSnapshot =
                        visualResult == null ? snapshot : visualResult.snapshot;
                String bucket = classifyBucket(
                        effectiveSnapshot, laneAnalysis, activeInstructionPanel);
                String snapshotManeuver = snapshotManeuver(snapshot);
                String effectiveManeuver = snapshotManeuver(effectiveSnapshot);
                String trustedLanes = trustedLanesForCrop(effectiveSnapshot, laneAnalysis);
                String laneSource = laneAnalysis.source.logValue;
                String maneuverSource = visualResult == null ? "none" : visualResult.maneuverSource;
                String missingBucket = isMissingBucket(bucket) ? bucket : "";
                boolean frameArtifactsQueued = false;
                WazeCaptureDebugWriter writer = WazeCaptureDebugWriter.get();
                if (detailedDebugArtifacts && !sourceFileName.isEmpty()) {
                    long artifactQueueStartMs = SystemClock.elapsedRealtime();
                    frameArtifactsQueued = writer.frameArtifacts(
                            dir,
                            sourceFileName,
                            capture.frame,
                            missingBucket,
                            BUCKET_MISSING_LANES.equals(bucket) ? laneAnalysis : null);
                    artifactQueueMs = SystemClock.elapsedRealtime() - artifactQueueStartMs;
                    if (!frameArtifactsQueued) {
                        log(dir, "debug_artifact_drop type=frame file=" + sourceFileName
                                + " pendingTasks=" + writer.pendingTasks()
                                + " pendingBitmaps=" + writer.pendingBitmaps());
                    }
                }
                String finalTimingDetail = timingDetail(
                        index,
                        latestFrameId,
                        capture.captureStartMs,
                        capture.captureEndMs,
                        parseStartMs,
                        parseEndMs,
                        geometryEndMs - geometryStartMs,
                        laneAnalysisEndMs - laneAnalysisStartMs,
                        visualParseEndMs - visualParseStartMs,
                        panelEndMs - panelStartMs,
                        candidateEndMs - candidateStartMs,
                        artifactQueueMs,
                        commitEligible,
                        commitSkipReason);
                String missingFile = frameArtifactsQueued && !missingBucket.isEmpty()
                        ? missingBucket + "/" + sourceFileName
                        : "";
                WazeCropCandidate candidate = new WazeCropCandidate(
                        SystemClock.elapsedRealtime(),
                        displayId,
                        sourceFileName,
                        "backend=" + capture.backend
                                + backendUnavailableDetail(backendUnavailableReason)
                                + " routeAgeMs=" + evidenceAgeMs + " " + finalTimingDetail,
                        effectiveManeuver,
                        trustedLanes,
                        effectiveSnapshot == null ? 0 : effectiveSnapshot.confidence,
                        bucket,
                        missingFile,
                        snapshotManeuver,
                        snapshot == null ? "" : snapshot.laneString,
                        laneAnalysis,
                        laneSource,
                        maneuverSource);
                String candidateJson = candidate.toJsonLine();
                writer.appendSessionLine(dir, candidateJson);
                writer.rawEvent(context, "waze_crop", WAZE_PACKAGE, candidateJson);
                if (detailedDebugArtifacts) {
                    writer.appendCaptureEvent(dir, captureEventJson(
                            index,
                            displayId,
                            capture,
                            backendUnavailableReason,
                            geometry,
                            sourceFileName,
                            arrowInput,
                            lanesInput,
                            bucket,
                            effectiveManeuver,
                            trustedLanes,
                            laneSource,
                            maneuverSource,
                            commitEligible,
                            commitSkipReason));
                }
                if (isMissingBucket(bucket)) {
                    writer.rawEvent(context, "waze_crop_missing", WAZE_PACKAGE,
                            "bucket=" + bucket
                                    + " file=" + missingFile
                                    + " maneuver=" + snapshotManeuver
                                    + " lanes=" + trustedLanes);
                }
                AppEventLogger.event(context, "waze_crop ok display=" + displayId
                        + " file=" + sourceFileName
                        + " bucket=" + bucket
                        + " backend=" + capture.backend
                        + " laneSource=" + laneSource
                        + " maneuverSource=" + maneuverSource
                        + " " + finalTimingDetail);
                logSlowFrameIfNeeded(
                        dir,
                        index,
                        displayId,
                        parseEndMs - capture.captureStartMs,
                        capture.captureEndMs - capture.captureStartMs,
                        artifactQueueMs,
                        geometryEndMs - geometryStartMs,
                        laneAnalysisEndMs - laneAnalysisStartMs,
                        visualParseEndMs - visualParseStartMs,
                        panelEndMs - panelStartMs,
                        candidateEndMs - candidateStartMs,
                        commitEligible,
                        commitSkipReason);
                NavigationLogStorage.enforceNavCaptureRetention(
                        context, SESSION_DIR, workerSessionName, sourceFileName);
                return;
            } finally {
                capture.frame.recycle();
                NavigationLogStorage.endCaptureFrame();
            }
        } catch (RuntimeException e) {
            log(dir, "frame parse fatal " + e.getClass().getSimpleName()
                    + " " + safe(e.getMessage()));
            NavHudLiveSender.get(context).onWazeCropUnavailable(
                    "frame-parse-fatal " + e.getClass().getSimpleName());
        }
    }

    //records per-frame beta capture metadata without adding a JSON dependency.
    private static String captureEventJson(
            int frameId,
            int displayId,
            WazeFrameCaptureBackend.CaptureResult capture,
            String backendUnavailableReason,
            WazeAccessibilityGeometry geometry,
            String sourceFrame,
            String arrowInput,
            String lanesInput,
            String bucket,
            String maneuver,
            String lanes,
            String laneSource,
            String maneuverSource,
            boolean commitEligible,
            String commitSkipReason) {
        return "{"
                + "\"frameId\":" + frameId
                + ",\"displayId\":" + displayId
                + ",\"backend\":\"" + NavCaptureStore.esc(capture.backend) + "\""
                + ",\"backendUnavailableReason\":\""
                + NavCaptureStore.esc(backendUnavailableReason) + "\""
                + ",\"captureMs\":" + Math.max(0L, capture.captureEndMs - capture.captureStartMs)
                + ",\"geometry\":\"" + NavCaptureStore.esc(geometry.summary()) + "\""
                + ",\"sourceFrame\":\"" + NavCaptureStore.esc(sourceFrame) + "\""
                + ",\"arrowInput\":\"" + NavCaptureStore.esc(arrowInput) + "\""
                + ",\"lanesInput\":\"" + NavCaptureStore.esc(lanesInput) + "\""
                + ",\"bucket\":\"" + NavCaptureStore.esc(bucket) + "\""
                + ",\"maneuver\":\"" + NavCaptureStore.esc(maneuver) + "\""
                + ",\"lanes\":\"" + NavCaptureStore.esc(lanes) + "\""
                + ",\"laneSource\":\"" + NavCaptureStore.esc(laneSource) + "\""
                + ",\"maneuverSource\":\"" + NavCaptureStore.esc(maneuverSource) + "\""
                + ",\"commitEligible\":" + commitEligible
                + ",\"commitSkipReason\":\"" + NavCaptureStore.esc(commitSkipReason) + "\""
                + "}";
    }

    //keeps unavailable backend diagnostics attached to frame metadata without changing parser behavior.
    private static String backendUnavailableDetail(String reason) {
        String safeReason = safe(reason);
        if (safeReason.isEmpty()) {
            return "";
        }
        return " backendUnavailableReason=" + safeReason;
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    private static String classifyBucket(NavSnapshot snapshot, boolean missingLaneGuidance) {
        return classifyBucket(
                snapshot,
                missingLaneGuidance
                        ? WazeVisualCueParser.LaneGuidanceAnalysis.unparsed()
                        : WazeVisualCueParser.LaneGuidanceAnalysis.none(),
                !isIdleSnapshot(snapshot));
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    private static String classifyBucket(
            NavSnapshot snapshot,
            WazeVisualCueParser.LaneGuidanceStatus laneStatus,
            boolean activeInstructionPanel) {
        return classifyBucket(
                snapshot,
                laneAnalysisFromStatus(laneStatus, false),
                activeInstructionPanel);
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    private static String classifyBucket(
            NavSnapshot snapshot,
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis,
            boolean activeInstructionPanel) {
        if (snapshot != null
                && snapshot.sourceApp == NavSnapshot.SourceApp.WAZE
                && snapshot.maneuver == NavSnapshot.Maneuver.ARRIVE) {
            return BUCKET_KNOWN;
        }
        if (!activeInstructionPanel) {
            return BUCKET_IGNORED_IDLE;
        }
        if (snapshot == null
                || snapshot.sourceApp != NavSnapshot.SourceApp.WAZE) {
            return BUCKET_MISSING_MANEUVERS;
        }
        if (isIdleSnapshot(snapshot)) {
            return BUCKET_IGNORED_IDLE;
        }
        if (blocksSingleFallback(laneAnalysis)) {
            return BUCKET_MISSING_LANES;
        }
        if (snapshot.maneuver == NavSnapshot.Maneuver.UNKNOWN) {
            return BUCKET_MISSING_MANEUVERS;
        }
        return BUCKET_KNOWN;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysisFromStatus(
            WazeVisualCueParser.LaneGuidanceStatus laneStatus,
            boolean blocksSingleFallback) {
        if (laneStatus == WazeVisualCueParser.LaneGuidanceStatus.UNPARSED_ROW) {
            return blocksSingleFallback
                    ? WazeVisualCueParser.LaneGuidanceAnalysis.unparsed()
                    : WazeVisualCueParser.LaneGuidanceAnalysis.none(
                            WazeVisualCueParser.LaneFailureReason.UNKNOWN_GLYPH);
        }
        return WazeVisualCueParser.LaneGuidanceAnalysis.none();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean blocksSingleFallback(
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis) {
        return laneAnalysis != null
                && laneAnalysis.status == WazeVisualCueParser.LaneGuidanceStatus.UNPARSED_ROW
                && laneAnalysis.blocksSingleFallback;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isMissingBucket(String bucket) {
        return BUCKET_MISSING_MANEUVERS.equals(bucket) || BUCKET_MISSING_LANES.equals(bucket);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isIdleSnapshot(NavSnapshot snapshot) {
        return snapshot != null
                && snapshot.maneuver == NavSnapshot.Maneuver.UNKNOWN
                && snapshot.confidence <= 0
                && snapshot.distanceMeters <= 0
                && safe(snapshot.streetName).isEmpty()
                && safe(snapshot.laneString).isEmpty();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String snapshotLanesForCrop(NavSnapshot snapshot) {
        if (snapshot == null
                || snapshot.sourceApp != NavSnapshot.SourceApp.WAZE
                || snapshot.maneuver == NavSnapshot.Maneuver.UNKNOWN) {
            return "";
        }
        if (!WazeVisualCueParser.isKnownMultiLaneSignature(snapshot.laneString)) {
            return "";
        }
        return snapshot.laneString;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String trustedLanesForCrop(
            NavSnapshot snapshot,
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis) {
        WazeVisualCueParser.LaneGuidanceStatus status = laneAnalysis == null
                ? WazeVisualCueParser.LaneGuidanceStatus.NONE
                : laneAnalysis.status;
        return trustedLanesForCrop(snapshot, status);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String trustedLanesForCrop(
            NavSnapshot snapshot,
            WazeVisualCueParser.LaneGuidanceStatus laneStatus) {
        if (laneStatus != WazeVisualCueParser.LaneGuidanceStatus.PARSED) {
            return "";
        }
        return snapshotLanesForCrop(snapshot);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String snapshotManeuver(NavSnapshot snapshot) {
        if (snapshot == null) {
            return "UNKNOWN";
        }
        return snapshot.maneuver.name();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean isActiveGeneration(int workerGeneration) {
        synchronized (lock) {
            return active && generation == workerGeneration;
        }
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    private int latestFrameId() {
        synchronized (lock) {
            return screenshotIndex;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String visualCommitSkipReason(
            boolean activeGeneration,
            int frameId,
            int latestFrameId,
            long ageMs) {
        if (!activeGeneration) {
            return "inactive-generation";
        }
        if (frameId <= 0 || latestFrameId <= 0) {
            return "bad-frame";
        }
        if (frameId != latestFrameId) {
            return "superseded-frame latestFrameId=" + latestFrameId;
        }
        if (ageMs > MAX_VISUAL_COMMIT_AGE_MS) {
            return "stale-frame-age ageMs=" + ageMs
                    + " maxMs=" + MAX_VISUAL_COMMIT_AGE_MS;
        }
        return "";
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String timingDetail(
            int frameId,
            int latestFrameId,
            long captureStartMs,
            long captureEndMs,
            long parseStartMs,
            long parseEndMs,
            long geometryCtxMs,
            long laneAnalysisMs,
            long visualParseMs,
            long panelMs,
            long candidateMs,
            long artifactQueueMs,
            boolean commitEligible,
            String commitSkipReason) {
        return "frameId=" + frameId
                + " latestFrameId=" + latestFrameId
                + " captureMs=" + Math.max(0L, captureEndMs - captureStartMs)
                + " parseMs=" + Math.max(0L, parseEndMs - parseStartMs)
                + " geometryCtxMs=" + Math.max(0L, geometryCtxMs)
                + " laneAnalysisMs=" + Math.max(0L, laneAnalysisMs)
                + " visualParseMs=" + Math.max(0L, visualParseMs)
                + " panelMs=" + Math.max(0L, panelMs)
                + " candidateMs=" + Math.max(0L, candidateMs)
                + " artifactQueueMs=" + Math.max(0L, artifactQueueMs)
                + " totalMs=" + Math.max(0L, parseEndMs - captureStartMs)
                + " commit=" + (commitEligible ? "yes" : "no")
                + " skipReason=" + NavCaptureStore.esc(safe(commitSkipReason));
    }

    //logs one coarse timing summary only when a frame threatens route freshness.
    private void logSlowFrameIfNeeded(
            File dir,
            int frameId,
            int displayId,
            long totalMs,
            long captureMs,
            long artifactQueueMs,
            long geometryCtxMs,
            long laneAnalysisMs,
            long visualParseMs,
            long panelMs,
            long candidateMs,
            boolean commitEligible,
            String commitSkipReason) {
        boolean stale = safe(commitSkipReason).startsWith("stale-frame-age");
        if (!stale && totalMs < SLOW_FRAME_LOG_MS) {
            return;
        }
        log(dir, "slow_frame frameId=" + frameId
                + " display=" + displayId
                + " totalMs=" + Math.max(0L, totalMs)
                + " captureMs=" + Math.max(0L, captureMs)
                + " artifactQueueMs=" + Math.max(0L, artifactQueueMs)
                + " geometryCtxMs=" + Math.max(0L, geometryCtxMs)
                + " laneAnalysisMs=" + Math.max(0L, laneAnalysisMs)
                + " visualParseMs=" + Math.max(0L, visualParseMs)
                + " panelMs=" + Math.max(0L, panelMs)
                + " candidateMs=" + Math.max(0L, candidateMs)
                + " dominant=" + dominantTimingBucket(
                captureMs,
                artifactQueueMs,
                geometryCtxMs,
                laneAnalysisMs,
                visualParseMs,
                panelMs,
                candidateMs)
                + " commit=" + (commitEligible ? "yes" : "no")
                + " skipReason=" + NavCaptureStore.esc(safe(commitSkipReason)));
    }

    //keeps slow-frame diagnostics cheap by reporting the biggest existing timing bucket.
    private static String dominantTimingBucket(
            long captureMs,
            long artifactQueueMs,
            long geometryCtxMs,
            long laneAnalysisMs,
            long visualParseMs,
            long panelMs,
            long candidateMs) {
        String name = "capture";
        long value = Math.max(0L, captureMs);
        long candidateValue = Math.max(0L, artifactQueueMs);
        if (candidateValue > value) {
            name = "artifactQueue";
            value = candidateValue;
        }
        candidateValue = Math.max(0L, geometryCtxMs);
        if (candidateValue > value) {
            name = "geometryCtx";
            value = candidateValue;
        }
        candidateValue = Math.max(0L, laneAnalysisMs);
        if (candidateValue > value) {
            name = "laneAnalysis";
            value = candidateValue;
        }
        candidateValue = Math.max(0L, visualParseMs);
        if (candidateValue > value) {
            name = "visualParse";
            value = candidateValue;
        }
        candidateValue = Math.max(0L, panelMs);
        if (candidateValue > value) {
            name = "panel";
            value = candidateValue;
        }
        candidateValue = Math.max(0L, candidateMs);
        if (candidateValue > value) {
            name = "candidate";
        }
        return name;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private String currentSessionShellDir() {
        synchronized (lock) {
            return sessionShellDir;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private boolean currentSessionShellWritable() {
        synchronized (lock) {
            return sessionShellWritable;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void log(File dir, String line) {
        String safeLine = safe(line);
        Log.i(TAG, safeLine);
        AppEventLogger.event(context, "waze_crop " + safeLine);
        WazeCaptureDebugWriter writer = WazeCaptureDebugWriter.get();
        writer.rawEvent(context, "waze_crop", WAZE_PACKAGE, safeLine);
        writer.appendSessionLine(dir, "{"
                + "\"t\":" + SystemClock.elapsedRealtime()
                + ",\"event\":\"" + NavCaptureStore.esc(safeLine) + "\""
                + "}");
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String timestampForFile() {
        synchronized (SESSION_FORMAT) {
            return SESSION_FORMAT.format(new Date());
        }
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
