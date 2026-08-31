package com.bydhud.app;

//observes foreground apps for bounded log-only diagnostics.

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

//anchors the NavAccessibilityService android entry point so lifecycle recovery stays separate from business logic.
public final class NavAccessibilityService extends AccessibilityService {
    private static final long THROTTLE_MS = 500L;
    private static final long STEERING_KEY_TAIL_TIMEOUT_MS = 3_000L;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 80;
    private static final int PAYLOAD_CHAR_LIMIT = 2000;
    private static final int FIELD_CHAR_LIMIT = 180;
    private static final String TRUNCATED_MARKER = "[truncated]";
    private static volatile NavAccessibilityService activeService;
    private static volatile long lastConnectedElapsedMs;
    private static volatile long lastEventElapsedMs;
    private static volatile boolean runtimeCrashed;
    private static volatile String lastRuntimeDetail = "never connected";
    private static final AtomicLong STEERING_LEARNING_REVISION = new AtomicLong();

    private final Object captureQueueLock = new Object();
    private final HandlerThread captureThread = new HandlerThread(
            "BydHudAccessibilityCapture", Process.THREAD_PRIORITY_BACKGROUND);
    private Handler captureHandler;
    private String pendingPackageName;
    private String pendingSource;
    private boolean captureScheduled;
    private long lastCaptureElapsedMs;
    private final Set<String> observedThisProcess = new HashSet<>();
    private final Object steeringLock = new Object();
    private volatile boolean keyLearning;
    private volatile boolean steeringSuspended;
    private volatile int capturedKeyCode = SteeringTransferPreferences.NO_KEY_CODE;
    private volatile int suppressKeyCode = SteeringTransferPreferences.NO_KEY_CODE;
    private volatile boolean mappedKeyActive;
    private volatile long steeringRuntimeGeneration;
    private long steeringKeyTailGeneration;

    @Override
    public void onCreate() {
        super.onCreate();
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        NavCaptureIngressPolicy.refreshPreferencesAsync(this);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isConnectedForRuntimeCheck() {
        return activeService != null;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isCrashedForRuntimeCheck() {
        return runtimeCrashed;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String runtimeDetailForRuntimeCheck() {
        NavAccessibilityService service = activeService;
        if (service != null) {
            return "connected elapsedMs=" + lastConnectedElapsedMs
                    + " lastEventMs=" + lastEventElapsedMs;
        }
        return lastRuntimeDetail;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void requestActiveWindowCapture(Context context, String packageName, String reason) {
        NavAccessibilityService service = activeService;
        if (service == null) {
            AppEventLogger.event(context, "accessibility_active_scan skipped no-service reason="
                    + safe(reason));
            return;
        }
        NavCaptureIngressPolicy.Mode mode = NavCaptureIngressPolicy.mode(packageName);
        if (mode == NavCaptureIngressPolicy.Mode.LOG_ONLY) {
            service.postCaptureActiveWindow(packageName, "active-" + safe(reason));
        }
    }

    static void cancelPendingCapture(String packageName) {
        NavAccessibilityService service = activeService;
        if (service == null) return;
        String normalized = safe(packageName).toLowerCase(java.util.Locale.ROOT);
        synchronized (service.captureQueueLock) {
            if (!normalized.equals(safe(service.pendingPackageName)
                    .toLowerCase(java.util.Locale.ROOT))) return;
            service.pendingPackageName = null;
            service.pendingSource = null;
        }
    }

    static void suspendForUserShutdown(Context context, String reason) {
        NavAccessibilityService service = activeService;
        if (service == null) {
            return;
        }
        service.steeringSuspended = true;
        Handler handler = service.captureHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        synchronized (service.captureQueueLock) {
            service.pendingPackageName = null;
            service.pendingSource = null;
            service.captureScheduled = false;
        }
        service.clearSteeringTransientState();
        AppEventLogger.event(context, "accessibility_service suspended reason=" + safe(reason));
    }

    /** Starts raw key learning; the first delivered non-repeat down is persisted. */
    static boolean beginKeyLearning(Context context) {
        NavAccessibilityService service = activeService;
        if (service == null) {
            AppEventLogger.event(context, "steering_learning skipped no-service");
            return false;
        }
        service.beginKeyLearningInternal();
        return true;
    }

    static void cancelKeyLearning() {
        NavAccessibilityService service = activeService;
        if (service != null) service.cancelKeyLearningTransient();
    }

    static boolean isKeyLearning() {
        NavAccessibilityService service = activeService;
        return service != null && service.keyLearning;
    }

    static int capturedKeyCode() {
        NavAccessibilityService service = activeService;
        return service == null ? SteeringTransferPreferences.NO_KEY_CODE : service.capturedKeyCode;
    }

    static long keyLearningRevision() {
        return STEERING_LEARNING_REVISION.get();
    }

    private boolean isSteeringRequestCurrent(long runtimeGeneration, long bindingRevision) {
        return SteeringTransferPolicy.isRequestCurrent(
                activeService == this && !steeringSuspended,
                HudPrefs.isUserShutdownActive(this),
                runtimeGeneration, steeringRuntimeGeneration,
                bindingRevision, SteeringTransferPreferences.revision(this));
    }

    static void resumeSteeringRuntime(Context context, String reason) {
        NavAccessibilityService service = activeService;
        if (service == null) return;
        service.steeringSuspended = false;
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeService = this;
        synchronized (steeringLock) {
            steeringRuntimeGeneration++;
        }
        steeringSuspended = HudPrefs.isUserShutdownActive(this);
        runtimeCrashed = false;
        lastConnectedElapsedMs = SystemClock.elapsedRealtime();
        lastRuntimeDetail = "connected";
        AppEventLogger.event(this, "accessibility_service connected");
        NavCaptureIngressPolicy.refreshPreferencesAsync(this);
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (steeringSuspended) return;
        String packageName = safe(event.getPackageName());
        if (packageName.isEmpty()) return;
        boolean newlyObserved;
        synchronized (observedThisProcess) {
            newlyObserved = observedThisProcess.size() < 128
                    && observedThisProcess.add(packageName);
        }
        if (newlyObserved) postObservedPackage(packageName);
        if (NavCaptureIngressPolicy.mode(packageName)
                != NavCaptureIngressPolicy.Mode.LOG_ONLY) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastCaptureElapsedMs < THROTTLE_MS) {
            return;
        }
        lastCaptureElapsedMs = now;
        postCaptureActiveWindow(packageName,
                "eventType=" + event.getEventType());
    }

    @Override
    //cleans up lifecycle state here so Android teardown does not leave stale runtime markers behind.
    public void onDestroy() {
        clearSteeringTransientState();
        Handler handler = captureHandler;
        if (activeService == this) {
            activeService = null;
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        synchronized (captureQueueLock) {
            pendingPackageName = null;
            pendingSource = null;
            captureScheduled = false;
        }
        captureThread.quitSafely();
        lastRuntimeDetail = "destroyed";
        AppEventLogger.event(this, "accessibility_service destroyed");
        super.onDestroy();
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onInterrupt() {
        clearSteeringTransientState();
        Handler handler = captureHandler;
        if (handler != null) {
            handler.post(() -> NavCaptureStore.rawEvent(
                    this, "accessibility_interrupt", "", "service interrupted"));
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        if (keyLearning && HudPrefs.isUserShutdownActive(this)) {
            cancelKeyLearningTransient();
        }
        if (keyLearning) {
            if (SteeringTransferPolicy.isFirstDown(
                    event.getAction(), event.getRepeatCount())) {
                int code = event.getKeyCode();
                synchronized (steeringLock) {
                    capturedKeyCode = code;
                    keyLearning = false;
                    suppressKeyCode = code;
                    mappedKeyActive = false;
                }
                STEERING_LEARNING_REVISION.incrementAndGet();
                armSteeringKeyTailTimeout();
                SteeringTransferPreferences.setKeyCode(this, code);
                AppEventLogger.event(this, "steering_learning captured keycode=" + code);
                MainActivity.publishSharedUiStateChange();
            }
            //While the dialog is open, every delivered key event belongs to learning.
            return true;
        }

        int keyCode = event.getKeyCode();
        int suppressed = suppressKeyCode;
        if (suppressed >= 0 && keyCode == suppressed) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                synchronized (steeringLock) {
                    suppressKeyCode = SteeringTransferPreferences.NO_KEY_CODE;
                }
                cancelSteeringKeyTailTimeoutIfIdle();
            } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
                armSteeringKeyTailTimeout();
            }
            return true;
        }

        final long runtimeGeneration = steeringRuntimeGeneration;
        final long bindingRevision = SteeringTransferPreferences.revision(this);
        int configured = SteeringTransferPreferences.keyCode(this);
        if (!SteeringTransferPolicy.isMappedKey(keyCode, configured)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean startTransfer;
            synchronized (steeringLock) {
                startTransfer = SteeringTransferPolicy.shouldStartTransfer(
                        event.getAction(), event.getRepeatCount(), mappedKeyActive);
                mappedKeyActive = true;
            }
            armSteeringKeyTailTimeout();
            if (startTransfer) {
                NavAppDisplayController.get(this).requestSteeringToggle(
                        SteeringTransferPreferences.packageName(this),
                        SteeringTransferPreferences.profile(this),
                        "steering-key",
                        () -> isSteeringRequestCurrent(runtimeGeneration, bindingRevision));
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            synchronized (steeringLock) {
                mappedKeyActive = false;
            }
            cancelSteeringKeyTailTimeoutIfIdle();
        }
        //The assigned key never reaches stock handling, even when no transfer can run.
        return true;
    }

    private void beginKeyLearningInternal() {
        synchronized (steeringLock) {
            keyLearning = true;
            capturedKeyCode = SteeringTransferPreferences.NO_KEY_CODE;
            suppressKeyCode = SteeringTransferPreferences.NO_KEY_CODE;
            mappedKeyActive = false;
            steeringKeyTailGeneration++;
        }
        STEERING_LEARNING_REVISION.incrementAndGet();
        AppEventLogger.event(this, "steering_learning started");
        MainActivity.publishSharedUiStateChange();
    }

    private void cancelKeyLearningTransient() {
        boolean changed;
        synchronized (steeringLock) {
            changed = keyLearning;
            keyLearning = false;
            capturedKeyCode = SteeringTransferPreferences.keyCode(this);
            mappedKeyActive = false;
            if (suppressKeyCode < 0) {
                steeringKeyTailGeneration++;
            }
        }
        if (changed) STEERING_LEARNING_REVISION.incrementAndGet();
        MainActivity.publishSharedUiStateChange();
    }

    private void clearSteeringTransientState() {
        boolean changed;
        synchronized (steeringLock) {
            steeringRuntimeGeneration++;
            changed = keyLearning;
            keyLearning = false;
            capturedKeyCode = SteeringTransferPreferences.keyCode(this);
            suppressKeyCode = SteeringTransferPreferences.NO_KEY_CODE;
            mappedKeyActive = false;
            steeringKeyTailGeneration++;
        }
        if (changed) STEERING_LEARNING_REVISION.incrementAndGet();
        MainActivity.publishSharedUiStateChange();
    }

    private void armSteeringKeyTailTimeout() {
        Handler handler = captureHandler;
        if (handler == null) return;
        final long generation;
        synchronized (steeringLock) {
            generation = ++steeringKeyTailGeneration;
        }
        handler.postDelayed(
                () -> expireSteeringKeyTail(generation),
                STEERING_KEY_TAIL_TIMEOUT_MS);
    }

    private void cancelSteeringKeyTailTimeoutIfIdle() {
        synchronized (steeringLock) {
            if (suppressKeyCode < 0 && !mappedKeyActive) {
                steeringKeyTailGeneration++;
            }
        }
    }

    private void expireSteeringKeyTail(long generation) {
        synchronized (steeringLock) {
            if (generation != steeringKeyTailGeneration) return;
            suppressKeyCode = SteeringTransferPreferences.NO_KEY_CODE;
            mappedKeyActive = false;
            steeringKeyTailGeneration++;
        }
        AppEventLogger.event(this, "steering_key_tail expired");
    }

    //guard active-window traversal so accessibility node trees are captured by one serialized path.
    private void postCaptureActiveWindow(String packageName, String source) {
        Handler handler = captureHandler;
        if (handler == null) {
            return;
        }
        synchronized (captureQueueLock) {
            pendingPackageName = packageName;
            pendingSource = source;
            if (captureScheduled) {
                return;
            }
            captureScheduled = true;
        }
        if (!handler.post(this::drainLatestCapture)) {
            synchronized (captureQueueLock) {
                captureScheduled = false;
            }
        }
    }

    private void postObservedPackage(String packageName) {
        Handler handler = captureHandler;
        if (handler != null) {
            handler.post(() -> NavCapturePrefs.addObservedPackageFast(this, packageName));
        }
    }

    private void drainLatestCapture() {
        String packageName;
        String source;
        synchronized (captureQueueLock) {
            packageName = pendingPackageName;
            source = pendingSource;
            pendingPackageName = null;
            pendingSource = null;
            if (packageName == null || activeService != this) {
                captureScheduled = false;
                return;
            }
        }
        captureActiveWindow(packageName, source);
        Handler handler = captureHandler;
        synchronized (captureQueueLock) {
            if (pendingPackageName == null || handler == null) {
                captureScheduled = false;
                return;
            }
        }
        handler.postDelayed(this::drainLatestCapture, THROTTLE_MS);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void captureActiveWindow(String packageName, String source) {
        NavCaptureIngressPolicy.Mode mode = NavCaptureIngressPolicy.mode(packageName);
        if (mode != NavCaptureIngressPolicy.Mode.LOG_ONLY) return;
        try {
            lastEventElapsedMs = SystemClock.elapsedRealtime();
            runtimeCrashed = false;
            lastRuntimeDetail = "capture ok elapsedMs=" + lastEventElapsedMs;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                NavCaptureStore.rawEvent(this, "accessibility", packageName,
                        source + "; root=false");
                return;
            }
            try {
                CaptureState state = new CaptureState();
                StringBuilder builder = new StringBuilder(512);
                builder.append(source);
                builder.append("; package=").append(packageName);
                collectNode(packageName, root, 0, builder, state);
                builder.append("; nodes=").append(state.nodes);
                builder.append("; truncated=").append(state.truncated ? "true" : "false");
                String payload = capPayload(builder.toString());
                publishAccessibilityPayload(packageName, payload);
            } finally {
                root.recycle();
            }
        } catch (RuntimeException e) {
            runtimeCrashed = true;
            lastRuntimeDetail = "capture error " + e.getClass().getSimpleName()
                    + ": " + safe(e.getMessage());
            AppEventLogger.event(this, "accessibility_service capture_error "
                    + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void publishAccessibilityPayload(String packageName, String payload) {
        if (NavCaptureIngressPolicy.mode(packageName)
                != NavCaptureIngressPolicy.Mode.LOG_ONLY) {
            return;
        }
        NavCaptureStore.rawEvent(this, "accessibility", packageName, payload);
        NavParserResult parsed = NavParserDispatcher.parseAccessibility(
                packageName, payload, null);
        if (parsed != null) {
            NavCaptureStore.snapshot(this, parsed.snapshot);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void collectNode(
            String packageName,
            AccessibilityNodeInfo node,
            int depth,
            StringBuilder builder,
            CaptureState state) {
        if (node == null || state.truncated) {
            return;
        }
        if (depth > MAX_DEPTH || state.nodes >= MAX_NODES) {
            state.truncated = true;
            return;
        }
        state.nodes++;
        appendNode(packageName, builder, depth, node);
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                collectNode(packageName, child, depth + 1, builder, state);
            } finally {
                child.recycle();
            }
            if (state.truncated) {
                return;
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void appendNode(
            String packageName,
            StringBuilder builder,
            int depth,
            AccessibilityNodeInfo node) {
        String text = safe(node.getText());
        String description = safe(node.getContentDescription());
        String viewId = safe(node.getViewIdResourceName());
        String className = safe(node.getClassName());
        if (!NavAccessibilityNodeCapturePolicy.shouldLogNodeForCapture(
                packageName, viewId, text, description, className)) {
            return;
        }
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        builder.append("; node[")
                .append(depth)
                .append("]");
        if (!viewId.isEmpty()) {
            builder.append(" id=").append(capField(viewId));
        }
        if (!text.isEmpty()) {
            builder.append(" text=").append(capField(text));
        }
        if (!description.isEmpty()) {
            builder.append(" desc=").append(capField(description));
        }
        if (text.isEmpty() && description.isEmpty() && !className.isEmpty()) {
            builder.append(" class=").append(capField(className));
        }
        if (text.isEmpty() && description.isEmpty() && !className.isEmpty()) {
            builder.append(" bounds=").append(capField(bounds.left + ","
                    + bounds.top + "," + bounds.right + "," + bounds.bottom));
        }
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(CharSequence value) {
        if (value == null) {
            return "";
        }
        return value.toString().replace('\n', ' ').replace('\r', ' ').trim();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String capField(String value) {
        return cap(value, FIELD_CHAR_LIMIT);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String capPayload(String value) {
        return cap(value, PAYLOAD_CHAR_LIMIT);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String cap(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        int prefixLength = Math.max(0, limit - TRUNCATED_MARKER.length());
        return value.substring(0, prefixLength) + TRUNCATED_MARKER;
    }

    //models CaptureState data here so transport and parser layers share a stable contract.
    private static final class CaptureState {
        int nodes;
        boolean truncated;
    }

}
