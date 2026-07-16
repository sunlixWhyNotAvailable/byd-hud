package com.bydhud.app;

//observes foreground navigation apps so HUD output can react when notifications are incomplete.

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

//anchors the NavAccessibilityService android entry point so lifecycle recovery stays separate from business logic.
public final class NavAccessibilityService extends AccessibilityService {
    private static final long THROTTLE_MS = 500L;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 80;
    private static final int PAYLOAD_CHAR_LIMIT = 2000;
    private static final int FIELD_CHAR_LIMIT = 180;
    private static final String TRUNCATED_MARKER = "[truncated]";
    private static final String WAZE_PACKAGE = "com.waze";
    private static volatile NavAccessibilityService activeService;
    private static volatile long lastConnectedElapsedMs;
    private static volatile long lastEventElapsedMs;
    private static volatile boolean runtimeCrashed;
    private static volatile String lastRuntimeDetail = "never connected";

    private final Handler captureHandler = new Handler(Looper.getMainLooper());
    private long lastCaptureElapsedMs;

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
        service.postCaptureActiveWindow(packageName, "active-" + safe(reason));
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeService = this;
        runtimeCrashed = false;
        lastConnectedElapsedMs = SystemClock.elapsedRealtime();
        lastRuntimeDetail = "connected";
        AppEventLogger.event(this, "accessibility_service connected");
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        String packageName = String.valueOf(event.getPackageName());
        NavCapturePrefs.addObservedPackage(this, packageName);
        if (!NavCapturePrefs.isCaptureEnabled(this, packageName)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastCaptureElapsedMs < THROTTLE_MS) {
            return;
        }
        lastCaptureElapsedMs = now;
        postCaptureActiveWindow(packageName, "eventType=" + event.getEventType());
    }

    @Override
    //cleans up lifecycle state here so Android teardown does not leave stale runtime markers behind.
    public void onDestroy() {
        if (activeService == this) {
            activeService = null;
        }
        captureHandler.removeCallbacksAndMessages(null);
        lastRuntimeDetail = "destroyed";
        AppEventLogger.event(this, "accessibility_service destroyed");
        super.onDestroy();
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onInterrupt() {
        NavCaptureStore.rawEvent(this, "accessibility_interrupt", "", "service interrupted");
    }

    //guard active-window traversal so accessibility node trees are captured by one serialized path.
    private void postCaptureActiveWindow(String packageName, String source) {
        captureHandler.post(() -> captureActiveWindow(packageName, source));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void captureActiveWindow(String packageName, String source) {
        try {
            lastEventElapsedMs = SystemClock.elapsedRealtime();
            runtimeCrashed = false;
            lastRuntimeDetail = "capture ok elapsedMs=" + lastEventElapsedMs;
            WazeRouteNodeScanResult wazeNodes = null;
            if (WAZE_PACKAGE.equals(packageName)) {
                wazeNodes = captureWazeRouteNodesAcrossWindows(source);
                if (wazeNodes.hasRouteEvidence) {
                    publishAccessibilityPayload(packageName, wazeNodes.payload);
                }
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                NavCaptureStore.rawEvent(this, "accessibility", packageName,
                        source + "; root=false");
                if (wazeNodes != null && !wazeNodes.hasRouteEvidence) {
                    NavHudLiveSender.get(this).onWazeRouteNodesMissing(
                            source + " windows=" + wazeNodes.windowCount + " root=false");
                }
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
                boolean feedLiveParser = wazeNodes == null || !wazeNodes.hasRouteEvidence;
                NavRouteEvidencePolicy.RawRouteState rawState =
                        publishAccessibilityPayload(packageName, payload, feedLiveParser);
                if (wazeNodes != null && wazeNodes.hasRouteEvidence) {
                    NavHudLiveSender.get(this).updateWazeAccessibilityGeometry(
                            packageName, payload);
                } else if (wazeNodes != null
                        && rawState != NavRouteEvidencePolicy.RawRouteState.ACTIVE_ROUTE) {
                    NavHudLiveSender.get(this).onWazeRouteNodesMissing(
                            source + " windows=" + wazeNodes.windowCount);
                }
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
    private NavRouteEvidencePolicy.RawRouteState publishAccessibilityPayload(
            String packageName, String payload) {
        return publishAccessibilityPayload(packageName, payload, true);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private NavRouteEvidencePolicy.RawRouteState publishAccessibilityPayload(
            String packageName, String payload, boolean feedLiveParser) {
        NavCaptureStore.rawEvent(this, "accessibility", packageName, payload);
        NavRouteEvidencePolicy.RawRouteState rawState =
                NavRouteEvidencePolicy.classifyRawPayload(packageName, payload);
        if (rawState == NavRouteEvidencePolicy.RawRouteState.ACTIVE_ROUTE) {
            long nowElapsedMs = SystemClock.elapsedRealtime();
            NavRouteStateStore.get(this).updateFromRawPayload(
                    packageName, "accessibility_raw", payload, nowElapsedMs);
            WazeRouteTracker.get(this).updateFromRawPayload(
                    "accessibility_raw", packageName, payload, nowElapsedMs);
        }
        if (feedLiveParser) {
            NavHudLiveSender.get(this).updateFromNavigationAccessibility(packageName, payload);
        }
        return rawState;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private WazeRouteNodeScanResult captureWazeRouteNodesAcrossWindows(String source) {
        StringBuilder builder = new StringBuilder(512);
        builder.append(source)
                .append("; package=").append(WAZE_PACKAGE)
                .append("; waze_nodes=true");
        int[] nodeIndex = {0};
        int windowCount = 0;

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            windowCount = windows.size();
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                try {
                    appendWazeRouteNodes(root, builder, nodeIndex);
                } finally {
                    root.recycle();
                }
            }
        }

        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null) {
            try {
                appendWazeRouteNodes(activeRoot, builder, nodeIndex);
            } finally {
                activeRoot.recycle();
            }
        }

        boolean hasRouteEvidence =
                NavRouteEvidencePolicy.hasWazeRouteNodeEvidence(builder.toString());
        builder.append(hasRouteEvidence ? "; waze_nodes ok" : "; waze_nodes missing");
        String payload = capPayload(builder
                .append("; nodes=").append(nodeIndex[0])
                .append("; windows=").append(windowCount)
                .toString());
        NavCaptureStore.rawEvent(this, "accessibility_waze_nodes", WAZE_PACKAGE, payload);
        return new WazeRouteNodeScanResult(payload, hasRouteEvidence, windowCount);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void appendWazeRouteNodes(
            AccessibilityNodeInfo root,
            StringBuilder builder,
            int[] nodeIndex) {
        if (root == null) {
            return;
        }
        appendWazeRouteNodeMatches(builder, nodeIndex, "com.waze:id/navBarDistance",
                root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance"));
        appendWazeRouteNodeMatches(builder, nodeIndex, "com.waze:id/navBarStreetLine",
                root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarStreetLine"));
        appendWazeRouteNodeMatches(builder, nodeIndex, "com.waze:id/lblDistanceToDestination",
                root.findAccessibilityNodeInfosByViewId("com.waze:id/lblDistanceToDestination"));
        appendWazeRouteNodeMatches(builder, nodeIndex, "com.waze:id/lblTimeToDestination",
                root.findAccessibilityNodeInfosByViewId("com.waze:id/lblTimeToDestination"));
        appendWazeRouteNodeMatches(builder, nodeIndex, "com.waze:id/lblArrivalTime",
                root.findAccessibilityNodeInfosByViewId("com.waze:id/lblArrivalTime"));
        appendWazeRouteNodeMatches(builder, nodeIndex, "com.waze:id/pillViewLabel",
                root.findAccessibilityNodeInfosByViewId("com.waze:id/pillViewLabel"));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void appendWazeRouteNodeMatches(
            StringBuilder builder,
            int[] nodeIndex,
            String viewId,
            List<AccessibilityNodeInfo> matches) {
        if (matches == null) {
            return;
        }
        for (AccessibilityNodeInfo match : matches) {
            if (match == null) {
                continue;
            }
            try {
                if (appendDirectWazeNode(builder, nodeIndex[0], viewId, match)) {
                    nodeIndex[0]++;
                }
            } finally {
                match.recycle();
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static boolean appendDirectWazeNode(
            StringBuilder builder,
            int index,
            String viewId,
            AccessibilityNodeInfo node) {
        String text = safe(node.getText());
        String description = safe(node.getContentDescription());
        if (text.isEmpty() && description.isEmpty()) {
            return false;
        }
        builder.append("; node[").append(index).append("] id=").append(capField(viewId));
        if (!text.isEmpty()) {
            builder.append(" text=").append(capField(text));
        }
        if (!description.isEmpty()) {
            builder.append(" desc=").append(capField(description));
        }
        return true;
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
        boolean includeBounds = shouldAppendBoundsForCapture(packageName, viewId);
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
        if (includeBounds || (text.isEmpty() && description.isEmpty() && !className.isEmpty())) {
            builder.append(" bounds=").append(capField(bounds.left + ","
                    + bounds.top + "," + bounds.right + "," + bounds.bottom));
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean shouldAppendBoundsForCapture(String packageName, String viewId) {
        if (NavTextNormalizer.sourceApp(packageName) != NavSnapshot.SourceApp.WAZE) {
            return false;
        }
        String idLower = NavTextNormalizer.lower(viewId);
        return idLower.endsWith(":id/navbardirection")
                || idLower.endsWith(":id/laneguidanceview")
                || idLower.contains("laneguidance");
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

    //defines the WazeRouteNodeScanResult module boundary so related behavior stays readable inside one unit.
    private static final class WazeRouteNodeScanResult {
        final String payload;
        final boolean hasRouteEvidence;
        final int windowCount;

        WazeRouteNodeScanResult(String payload, boolean hasRouteEvidence, int windowCount) {
            this.payload = payload;
            this.hasRouteEvidence = hasRouteEvidence;
            this.windowCount = windowCount;
        }
    }
}
