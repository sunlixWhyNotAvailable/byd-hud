package com.bydhud.app;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import org.json.JSONObject;

/** Latest-only spatial preparation; the sender only reads immutable results. */
final class VehicleRouteRuntime {
    private static volatile VehicleRouteRuntime current;
    private final Context context;
    private final HandlerThread thread = new HandlerThread("byd-route-position");
    private final Handler worker;
    private final Runnable drain = this::refresh;
    private final Runnable expire = this::schedule;
    private final Object lock = new Object();
    private long generation;
    private boolean stopped;
    private String distanceOwner = "";
    private NavigatorRouteData.Header distanceHeader;
    private DirectTbtFrame.SourceDistance distance;
    private NavigatorRouteData route;
    private HudSpatialGeometry resolver;
    private HudSpatialGeometry.Result result;
    private String resultOwner = "";
    private HudSpatialGeometry.Result fallback;
    private volatile boolean permissionsGranted;
    private String inputOwner = "";
    private NavigatorRouteData inputRoute;
    private NavigatorRouteStore.CurrentStep inputStep;
    private boolean inputActive;
    private boolean inputPositionPresent;
    private boolean resetResolver;
    private String loggedKey = "";

    private VehicleRouteRuntime(Context context) {
        this.context = context.getApplicationContext();
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    static synchronized void start(Context context) {
        if (current != null) return;
        current = new VehicleRouteRuntime(context);
        HudRuntimeService.setVehiclePositionListener(VehicleRouteRuntime::changed);
        current.schedule();
    }

    static synchronized void stop(String reason) {
        VehicleRouteRuntime state = current;
        current = null;
        HudRuntimeService.setVehiclePositionListener(null);
        HudRuntimeService.setVehiclePositionRouteSource(null);
        if (state == null) return;
        synchronized (state.lock) {
            state.stopped = true;
            state.generation++;
            state.distance = null;
            state.distanceHeader = null;
            state.result = null;
        }
        state.worker.removeCallbacksAndMessages(null);
        state.thread.quitSafely();
    }

    static void changed() {
        VehicleRouteRuntime state = current;
        if (state != null) state.schedule();
    }

    static HudSpatialGeometry.Result snapshot(String owner, long nowMs) {
        VehicleRouteRuntime state = current;
        if (state == null || !state.outputEnabled(owner) || !state.permissionsGranted
                || !HudRuntimeService.isVehiclePositionPermissionGranted())
            return HudSpatialGeometry.Result.off();
        NavigatorRouteStore.CurrentSnapshot source = NavigatorRouteReceiver.snapshot(owner);
        VehiclePositionController.Snapshot location = HudRuntimeService.getVehiclePositionSnapshot();
        synchronized (state.lock) {
            if (state.stopped) return HudSpatialGeometry.Result.off();
            if (HudRuntimeService.isVehiclePositionRuntimeSuspended())
                return state.fallback(source, "runtime_suspended");
            HudSpatialGeometry.Result ready = state.result;
            if (source == null || source.route == null)
                return state.fallback(source, "route_unavailable");
            if (ready == null || !owner.equals(state.resultOwner)
                    || ready.header == null || !ready.header.sameSnapshot(source.header)
                    || ready.stepSerial != stepSerial(source))
                return state.fallback(source, "preparing");
            if (ready.mode == HudSpatialGeometry.Mode.REAL) {
                if (location == null || !owner.equals(location.routeSource)
                        || location.position == null)
                    return state.fallback(source, "position_unavailable");
                if (!ready.usableAt(nowMs)) return state.fallback(source, "input_expired");
            }
            return ready;
        }
    }

    private boolean outputEnabled(String owner) {
        return owner != null && owner.equals(NavCapturePrefs.getHudPackage(context))
                && NavCapturePrefs.isHudEnabled(context, owner)
                && HudPrefs.isVehiclePositionEnabled(context)
                && !HudPrefs.isUserShutdownActive(context);
    }

    private static long stepSerial(NavigatorRouteStore.CurrentSnapshot source) {
        return source == null || source.step == null ? -1 : source.step.stepSerial;
    }

    private HudSpatialGeometry.Result fallback(NavigatorRouteStore.CurrentSnapshot source, String reason) {
        NavigatorRouteData.Header header = source == null ? null : source.header;
        if (fallback == null || !reason.equals(fallback.reason)
                || fallback.stepSerial != stepSerial(source)
                || (header == null ? fallback.header != null
                        : !header.sameSnapshot(fallback.header))) {
            fallback = HudSpatialGeometry.Result.fallback(header, stepSerial(source), reason);
        }
        return fallback;
    }

    static void onFrame(String owner, DirectTbtFrame frame) {
        VehicleRouteRuntime state = current;
        if (state == null || !NavCapturePrefs.isHudEnabled(state.context, owner)) return;
        NavigatorRouteStore.CurrentSnapshot route = NavigatorRouteReceiver.snapshot(owner);
        synchronized (state.lock) {
            if (state.stopped) return;
            state.distanceOwner = owner;
            state.distanceHeader = route == null ? null : route.header;
            state.distance = frame == null ? null : frame.getSourceDistance();
        }
        state.schedule();
    }

    private void schedule() {
        String owner = NavCapturePrefs.getHudPackage(context);
        boolean active = outputEnabled(owner)
                && !HudRuntimeService.isVehiclePositionRuntimeSuspended();
        NavigatorRouteStore.CurrentSnapshot source = NavigatorRouteReceiver.snapshot(owner);
        VehiclePositionController.Snapshot location = HudRuntimeService.getVehiclePositionSnapshot();
        boolean positionPresent = active && location != null && owner.equals(location.routeSource)
                && location.position != null;
        NavigatorRouteData nextRoute = source == null ? null : source.route;
        NavigatorRouteStore.CurrentStep nextStep = source == null ? null : source.step;
        synchronized (lock) {
            if (stopped) return;
            boolean routeChanged = !owner.equals(inputOwner) || nextRoute != inputRoute;
            if (routeChanged || nextStep != inputStep || active != inputActive
                    || positionPresent != inputPositionPresent) {
                generation++;
                result = null;
                if (routeChanged || !active || !positionPresent) resetResolver = true;
            }
            inputOwner = owner;
            inputRoute = nextRoute;
            inputStep = nextStep;
            inputActive = active;
            inputPositionPresent = positionPresent;
            worker.removeCallbacks(drain);
            worker.post(drain);
        }
    }

    private void refresh() {
        final long token;
        final String frameOwner;
        final NavigatorRouteData.Header frameHeader;
        final DirectTbtFrame.SourceDistance sample;
        synchronized (lock) {
            if (stopped) return;
            token = generation;
            frameOwner = distanceOwner;
            frameHeader = distanceHeader;
            sample = distance;
            if (resetResolver) {
                route = null;
                resolver = null;
                resetResolver = false;
            }
        }
        worker.removeCallbacks(expire);
        String owner = NavCapturePrefs.getHudPackage(context);
        NavigatorRouteStore.CurrentSnapshot snapshot = NavigatorRouteReceiver.snapshot(owner);
        permissionsGranted = HudRuntimeService.hasVehiclePositionPermissions(context);
        boolean requested = outputEnabled(owner);
        boolean enabled = requested && permissionsGranted;
        boolean suspended = HudRuntimeService.isVehiclePositionRuntimeSuspended();
        boolean hasRoute = snapshot != null && snapshot.header != null
                && (snapshot.route != null
                || snapshot.outcome == NavigatorRouteStore.Outcome.COLLECTING
                || snapshot.outcome == NavigatorRouteStore.Outcome.READY_TO_DECODE
                || snapshot.outcome == NavigatorRouteStore.Outcome.REPLAY_REQUIRED);
        boolean eligible = enabled && !suspended && hasRoute;
        HudRuntimeService.setVehiclePositionRouteSource(requested && hasRoute ? owner : null);
        if (!enabled) {
            synchronized (lock) {
                if (stopped || token != generation) return;
                result = HudSpatialGeometry.Result.off();
                resultOwner = owner;
                distance = null;
                distanceHeader = null;
                route = null;
                resolver = null;
            }
            record(result, null, null, SystemClock.elapsedRealtime());
            return;
        }
        if (!eligible || snapshot.route == null) {
            HudSpatialGeometry.Result unavailable;
            synchronized (lock) {
                if (stopped || token != generation) return;
                result = fallback(snapshot, suspended ? "runtime_suspended" : "route_unavailable");
                resultOwner = owner;
                unavailable = result;
                route = null;
                resolver = null;
            }
            record(unavailable, null, null, SystemClock.elapsedRealtime());
            return;
        }
        if (route != snapshot.route) {
            route = snapshot.route;
            resolver = new HudSpatialGeometry(route);
        }
        VehiclePositionController.Snapshot location = HudRuntimeService.getVehiclePositionSnapshot();
        VehiclePosition position = location != null && owner.equals(location.routeSource)
                ? location.position : null;
        DirectTbtFrame.SourceDistance matched = owner.equals(frameOwner)
                && route.header().sameSnapshot(frameHeader) ? sample : null;
        long now = SystemClock.elapsedRealtime();
        HudSpatialGeometry.Result next = resolver.resolve(snapshot.step, position, matched, now);
        synchronized (lock) {
            if (stopped || token != generation) return;
            result = next;
            resultOwner = owner;
        }
        record(next, position, matched, now);
        long deadline = next.mode == HudSpatialGeometry.Mode.REAL
                ? next.validUntilElapsedMs + 1 : Long.MAX_VALUE;
        if (position != null && NavigatorManeuverAnchor.fresh(position.elapsedRealtimeMs, now))
            deadline = Math.min(deadline,
                    position.elapsedRealtimeMs + NavigatorManeuverAnchor.MAX_AGE_MS + 1);
        if (matched != null && NavigatorManeuverAnchor.fresh(matched.elapsedMs, now))
            deadline = Math.min(deadline, matched.elapsedMs + NavigatorManeuverAnchor.MAX_AGE_MS + 1);
        if (deadline != Long.MAX_VALUE) worker.postDelayed(expire, Math.max(1, deadline - now));
    }

    private void record(HudSpatialGeometry.Result next, VehiclePosition position,
            DirectTbtFrame.SourceDistance distance, long now) {
        boolean positionFresh = position != null
                && NavigatorManeuverAnchor.fresh(position.elapsedRealtimeMs, now);
        boolean bearingAvailable = positionFresh && position.bearingDegrees != null;
        if (next == null) return;
        NavigatorRouteData.Header header = next.header;
        String source = header == null ? "none" : header.navigator;
        long epoch = header == null ? -1 : header.producerEpoch;
        long revision = header == null ? -1 : header.routeRevision;
        String key = next.mode + ":" + source + ':' + epoch + ':'
                + revision + ':' + next.stepSerial + ':' + next.origin + ':' + next.reason
                + ':' + next.headingOrigin
                + ':' + positionFresh + ':' + bearingAvailable;
        if (key.equals(loggedKey)) return;
        loggedKey = key;
        NavigatorRouteLog.event(context, "geometry prepared mode=" + next.mode
                + " source=" + source + " epoch=" + epoch + " revision=" + revision
                + " step=" + next.stepSerial + " origin=" + next.origin
                + " heading_origin=" + next.headingOrigin
                + " reason=" + next.reason + " fix_age_ms=" + (position == null ? -1 : now - position.elapsedRealtimeMs)
                + " bearing_available=" + bearingAvailable);
        if (!HudPrefs.isDetailedDebugArtifactsEnabled(context)) return;
        try {
            JSONObject details = new JSONObject().put("source", source)
                    .put("epoch", epoch).put("revision", revision)
                    .put("mode", next.mode.name()).put("step", next.stepSerial)
                    .put("origin", next.origin == null ? JSONObject.NULL : next.origin.name())
                    .put("headingOrigin", next.headingOrigin == null ? JSONObject.NULL : next.headingOrigin.name())
                    .put("reason", next.reason).put("elapsedMs", now)
                    .put("latitude", next.anchorLatitude == null ? JSONObject.NULL : next.anchorLatitude)
                    .put("longitude", next.anchorLongitude == null ? JSONObject.NULL : next.anchorLongitude)
                    .put("distanceMeters", distance == null ? JSONObject.NULL : distance.meters);
            if (position != null) {
                details.put("vehicleLatitude", position.latitude).put("vehicleLongitude", position.longitude)
                        .put("fixElapsedMs", position.elapsedRealtimeMs).put("mock", position.mock)
                        .put("bearingDegrees", position.bearingDegrees == null ? JSONObject.NULL : position.bearingDegrees);
            }
            AppEventLogger.event(context, "navigator_route_anchor " + details);
        } catch (org.json.JSONException invalid) {
            NavigatorRouteLog.error(context, source, epoch, revision, "geometry_record");
        }
    }
}
