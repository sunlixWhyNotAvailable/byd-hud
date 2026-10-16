package com.bydhud.app;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import org.json.JSONObject;

/** Latest-only preparation for the future spatial HUD consumer; never writes vehicle packets. */
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
    private NavigatorManeuverAnchor resolver;
    private volatile NavigatorManeuverAnchor.Result result;
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

    static NavigatorManeuverAnchor.Result snapshot() {
        VehicleRouteRuntime state = current;
        return state == null ? null : state.result;
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
        synchronized (lock) {
            if (stopped) return;
            generation++;
            result = null;
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
        }
        worker.removeCallbacks(expire);
        String owner = NavCapturePrefs.getHudPackage(context);
        NavigatorRouteStore.CurrentSnapshot snapshot = NavigatorRouteReceiver.snapshot(owner);
        boolean eligible = HudPrefs.isVehiclePositionEnabled(context)
                && NavCapturePrefs.isHudEnabled(context, owner)
                && snapshot != null && snapshot.header != null
                && (snapshot.route != null
                || snapshot.outcome == NavigatorRouteStore.Outcome.COLLECTING
                || snapshot.outcome == NavigatorRouteStore.Outcome.READY_TO_DECODE
                || snapshot.outcome == NavigatorRouteStore.Outcome.REPLAY_REQUIRED);
        HudRuntimeService.setVehiclePositionRouteSource(eligible ? owner : null);
        if (!eligible) {
            synchronized (lock) {
                if (stopped || token != generation) return;
                result = null;
                distance = null;
                distanceHeader = null;
                route = null;
                resolver = null;
                loggedKey = "";
            }
            return;
        }
        if (snapshot.route == null) {
            synchronized (lock) {
                if (stopped || token != generation) return;
                result = null;
                route = null;
                resolver = null;
            }
            return;
        }
        if (route != snapshot.route) {
            route = snapshot.route;
            resolver = new NavigatorManeuverAnchor(route);
        }
        VehiclePositionController.Snapshot location = HudRuntimeService.getVehiclePositionSnapshot();
        VehiclePosition position = location != null && owner.equals(location.routeSource)
                ? location.position : null;
        DirectTbtFrame.SourceDistance matched = owner.equals(frameOwner)
                && route.header().sameSnapshot(frameHeader) ? sample : null;
        long now = SystemClock.elapsedRealtime();
        NavigatorManeuverAnchor.Result next = resolver.resolve(snapshot.step, position, matched, now);
        synchronized (lock) {
            if (stopped || token != generation) return;
            result = next;
        }
        record(next, position, matched, now);
        long deadline = Long.MAX_VALUE;
        if (position != null && NavigatorManeuverAnchor.fresh(position.elapsedRealtimeMs, now))
            deadline = position.elapsedRealtimeMs + NavigatorManeuverAnchor.MAX_AGE_MS + 1;
        if (matched != null && NavigatorManeuverAnchor.fresh(matched.elapsedMs, now))
            deadline = Math.min(deadline, matched.elapsedMs + NavigatorManeuverAnchor.MAX_AGE_MS + 1);
        if (deadline != Long.MAX_VALUE) worker.postDelayed(expire, Math.max(1, deadline - now));
    }

    private void record(NavigatorManeuverAnchor.Result next, VehiclePosition position,
            DirectTbtFrame.SourceDistance distance, long now) {
        boolean positionFresh = position != null
                && NavigatorManeuverAnchor.fresh(position.elapsedRealtimeMs, now);
        boolean bearingAvailable = positionFresh && position.bearingDegrees != null;
        String key = next.header.navigator + ':' + next.header.producerEpoch + ':'
                + next.header.routeRevision + ':' + next.stepSerial + ':' + next.origin + ':' + next.reason
                + ':' + positionFresh + ':' + bearingAvailable;
        if (key.equals(loggedKey)) return;
        loggedKey = key;
        NavigatorRouteLog.event(context, "anchor source=" + next.header.navigator
                + " epoch=" + next.header.producerEpoch + " revision=" + next.header.routeRevision
                + " step=" + next.stepSerial + " origin=" + next.origin.name().toLowerCase(java.util.Locale.ROOT)
                + " reason=" + next.reason + " fix_age_ms=" + (position == null ? -1 : now - position.elapsedRealtimeMs)
                + " bearing_available=" + bearingAvailable);
        if (!HudPrefs.isDetailedDebugArtifactsEnabled(context)) return;
        try {
            JSONObject details = new JSONObject().put("source", next.header.navigator)
                    .put("epoch", next.header.producerEpoch).put("revision", next.header.routeRevision)
                    .put("step", next.stepSerial).put("origin", next.origin.name())
                    .put("reason", next.reason).put("elapsedMs", now)
                    .put("latitude", next.latitude == null ? JSONObject.NULL : next.latitude)
                    .put("longitude", next.longitude == null ? JSONObject.NULL : next.longitude)
                    .put("distanceMeters", distance == null ? JSONObject.NULL : distance.meters);
            if (position != null) {
                details.put("vehicleLatitude", position.latitude).put("vehicleLongitude", position.longitude)
                        .put("fixElapsedMs", position.elapsedRealtimeMs).put("mock", position.mock)
                        .put("bearingDegrees", position.bearingDegrees == null ? JSONObject.NULL : position.bearingDegrees);
            }
            AppEventLogger.event(context, "navigator_route_anchor " + details);
        } catch (org.json.JSONException invalid) {
            NavigatorRouteLog.error(context, next.header.navigator, next.header.producerEpoch,
                    next.header.routeRevision, "anchor_record");
        }
    }
}
