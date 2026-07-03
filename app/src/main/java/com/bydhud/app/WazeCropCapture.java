package com.bydhud.app;

//captures Waze screen crops so template and geometry parsers have reproducible visual evidence.

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

//defines the WazeCropCapture module boundary so related behavior stays readable inside one unit.
final class WazeCropCapture {
    static final boolean PRODUCTION_DELETE_AFTER_PARSE = false;
    static final String BUCKET_KNOWN = "known";
    static final String BUCKET_IGNORED_IDLE = "ignored-idle";
    static final String BUCKET_MISSING_MANEUVERS = "missing-maneuvers";
    static final String BUCKET_MISSING_LANES = "missing-lanes";
    private static final String TAG = "BydHudWazeCrop";
    private static final String WAZE_PACKAGE = "com.waze";
    private static final String SESSION_DIR = "waze-crop";
    private static final String SESSION_LOG = "session.jsonl";
    private static final long CAPTURE_INTERVAL_MS = 1500L;
    private static final long INACTIVE_ROUTE_PROBE_INTERVAL_MS = 5000L;
    private static final long DISPLAY_REFRESH_INTERVAL_MS = 5000L;
    private static final long ACCESSIBILITY_GEOMETRY_TTL_MS = 7000L;
    static final long MAX_VISUAL_COMMIT_AGE_MS = 3000L;
    private static final SimpleDateFormat SESSION_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    private static WazeCropCapture instance;

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static synchronized WazeCropCapture get(Context context) {
        if (instance == null) {
            instance = new WazeCropCapture(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Object lock = new Object();
    private int generation;
    private boolean active;
    private File sessionDir;
    private String sessionShellDir = "";
    private boolean sessionShellWritable;
    private String sessionName = "";
    private int screenshotIndex;
    private NavAppDisplayState lastDisplayState = new NavAppDisplayState(
            WAZE_PACKAGE, -1, NavAppDisplayState.DISPLAY_UNKNOWN, false, "not checked");
    private long lastDisplayCheckMs;
    private WazeAccessibilityGeometry latestAccessibilityGeometry = WazeAccessibilityGeometry.EMPTY;
    private long latestAccessibilityGeometryMs;
    private final WazeFrameCaptureBackend frameBackend;
    private final WazeCaptureDebugArtifacts debugArtifacts = new WazeCaptureDebugArtifacts();

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
        Thread worker = new Thread(() -> runLoop(workerGeneration, dir, name),
                "BydHudWazeCrop");
        worker.start();
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    void stop(String reason) {
        File dir;
        synchronized (lock) {
            if (!active) {
                return;
            }
            active = false;
            generation++;
            dir = sessionDir;
            lastDisplayState = new NavAppDisplayState(
                    WAZE_PACKAGE, -1, NavAppDisplayState.DISPLAY_UNKNOWN, false, "not checked");
            lastDisplayCheckMs = 0L;
            latestAccessibilityGeometry = WazeAccessibilityGeometry.EMPTY;
            latestAccessibilityGeometryMs = 0L;
        }
        log(dir, "stop reason=" + safe(reason));
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
            if (!(displayId == 0 || displayId > 0) || !state.isUsableForCrop()) {
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
                log(dir, "frame backend unavailable frameId=" + index
                        + " backend=" + capture.backend
                        + " reason=" + capture.reason
                        + " fallbackBackend=" + WazeFrameCaptureBackend.BACKEND_SCREENCAP_FALLBACK);
                if (!currentSessionShellWritable() || shellDir.isEmpty()) {
                    log(dir, "frame unavailable frameId=" + index
                            + " backend=" + capture.backend
                            + " reason=" + capture.reason
                            + " fallbackSkipped=shell-path-unavailable");
                    NavHudLiveSender.get(context).onWazeCropUnavailable(
                            "frame-unavailable " + capture.reason);
                    return;
                }
                sourceFileName = String.format(Locale.US, "screen_%04d.png", index);
                String path = shellDir + "/" + sourceFileName;
                long fallbackStartMs = SystemClock.elapsedRealtime();
                LocalAdbBridge.ShellResult result =
                        LocalAdbBridge.runRuntimeShellCommand(
                                context, "screencap -d " + displayId + " -p " + path);
                long fallbackEndMs = SystemClock.elapsedRealtime();
                if (!result.success()) {
                    log(dir, "frame unavailable frameId=" + index
                            + " backend=" + capture.backend
                            + " reason=" + capture.reason
                            + " fallbackBackend=" + WazeFrameCaptureBackend.BACKEND_SCREENCAP_FALLBACK
                            + " captureMs=" + (fallbackEndMs - fallbackStartMs)
                            + " " + result.shortDetail());
                    NavHudLiveSender.get(context).onWazeCropUnavailable(
                            "frame-unavailable " + capture.reason);
                    return;
                }
                Bitmap fallback = BitmapFactory.decodeFile(new File(dir, sourceFileName).getAbsolutePath());
                if (fallback == null) {
                    log(dir, "screencap fallback decode failed frameId=" + index);
                    NavHudLiveSender.get(context).onWazeCropUnavailable(
                            "screencap-fallback-decode-failed display=" + displayId);
                    return;
                }
                capture = WazeFrameCaptureBackend.CaptureResult.okWithTiming(
                        WazeFrameCaptureBackend.BACKEND_SCREENCAP_FALLBACK,
                        fallback,
                        fallbackStartMs,
                        fallbackEndMs);
                if (!detailedDebugArtifacts) {
                    deleteQuietly(new File(dir, sourceFileName));
                    sourceFileName = "";
                }
            }
            try {
                if (!isActiveGeneration(workerGeneration)) {
                    return;
                }
                NavSnapshot snapshot = WazeRouteTracker.get(context).latestSnapshot();
                long sourceFrameSaveStartMs = SystemClock.elapsedRealtime();
                if (detailedDebugArtifacts && sourceFileName.isEmpty()) {
                    sourceFileName = debugArtifacts.saveSourceFrame(dir, index, capture.frame);
                }
                long sourceFrameSaveMs = SystemClock.elapsedRealtime() - sourceFrameSaveStartMs;
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
                String timingDetail = timingDetail(
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
                        sourceFrameSaveMs,
                        commitEligible,
                        commitSkipReason);
                if (visualNavigationCandidate && commitEligible) {
                    long now = SystemClock.elapsedRealtime();
                    WazeRouteTracker.get(context).onVisualRouteEvidence(
                            "visual navigation cue", now);
                    NavHudLiveSender.get(context).onWazeVisualRouteEvidence(
                            "visual navigation cue");
                    log(dir, "visual route evidence navigation cue file=" + sourceFileName
                            + " " + timingDetail);
                } else if (visualNavigationCandidate) {
                    log(dir, "visual route evidence skipped file=" + sourceFileName
                            + " " + timingDetail);
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
                                + " " + timingDetail);
                    }
                } else if (blocksSingleFallback(laneAnalysis)) {
                    if (commitEligible) {
                        NavHudLiveSender.get(context).onWazeUnknownLaneRow(
                                "file=" + sourceFileName + " reason=" + laneAnalysis.reason.name());
                    } else {
                        log(dir, "unknown lane row skipped file=" + sourceFileName
                                + " reason=" + laneAnalysis.reason.name()
                                + " " + timingDetail);
                    }
                } else if (!visualNavigationCandidate) {
                    if (commitEligible) {
                        NavHudLiveSender.get(context).onWazeCropUnavailable(
                                "main-visible-no-cue file=" + sourceFileName);
                    } else {
                        log(dir, "crop unavailable skipped file=" + sourceFileName
                                + " " + timingDetail);
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
                String missingFile = detailedDebugArtifacts
                        ? copyMissingCueIfNeeded(dir, sourceFileName, bucket)
                        : "";
                if (detailedDebugArtifacts && BUCKET_MISSING_LANES.equals(bucket)) {
                    exportMissingLaneCells(dir, sourceFileName, laneAnalysis);
                }
                WazeCropCandidate candidate = new WazeCropCandidate(
                        SystemClock.elapsedRealtime(),
                        displayId,
                        sourceFileName,
                        "backend=" + capture.backend
                                + backendUnavailableDetail(backendUnavailableReason)
                                + " routeAgeMs=" + evidenceAgeMs + " " + timingDetail,
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
                appendSessionLine(dir, candidate.toJsonLine());
                NavCaptureStore.rawEvent(context, "waze_crop", WAZE_PACKAGE, candidate.toJsonLine());
                if (detailedDebugArtifacts) {
                    debugArtifacts.appendEvent(dir, captureEventJson(
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
                    NavCaptureStore.rawEvent(context, "waze_crop_missing", WAZE_PACKAGE,
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
                        + " " + timingDetail);
                NavigationLogStorage.enforceNavCaptureRetention(
                        context, SESSION_DIR, workerSessionName, sourceFileName);
                return;
            } finally {
                capture.frame.recycle();
            }
        } catch (RuntimeException e) {
            log(dir, "frame parse fatal " + e.getClass().getSimpleName()
                    + " " + safe(e.getMessage()));
            NavHudLiveSender.get(context).onWazeCropUnavailable(
                    "frame-parse-fatal " + e.getClass().getSimpleName());
        } catch (IOException e) {
            log(dir, "frame fatal " + e.getClass().getSimpleName()
                    + " " + safe(e.getMessage()));
            NavHudLiveSender.get(context).onWazeCropUnavailable(
                    "frame-fatal " + e.getClass().getSimpleName());
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

    //keeps fallback diagnostics attached to successful screencap frames without changing parser behavior.
    private static String backendUnavailableDetail(String reason) {
        String safeReason = safe(reason);
        if (safeReason.isEmpty()) {
            return "";
        }
        return " backendUnavailableReason=" + safeReason;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private String copyMissingCueIfNeeded(File dir, String fileName, String bucket) {
        if (!isMissingBucket(bucket) || dir == null || fileName == null || fileName.isEmpty()) {
            return "";
        }
        File source = new File(dir, fileName);
        File targetDir = new File(dir, bucket);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            log(dir, "missing bucket mkdir failed: " + bucket);
            return "";
        }
        File target = new File(targetDir, fileName);
        try {
            copyFile(source, target);
            return bucket + "/" + fileName;
        } catch (IOException e) {
            log(dir, "missing bucket copy failed: " + bucket
                    + " " + safe(e.getMessage()));
            return "";
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void exportMissingLaneCells(
            File dir,
            String fileName,
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis) {
        if (dir == null || laneAnalysis == null || laneAnalysis.cells.isEmpty()) {
            return;
        }
        File source = new File(dir, fileName);
        File targetDir = new File(dir, BUCKET_MISSING_LANES);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            log(dir, "missing lane cell mkdir failed");
            return;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (bitmap == null) {
            log(dir, "missing lane cell decode failed file=" + fileName);
            return;
        }
        int exported = 0;
        try {
            List<WazeLaneCell> cells = laneAnalysis.cells;
            for (WazeLaneCell cell : cells) {
                if (!shouldExportMissingLaneCell(cell)) {
                    continue;
                }
                if (exportLaneCell(bitmap, targetDir, fileName, cell)) {
                    exported++;
                }
            }
        } finally {
            bitmap.recycle();
        }
        log(dir, "missing lane cell artifacts file=" + fileName + " count=" + exported);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean shouldExportMissingLaneCell(WazeLaneCell cell) {
        if (cell == null) {
            return false;
        }
        String reason = cell.failureReason == null ? "" : cell.failureReason.trim();
        return !"NONE".equals(reason);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean exportLaneCell(
            Bitmap source,
            File targetDir,
            String fileName,
            WazeLaneCell cell) {
        if (source == null || targetDir == null || cell == null) {
            return false;
        }
        int x1 = clamp(cell.x1, 0, source.getWidth() - 1);
        int y1 = clamp(cell.y1, 0, source.getHeight() - 1);
        int x2 = clamp(cell.x2, x1, source.getWidth() - 1);
        int y2 = clamp(cell.y2, y1, source.getHeight() - 1);
        int width = x2 - x1 + 1;
        int height = y2 - y1 + 1;
        if (width <= 0 || height <= 0) {
            return false;
        }
        String baseName = stripPngSuffix(fileName)
                + ".cell_" + String.format(Locale.US, "%02d", cell.index);
        Bitmap raw = Bitmap.createBitmap(source, x1, y1, width, height);
        try {
            File rawFile = new File(targetDir, baseName + ".raw.png");
            if (!writePng(raw, rawFile)) {
                return false;
            }
            Bitmap normalized = normalizedCell(raw);
            try {
                File normalizedFile = new File(targetDir, baseName + ".norm.png");
                if (!writePng(normalized, normalizedFile)) {
                    return false;
                }
            } finally {
                normalized.recycle();
            }
            writeCellMeta(new File(targetDir, baseName + ".meta.json"), fileName, cell, x1, y1, x2, y2);
            return true;
        } finally {
            raw.recycle();
        }
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static Bitmap normalizedCell(Bitmap raw) {
        final int targetWidth = 64;
        final int targetHeight = 96;
        Bitmap out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF000000);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        float scale = Math.min(
                targetWidth / Math.max(1.0f, raw.getWidth()),
                targetHeight / Math.max(1.0f, raw.getHeight()));
        int drawWidth = Math.max(1, Math.round(raw.getWidth() * scale));
        int drawHeight = Math.max(1, Math.round(raw.getHeight() * scale));
        int left = (targetWidth - drawWidth) / 2;
        int top = (targetHeight - drawHeight) / 2;
        Rect dst = new Rect(left, top, left + drawWidth, top + drawHeight);
        canvas.drawBitmap(raw, null, dst, paint);
        return out;
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    private static boolean writePng(Bitmap bitmap, File file) {
        if (bitmap == null || file == null) {
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            return false;
        }
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    private static void writeCellMeta(
            File file,
            String sourceScreen,
            WazeLaneCell cell,
            int x1,
            int y1,
            int x2,
            int y2) {
        if (file == null || cell == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write("{"
                    + "\"sourceScreen\":\"" + NavCaptureStore.esc(sourceScreen) + "\""
                    + ",\"cellIndex\":" + cell.index
                    + ",\"bbox\":{\"x1\":" + x1
                    + ",\"y1\":" + y1
                    + ",\"x2\":" + x2
                    + ",\"y2\":" + y2 + "}"
                    + ",\"geometryGuess\":\"" + NavCaptureStore.esc(cell.geometryGuess) + "\""
                    + ",\"failureReason\":\"" + NavCaptureStore.esc(cell.failureReason) + "\""
                    + ",\"layout\":\"LANES\""
                    + "}");
        } catch (IOException ignored) {
            //keeps debug export best-effort because missing artifacts must not block live navigation.
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String stripPngSuffix(String fileName) {
        String safeName = fileName == null ? "screen" : fileName.trim();
        if (safeName.toLowerCase(Locale.US).endsWith(".png")) {
            return safeName.substring(0, safeName.length() - 4);
        }
        return safeName;
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static void copyFile(File source, File target) throws IOException {
        FileInputStream in = new FileInputStream(source);
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
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
            long sourceFrameSaveMs,
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
                + " sourceFrameSaveMs=" + Math.max(0L, sourceFrameSaveMs)
                + " totalMs=" + Math.max(0L, parseEndMs - captureStartMs)
                + " commit=" + (commitEligible ? "yes" : "no")
                + " skipReason=" + NavCaptureStore.esc(safe(commitSkipReason));
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
        NavCaptureStore.rawEvent(context, "waze_crop", WAZE_PACKAGE, safeLine);
        appendSessionLine(dir, "{"
                + "\"t\":" + SystemClock.elapsedRealtime()
                + ",\"event\":\"" + NavCaptureStore.esc(safeLine) + "\""
                + "}");
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void appendSessionLine(File dir, String line) {
        if (dir == null) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File file = new File(dir, SESSION_LOG);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException e) {
            Log.w(TAG, "session append failed: " + file.getAbsolutePath(), e);
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //keeps fallback screencaps temporary unless detailed artifact capture is enabled.
    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            //best effort cleanup; parse already has the decoded frame.
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
