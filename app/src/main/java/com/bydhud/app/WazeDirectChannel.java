package com.bydhud.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

import androidx.car.app.AppInfo;
import androidx.car.app.FailureResponse;
import androidx.car.app.HandshakeInfo;
import androidx.car.app.IAppHost;
import androidx.car.app.IAppManager;
import androidx.car.app.ICarApp;
import androidx.car.app.ICarHost;
import androidx.car.app.IOnDoneCallback;
import androidx.car.app.ISurfaceCallback;
import androidx.car.app.SessionInfo;
import androidx.car.app.SessionInfoIntentEncoder;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Alert;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.CarText;
import androidx.car.app.model.Distance;
import androidx.car.app.model.DateTimeWithZone;
import androidx.car.app.model.Template;
import androidx.car.app.model.TemplateWrapper;
import androidx.car.app.navigation.INavigationHost;
import androidx.car.app.navigation.model.Lane;
import androidx.car.app.navigation.model.LaneDirection;
import androidx.car.app.navigation.model.Maneuver;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.navigation.model.Step;
import androidx.car.app.navigation.model.TravelEstimate;
import androidx.car.app.navigation.model.Trip;
import androidx.car.app.serialization.Bundleable;
import androidx.car.app.versioning.CarAppApiLevels;
import androidx.core.graphics.drawable.IconCompat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct AndroidX Car App host for Waze cluster data or the optional main Surface.
 *
 * <p>The Waze direct-channel bridge was inspired by reference source material
 * shared by MaxTitan.</p>
 */
public final class WazeDirectChannel {
    enum Mode {
        CLUSTER,
        MAIN_SURFACE
    }
    static final String OWNER_PACKAGE = "com.waze";
    private static final String CAR_APP_ACTION = "androidx.car.app.CarAppService";
    private static final ComponentName WAZE_SERVICE = new ComponentName(
            "com.waze", "com.waze.car_lib.WazeCarAppService");
    private static final long REBIND_DELAY_MS = 1000L;
    private static final long BIND_CALLBACK_TIMEOUT_MS = 10000L;
    private static final long FRAME_SILENCE_MS = 5000L;
    private static final long HEALTH_PROBE_TIMEOUT_MS = 5000L;
    private static final long ALERT_TTL_MS = 5000L;
    private static final int MAX_ICON_DIMENSION_PX = 256;
    private static final Pattern ALERT_DISTANCE = Pattern.compile(
            "(\\d+[.,]?\\d*)\\s*(\\u043a\\u043c|km|mi|yd|ft|\\u043c|m)(?=$|\\s|[.,;:!?])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final Context context;
    private final Listener listener;
    private final HandlerThread channelThread = new HandlerThread(
            "WazeDirectChannel", Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler channelHandler;
    private final Map<Integer, byte[]> maneuverIcons = new HashMap<>();

    private volatile int generation;
    private volatile int sessionGeneration;
    private volatile boolean active;
    private volatile boolean suspended;
    private volatile boolean shutdown;
    private volatile boolean handshakeAvailable;
    private volatile boolean wazeAlertsEnabled;

    private String startReason = "";
    private boolean binding;
    private boolean bound;
    private boolean appStarted;
    private boolean resumed;
    private boolean navigationActive;
    private boolean acceptedRouteFrame;
    private boolean terminalRouteLatched;
    private long terminalBridgeGeneration;
    private boolean unavailableBindFailureReported;
    private String bindDeferredReason = "";
    private int routingSequence;
    private Connection connection;
    private ICarApp carApp;
    private IAppManager appManager;
    private CarHost carHost;
    private WazeRouteTiming routeTiming;
    private DirectTbtFrame navigationFrame = DirectTbtFrame.empty();
    private DirectTbtFrame.AlertOverlay alert = DirectTbtFrame.AlertOverlay.inactive();
    private Runnable rebindRunnable;
    private Runnable bindCallbackTimeout;
    private Runnable alertWatchdog;
    private int alertRevision;
    private int lastKnownRawManeuverType = -1;
    private boolean navigationDistanceKnown;
    private Runnable frameSilenceCheck;
    private Runnable healthProbeTimeout;
    private long lastDirectActivityMs;
    private boolean healthProbeInFlight;
    private int healthProbeSequence;
    private final TemplateRefreshGate templateRefreshGate = new TemplateRefreshGate();
    private Mode mode = Mode.CLUSTER;

    public WazeDirectChannel(Context context, Listener listener) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.listener = Objects.requireNonNull(listener, "listener");
        wazeAlertsEnabled = HudPrefs.isWazeAlertsEnabled(this.context);
        channelThread.start();
        channelHandler = new Handler(channelThread.getLooper());
    }

    public void start(String reason) {
        start(reason, Mode.CLUSTER);
    }

    public void start(String reason, Mode requestedMode) {
        Mode selected = requestedMode == null ? Mode.CLUSTER : requestedMode;
        runOnChannel(() -> startOnChannel(reason, selected));
    }

    /** Applies the authoritative fresh-route decision already made by the lifecycle store. */
    void openAcceptedFreshRoute(String reason, long bridgeGeneration) {
        runOnChannel(() -> {
            if (bridgeGeneration > terminalBridgeGeneration) {
                terminalBridgeGeneration = bridgeGeneration;
            }
            rearmRouteTerminal("accepted_fresh_route:" + safeText(reason));
        });
    }

    /** Applies a lifecycle terminal to callbacks that did not reach Waze's
     * navigation host (for example an observer-only state transition). */
    void noteRouteTerminalGeneration(String reason, long bridgeGeneration) {
        runOnChannel(() -> {
            if (bridgeGeneration > terminalBridgeGeneration) {
                terminalBridgeGeneration = bridgeGeneration;
            }
            latchRouteTerminal("lifecycle:" + safeText(reason));
        });
    }

    public void stop(String reason) {
        runOnChannel(() -> suspendOnChannel(reason));
    }

    void prepareSurfaceHandoff(String reason) {
        runOnChannel(() -> {
            if (!active || suspended || mode != Mode.MAIN_SURFACE || carHost == null) return;
            carHost.appHost.prepareSurfaceHandoff(reason);
        });
    }

    public void hardStop(String reason) {
        runOnChannel(() -> hardStopOnChannel(reason));
    }

    boolean isActive() {
        return active && !suspended;
    }

    public void shutdown(String reason) {
        runOnChannel(() -> {
            if (shutdown) return;
            shutdown = true;
            hardStopOnChannel(reason);
            channelThread.quitSafely();
        });
    }

    public boolean isHandshakeAvailable() {
        return handshakeAvailable;
    }

    int sessionGeneration() {
        return sessionGeneration;
    }

    public void onWazeAlertsPreferenceChanged(boolean enabled) {
        wazeAlertsEnabled = enabled;
        runOnChannel(() -> {
            if (enabled || !alert.isActive()) return;
            clearAlert(false, "alerts_disabled");
            DirectTbtFrame frame = navigationFrame.withAlertOverlay(alert);
            int callbackGeneration = sessionGeneration;
            callback(() -> listener.onAlertCleared(
                    OWNER_PACKAGE, callbackGeneration, frame, "alerts_disabled"));
        });
    }

    private void startOnChannel(String reason, Mode requestedMode) {
        if (shutdown) {
            log("start ignored after shutdown: " + safeText(reason));
            return;
        }
        if (active && mode != requestedMode) {
            switchModeOnChannel(requestedMode, reason);
            return;
        }
        mode = requestedMode;
        if (active) {
            if (suspended) {
                resumeOnChannel(reason);
                return;
            }
            log("start ignored while active: " + safeText(reason));
            return;
        }
        active = true;
        suspended = false;
        generation++;
        sessionGeneration++;
        prepareRouteStart(reason);
        log("start generation=" + generation + " reason=" + startReason);
        connectWaze(generation);
    }

    private void switchModeOnChannel(Mode requestedMode, String reason) {
        int oldGeneration = generation;
        cancelRebind();
        cancelAlertWatchdog();
        cancelDirectHealth();
        setHandshakeUnavailable("mode-switch:" + safeText(reason), false);
        endNavigation("mode-switch:" + safeText(reason), false);
        pauseAndStopSession(oldGeneration, "mode-switch:" + safeText(reason));
        releaseBinding(connection);
        clearSessionState();
        mode = requestedMode;
        active = true;
        suspended = false;
        generation++;
        sessionGeneration++;
        prepareRouteStart(reason);
        log("mode switched mode=" + mode + " generation=" + generation
                + " reason=" + safeText(reason));
        connectWaze(generation);
    }

    private void resumeOnChannel(String reason) {
        suspended = false;
        sessionGeneration++;
        prepareRouteStart(reason);
        log("resume generation=" + generation + " reason=" + startReason);
        if (carApp != null && bound) {
            try {
                startSession(generation);
            } catch (Throwable t) {
                sessionFailure(generation, "resume_session_exception", t);
            }
        } else if (!binding && !bound) {
            connectWaze(generation);
        }
    }

    private void prepareRouteStart(String reason) {
        templateRefreshGate.reset();
        startReason = safeText(reason);
        routeTiming = null;
        if (mode == Mode.CLUSTER) {
            routeTiming = WazeRouteTiming.claimForDirect(SystemClock.elapsedRealtime());
            if (routeTiming != null) {
                routeTiming.markDirectStart(
                        SystemClock.elapsedRealtime(), sessionGeneration, reason);
                log(routeTiming.directLine("direct_start"));
            }
        }
        unavailableBindFailureReported = false;
        bindDeferredReason = "";
        routingSequence = 0;
        navigationFrame = DirectTbtFrame.empty();
        lastKnownRawManeuverType = -1;
        navigationDistanceKnown = false;
        alert = DirectTbtFrame.AlertOverlay.inactive();
        maneuverIcons.clear();
    }

    private void suspendOnChannel(String reason) {
        String stopReason = safeText(reason);
        if (!active || suspended) {
            setHandshakeUnavailable("suspended:" + stopReason, false);
            return;
        }

        int oldGeneration = generation;
        suspended = true;
        cancelRebind();
        cancelAlertWatchdog();
        cancelDirectHealth();
        templateRefreshGate.reset();
        setHandshakeUnavailable("suspended:" + stopReason, false);
        endNavigation("suspended:" + stopReason, false);
        pauseAndStopSession(oldGeneration, stopReason);
        bindDeferredReason = "";
        log("suspended reason=" + stopReason + " bindingRetained=" + bound);
    }

    private void hardStopOnChannel(String reason) {
        String stopReason = safeText(reason);
        templateRefreshGate.reset();
        if (!active) {
            setHandshakeUnavailable("hard-stopped:" + stopReason, false);
            return;
        }

        int oldGeneration = generation;
        active = false;
        suspended = false;
        generation++;
        sessionGeneration++;
        cancelRebind();
        cancelAlertWatchdog();
        cancelDirectHealth();
        setHandshakeUnavailable("hard-stopped:" + stopReason, false);
        endNavigation("hard-stopped:" + stopReason, false);
        pauseAndStopSession(oldGeneration, stopReason);
        releaseBinding(connection);
        clearSessionState();
        bindDeferredReason = "";
        log("hard stopped reason=" + stopReason);
    }

    private void pauseAndStopSession(int expectedGeneration, String reason) {
        ICarApp app = carApp;
        boolean pause = resumed;
        boolean stop = appStarted;
        resumed = false;
        appStarted = false;
        if (app == null || (!pause && !stop)) return;
        try {
            if (pause) app.onAppPause(new DoneCallback(expectedGeneration, "onAppPause", null));
            if (stop) app.onAppStop(new DoneCallback(expectedGeneration, "onAppStop", null));
            log("session lifecycle stopped pause=" + pause + " stop=" + stop
                    + " reason=" + safeText(reason));
        } catch (Throwable t) {
            log("lifecycle stop failed: " + t);
        }
    }

    private void connectWaze(int expectedGeneration) {
        if (!isCurrent(expectedGeneration) || binding || bound) return;

        if (deferBindIfNeeded(expectedGeneration)) return;

        CarHost nextHost = new CarHost(expectedGeneration);
        Connection nextConnection = new Connection(expectedGeneration);
        Intent intent = new Intent(CAR_APP_ACTION).setComponent(WAZE_SERVICE);
        SessionInfo sessionInfo = mode == Mode.MAIN_SURFACE
                ? SessionInfo.DEFAULT_SESSION_INFO
                : new SessionInfo(SessionInfo.DISPLAY_TYPE_CLUSTER, "cluster");
        SessionInfoIntentEncoder.encode(sessionInfo, intent);

        carHost = nextHost;
        connection = nextConnection;
        binding = true;
        if (routeTiming != null) {
            routeTiming.markBindRequest(SystemClock.elapsedRealtime());
            routeTiming.markBindStart(SystemClock.elapsedRealtime());
        }
        try {
            bound = context.bindService(intent, nextConnection, Context.BIND_AUTO_CREATE);
            binding = bound;
            if (routeTiming != null) {
                routeTiming.markBindResult(SystemClock.elapsedRealtime(), bound);
                log(routeTiming.directLine("bind_result"));
            }
            if (bound) {
                log("bind result=true component=" + WAZE_SERVICE.flattenToShortString()
                        + " mode=" + mode);
                scheduleBindCallbackTimeout(expectedGeneration, nextConnection);
            } else {
                releaseBinding(nextConnection);
                carHost = null;
                if (deferBindIfNeeded(expectedGeneration)) return;
                reportBindUnavailable(
                        "bind result=false component=" + WAZE_SERVICE.flattenToShortString(),
                        "bind_failed");
                scheduleRebind(expectedGeneration);
            }
        } catch (Throwable t) {
            if (routeTiming != null) {
                routeTiming.markBindResult(SystemClock.elapsedRealtime(), false);
                log(routeTiming.directLine("bind_exception"));
            }
            releaseBinding(nextConnection);
            carHost = null;
            if (deferBindIfNeeded(expectedGeneration)) return;
            reportBindUnavailable("bind failed: " + t, "bind_exception");
            scheduleRebind(expectedGeneration);
        }
    }

    private boolean deferBindIfNeeded(int expectedGeneration) {
        String deferredReason = wazeBindDeferralReason();
        if (deferredReason.isEmpty()) {
            if (!bindDeferredReason.isEmpty()) {
                log("direct_bind_released previous_reason=" + bindDeferredReason);
                bindDeferredReason = "";
            }
            return false;
        }
        if (!deferredReason.equals(bindDeferredReason)) {
            bindDeferredReason = deferredReason;
            log("direct_bind_deferred reason=" + deferredReason
                    + " package_stopped=" + "package_stopped".equals(deferredReason));
        }
        scheduleRebind(expectedGeneration);
        return true;
    }

    private String wazeBindDeferralReason() {
        PackageManager packageManager = context.getPackageManager();
        if (packageManager == null) return "package_manager_missing";
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = packageManager.getApplicationInfo(WAZE_SERVICE.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return "package_missing";
        }
        ServiceInfo serviceInfo;
        try {
            serviceInfo = packageManager.getServiceInfo(WAZE_SERVICE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return "service_missing";
        }
        return bindDeferralReason(
                true,
                applicationInfo.enabled,
                true,
                serviceInfo.enabled,
                (applicationInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0);
    }

    static String bindDeferralReason(boolean packagePresent, boolean packageEnabled,
                                     boolean servicePresent, boolean serviceEnabled,
                                     boolean packageStopped) {
        if (!packagePresent) return "package_missing";
        if (!packageEnabled) return "package_disabled";
        if (!servicePresent) return "service_missing";
        if (!serviceEnabled) return "service_disabled";
        if (packageStopped) return "package_stopped";
        return "";
    }

    private void onConnected(int expectedGeneration, Connection source,
                             ComponentName name, IBinder binder) {
        if (!isCurrent(expectedGeneration) || source != connection) {
            releaseBinding(source);
            return;
        }
        binding = false;
        bound = true;
        cancelBindCallbackTimeout(source);
        unavailableBindFailureReported = false;
        carApp = ICarApp.Stub.asInterface(binder);
        if (routeTiming != null) {
            routeTiming.markCarAppConnected(SystemClock.elapsedRealtime());
            log(routeTiming.directLine("car_app_connected"));
        }
        log("connected component=" + name.flattenToShortString());
        try {
            carApp.getAppInfo(new DoneCallback(
                    expectedGeneration, "getAppInfo",
                    response -> onAppInfo(expectedGeneration, response)));
        } catch (Throwable t) {
            sessionFailure(expectedGeneration, "get_app_info_exception", t);
        }
    }

    private void onAppInfo(int expectedGeneration, Bundleable response) throws Exception {
        Object value = response == null ? null : response.get();
        if (!(value instanceof AppInfo)) {
            throw new IllegalStateException("Expected AppInfo, got " + value);
        }
        AppInfo info = (AppInfo) value;
        int hostLatest = CarAppApiLevels.getLatest();
        int negotiated = Math.min(info.getLatestCarAppApiLevel(), hostLatest);
        if (negotiated < info.getMinCarAppApiLevel()) {
            throw new IllegalStateException("No shared Car App API level");
        }
        log("app info min=" + info.getMinCarAppApiLevel()
                + " latest=" + info.getLatestCarAppApiLevel()
                + " negotiated=" + negotiated);
        HandshakeInfo handshake = new HandshakeInfo(context.getPackageName(), negotiated);
        carApp.onHandshakeCompleted(Bundleable.create(handshake),
                new DoneCallback(expectedGeneration, "handshake", ignored -> {
                    createSession(expectedGeneration);
                }));
    }

    private void createSession(int expectedGeneration) throws Exception {
        ICarApp app = requireCarApp();
        app.onAppCreate(carHost, new Intent(), context.getResources().getConfiguration(),
                new DoneCallback(expectedGeneration, "onAppCreate",
                        ignored -> startSession(expectedGeneration)));
    }

    private void startSession(int expectedGeneration) throws Exception {
        requireCarApp().onAppStart(new DoneCallback(
                expectedGeneration, "onAppStart", ignored -> {
                    appStarted = true;
                    if (suspended) {
                        pauseAndStopSession(expectedGeneration, "suspended-during-start");
                        return;
                    }
                    requireCarApp().onAppResume(new DoneCallback(
                            expectedGeneration, "onAppResume",
                            resumedValue -> onResumed(expectedGeneration)));
                }));
    }

    private void onResumed(int expectedGeneration) throws Exception {
        resumed = true;
        if (suspended) {
            pauseAndStopSession(expectedGeneration, "suspended-during-resume");
            return;
        }
        log("session resumed");
        carHost.appHost.setInvalidateCallback(() -> fetchTemplate(expectedGeneration));
        requireCarApp().getManager("app", new DoneCallback(
                expectedGeneration, "getManager:app",
                response -> onAppManager(expectedGeneration, response)));
    }

    private void onAppManager(int expectedGeneration, Bundleable response) throws Exception {
        Object value = response == null ? null : response.get();
        if (value instanceof IAppManager) {
            appManager = (IAppManager) value;
        } else if (value instanceof IBinder) {
            appManager = IAppManager.Stub.asInterface((IBinder) value);
        } else {
            throw new IllegalStateException("Unexpected app manager " + value);
        }
        if (suspended) return;
        setHandshakeAvailable("session_ready:" + startReason);
        if (routeTiming != null) {
            routeTiming.markSessionReady(SystemClock.elapsedRealtime());
            log(routeTiming.directLine("session_ready"));
        }
        fetchTemplate(expectedGeneration);
    }

    private void fetchTemplate(int expectedGeneration) {
        if (!isCurrent(expectedGeneration) || suspended || appManager == null) return;
        TemplateRefreshGate.Request request = templateRefreshGate.begin();
        if (request == null) return;
        startTemplateRequest(expectedGeneration, request);
    }

    private void startTemplateRequest(int expectedGeneration,
                                       TemplateRefreshGate.Request request) {
        try {
            appManager.getTemplate(new DoneCallback(
                    expectedGeneration, "getTemplate",
                    response -> {
                        onTemplate(expectedGeneration, response);
                        recordTemplateResponse("getTemplate");
                    },
                    () -> completeTemplateRequest(expectedGeneration, request)));
        } catch (Throwable t) {
            sessionFailure(expectedGeneration, "get_template_exception", t);
            completeTemplateRequest(expectedGeneration, request);
        }
    }

    private void completeTemplateRequest(int expectedGeneration,
                                         TemplateRefreshGate.Request request) {
        TemplateRefreshGate.Request followUp = templateRefreshGate.complete(request);
        if (followUp == null) return;
        if (!isCurrent(expectedGeneration) || suspended || appManager == null) {
            templateRefreshGate.reset();
            return;
        }
        startTemplateRequest(expectedGeneration, followUp);
    }

    private void onTemplate(int expectedGeneration, Bundleable response) throws Exception {
        if (suspended) return;
        Object value = response == null ? null : response.get();
        if (!(value instanceof TemplateWrapper)) {
            log("unexpected template wrapper=" + typeName(value));
            return;
        }
        Template template = ((TemplateWrapper) value).getTemplate();
        if (mode == Mode.MAIN_SURFACE) WazeSurfaceActivity.showTemplate(template);
        if (!(template instanceof NavigationTemplate)) {
            log("template=" + typeName(template));
            return;
        }
        NavigationTemplate.NavigationInfo info =
                ((NavigationTemplate) template).getNavigationInfo();
        if (!(info instanceof RoutingInfo)) {
            log("navigation info=" + typeName(info));
            if (info == null) {
                if (acceptedRouteFrame) {
                    latchRouteTerminal("navigation_info_null");
                    endNavigation("navigation_info_null");
                } else {
                    log("navigation info null ignored before first route frame");
                }
            }
            return;
        }
        RoutingInfo routing = (RoutingInfo) info;
        int sequence = ++routingSequence;
        Step current = routing.getCurrentStep();
        log("routing sequence=" + sequence + " current=" + (current != null)
                + " next=" + (routing.getNextStep() != null));
        if (current != null) {
            publishCurrentStep(current, routing.getCurrentDistance(),
                    true, "routing_info:" + sequence);
        }
        logNextStep(routing.getNextStep(), "routing_next:" + sequence);
    }

    private void onBindingLost(int expectedGeneration, Connection source, String reason) {
        if (!isCurrent(expectedGeneration) || source != connection) return;
        boolean wasSuspended = suspended;
        log("binding lost: " + reason);
        releaseBinding(source);
        generation++;
        sessionGeneration++;
        setHandshakeUnavailable(reason, false);
        unavailableBindFailureReported = true;
        endNavigation(reason, false);
        clearSessionState();
        if (wasSuspended) {
            active = false;
            suspended = false;
            log("binding lost while suspended; rebind deferred until next route");
            return;
        }
        scheduleRebind(generation);
    }

    private void sessionFailure(int expectedGeneration, String reason, Throwable error) {
        if (!isCurrent(expectedGeneration)) return;
        log(reason + ": " + error);
        Connection source = connection;
        if (source != null) {
            onBindingLost(expectedGeneration, source, reason);
        } else {
            templateRefreshGate.reset();
            generation++;
            sessionGeneration++;
            unavailableBindFailureReported = true;
            setHandshakeUnavailable(reason, true);
            scheduleRebind(generation);
        }
    }

    private void scheduleRebind(int expectedGeneration) {
        cancelRebind();
        rebindRunnable = () -> {
            rebindRunnable = null;
            connectWaze(expectedGeneration);
        };
        channelHandler.postDelayed(rebindRunnable, REBIND_DELAY_MS);
    }

    private void scheduleBindCallbackTimeout(
            int expectedGeneration,
            Connection source) {
        cancelBindCallbackTimeout(null);
        bindCallbackTimeout = () -> {
            bindCallbackTimeout = null;
            if (!isCurrent(expectedGeneration)
                    || source != connection
                    || !binding
                    || !bound) {
                return;
            }
            onBindingLost(expectedGeneration, source, "bind_callback_timeout");
        };
        channelHandler.postDelayed(bindCallbackTimeout, BIND_CALLBACK_TIMEOUT_MS);
    }

    private void cancelBindCallbackTimeout(Connection source) {
        if (source != null && source != connection) return;
        if (bindCallbackTimeout == null) return;
        channelHandler.removeCallbacks(bindCallbackTimeout);
        bindCallbackTimeout = null;
    }

    private void reportBindUnavailable(String message, String reason) {
        if (unavailableBindFailureReported) return;
        unavailableBindFailureReported = true;
        log(message);
        setHandshakeUnavailable(reason, true);
    }

    private void cancelRebind() {
        if (rebindRunnable == null) return;
        channelHandler.removeCallbacks(rebindRunnable);
        rebindRunnable = null;
    }

    private void releaseBinding(Connection source) {
        if (source != null) {
            try {
                context.unbindService(source);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (source == connection) {
            cancelBindCallbackTimeout(source);
            connection = null;
            binding = false;
            bound = false;
        }
    }

    private void clearSessionState() {
        templateRefreshGate.reset();
        if (carHost != null) carHost.appHost.clearSurfaceSession("session-cleared");
        carApp = null;
        appManager = null;
        carHost = null;
        appStarted = false;
        resumed = false;
        routingSequence = 0;
        maneuverIcons.clear();
        navigationFrame = DirectTbtFrame.empty();
        lastKnownRawManeuverType = -1;
        navigationDistanceKnown = false;
        alert = DirectTbtFrame.AlertOverlay.inactive();
        alertRevision++;
        cancelAlertWatchdog();
        cancelDirectHealth();
    }

    private ICarApp requireCarApp() {
        if (carApp == null) throw new IllegalStateException("Waze binder is unavailable");
        return carApp;
    }

    private void setHandshakeAvailable(String reason) {
        if (suspended || handshakeAvailable) return;
        handshakeAvailable = true;
        int callbackGeneration = sessionGeneration;
        callback(() -> listener.onHandshakeAvailable(
                OWNER_PACKAGE, callbackGeneration, reason));
        callback(() -> listener.onLiveness(
                OWNER_PACKAGE, callbackGeneration, "handshake:" + reason));
    }

    private void setHandshakeUnavailable(String reason, boolean force) {
        boolean changed = handshakeAvailable;
        handshakeAvailable = false;
        int callbackGeneration = sessionGeneration;
        if (changed || force) callback(() -> listener.onHandshakeUnavailable(
                OWNER_PACKAGE, callbackGeneration, reason));
    }

    private void beginNavigation(String reason) {
        if (suspended || navigationActive || terminalRouteLatched) {
            if (terminalRouteLatched) {
                log("navigation start rejected by terminal latch reason="
                        + safeText(reason));
            }
            return;
        }
        navigationActive = true;
        maneuverIcons.clear();
        recordDirectActivity("navigation_started");
        int callbackGeneration = sessionGeneration;
        callback(() -> listener.onNavigationStarted(
                OWNER_PACKAGE, callbackGeneration, reason));
    }

    private void explicitNavigationStarted() {
        if (suspended) return;
        beginNavigation("waze_navigation_started");
    }

    private void rearmRouteTerminal(String reason) {
        acceptedRouteFrame = false;
        if (!terminalRouteLatched) return;
        terminalRouteLatched = false;
        log("route terminal latch cleared reason=" + reason);
    }

    private void latchRouteTerminal(String reason) {
        if (terminalRouteLatched) return;
        terminalRouteLatched = true;
        log("route terminal latch set reason=" + reason);
    }

    private void endNavigation(String reason) {
        endNavigation(reason, true);
    }

    private void endNavigation(String reason, boolean notifyListener) {
        cancelAlertWatchdog();
        cancelDirectHealth();
        alert = DirectTbtFrame.AlertOverlay.inactive();
        alertRevision++;
        navigationFrame = DirectTbtFrame.empty();
        lastKnownRawManeuverType = -1;
        navigationDistanceKnown = false;
        maneuverIcons.clear();
        if (!navigationActive) return;
        int routeGeneration = sessionGeneration;
        int callbackGeneration = notifyListener ? ++sessionGeneration : sessionGeneration;
        navigationActive = false;
        if (notifyListener) {
            callback(() -> listener.onNavigationEnded(
                    OWNER_PACKAGE, routeGeneration, callbackGeneration, reason));
        } else {
            log("navigation session cleared without route end reason=" + reason);
        }
    }

    private void publishCurrentStep(Step step, Distance distance,
                                    boolean authoritativeLanes, String reason) {
        publishCurrentStep(step, distance, authoritativeLanes, reason, null);
    }

    private void publishCurrentStep(Step step, Distance distance,
                                    boolean authoritativeLanes, String reason,
                                    DirectTbtFrame.TripMetrics tripMetrics) {
        if (suspended) {
            log("route frame ignored while suspended source=" + reason);
            return;
        }
        if (terminalRouteLatched) {
            log("late route frame rejected source=" + reason);
            return;
        }
        if (!navigationActive) beginNavigation("frame_received:" + reason);
        DirectTbtFrame.TripMetrics selectedMetrics = tripMetrics == null
                ? navigationFrame.getTripMetrics() : tripMetrics;
        DirectTbtFrame next = frameFromStep(step, distance, selectedMetrics);
        navigationDistanceKnown = distance != null;
        acceptedRouteFrame = true;
        int previousKnownRaw = lastKnownRawManeuverType;
        int nextRaw = next.getRawManeuverType();
        boolean maneuverChanged = previousKnownRaw >= 0
                && nextRaw >= 0 && previousKnownRaw != nextRaw;
        if (nextRaw >= 0) lastKnownRawManeuverType = nextRaw;
        if (!authoritativeLanes && !maneuverChanged
                && !next.hasLaneGuidance() && navigationFrame.hasLaneGuidance()) {
            next = next.withLanesFrom(navigationFrame);
            log("lanes preserved source=trip raw=" + next.getRawManeuverType());
        } else if ((authoritativeLanes || maneuverChanged)
                && navigationFrame.hasLaneGuidance() && !next.hasLaneGuidance()) {
            log("lanes cleared source=" + (authoritativeLanes ? "routing_info" : "trip")
                    + " maneuverChanged=" + maneuverChanged);
        }
        navigationFrame = next;
        recordDirectActivity("frame:" + reason);
        emitFrame(reason, true);
    }

    private DirectTbtFrame frameFromStep(
            Step step, Distance distance, DirectTbtFrame.TripMetrics tripMetrics) {
        Maneuver maneuver = step.getManeuver();
        int rawType = maneuver == null ? -1 : maneuver.getType();
        int amap = maneuver == null ? 0 : mapWazeToAmap(rawType);
        int byd = amap == 15 ? 99 : mapAmapToByd(amap);
        int amapBroadcast = maneuver == null ? 0 : mapWazeToAmapBroadcast(rawType, amap);
        int roundaboutExit = roundaboutExitNumber(maneuver);
        String road = text(step.getRoad());
        String cue = text(step.getCue());
        byte[] lanePng = renderIcon(step.getLanesImage(), "lanes");
        byte[] maneuverPng = maneuver == null
                ? new byte[0] : renderIcon(maneuver.getIcon(), "maneuver");

        if (rawType >= 0 && maneuverPng.length > 0) {
            maneuverIcons.put(rawType, maneuverPng.clone());
        } else if (rawType >= 0) {
            byte[] cached = maneuverIcons.get(rawType);
            maneuverPng = cached == null ? new byte[0] : cached.clone();
        }

        return new DirectTbtFrame(rawType, amap, byd, meters(distance), road, cue,
                road.isEmpty() ? cue : road, maneuverPng, lanePng,
                mapLanes(step.getLanes()), DirectTbtFrame.AlertOverlay.inactive(),
                tripMetrics).withVehicleTbt(amapBroadcast, roundaboutExit);
    }

    private void emitFrame(String reason) {
        emitFrame(reason, false);
    }

    private void emitFrame(String reason, boolean routeFrame) {
        if (routeFrame) {
            alert = selectAlertOverlayCandidate(
                    alert, navigationFrame, navigationDistanceKnown);
        }
        DirectTbtFrame frame = navigationFrame.withAlertOverlay(alert);
        int callbackGeneration = sessionGeneration;
        WazeRouteTiming.Frame timing = routeTiming == null || !routeFrame
                ? null : routeTiming.beginFrame(SystemClock.elapsedRealtime(), reason);
        if (timing != null) timing.markListenerHandoff(SystemClock.elapsedRealtime());
        callback(() -> listener.onFrame(
                OWNER_PACKAGE, callbackGeneration, frame, reason, timing));
    }

    static boolean shouldUseRouteFrameDuringAlert(
            DirectTbtFrame routeFrame,
            boolean routeDistanceKnown,
            DirectTbtFrame.AlertOverlay alert) {
        if (routeFrame == null || alert == null || !alert.isActive()
                || !routeDistanceKnown || routeFrame.getRawManeuverType() < 0) {
            return false;
        }
        int nativeManeuver = routeFrame.getBydManeuver();
        if (nativeManeuver <= 0 || nativeManeuver == 99) return false;
        return !alert.isDistanceKnown()
                || routeFrame.getDistanceMeters() <= alert.getDistanceMeters();
    }

    static DirectTbtFrame.AlertOverlay selectAlertOverlayCandidate(
            DirectTbtFrame.AlertOverlay candidate,
            DirectTbtFrame routeFrame,
            boolean routeDistanceKnown) {
        if (candidate == null || !candidate.isActive()) {
            return candidate == null ? DirectTbtFrame.AlertOverlay.inactive() : candidate;
        }
        return candidate.withRouteFrame(
                shouldUseRouteFrameDuringAlert(routeFrame, routeDistanceKnown, candidate));
    }

    private void showAlert(Alert value) {
        if (suspended) return;
        String title = text(value.getTitle());
        String subtitle = text(value.getSubtitle());
        int distanceMeters = parseDistanceMeters(title + " " + subtitle);
        String displayText = subtitle.isEmpty() ? title : subtitle;
        byte[] icon = renderIcon(value.getIcon(), "alert");
        if (alert.isActive() && alert.getId() != value.getId()) {
            log("alert replaced oldId=" + alert.getId() + " newId=" + value.getId());
        }
        int revision = ++alertRevision;
        DirectTbtFrame.AlertOverlay next = DirectTbtFrame.AlertOverlay.active(
                value.getId(), distanceMeters, displayText, icon);
        alert = selectAlertOverlayCandidate(next, navigationFrame, navigationDistanceKnown);
        log("alert show revision=" + revision + " id=" + value.getId()
                + " distanceM=" + distanceMeters + " iconBytes=" + icon.length);
        emitFrame("alert_show");
        armAlertWatchdog(revision);
    }

    private void dismissAlert(int alertId) {
        if (!alert.isActive() || alert.getId() != alertId) {
            log("stale alert dismiss id=" + alertId
                    + " activeId=" + (alert.isActive() ? alert.getId() : -1));
            return;
        }
        clearAlert(true, "alert_dismiss");
    }

    private void clearAlert(boolean emit, String reason) {
        if (!alert.isActive()) return;
        int oldId = alert.getId();
        cancelAlertWatchdog();
        alertRevision++;
        alert = DirectTbtFrame.AlertOverlay.inactive();
        log("alert cleared id=" + oldId + " reason=" + reason);
        if (emit) emitFrame(reason);
    }

    private void armAlertWatchdog(int revision) {
        cancelAlertWatchdog();
        int expectedGeneration = generation;
        alertWatchdog = () -> {
            alertWatchdog = null;
            if (!isCurrent(expectedGeneration)
                    || !alert.isActive()
                    || alertRevision != revision) return;
            clearAlert(true, "alert_ttl_expired");
        };
        channelHandler.postDelayed(alertWatchdog, ALERT_TTL_MS);
        log("alert ttl refreshed revision=" + revision + " timeoutMs=" + ALERT_TTL_MS);
    }

    private void cancelAlertWatchdog() {
        if (alertWatchdog == null) return;
        channelHandler.removeCallbacks(alertWatchdog);
        alertWatchdog = null;
    }

    private void recordDirectActivity(String reason) {
        if (!navigationActive) return;
        boolean completedProbe = healthProbeInFlight;
        lastDirectActivityMs = SystemClock.elapsedRealtime();
        healthProbeInFlight = false;
        if (healthProbeTimeout != null) {
            channelHandler.removeCallbacks(healthProbeTimeout);
            healthProbeTimeout = null;
        }
        if (completedProbe) {
            log("health probe recovered reason=" + reason);
        }
        scheduleFrameSilenceCheck();
        int callbackGeneration = sessionGeneration;
        callback(() -> listener.onLiveness(
                OWNER_PACKAGE, callbackGeneration, reason));
    }

    private void recordTemplateResponse(String reason) {
        if (navigationActive) recordDirectActivity("template_response:" + reason);
    }

    private void scheduleFrameSilenceCheck() {
        if (!navigationActive || !active || shutdown) return;
        if (frameSilenceCheck != null) {
            channelHandler.removeCallbacks(frameSilenceCheck);
        }
        int expectedGeneration = generation;
        frameSilenceCheck = () -> {
            frameSilenceCheck = null;
            if (!isCurrent(expectedGeneration) || !navigationActive) return;
            long ageMs = SystemClock.elapsedRealtime() - lastDirectActivityMs;
            if (ageMs < FRAME_SILENCE_MS) {
                scheduleFrameSilenceCheck();
                return;
            }
            startHealthProbe(expectedGeneration, ageMs);
        };
        long delayMs = Math.max(1L,
                FRAME_SILENCE_MS - (SystemClock.elapsedRealtime() - lastDirectActivityMs));
        channelHandler.postDelayed(frameSilenceCheck, delayMs);
    }

    private void startHealthProbe(int expectedGeneration, long silenceMs) {
        if (!isCurrent(expectedGeneration) || !navigationActive || healthProbeInFlight) return;
        if (appManager == null) {
            sessionFailure(expectedGeneration, "health_probe_manager_missing",
                    new IllegalStateException("Waze app manager unavailable"));
            return;
        }
        healthProbeInFlight = true;
        int expectedProbe = ++healthProbeSequence;
        log("health probe start silenceMs=" + silenceMs + " sequence=" + expectedProbe);
        healthProbeTimeout = () -> {
            healthProbeTimeout = null;
            if (!isCurrent(expectedGeneration)
                    || !navigationActive
                    || !healthProbeInFlight
                    || healthProbeSequence != expectedProbe) return;
            sessionFailure(expectedGeneration, "health_probe_timeout",
                    new IllegalStateException("No getTemplate response in "
                            + HEALTH_PROBE_TIMEOUT_MS + "ms"));
        };
        channelHandler.postDelayed(healthProbeTimeout, HEALTH_PROBE_TIMEOUT_MS);
        fetchTemplate(expectedGeneration);
    }

    private void cancelDirectHealth() {
        if (frameSilenceCheck != null) {
            channelHandler.removeCallbacks(frameSilenceCheck);
            frameSilenceCheck = null;
        }
        if (healthProbeTimeout != null) {
            channelHandler.removeCallbacks(healthProbeTimeout);
            healthProbeTimeout = null;
        }
        lastDirectActivityMs = 0L;
        healthProbeInFlight = false;
        healthProbeSequence++;
    }

    private List<DirectTbtFrame.Lane> mapLanes(List<Lane> lanes) {
        if (lanes == null || lanes.isEmpty()) return Collections.emptyList();
        List<DirectTbtFrame.Lane> out = new ArrayList<>();
        for (Lane lane : lanes) {
            List<LaneDirection> directions = lane.getDirections();
            if (directions == null || directions.isEmpty()) continue;
            int fullMask = 0;
            int selectedMask = 0;
            int firstUturnMask = 0;
            int selectedUturnMask = 0;
            boolean invalid = false;
            StringBuilder raw = new StringBuilder();
            for (LaneDirection direction : directions) {
                if (raw.length() > 0) raw.append('|');
                raw.append(direction.getShape()).append(':').append(direction.isRecommended());
                int category = laneCategory(direction.getShape());
                if (category == 0) {
                    invalid = true;
                    continue;
                }
                if ((category & (LANE_U_LEFT | LANE_U_RIGHT)) != 0
                        && firstUturnMask == 0) {
                    firstUturnMask = category;
                } else if ((category & (LANE_U_LEFT | LANE_U_RIGHT)) == 0) {
                    fullMask |= category;
                }
                if (direction.isRecommended()) {
                    if ((category & (LANE_U_LEFT | LANE_U_RIGHT)) != 0) {
                        if (selectedUturnMask == 0) selectedUturnMask = category;
                    } else {
                        selectedMask |= category;
                    }
                }
            }
            if (invalid && fullMask == 0 && firstUturnMask == 0) continue;
            int completeCode = firstUturnMask != 0
                    ? firstUturnMask == LANE_U_LEFT ? 5 : 8
                    : DirectTbtFrame.Lane.instrumentCodeForMask(fullMask);
            if (completeCode < 0) continue;
            int recommendationCode;
            if (firstUturnMask != 0) {
                recommendationCode = selectedUturnMask == firstUturnMask ? completeCode
                        : DirectTbtFrame.Lane.NO_RECOMMENDATION;
            } else {
                int selectedCode = selectedMask == 0
                        ? -1 : DirectTbtFrame.Lane.instrumentCodeForMask(selectedMask);
                recommendationCode = selectedCode < 0
                        ? DirectTbtFrame.Lane.NO_RECOMMENDATION : selectedCode;
            }
            out.add(new DirectTbtFrame.Lane(completeCode, recommendationCode, raw.toString()));
        }
        return out;
    }

    static int[] laneCodesForTest(int[] shapes, boolean[] selected) {
        if (shapes == null || shapes.length == 0) return new int[]{-1, 255};
        int fullMask = 0;
        int selectedMask = 0;
        int firstUturnMask = 0;
        int selectedUturnMask = 0;
        for (int i = 0; i < shapes.length; i++) {
            int category = laneCategory(shapes[i]);
            if (category == 0) continue;
            if ((category & (LANE_U_LEFT | LANE_U_RIGHT)) != 0) {
                if (firstUturnMask == 0) firstUturnMask = category;
            } else {
                fullMask |= category;
            }
            if (selected != null && i < selected.length && selected[i]) {
                if ((category & (LANE_U_LEFT | LANE_U_RIGHT)) != 0) {
                    if (selectedUturnMask == 0) selectedUturnMask = category;
                } else {
                    selectedMask |= category;
                }
            }
        }
        int full = firstUturnMask != 0
                ? firstUturnMask == LANE_U_LEFT ? 5 : 8
                : DirectTbtFrame.Lane.instrumentCodeForMask(fullMask);
        if (full < 0) return new int[]{-1, 255};
        int selectedCode = DirectTbtFrame.Lane.instrumentCodeForMask(selectedMask);
        int recommendation = firstUturnMask != 0
                ? selectedUturnMask == firstUturnMask ? full : 255
                : selectedCode < 0 ? 255 : selectedCode;
        return new int[]{full, recommendation};
    }

    private void logNextStep(Step step, String source) {
        if (step == null) {
            log(source + " absent");
            return;
        }
        Maneuver maneuver = step.getManeuver();
        log(source + " rawType=" + (maneuver == null ? -1 : maneuver.getType())
                + " road=" + text(step.getRoad())
                + " cue=" + text(step.getCue())
                + " lanes=" + (step.getLanes() == null ? 0 : step.getLanes().size()));
    }

    private byte[] renderIcon(CarIcon icon, String kind) {
        if (icon == null) return new byte[0];
        Bitmap bitmap = null;
        try {
            IconCompat compat = icon.getIcon();
            Drawable drawable = compat == null ? null : compat.loadDrawable(context);
            if (drawable == null) return new byte[0];
            int sourceWidth = drawable.getIntrinsicWidth() > 0
                    ? drawable.getIntrinsicWidth() : 80;
            int sourceHeight = drawable.getIntrinsicHeight() > 0
                    ? drawable.getIntrinsicHeight() : 80;
            float scale = Math.min(1f, MAX_ICON_DIMENSION_PX
                    / (float) Math.max(sourceWidth, sourceHeight));
            int width = Math.max(1, Math.round(sourceWidth * scale));
            int height = Math.max(1, Math.round(sourceHeight * scale));
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return new byte[0];
            byte[] bytes = out.toByteArray();
            log("icon rendered kind=" + kind + " bytes=" + bytes.length);
            return bytes;
        } catch (Throwable t) {
            log("icon render failed kind=" + kind + ": " + t);
            return new byte[0];
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    static int parseDistanceMeters(String value) {
        Matcher matcher = ALERT_DISTANCE.matcher(safeText(value));
        if (!matcher.find()) return -1;
        try {
            double number = Double.parseDouble(matcher.group(1).replace(',', '.'));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            if (unit.equals("km") || unit.equals("\u043a\u043c")) number *= 1000d;
            else if (unit.equals("mi")) number *= 1609.344d;
            else if (unit.equals("yd")) number *= 0.9144d;
            else if (unit.equals("ft")) number *= 0.3048d;
            return Math.max(0, (int) Math.round(number));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void runOnChannel(Runnable action) {
        if (Thread.currentThread() == channelThread) action.run();
        else channelHandler.post(action);
    }

    private void postBinder(int expectedGeneration, Runnable action) {
        channelHandler.post(() -> {
            if (isCurrent(expectedGeneration)) action.run();
        });
    }

    private boolean isCurrent(int expectedGeneration) {
        return active && !shutdown && generation == expectedGeneration;
    }

    private void callback(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
        }
    }

    private void log(String message) {
        callback(() -> listener.onLog(message));
    }

    private static String text(CarText value) {
        return value == null ? "" : value.toString();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    static final class TemplateRefreshGate {
        private Request inFlight;
        private boolean pending;

        Request begin() {
            if (inFlight != null) {
                pending = true;
                return null;
            }
            inFlight = new Request();
            return inFlight;
        }

        Request complete(Request request) {
            if (request == null || request != inFlight) return null;
            if (!pending) {
                inFlight = null;
                return null;
            }
            pending = false;
            inFlight = new Request();
            return inFlight;
        }

        void reset() {
            inFlight = null;
            pending = false;
        }

        static final class Request {
        }
    }

    private static int meters(Distance distance) {
        if (distance == null) return 0;
        double value = distance.getDisplayDistance();
        switch (distance.getDisplayUnit()) {
            case Distance.UNIT_KILOMETERS:
            case Distance.UNIT_KILOMETERS_P1:
                return (int) Math.round(value * 1000d);
            case Distance.UNIT_MILES:
            case Distance.UNIT_MILES_P1:
                return (int) Math.round(value * 1609.344d);
            case Distance.UNIT_YARDS:
                return (int) Math.round(value * 0.9144d);
            case Distance.UNIT_FEET:
                return (int) Math.round(value * 0.3048d);
            default:
                return (int) Math.round(value);
        }
    }

    static DirectTbtFrame.TripMetrics destinationMetrics(
            List<TravelEstimate> estimates) {
        if (estimates == null || estimates.isEmpty()) {
            return DirectTbtFrame.TripMetrics.empty();
        }
        DirectTbtFrame.TravelMetrics nextStop = travelMetrics(estimates.get(0));
        DirectTbtFrame.TravelMetrics wholeRoute = estimates.size() > 1
                ? travelMetrics(estimates.get(estimates.size() - 1))
                : DirectTbtFrame.TravelMetrics.unavailable();
        return new DirectTbtFrame.TripMetrics(nextStop, wholeRoute);
    }

    private static DirectTbtFrame.TravelMetrics travelMetrics(TravelEstimate estimate) {
        if (estimate == null) return DirectTbtFrame.TravelMetrics.unavailable();
        DateTimeWithZone arrival = estimate.getArrivalTimeAtDestination();
        long arrivalTimeMs = arrival == null ? -1L : arrival.getTimeSinceEpochMillis();
        int arrivalZoneOffsetSeconds = arrival == null
                ? DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS
                : arrival.getZoneOffsetSeconds();
        long remainingSeconds = estimate.getRemainingTimeSeconds();
        Distance remainingDistance = estimate.getRemainingDistance();
        long remainingMeters = remainingDistance == null ? -1L : meters(remainingDistance);
        return new DirectTbtFrame.TravelMetrics(
                arrivalTimeMs, arrivalZoneOffsetSeconds,
                remainingSeconds, remainingMeters);
    }

    private static final int LANE_STRAIGHT = 1;
    private static final int LANE_LEFT = 2;
    private static final int LANE_RIGHT = 4;
    private static final int LANE_U_LEFT = 8;
    private static final int LANE_U_RIGHT = 16;

    private static int laneCategory(int shape) {
        switch (shape) {
            case LaneDirection.SHAPE_NORMAL_LEFT:
            case LaneDirection.SHAPE_SHARP_LEFT:
            case LaneDirection.SHAPE_SLIGHT_LEFT:
                return LANE_LEFT;
            case LaneDirection.SHAPE_NORMAL_RIGHT:
            case LaneDirection.SHAPE_SHARP_RIGHT:
            case LaneDirection.SHAPE_SLIGHT_RIGHT:
                return LANE_RIGHT;
            case LaneDirection.SHAPE_U_TURN_LEFT:
                return LANE_U_LEFT;
            case LaneDirection.SHAPE_U_TURN_RIGHT:
                return LANE_U_RIGHT;
            case LaneDirection.SHAPE_STRAIGHT:
                return LANE_STRAIGHT;
            default:
                // Preserve the legacy consumer fallback: unknown AndroidX shapes
                // were emitted as straight Instrument lanes.
                return LANE_STRAIGHT;
        }
    }

    // Intermediate maneuver codes: 0 unknown; 2/3 normal left/right;
    // 4/5 slight or keep left/right; 6/7 sharp left/right; 8/19 U-turn left/right;
    // 9 straight/depart/name-change/unspecified-merge/ferry; 15 destination;
    // 22/25 clockwise/counter-clockwise roundabout.
    private static int mapWazeToAmap(int type) {
        switch (type) {
            case Maneuver.TYPE_TURN_NORMAL_LEFT:
            case Maneuver.TYPE_ON_RAMP_NORMAL_LEFT:
            case Maneuver.TYPE_FERRY_BOAT_LEFT:
            case Maneuver.TYPE_FERRY_TRAIN_LEFT:
                return 2;
            case Maneuver.TYPE_TURN_NORMAL_RIGHT:
            case Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT:
            case Maneuver.TYPE_FERRY_BOAT_RIGHT:
            case Maneuver.TYPE_FERRY_TRAIN_RIGHT:
                return 3;
            case Maneuver.TYPE_KEEP_LEFT:
            case Maneuver.TYPE_TURN_SLIGHT_LEFT:
            case Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT:
            case Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT:
            case Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT:
            case Maneuver.TYPE_FORK_LEFT:
            case Maneuver.TYPE_MERGE_LEFT:
                return 4;
            case Maneuver.TYPE_KEEP_RIGHT:
            case Maneuver.TYPE_TURN_SLIGHT_RIGHT:
            case Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT:
            case Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT:
            case Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT:
            case Maneuver.TYPE_FORK_RIGHT:
            case Maneuver.TYPE_MERGE_RIGHT:
                return 5;
            case Maneuver.TYPE_TURN_SHARP_LEFT:
            case Maneuver.TYPE_ON_RAMP_SHARP_LEFT:
                return 6;
            case Maneuver.TYPE_TURN_SHARP_RIGHT:
            case Maneuver.TYPE_ON_RAMP_SHARP_RIGHT:
                return 7;
            case Maneuver.TYPE_U_TURN_LEFT:
            case Maneuver.TYPE_ON_RAMP_U_TURN_LEFT:
                return 8;
            case Maneuver.TYPE_U_TURN_RIGHT:
            case Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT:
                return 19;
            case Maneuver.TYPE_DESTINATION:
            case Maneuver.TYPE_DESTINATION_STRAIGHT:
            case Maneuver.TYPE_DESTINATION_LEFT:
            case Maneuver.TYPE_DESTINATION_RIGHT:
                return 15;
            case Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW:
            case Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE:
            case Maneuver.TYPE_ROUNDABOUT_ENTER_CW:
            case Maneuver.TYPE_ROUNDABOUT_EXIT_CW:
                return 22;
            case Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW:
            case Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE:
            case Maneuver.TYPE_ROUNDABOUT_ENTER_CCW:
            case Maneuver.TYPE_ROUNDABOUT_EXIT_CCW:
                return 25;
            case Maneuver.TYPE_DEPART:
            case Maneuver.TYPE_NAME_CHANGE:
            case Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED:
            case Maneuver.TYPE_STRAIGHT:
            case Maneuver.TYPE_FERRY_BOAT:
            case Maneuver.TYPE_FERRY_TRAIN:
                return 9;
            default:
                return 0;
        }
    }

    private static int mapAmapToByd(int amap) {
        switch (amap) {
            case 2:
            case 6:
                return 1;
            case 3:
            case 7:
                return 2;
            case 4:
                return 3;
            case 5:
                return 5;
            case 8:
                return 7;
            case 19:
                return 8;
            case 9:
                return 11;
            case 15:
            case 22:
            case 25:
                return 99;
            default:
                return 0;
        }
    }

    public interface Listener {
        void onHandshakeAvailable(String ownerPackage, int sessionGeneration, String reason);

        void onHandshakeUnavailable(String ownerPackage, int sessionGeneration, String reason);

        void onNavigationStarted(String ownerPackage, int sessionGeneration, String reason);

        void onFrame(String ownerPackage, int sessionGeneration,
                DirectTbtFrame frame, String reason, WazeRouteTiming.Frame timing);

        void onAlertCleared(String ownerPackage, int sessionGeneration,
                DirectTbtFrame frame, String reason);

        void onNavigationEnded(String ownerPackage, int routeGeneration,
                int callbackGeneration, String reason);

        void onLiveness(String ownerPackage, int sessionGeneration, String reason);

        void onSurfaceReady(String ownerPackage, int sessionGeneration,
                long activityInstanceId, int displayId, long surfaceEpoch);

        void onSurfaceUnavailable(String ownerPackage, int sessionGeneration, String reason);

        void onLog(String message);
    }

    private final class Connection implements ServiceConnection {
        private final int expectedGeneration;

        Connection(int expectedGeneration) {
            this.expectedGeneration = expectedGeneration;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            runOnChannel(() -> onConnected(expectedGeneration, this, name, service));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            runOnChannel(() -> onBindingLost(
                    expectedGeneration, this, "service_disconnected"));
        }

        @Override
        public void onBindingDied(ComponentName name) {
            runOnChannel(() -> onBindingLost(expectedGeneration, this, "binding_died"));
        }

        @Override
        public void onNullBinding(ComponentName name) {
            runOnChannel(() -> onBindingLost(expectedGeneration, this, "null_binding"));
        }
    }

    private final class CarHost extends ICarHost.Stub {
        private final int expectedGeneration;
        final NavigationHost navigationHost;
        final AppHost appHost;

        CarHost(int expectedGeneration) {
            this.expectedGeneration = expectedGeneration;
            navigationHost = new NavigationHost(expectedGeneration);
            appHost = new AppHost(expectedGeneration);
            if (mode == Mode.MAIN_SURFACE) WazeSurfaceActivity.attachHostBridge(appHost);
        }

        @Override
        public int getInterfaceVersion() {
            return ICarHost.VERSION;
        }

        @Override
        public IBinder getHost(String type) {
            if (!isCurrent(expectedGeneration)) return null;
            postBinder(expectedGeneration, () -> log("get host type=" + type));
            if ("navigation".equals(type)) return navigationHost.asBinder();
            if ("app".equals(type)) return appHost.asBinder();
            return null;
        }

        @Override
        public void startCarApp(Intent intent) {
            postBinder(expectedGeneration, () -> log("start car app ignored"));
        }

        @Override
        public void finish() {
            postBinder(expectedGeneration, () -> {
                latchRouteTerminal("car_host_finish");
                endNavigation("car_host_finish");
            });
        }
    }

    private final class AppHost extends IAppHost.Stub
            implements WazeSurfaceActivity.HostBridge {
        private final int expectedGeneration;
        private Runnable invalidateCallback;
        private ISurfaceCallback surfaceCallback;
        private Surface surface;
        private int surfaceWidth;
        private int surfaceHeight;
        private int surfaceDpi;
        private long activityInstanceId;
        private int activityDisplayId = -1;
        private long activitySurfaceEpoch;
        private final Rect visibleArea = new Rect();
        private boolean surfaceDeliveryAttempted;
        private boolean surfaceDelivered;

        AppHost(int expectedGeneration) {
            this.expectedGeneration = expectedGeneration;
        }

        @Override
        public int getInterfaceVersion() {
            return IAppHost.VERSION;
        }

        void setInvalidateCallback(Runnable callback) {
            invalidateCallback = callback;
        }

        @Override
        public void setSurfaceCallback(ISurfaceCallback callback) {
            postBinder(expectedGeneration, () -> {
                if (surfaceCallback != callback && surfaceCallback != null) {
                    notifySurfaceDestroyed("callback-replaced");
                    surfaceDeliveryAttempted = false;
                    surfaceDelivered = false;
                }
                surfaceCallback = callback;
                log("surface callback present=" + (callback != null) + " mode=" + mode);
                deliverSurfaceIfReady();
            });
        }

        void clearSurfaceSession(String reason) {
            WazeSurfaceActivity.detachHostBridge(this);
            notifySurfaceDestroyed(reason);
            surfaceCallback = null;
            surface = null;
            visibleArea.setEmpty();
            surfaceDeliveryAttempted = false;
            surfaceDelivered = false;
        }

        void prepareSurfaceHandoff(String reason) {
            notifySurfaceDestroyed("display-handoff:" + safeText(reason));
            surface = null;
            visibleArea.setEmpty();
            surfaceDeliveryAttempted = false;
            surfaceDelivered = false;
            log("surface delivery reset for display handoff reason=" + safeText(reason));
        }

        @Override
        public void onSurfaceReady(Surface value, int width, int height, int dpi, Rect area,
                long instanceId, int displayId, long surfaceEpoch) {
            runOnChannel(() -> {
                if (!isCurrent(expectedGeneration) || mode != Mode.MAIN_SURFACE
                        || value == null || !value.isValid()) return;
                surface = value;
                surfaceWidth = Math.max(1, width);
                surfaceHeight = Math.max(1, height);
                surfaceDpi = Math.max(1, dpi);
                activityInstanceId = instanceId;
                activityDisplayId = displayId;
                activitySurfaceEpoch = surfaceEpoch;
                visibleArea.set(area == null ? new Rect() : area);
                deliverSurfaceIfReady();
            });
        }

        @Override
        public void onSurfaceDestroyed(Runnable completion) {
            runOnChannel(() -> {
                notifySurfaceDestroyed("activity-surface-destroyed", completion);
                surface = null;
                surfaceDelivered = false;
                int callbackGeneration = sessionGeneration;
                callback(() -> listener.onSurfaceUnavailable(
                        OWNER_PACKAGE, callbackGeneration, "activity-surface-destroyed"));
            });
        }

        @Override
        public void onVisibleAreaChanged(Rect area) {
            runOnChannel(() -> {
                visibleArea.set(area == null ? new Rect() : area);
                sendVisibleArea();
            });
        }

        @Override
        public void onClick(float x, float y) {
            dispatchSurfaceInput("click", callback -> callback.onClick(x, y));
        }

        @Override
        public void onScroll(float distanceX, float distanceY) {
            dispatchSurfaceInput("scroll", callback -> callback.onScroll(distanceX, distanceY));
        }

        @Override
        public void onFling(float velocityX, float velocityY) {
            dispatchSurfaceInput("fling", callback -> callback.onFling(velocityX, velocityY));
        }

        @Override
        public void onScale(float focusX, float focusY, float scaleFactor) {
            dispatchSurfaceInput("scale",
                    callback -> callback.onScale(focusX, focusY, scaleFactor));
        }

        @Override
        public void onBackPressedByHost() {
            runOnChannel(() -> {
                if (appManager == null) return;
                try {
                    appManager.onBackPressed(new DoneCallback(
                            expectedGeneration, "onBackPressed", null));
                } catch (Throwable error) {
                    log("surface back failed: " + error);
                }
            });
        }

        private void deliverSurfaceIfReady() {
            ISurfaceCallback surfaceCallbackValue = surfaceCallback;
            Surface value = surface;
            if (surfaceDelivered || mode != Mode.MAIN_SURFACE
                    || surfaceCallbackValue == null || value == null
                    || !value.isValid()) return;
            surfaceDeliveryAttempted = true;
            long deliveredInstanceId = activityInstanceId;
            int deliveredDisplayId = activityDisplayId;
            long deliveredSurfaceEpoch = activitySurfaceEpoch;
            try {
                surfaceCallbackValue.onSurfaceAvailable(Bundleable.create(new SurfaceContainer(
                                value, surfaceWidth, surfaceHeight, surfaceDpi)),
                        new DoneCallback(expectedGeneration, "onSurfaceAvailable", ignored -> {
                            if (surfaceCallbackValue != surfaceCallback || surface != value
                                    || !value.isValid()
                                    || deliveredInstanceId != activityInstanceId
                                    || deliveredDisplayId != activityDisplayId
                                    || deliveredSurfaceEpoch != activitySurfaceEpoch) return;
                            surfaceDelivered = true;
                            sendVisibleArea();
                            int callbackGeneration = sessionGeneration;
                            callback(() -> listener.onSurfaceReady(
                                    OWNER_PACKAGE, callbackGeneration,
                                    deliveredInstanceId, deliveredDisplayId,
                                    deliveredSurfaceEpoch));
                            log("surface delivered width=" + surfaceWidth
                                    + " height=" + surfaceHeight + " dpi=" + surfaceDpi);
                        }));
            } catch (Throwable error) {
                log("surface delivery failed: " + error);
                int callbackGeneration = sessionGeneration;
                callback(() -> listener.onSurfaceUnavailable(
                        OWNER_PACKAGE, callbackGeneration, "surface-delivery-failed"));
            }
        }

        private void notifySurfaceDestroyed(String reason) {
            notifySurfaceDestroyed(reason, null);
        }

        private void notifySurfaceDestroyed(String reason, Runnable completion) {
            ISurfaceCallback callback = surfaceCallback;
            if (!surfaceDeliveryAttempted || callback == null) {
                if (completion != null) completion.run();
                return;
            }
            try {
                callback.onSurfaceDestroyed(Bundleable.create(
                                new SurfaceContainer(null, 0, 0, 0)),
                        new DoneCallback(expectedGeneration, "onSurfaceDestroyed", null,
                                completion));
                log("surface destroyed notified reason=" + safeText(reason));
            } catch (Throwable error) {
                log("surface destroy notify failed: " + error);
                if (completion != null) completion.run();
            }
        }

        private void sendVisibleArea() {
            ISurfaceCallback callback = surfaceCallback;
            if (!surfaceDelivered || callback == null || visibleArea.isEmpty()) return;
            Rect area = new Rect(visibleArea);
            try {
                callback.onVisibleAreaChanged(area,
                        new DoneCallback(expectedGeneration, "onVisibleAreaChanged", null));
                callback.onStableAreaChanged(area,
                        new DoneCallback(expectedGeneration, "onStableAreaChanged", null));
            } catch (Throwable error) {
                log("surface area failed: " + error);
            }
        }

        private void dispatchSurfaceInput(String type, SurfaceInput input) {
            runOnChannel(() -> {
                ISurfaceCallback callback = surfaceCallback;
                if (callback == null) return;
                try {
                    input.run(callback);
                    log("surface input type=" + type);
                } catch (Throwable error) {
                    log("surface input failed type=" + type + " error=" + error);
                }
            });
        }

        @Override
        public void invalidate() {
            postBinder(expectedGeneration, () -> {
                log("app invalidated");
                if (invalidateCallback != null) invalidateCallback.run();
            });
        }

        @Override
        public void showToast(CharSequence text, int duration) {
            postBinder(expectedGeneration, () ->
                    log("toast text=" + text + " duration=" + duration));
        }

        @Override
        public void showAlert(Bundleable bundle) {
            postBinder(expectedGeneration, () -> {
                try {
                    Object value = bundle == null ? null : bundle.get();
                    if (value instanceof Alert) {
                        Alert alert = (Alert) value;
                        if (mode == Mode.MAIN_SURFACE) WazeSurfaceActivity.showAlert(alert);
                        if (wazeAlertsEnabled) WazeDirectChannel.this.showAlert(alert);
                    }
                    else log("invalid alert=" + typeName(value));
                } catch (Throwable t) {
                    log("alert parse failed: " + t);
                }
            });
        }

        @Override
        public void dismissAlert(int alertId) {
            postBinder(expectedGeneration, () -> {
                if (mode == Mode.MAIN_SURFACE) WazeSurfaceActivity.dismissAlert(alertId);
                WazeDirectChannel.this.dismissAlert(alertId);
            });
        }

        @Override
        public Bundleable openMicrophone(Bundleable request) {
            postBinder(expectedGeneration, () -> log("microphone unsupported"));
            return null;
        }

        @Override
        public void sendLocation(Location location) {
            postBinder(expectedGeneration, () -> log(
                    "location provider=" + (location == null ? "null" : location.getProvider())));
        }

        private interface SurfaceInput {
            void run(ISurfaceCallback callback) throws Exception;
        }
    }

    private static int mapWazeToAmapBroadcast(int type, int mappedManeuver) {
        switch (type) {
            case Maneuver.TYPE_ROUNDABOUT_EXIT_CW:
                return 18;
            case Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW:
            case Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE:
            case Maneuver.TYPE_ROUNDABOUT_ENTER_CW:
                return 17;
            case Maneuver.TYPE_ROUNDABOUT_EXIT_CCW:
                return 12;
            case Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW:
            case Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE:
            case Maneuver.TYPE_ROUNDABOUT_ENTER_CCW:
                return 11;
            default:
                return mappedManeuver;
        }
    }

    private static int roundaboutExitNumber(Maneuver maneuver) {
        if (maneuver == null) return 0;
        try {
            return maneuver.getRoundaboutExitNumber();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    static int mapWazeToAmapBroadcastForTest(int type) {
        return mapWazeToAmapBroadcast(type, mapWazeToAmap(type));
    }

    private final class NavigationHost extends INavigationHost.Stub {
        private final int expectedGeneration;

        NavigationHost(int expectedGeneration) {
            this.expectedGeneration = expectedGeneration;
        }

        @Override
        public int getInterfaceVersion() {
            return INavigationHost.VERSION;
        }

        @Override
        public void setVoiceAssistantCapabilities(Bundleable capabilities) {
            postBinder(expectedGeneration, () -> log("voice assistant capabilities received"));
        }

        @Override
        public void navigationStarted() {
            postBinder(expectedGeneration, WazeDirectChannel.this::explicitNavigationStarted);
        }

        @Override
        public void navigationEnded() {
            postBinder(expectedGeneration, () -> {
                latchRouteTerminal("waze_navigation_ended");
                endNavigation("waze_navigation_ended");
            });
        }

        @Override
        public void updateTrip(Bundleable bundle) {
            postBinder(expectedGeneration, () -> {
                try {
                    Object value = bundle == null ? null : bundle.get();
                    if (!(value instanceof Trip)) {
                        log("invalid trip=" + typeName(value));
                        return;
                    }
                    Trip trip = (Trip) value;
                    List<TravelEstimate> destinationEstimates =
                            trip.getDestinationTravelEstimates();
                    DirectTbtFrame.TripMetrics tripMetrics =
                            destinationMetrics(destinationEstimates);
                    List<Step> steps = trip.getSteps();
                    if (steps == null || steps.isEmpty()) {
                        navigationFrame = navigationFrame.withTripMetrics(tripMetrics);
                        log("trip has no steps; destination metrics updated");
                        if (navigationActive || acceptedRouteFrame) {
                            recordDirectActivity("frame:trip_metrics_only");
                            emitFrame("trip_metrics_only");
                        }
                        return;
                    }
                    Distance distance = null;
                    List<TravelEstimate> estimates = trip.getStepTravelEstimates();
                    if (estimates != null && !estimates.isEmpty()) {
                        distance = estimates.get(0).getRemainingDistance();
                    }
                    publishCurrentStep(steps.get(0), distance, false,
                            "trip_current", tripMetrics);
                    logNextStep(steps.size() > 1 ? steps.get(1) : null, "trip_next");
                    log("trip destination estimates="
                            + (destinationEstimates == null ? 0 : destinationEstimates.size())
                            + " nextStopSeconds="
                            + tripMetrics.getNextStop().getRemainingTimeSeconds()
                            + " nextStopMeters="
                            + tripMetrics.getNextStop().getRemainingDistanceMeters()
                            + " remainingSeconds="
                            + tripMetrics.getWholeRoute().getRemainingTimeSeconds()
                            + " remainingMeters="
                            + tripMetrics.getWholeRoute().getRemainingDistanceMeters());
                } catch (Throwable t) {
                    log("trip parse failed: " + t);
                }
            });
        }
    }

    private final class DoneCallback extends IOnDoneCallback.Stub {
        private final int expectedGeneration;
        private final String name;
        private final ResultHandler success;
        private final Runnable completion;
        private final int expectedSessionGeneration;

        DoneCallback(int expectedGeneration, String name, ResultHandler success) {
            this(expectedGeneration, name, success, null);
        }

        DoneCallback(int expectedGeneration, String name, ResultHandler success,
                Runnable completion) {
            this.expectedGeneration = expectedGeneration;
            this.expectedSessionGeneration = sessionGeneration;
            this.name = name;
            this.success = success;
            this.completion = completion;
        }

        @Override
        public int getInterfaceVersion() {
            return IOnDoneCallback.VERSION;
        }

        @Override
        public void onSuccess(Bundleable response) {
            postBinder(expectedGeneration, () -> {
                if (!callbackMatchesSession(
                        expectedSessionGeneration, sessionGeneration, suspended)) {
                    if (completion != null) completion.run();
                    return;
                }
                log("callback success=" + name);
                try {
                    if (success != null) success.run(response);
                } catch (Throwable t) {
                    sessionFailure(expectedGeneration, "callback_handler_" + name, t);
                } finally {
                    if (completion != null) completion.run();
                }
            });
        }

        @Override
        public void onFailure(Bundleable failure) {
            String payload;
            try {
                Object value = failure == null ? null : failure.get();
                payload = value instanceof FailureResponse
                        ? ((FailureResponse) value).getStackTrace() : String.valueOf(value);
            } catch (Throwable t) {
                payload = "failure decode error=" + t;
            }
            String finalPayload = payload;
            postBinder(expectedGeneration, () -> {
                if (!callbackMatchesSession(
                        expectedSessionGeneration, sessionGeneration, suspended)) {
                    if (completion != null) completion.run();
                    return;
                }
                try {
                    if (success == null) {
                        log("callback failure=" + name + " detail=" + finalPayload);
                    } else {
                        sessionFailure(expectedGeneration, "callback_failure_" + name,
                                new IllegalStateException(finalPayload));
                    }
                } finally {
                    if (completion != null) completion.run();
                }
            });
        }
    }

    static boolean callbackMatchesSession(
            int expectedSessionGeneration, int currentSessionGeneration, boolean suspended) {
        return !suspended && expectedSessionGeneration == currentSessionGeneration;
    }

    private interface ResultHandler {
        void run(Bundleable result) throws Exception;
    }
}
