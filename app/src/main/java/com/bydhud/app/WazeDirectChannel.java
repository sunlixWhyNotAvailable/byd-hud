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
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

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

/** Direct, surface-free AndroidX Car App host for Waze cluster navigation data. */
public final class WazeDirectChannel {
    static final String OWNER_PACKAGE = "com.waze";
    private static final String CAR_APP_ACTION = "androidx.car.app.CarAppService";
    private static final ComponentName WAZE_SERVICE = new ComponentName(
            "com.waze", "com.waze.car_lib.WazeCarAppService");
    private static final long REBIND_DELAY_MS = 1000L;
    private static final long BIND_CALLBACK_TIMEOUT_MS = 10000L;
    private static final long FRAME_SILENCE_MS = 5000L;
    private static final long HEALTH_PROBE_TIMEOUT_MS = 5000L;
    private static final long ALERT_TTL_MS = 5000L;
    private static final int ALERT_ROUTE_NATIVE_NEAR_METERS = 100;
    private static final int MAX_ICON_DIMENSION_PX = 256;
    private static final Pattern ALERT_DISTANCE = Pattern.compile(
            "(\\d+[.,]?\\d*)\\s*(\\u043a\\u043c|km|mi|yd|ft|\\u043c|m)(?=$|\\s|[.,;:!?])",
            Pattern.CASE_INSENSITIVE);

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
    private boolean unavailableBindFailureReported;
    private String bindDeferredReason = "";
    private int routingSequence;
    private Connection connection;
    private ICarApp carApp;
    private IAppManager appManager;
    private CarHost carHost;
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

    public WazeDirectChannel(Context context, Listener listener) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.listener = Objects.requireNonNull(listener, "listener");
        wazeAlertsEnabled = HudPrefs.isWazeAlertsEnabled(this.context);
        channelThread.start();
        channelHandler = new Handler(channelThread.getLooper());
    }

    public void start(String reason) {
        runOnChannel(() -> startOnChannel(reason));
    }

    public void stop(String reason) {
        runOnChannel(() -> suspendOnChannel(reason));
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

    private void startOnChannel(String reason) {
        if (shutdown) {
            log("start ignored after shutdown: " + safeText(reason));
            return;
        }
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
        startReason = safeText(reason);
        unavailableBindFailureReported = false;
        bindDeferredReason = "";
        routingSequence = 0;
        navigationFrame = DirectTbtFrame.empty();
        lastKnownRawManeuverType = -1;
        navigationDistanceKnown = false;
        alert = DirectTbtFrame.AlertOverlay.inactive();
        maneuverIcons.clear();
        rearmRouteTerminal("channel_start");
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
        setHandshakeUnavailable("suspended:" + stopReason, false);
        endNavigation("suspended:" + stopReason, false);
        pauseAndStopSession(oldGeneration, stopReason);
        bindDeferredReason = "";
        log("suspended reason=" + stopReason + " bindingRetained=" + bound);
    }

    private void hardStopOnChannel(String reason) {
        String stopReason = safeText(reason);
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
        SessionInfoIntentEncoder.encode(
                new SessionInfo(SessionInfo.DISPLAY_TYPE_CLUSTER, "cluster"), intent);

        carHost = nextHost;
        connection = nextConnection;
        binding = true;
        try {
            bound = context.bindService(intent, nextConnection, Context.BIND_AUTO_CREATE);
            binding = bound;
            if (bound) {
                log("bind result=true component=" + WAZE_SERVICE.flattenToShortString());
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
        rearmRouteTerminal("car_app_session_connected");
        setHandshakeAvailable("session_ready:" + startReason);
        fetchTemplate(expectedGeneration);
    }

    private void fetchTemplate(int expectedGeneration) {
        if (!isCurrent(expectedGeneration) || suspended || appManager == null) return;
        try {
            appManager.getTemplate(new DoneCallback(
                    expectedGeneration, "getTemplate",
                    response -> {
                        onTemplate(expectedGeneration, response);
                        recordTemplateResponse("getTemplate");
                    }));
        } catch (Throwable t) {
            sessionFailure(expectedGeneration, "get_template_exception", t);
        }
    }

    private void onTemplate(int expectedGeneration, Bundleable response) throws Exception {
        if (suspended) return;
        Object value = response == null ? null : response.get();
        if (!(value instanceof TemplateWrapper)) {
            log("unexpected template wrapper=" + typeName(value));
            return;
        }
        Template template = ((TemplateWrapper) value).getTemplate();
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
        if (suspended || navigationActive) return;
        navigationActive = true;
        maneuverIcons.clear();
        recordDirectActivity("navigation_started");
        int callbackGeneration = sessionGeneration;
        callback(() -> listener.onNavigationStarted(
                OWNER_PACKAGE, callbackGeneration, reason));
    }

    private void explicitNavigationStarted() {
        if (suspended) return;
        rearmRouteTerminal("waze_navigation_started");
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
        int callbackGeneration = notifyListener ? ++sessionGeneration : sessionGeneration;
        navigationActive = false;
        if (notifyListener) {
            callback(() -> listener.onNavigationEnded(
                    OWNER_PACKAGE, callbackGeneration, reason));
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
        emitFrame(reason);
    }

    private DirectTbtFrame frameFromStep(
            Step step, Distance distance, DirectTbtFrame.TripMetrics tripMetrics) {
        Maneuver maneuver = step.getManeuver();
        int rawType = maneuver == null ? -1 : maneuver.getType();
        int amap = maneuver == null ? 0 : mapWazeToAmap(rawType);
        int byd = amap == 15 ? 99 : mapAmapToByd(amap);
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
                tripMetrics);
    }

    private void emitFrame(String reason) {
        alert = alert.withRouteNative(
                shouldUseRouteNativeDuringAlert(
                        navigationFrame, navigationDistanceKnown, alert));
        DirectTbtFrame frame = navigationFrame.withAlertOverlay(alert);
        int callbackGeneration = sessionGeneration;
        callback(() -> listener.onFrame(
                OWNER_PACKAGE, callbackGeneration, frame, reason));
    }

    static boolean shouldUseRouteNativeDuringAlert(
            DirectTbtFrame routeFrame,
            boolean routeDistanceKnown,
            DirectTbtFrame.AlertOverlay alert) {
        if (routeFrame == null || alert == null || !alert.isActive()
                || !routeDistanceKnown || routeFrame.getRawManeuverType() < 0) {
            return false;
        }
        int nativeManeuver = routeFrame.getBydManeuver();
        if (nativeManeuver <= 0 || nativeManeuver == 99) return false;
        int routeDistance = routeFrame.getDistanceMeters();
        if (routeDistance <= ALERT_ROUTE_NATIVE_NEAR_METERS) return true;
        int alertDistance = alert.getDistanceMeters();
        return alertDistance > 0 && routeDistance <= alertDistance;
    }

    private void showAlert(Alert value) {
        if (suspended) return;
        String title = text(value.getTitle());
        String subtitle = text(value.getSubtitle());
        int distanceMeters = parseDistance(title + " " + subtitle);
        String displayText = subtitle.isEmpty() ? title : subtitle;
        byte[] icon = renderIcon(value.getIcon(), "alert");
        if (alert.isActive() && alert.getId() != value.getId()) {
            log("alert replaced oldId=" + alert.getId() + " newId=" + value.getId());
        }
        int revision = ++alertRevision;
        alert = DirectTbtFrame.AlertOverlay.active(
                value.getId(), distanceMeters, displayText, icon);
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
            LaneDirection chosen = directions.get(0);
            boolean recommended = false;
            StringBuilder raw = new StringBuilder();
            for (LaneDirection direction : directions) {
                if (raw.length() > 0) raw.append('|');
                raw.append(direction.getShape()).append(':').append(direction.isRecommended());
                if (direction.isRecommended() && !recommended) {
                    chosen = direction;
                    recommended = true;
                }
            }
            out.add(new DirectTbtFrame.Lane(
                    directionFromShape(chosen.getShape()), recommended, raw.toString()));
        }
        return out;
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

    private int parseDistance(String value) {
        Matcher matcher = ALERT_DISTANCE.matcher(safeText(value));
        if (!matcher.find()) return 0;
        try {
            double number = Double.parseDouble(matcher.group(1).replace(',', '.'));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            if (unit.equals("km") || unit.equals("\u043a\u043c")) number *= 1000d;
            else if (unit.equals("mi")) number *= 1609.344d;
            else if (unit.equals("yd")) number *= 0.9144d;
            else if (unit.equals("ft")) number *= 0.3048d;
            return Math.max(0, (int) Math.round(number));
        } catch (RuntimeException e) {
            log("alert distance parse failed: " + value);
            return 0;
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

    static DirectTbtFrame.TripMetrics destinationMetrics(TravelEstimate estimate) {
        if (estimate == null) return DirectTbtFrame.TripMetrics.empty();
        DateTimeWithZone arrival = estimate.getArrivalTimeAtDestination();
        long arrivalTimeMs = arrival == null ? -1L : arrival.getTimeSinceEpochMillis();
        int arrivalZoneOffsetSeconds = arrival == null
                ? DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS
                : arrival.getZoneOffsetSeconds();
        long remainingSeconds = estimate.getRemainingTimeSeconds();
        Distance remainingDistance = estimate.getRemainingDistance();
        long remainingMeters = remainingDistance == null ? -1L : meters(remainingDistance);
        return DirectTbtFrame.TripMetrics.nextStopOnly(
                new DirectTbtFrame.TravelMetrics(
                        arrivalTimeMs, arrivalZoneOffsetSeconds,
                        remainingSeconds, remainingMeters));
    }

    private static int directionFromShape(int shape) {
        switch (shape) {
            case LaneDirection.SHAPE_NORMAL_LEFT:
            case LaneDirection.SHAPE_SHARP_LEFT:
            case LaneDirection.SHAPE_SLIGHT_LEFT:
            case LaneDirection.SHAPE_U_TURN_LEFT:
                return 2;
            case LaneDirection.SHAPE_NORMAL_RIGHT:
            case LaneDirection.SHAPE_SHARP_RIGHT:
            case LaneDirection.SHAPE_SLIGHT_RIGHT:
            case LaneDirection.SHAPE_U_TURN_RIGHT:
                return 3;
            default:
                return 9;
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
                DirectTbtFrame frame, String reason);

        void onAlertCleared(String ownerPackage, int sessionGeneration,
                DirectTbtFrame frame, String reason);

        void onNavigationEnded(String ownerPackage, int sessionGeneration, String reason);

        void onLiveness(String ownerPackage, int sessionGeneration, String reason);

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

    private final class AppHost extends IAppHost.Stub {
        private final int expectedGeneration;
        private Runnable invalidateCallback;

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
            postBinder(expectedGeneration, () -> log("surface callback ignored"));
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
            if (!wazeAlertsEnabled) return;
            postBinder(expectedGeneration, () -> {
                if (!wazeAlertsEnabled) return;
                try {
                    Object value = bundle == null ? null : bundle.get();
                    if (value instanceof Alert) WazeDirectChannel.this.showAlert((Alert) value);
                    else log("invalid alert=" + typeName(value));
                } catch (Throwable t) {
                    log("alert parse failed: " + t);
                }
            });
        }

        @Override
        public void dismissAlert(int alertId) {
            postBinder(expectedGeneration, () -> WazeDirectChannel.this.dismissAlert(alertId));
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
                    TravelEstimate destinationEstimate = null;
                    List<TravelEstimate> destinationEstimates =
                            trip.getDestinationTravelEstimates();
                    if (destinationEstimates != null && !destinationEstimates.isEmpty()) {
                        destinationEstimate = destinationEstimates.get(0);
                    }
                    DirectTbtFrame.TripMetrics tripMetrics =
                            destinationMetrics(destinationEstimate);
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
                            + " remainingSeconds="
                            + tripMetrics.getNextStop().getRemainingTimeSeconds()
                            + " remainingMeters="
                            + tripMetrics.getNextStop().getRemainingDistanceMeters());
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

        DoneCallback(int expectedGeneration, String name, ResultHandler success) {
            this.expectedGeneration = expectedGeneration;
            this.name = name;
            this.success = success;
        }

        @Override
        public int getInterfaceVersion() {
            return IOnDoneCallback.VERSION;
        }

        @Override
        public void onSuccess(Bundleable response) {
            postBinder(expectedGeneration, () -> {
                log("callback success=" + name);
                if (success == null) return;
                try {
                    success.run(response);
                } catch (Throwable t) {
                    sessionFailure(expectedGeneration, "callback_handler_" + name, t);
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
                if (success == null) {
                    log("callback failure=" + name + " detail=" + finalPayload);
                    return;
                }
                sessionFailure(expectedGeneration, "callback_failure_" + name,
                        new IllegalStateException(finalPayload));
            });
        }
    }

    private interface ResultHandler {
        void run(Bundleable result) throws Exception;
    }
}
