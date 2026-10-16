package com.bydhud.navexport;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Separate from legacy TBT: old HUD versions have no receiver for this action. */
public final class RouteExport extends BroadcastReceiver {
    public static final String ACTION = "com.bydhud.app.action.NAV_ROUTE_GEOMETRY_V1";
    public static final String REQUEST = "com.bydhud.navexport.REQUEST_ROUTE_GEOMETRY_V1";
    private static final String HUD = "com.bydhud.app";
    private static final String TAG = "BYD_ROUTE_EXPORT";
    public static final int CHUNK_BYTES = 24 * 1024;
    private final long producerEpoch = SystemClock.elapsedRealtimeNanos();
    private final Context context;
    private final String navigator;
    private final Handler worker;
    private final PendingIntent identity;
    private final Runnable drain = this::drain;
    private long revision;
    private long sourceElapsedMs;
    private String routeId = "";
    private Source source;
    private String clearReason = "idle";
    private byte[] encoded;
    private int pointCount;
    private int maneuverCount;
    private boolean maneuversAvailable;
    private long sentRevision = -1;
    private long failedRevision = -1;
    private long replaySerial;
    private Bundle step;
    private String stepKey = "";
    private long stepSerial;
    private long sentStepSerial = -1;

    public interface Source { RouteData read() throws Exception; }

    public RouteExport(Context context, String navigator) {
        this.context = context.getApplicationContext();
        this.navigator = navigator;
        identity = PendingIntent.getBroadcast(this.context, 81,
                new Intent("com.bydhud.navexport.IDENTITY")
                        .setPackage(this.context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        HandlerThread thread = new HandlerThread("byd-route-export",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter(REQUEST);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                this.context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
            } else {
                this.context.registerReceiver(this, filter);
            }
        } catch (RuntimeException error) {
            thread.quitSafely();
            throw error;
        }
    }

    public synchronized void route(String id, Source value) {
        revision++;
        routeId = id;
        sourceElapsedMs = SystemClock.elapsedRealtime();
        source = value;
        encoded = null;
        step = null;
        stepKey = "";
        stepSerial++;
        schedule();
    }

    public synchronized void clear(String reason) {
        if (source == null && clearReason.equals(reason)) return;
        clearReason = reason;
        route("", null);
    }

    public synchronized void step(String key, Bundle value) {
        if (source == null || key.equals(stepKey)) return;
        stepKey = key;
        step = new Bundle(value);
        step.putLong("stepElapsedMs", SystemClock.elapsedRealtime());
        stepSerial++;
        schedule();
    }

    private void schedule() {
        worker.removeCallbacks(drain);
        worker.post(drain);
    }

    @Override public void onReceive(Context ignored, Intent intent) {
        try {
            if (intent == null || !REQUEST.equals(intent.getAction())
                    || intent.getIntExtra("schemaVersion", -1) != 1) return;
            PendingIntent token = intent.getParcelableExtra("bydhudIdentity");
            if (token == null || !HUD.equals(token.getCreatorPackage())) return;
            synchronized (this) {
                replaySerial++;
                sentRevision = -1;
                failedRevision = -1;
                sentStepSerial = -1;
                schedule();
            }
            Log.i(TAG, navigator + "|replay_requested");
        } catch (RuntimeException e) {
            failure("request", e);
        }
    }

    private void drain() {
        final long token;
        final long replay;
        final Source input;
        byte[] payload;
        synchronized (this) {
            token = revision;
            replay = replaySerial;
            if (failedRevision == token) return;
            input = source;
            payload = encoded;
        }
        try {
            if (input != null && payload == null) {
                RouteData data = input.read();
                payload = data.encode();
                synchronized (this) {
                    if (revision != token) return;
                    encoded = payload;
                    pointCount = data.points.length / 2;
                    maneuverCount = data.maneuvers.length;
                    maneuversAvailable = data.maneuversAvailable;
                }
                Log.i(TAG, navigator + "|captured|revision=" + token
                        + "|points=" + pointCount + "|maneuvers=" + maneuverCount
                        + "|maneuversAvailable=" + maneuversAvailable
                        + "|bytes=" + payload.length);
            }
            final boolean needsRoute;
            synchronized (this) {
                if (revision != token) return;
                needsRoute = sentRevision != token;
            }
            if (needsRoute) {
                int parts = payload == null ? 1 : (payload.length - 1) / CHUNK_BYTES + 1;
                for (int i = 0; i < parts; i++) {
                    Bundle event;
                    synchronized (this) {
                        if (revision != token) return;
                        event = envelope(token, payload == null ? "clear" : "route_chunk");
                        if (payload == null) {
                            event.putString("reason", clearReason);
                        } else {
                            event.putInt("chunkIndex", i);
                            event.putInt("chunkCount", parts);
                            event.putInt("encodedBytes", payload.length);
                            event.putInt("pointCount", pointCount);
                            event.putBoolean("geometryAvailable", pointCount > 0);
                            event.putInt("maneuverCount", maneuverCount);
                            event.putBoolean("maneuversAvailable", maneuversAvailable);
                            event.putByteArray("payload", Arrays.copyOfRange(payload,
                                    i * CHUNK_BYTES, Math.min(payload.length, (i + 1) * CHUNK_BYTES)));
                        }
                    }
                    dispatch(event);
                }
                synchronized (this) {
                    if (revision != token) return;
                    if (replaySerial == replay) sentRevision = token;
                }
                Log.i(TAG, navigator + "|dispatched|revision=" + token + "|parts=" + parts
                        + (payload == null ? "|clear" : "|receiverAck=unknown"));
            }
            Bundle event;
            long serial;
            synchronized (this) {
                if (revision != token || step == null || sentStepSerial == stepSerial) return;
                serial = stepSerial;
                event = envelope(token, "current_step");
                event.putLong("stepSerial", serial);
                event.putBundle("step", new Bundle(step));
            }
            dispatch(event);
            synchronized (this) {
                if (revision == token && replaySerial == replay) sentStepSerial = serial;
            }
            Bundle detail = event.getBundle("step");
            Log.i(TAG, navigator + "|step_dispatched|revision=" + token + "|serial=" + serial
                    + "|ordinal=" + detail.getInt("ordinal", -1)
                    + "|currentSegment=" + detail.getInt("currentSegmentIndex", -1)
                    + "|nextSegment=" + detail.getInt("nextSegmentIndex", -1));
        } catch (OutOfMemoryError exhausted) {
            synchronized (this) {
                if (revision != token) return;
                encoded = null;
                if (replaySerial == replay) failedRevision = token;
            }
            Log.w(TAG, navigator + "|failed|stage=export|reason=memory|revision=" + token);
        } catch (Exception e) {
            synchronized (this) {
                if (revision != token) return;
                // Do not spin on a malformed source; a new revision or explicit replay retries it.
                if (replaySerial == replay) {
                    sentRevision = token;
                    failedRevision = token;
                }
            }
            failure("export|revision=" + token, e);
            try {
                Bundle error;
                synchronized (this) {
                    if (revision != token) return;
                    error = envelope(token, "unavailable");
                    error.putString("reason", e.getClass().getSimpleName());
                }
                dispatch(error);
            } catch (RuntimeException ignored) { }
        }
    }

    private Bundle envelope(long token, String event) {
        Bundle data = new Bundle();
        data.putInt("schemaVersion", 1);
        data.putString("navigator", navigator);
        data.putLong("producerEpoch", producerEpoch);
        data.putLong("routeRevision", token);
        data.putString("routeId", routeId);
        data.putLong("sourceElapsedMs", sourceElapsedMs);
        data.putString("event", event);
        data.putString("encoding", "gzip-byr1");
        data.putString("coordinateFrame", "navigator_source_degrees");
        data.putParcelable("producerIdentity", identity);
        return data;
    }

    private void dispatch(Bundle data) {
        context.sendBroadcast(new Intent(ACTION).setPackage(HUD).putExtras(data));
    }

    private void failure(String stage, Exception e) {
        Log.w(TAG, navigator + "|failed|stage=" + stage + "|type=" + e.getClass().getSimpleName());
    }

    public static Object field(Object value, String name) throws Exception {
        if (value == null) throw new IllegalStateException("missing " + name);
        Field field = value.getClass().getField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    public static Object call(Object value, String name) throws Exception {
        if (value == null) throw new IllegalStateException("missing " + name);
        Method method = value.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }
}
