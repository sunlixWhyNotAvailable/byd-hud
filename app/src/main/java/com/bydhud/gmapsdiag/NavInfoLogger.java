package com.bydhud.gmapsdiag;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Production GMaps navigation bridge. The class name is fixed by the guarded hooks. */
public final class NavInfoLogger {
    public static final String ACTION_UNREGISTER = "com.bydhud.gmapsbridge.UNREGISTER";
    public static final String EXTRA_CLIENT = "com.bydhud.gmapsbridge.CLIENT";
    public static final String EXTRA_PROTOCOL_VERSION =
            "com.bydhud.gmapsbridge.PROTOCOL_VERSION";
    public static final String EXTRA_IDENTITY = "com.bydhud.gmapsbridge.IDENTITY";
    public static final String EXTRA_CHANNEL_ID = "com.bydhud.gmapsbridge.CHANNEL_ID";
    public static final int PROTOCOL_VERSION = 3;
    public static final int MESSAGE_HELLO = 1;
    public static final int MESSAGE_START = 2;
    public static final int MESSAGE_FRAME = 3;
    public static final int MESSAGE_STOP = 4;
    public static final int MESSAGE_MANEUVER_BITMAP = 5;
    public static final int MESSAGE_SPEED_LIMIT = 6;
    public static final int MESSAGE_HEARTBEAT = 7;
    public static final int CAP_STATE_REPLAY = 1;
    public static final int CAP_HEARTBEAT = 1 << 1;
    public static final int CAP_BITMAP_GENERATION = 1 << 2;
    public static final int CAP_ROUTE_GENERATION = 1 << 3;

    private static final String TAG = "BYD_GMAPS_BRIDGE";
    private static final String CLIENT_PACKAGE = "com.bydhud.app";
    private static final int MAX_FRAME_BYTES = 512 * 1024;
    private static final int MAX_BITMAP_DIMENSION_PX = 256;
    private static final Object FRAME_SIGNAL = new Object();
    private static final Object CLIENT_LOCK = new Object();
    private static final Object BITMAP_LOCK = new Object();
    private static final Object SPEED_LOCK = new Object();
    private static final Object STATE_LOCK = new Object();
    private static final AtomicLong NEXT_SEQUENCE = new AtomicLong();
    private static final long PROCESS_EPOCH = SystemClock.elapsedRealtimeNanos();
    private static final AtomicLong STATE_EPOCH = new AtomicLong();
    private static final AtomicLong NEXT_RENDER_GENERATION = new AtomicLong();
    private static final AtomicReference<Event> LATEST_FRAME = new AtomicReference<>();
    private static final AtomicReference<Event> LAST_FRAME_FOR_REPLAY =
            new AtomicReference<>();
    private static final AtomicReference<Event> PENDING_FRAME_FOR_REPLAY =
            new AtomicReference<>();
    private static final ThreadLocal<ModelToken> FRAME_MODEL = new ThreadLocal<>();

    private static volatile Messenger client;
    private static volatile IBinder clientBinder;
    private static volatile IBinder.DeathRecipient clientDeathRecipient;
    private static volatile String clientChannelId = "";
    private static String lastManeuverName = "";
    private static String lastManeuverViewId = "";
    private static byte[] lastManeuverPng;
    private static int lastManeuverWidth;
    private static int lastManeuverHeight;
    private static long lastManeuverSourceElapsedMs;
    private static long lastManeuverRenderGeneration;
    private static long lastManeuverSourceSequence = -1L;
    private static volatile Context applicationContext;
    private static volatile boolean routeActive;
    private static final Map<ImageView, PendingCapture> PENDING_CAPTURES =
            new WeakHashMap<>();
    private static Object lastSpeedStep;
    private static int lastSpeedProgress = -1;
    private static int lastSpeedLimit = Integer.MIN_VALUE;
    private static int lastSpeedLimitKph = Integer.MIN_VALUE;
    private static String lastSpeedUnit = "";
    private static boolean speedReflectionErrorLogged;

    static {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Event event;
                        synchronized (FRAME_SIGNAL) {
                            while ((event = LATEST_FRAME.getAndSet(null)) == null) {
                                FRAME_SIGNAL.wait();
                            }
                        }
                        write(event);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable error) {
                        Log.e(TAG, "FRAME_ERROR|type=" + error.getClass().getName()
                                + "|message=" + clean(error.getMessage()));
                    }
                }
            }
        }, "byd-gmaps-bridge");
        worker.setDaemon(true);
        worker.start();
        Thread heartbeat = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(2000L);
                        if (!noClient()) {
                            RouteSnapshot snapshot = routeSnapshot();
                            Bundle data = baseBundle(snapshot.epoch, snapshot.active);
                            data.putInt("capabilities", capabilities());
                            send(MESSAGE_HEARTBEAT, data);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable error) {
                        Log.w(TAG, "HEARTBEAT_FAILED|type="
                                + error.getClass().getSimpleName());
                    }
                }
            }
        }, "byd-gmaps-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
        Log.i(TAG, "BRIDGE_READY|protocol=" + PROTOCOL_VERSION);
    }

    private NavInfoLogger() {
    }

    /** Called at the exported stock receiver entry before it starts the requested channel. */
    public static void registerClient(Context context, Intent intent) {
        if (context == null || intent == null) {
            Log.w(TAG, "REGISTER_IGNORED|reason=null_context_or_intent");
            return;
        }
        applicationContext = context.getApplicationContext();
        try {
            String action = intent.getAction();
            boolean unregister = ACTION_UNREGISTER.equals(action);
            boolean startChannel = "ACTION_START_CHANNEL".equals(action);
            if (!unregister && !startChannel) return;
            boolean hasClient = intent.hasExtra(EXTRA_CLIENT);
            Log.i(TAG, "REGISTER_RECEIVED|action=" + action
                    + "|clientExtra=" + hasClient
                    + "|identityExtra=" + intent.hasExtra(EXTRA_IDENTITY)
                    + "|protocol=" + intent.getIntExtra(EXTRA_PROTOCOL_VERSION, -1));
            if (startChannel && !hasClient) {
                Log.w(TAG, "CLIENT_REJECTED|reason=missing_messenger_extra");
                return;
            }
            PendingIntent identity = intent.getParcelableExtra(EXTRA_IDENTITY);
            if (!isTrustedSender(identity)) {
                Log.w(TAG, "CLIENT_REJECTED|reason=untrusted_sender");
                return;
            }
            if (unregister) {
                clearClient("explicit_unregister", null);
                return;
            }
            int protocol = intent.getIntExtra(EXTRA_PROTOCOL_VERSION, -1);
            Messenger candidate = intent.getParcelableExtra(EXTRA_CLIENT);
            String channelId = intent.getStringExtra(EXTRA_CHANNEL_ID);
            if (protocol != PROTOCOL_VERSION || candidate == null) {
                Log.w(TAG, "CLIENT_REJECTED|reason=protocol_or_messenger|protocol=" + protocol);
                return;
            }
            installClient(candidate, channelId == null ? "" : channelId.trim());
        } catch (RuntimeException error) {
            Log.w(TAG, "CLIENT_REJECTED|reason=malformed_extras|type="
                    + error.getClass().getSimpleName());
        }
    }

    /** The producer's existing IF_NEZ branch skips serialization while no client is alive. */
    public static boolean noClient() {
        IBinder binder = clientBinder;
        return client == null || binder == null || !binder.isBinderAlive();
    }

    public static void log(Object message) {
        Event replaced = null;
        long eventSequence = -1L;
        synchronized (STATE_LOCK) {
            if (!routeActive) return;
            ModelToken model = FRAME_MODEL.get();
            FRAME_MODEL.remove();
            Event event = new Event(
                    NEXT_SEQUENCE.incrementAndGet(), SystemClock.elapsedRealtime(),
                    STATE_EPOCH.get(), message, model);
            eventSequence = event.sequence;
            // Keep the latest event for a client which registers after the producer
            // emitted it. Replay is published by the single worker only after its
            // optional model bitmap and ordered FRAME are ready.
            PENDING_FRAME_FOR_REPLAY.set(event);
            if (noClient()) return;
            synchronized (FRAME_SIGNAL) {
                replaced = LATEST_FRAME.getAndSet(event);
                FRAME_SIGNAL.notifyAll();
            }
        }
        if (replaced != null) {
            Log.w(TAG, "FRAME_REPLACED|oldSeq=" + replaced.sequence
                    + "|newSeq=" + eventSequence);
        }
    }

    public static void sessionStart(Object session) {
        final long epoch;
        synchronized (STATE_LOCK) {
            resetTransientState();
            routeActive = true;
            epoch = STATE_EPOCH.get();
        }
        sendControl(MESSAGE_START, "start", className(session), epoch, true);
    }

    /** Clears the model token at the 26.30 producer-frame boundary. */
    public static void beginFrameCaptureV26() {
        FRAME_MODEL.remove();
    }

    /** Captures the background-live 26.30 step model before payload production. */
    public static void captureManeuverModelV26(Object value) {
        if (value == null) return;
        RouteSnapshot snapshot = routeSnapshot();
        if (!snapshot.active) return;
        try {
            // Lbqmu.b[0] -> bqmn.c -> bqmn.b[index] -> bqmq.i (Lbqmm).
            Object root = requiredField(value, "b");
            Object bqmn = elementAt(root, 0);
            int index = intField(bqmn, "c");
            Object bqmq = elementAt(requiredField(bqmn, "b"), index);
            Object model = requiredField(bqmq, "i");
            String maneuver = enumName(readField(model, "a"));
            if (model == null || maneuver.isEmpty() || "UNKNOWN".equals(maneuver)) return;
            FRAME_MODEL.set(new ModelToken(model, maneuver, snapshot.epoch,
                    NEXT_RENDER_GENERATION.incrementAndGet()));
        } catch (Throwable error) {
            FRAME_MODEL.remove();
            Log.w(TAG, "MANEUVER_MODEL_FAILED|profile=26.30|type="
                    + error.getClass().getSimpleName()
                    + "|message=" + clean(error.getMessage()));
        }
    }

    public static void sessionOutputChanged(boolean enabled) {
        Log.i(TAG, "OUTPUT_CHANGED|elapsedMs=" + SystemClock.elapsedRealtime()
                + "|enabled=" + enabled);
    }

    public static void sessionStop() {
        final long eventEpoch;
        synchronized (STATE_LOCK) {
            routeActive = false;
            resetTransientState();
            eventEpoch = STATE_EPOCH.get();
        }
        sendControl(MESSAGE_STOP, "stop", "", eventEpoch, false);
    }

    /** Captures each changed maneuver after GMaps has rendered its turn-card ImageView. */
    public static void captureManeuverView(Object viewValue, Object maneuverValue) {
        if (!(viewValue instanceof ImageView) || maneuverValue == null) return;
        final ImageView view = (ImageView) viewValue;
        final String maneuver = enumName(readField(maneuverValue, "a"));
        captureManeuverViewValue(view, maneuver);
    }

    /** Captures the 26.30 post-body Optional<Lbqmm> value via Lbqmm.a.name(). */
    public static void captureManeuverViewV26(Object viewValue, Object optionalValue) {
        if (!(viewValue instanceof ImageView) || optionalValue == null) return;
        Object maneuverValue = readField(optionalValue, "a");
        if (maneuverValue == null) return;
        final ImageView view = (ImageView) viewValue;
        final String maneuver = enumName(readField(maneuverValue, "a"));
        captureManeuverViewValue(view, maneuver);
    }

    private static void captureManeuverViewValue(final ImageView view, String maneuver) {
        final RouteSnapshot snapshot = routeSnapshot();
        final long epoch = snapshot.epoch;
        if (!snapshot.active) return;
        if (maneuver.isEmpty() || "UNKNOWN".equals(maneuver)) return;
        final PendingCapture pending;
        synchronized (BITMAP_LOCK) {
            pending = PENDING_CAPTURES.get(view);
            if (pending != null) {
                pending.maneuver = maneuver;
                pending.epoch = epoch;
                pending.renderGeneration = NEXT_RENDER_GENERATION.incrementAndGet();
                return;
            }
            PendingCapture created = new PendingCapture(
                    maneuver, epoch, NEXT_RENDER_GENERATION.incrementAndGet());
            PENDING_CAPTURES.put(view, created);
            view.post(new Runnable() {
                @Override
                public void run() {
                    capturePendingManeuver(view, created);
                }
            });
        }
    }

    /** Mirrors GMaps' route-segment speed-limit selection without depending on its UI. */
    public static void captureSpeedLimitState(Object state) {
        if (state == null) return;
        RouteSnapshot snapshot = routeSnapshot();
        synchronized (SPEED_LOCK) {
            if (!snapshot.active || snapshot.epoch != STATE_EPOCH.get()) return;
            try {
                Object route = requiredField(state, "b");
                if (!booleanField(state, "o") || !invokeBoolean(route, "ay")) {
                    clearSpeedLimitLocked(snapshot);
                    return;
                }
                Object step = requiredField(state, "c");
                Object speedStep = step == null ? null : requiredField(step, "T");
                if (speedStep == null) {
                    clearSpeedLimitLocked(snapshot);
                    return;
                }
                int progress = intField(step, "l") - intField(state, "g");
                if (lastSpeedStep == null || !lastSpeedStep.equals(speedStep)) {
                    lastSpeedStep = speedStep;
                    lastSpeedProgress = -1;
                }
                Object changesValue = requiredField(speedStep, "M");
                if (!(changesValue instanceof List)) {
                    throw new IllegalStateException("speed changes are not a list");
                }
                @SuppressWarnings("unchecked")
                List<Object> changes = (List<Object>) changesValue;
                Object countriesValue = requiredField(route, "W");
                List<?> countries = countriesValue instanceof List
                        ? (List<?>) countriesValue : java.util.Collections.emptyList();
                for (Object change : changes) {
                    int position = intField(change, "b");
                    if (position <= lastSpeedProgress || position > progress) continue;
                    int kph = intField(change, "c");
                    String unit = speedUnit(intField(change, "d"), countries);
                    if (kph < 0 || unit.isEmpty()) {
                        publishSpeedLimitLocked(0, 0, "", snapshot);
                    } else {
                        int display = "mph".equals(unit)
                                ? Math.round(kph * 0.621371f) : kph;
                        publishSpeedLimitLocked(display, kph, unit, snapshot);
                    }
                }
                lastSpeedProgress = progress;
            } catch (Throwable error) {
                clearSpeedLimitLocked(snapshot);
                if (!speedReflectionErrorLogged) {
                    speedReflectionErrorLogged = true;
                    Log.w(TAG, "SPEED_LIMIT_FAILED|type="
                            + error.getClass().getSimpleName()
                            + "|message=" + clean(error.getMessage()));
                }
            }
        }
    }

    /** Maps 26.30 successor of captureSpeedLimitState. */
    public static void captureSpeedLimitStateV26(Object aggregateState) {
        if (aggregateState == null) return;
        RouteSnapshot snapshot = routeSnapshot();
        synchronized (SPEED_LOCK) {
            if (!snapshot.active || snapshot.epoch != STATE_EPOCH.get()) return;
            try {
                Object state = invokeObject(aggregateState, "d");
                Object route = requiredField(state, "b");
                if (!booleanField(state, "p") || !invokeBoolean(route, "ay")) {
                    clearSpeedLimitLocked(snapshot);
                    return;
                }
                Object step = requiredField(state, "c");
                Object speedStep = step == null ? null : requiredField(step, "U");
                if (speedStep == null) {
                    clearSpeedLimitLocked(snapshot);
                    return;
                }
                int progress = intField(step, "l") - intField(state, "h");
                if (lastSpeedStep == null || !lastSpeedStep.equals(speedStep)) {
                    lastSpeedStep = speedStep;
                    lastSpeedProgress = -1;
                }
                Object changesValue = requiredField(speedStep, "N");
                if (!(changesValue instanceof List)) {
                    throw new IllegalStateException("speed changes are not a list");
                }
                @SuppressWarnings("unchecked")
                List<Object> changes = (List<Object>) changesValue;
                Object countriesValue = requiredField(route, "Z");
                List<?> countries = countriesValue instanceof List
                        ? (List<?>) countriesValue : java.util.Collections.emptyList();
                for (Object change : changes) {
                    int position = intField(change, "b");
                    if (position <= lastSpeedProgress || position > progress) continue;
                    int kph = intField(change, "c");
                    String unit = speedUnitV26(intField(change, "d"), countries);
                    if (kph < 0 || unit.isEmpty()) {
                        publishSpeedLimitLocked(0, 0, "", snapshot);
                    } else {
                        int display = "mph".equals(unit)
                                ? Math.round(kph * 0.621371f) : kph;
                        publishSpeedLimitLocked(display, kph, unit, snapshot);
                    }
                }
                lastSpeedProgress = progress;
            } catch (Throwable error) {
                clearSpeedLimitLocked(snapshot);
                if (!speedReflectionErrorLogged) {
                    speedReflectionErrorLogged = true;
                    Log.w(TAG, "SPEED_LIMIT_FAILED|profile=26.30|type="
                            + error.getClass().getSimpleName()
                            + "|message=" + clean(error.getMessage()));
                }
            }
        }
    }

    private static void installClient(Messenger candidate, String channelId) {
        final IBinder binder = candidate.getBinder();
        final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                clearClient("binder_died", binder);
            }
        };
        synchronized (CLIENT_LOCK) {
            unlinkCurrentClientLocked();
            try {
                binder.linkToDeath(deathRecipient, 0);
            } catch (Throwable error) {
                Log.w(TAG, "CLIENT_REJECTED|reason=dead_binder");
                return;
            }
            // Keep the global client unpublished until HELLO and the matching
            // replay have been sent under STATE_LOCK. This prevents a concurrent
            // log worker from delivering FRAME before the receiver sees HELLO.
            client = null;
            clientBinder = null;
            clientDeathRecipient = null;
            clientChannelId = channelId;
        }
        boolean delivered = true;
        boolean published = false;
        synchronized (STATE_LOCK) {
            // HELLO and its replay must share one route snapshot. A STOP/START
            // between separate calls could otherwise pair an old HELLO with a
            // newer replay frame and resurrect stale state at the receiver.
            RouteSnapshot snapshot = new RouteSnapshot(STATE_EPOCH.get(), routeActive);
            Bundle data = baseBundle(snapshot.epoch, snapshot.active);
            data.putString("bridge", "gmaps-navinfo");
            data.putInt("capabilities", capabilities());
            data.putBoolean("routeActive", snapshot.active);
            delivered = sendTo(candidate, MESSAGE_HELLO, data);
            if (delivered) delivered = replayCurrentStateLocked(snapshot, candidate);
            if (delivered) {
                // Publish before releasing STATE_LOCK so STOP/START cannot land
                // between replay and the first post-registration control event.
                synchronized (CLIENT_LOCK) {
                    if (binder.isBinderAlive()) {
                        client = candidate;
                        clientBinder = binder;
                        clientDeathRecipient = deathRecipient;
                        published = true;
                        Event pending = PENDING_FRAME_FOR_REPLAY.get();
                        if (pending != null && pending.epoch == snapshot.epoch) {
                            synchronized (FRAME_SIGNAL) {
                                LATEST_FRAME.set(pending);
                                FRAME_SIGNAL.notifyAll();
                            }
                        }
                    }
                }
            }
        }
        if (!delivered || !published) {
            try {
                binder.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
            }
            synchronized (CLIENT_LOCK) {
                if (clientBinder == binder) {
                    unlinkCurrentClientLocked();
                    client = null;
                    clientBinder = null;
                    clientDeathRecipient = null;
                }
                clientChannelId = "";
            }
            Log.w(TAG, "CLIENT_REJECTED|reason=hello_or_replay_failed");
            return;
        }
        Log.i(TAG, "CLIENT_CONNECTED|protocol=" + PROTOCOL_VERSION);
    }

    private static boolean isTrustedSender(PendingIntent identity) {
        return identity != null && CLIENT_PACKAGE.equals(identity.getCreatorPackage());
    }

    private static void capturePendingManeuver(ImageView view, PendingCapture pending) {
        final String maneuver;
        final long epoch;
        final long renderGeneration;
        synchronized (BITMAP_LOCK) {
            maneuver = pending.maneuver;
            epoch = pending.epoch;
            renderGeneration = pending.renderGeneration;
        }
        captureRenderedManeuver(view, maneuver, epoch, renderGeneration);
        synchronized (BITMAP_LOCK) {
            PendingCapture current = PENDING_CAPTURES.get(view);
            if (current != pending) return;
            if (current.renderGeneration != renderGeneration) {
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        capturePendingManeuver(view, pending);
                    }
                });
            } else {
                PENDING_CAPTURES.remove(view);
            }
        }
    }

    private static void captureRenderedManeuver(
            ImageView view, String maneuver, long epoch, long renderGeneration) {
        if (epoch != STATE_EPOCH.get() || !routeActive) return;
        Bitmap bitmap = null;
        Drawable drawable = null;
        Rect oldBounds = null;
        try {
            drawable = view.getDrawable();
            if (drawable == null) throw new IllegalStateException("drawable_missing");
            int sourceWidth = drawable.getIntrinsicWidth() > 0
                    ? drawable.getIntrinsicWidth() : Math.max(1, view.getWidth());
            int sourceHeight = drawable.getIntrinsicHeight() > 0
                    ? drawable.getIntrinsicHeight() : Math.max(1, view.getHeight());
            float scale = Math.min(1f, MAX_BITMAP_DIMENSION_PX
                    / (float) Math.max(sourceWidth, sourceHeight));
            int width = Math.max(1, Math.round(sourceWidth * scale));
            int height = Math.max(1, Math.round(sourceHeight * scale));
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            oldBounds = new Rect(drawable.getBounds());
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("png_encode_failed");
            }
            byte[] png = output.toByteArray();
            if (png.length == 0 || png.length > MAX_FRAME_BYTES) {
                throw new IllegalStateException("png_size=" + png.length);
            }
            String viewId = resourceName(view);
            rememberBitmap(maneuver, viewId, png, width, height,
                    SystemClock.elapsedRealtime(), renderGeneration, epoch, -1L);
            boolean delivered;
            synchronized (STATE_LOCK) {
                if (epoch != STATE_EPOCH.get() || !routeActive) return;
                Bundle data = baseBundle(epoch, true);
                data.putString("maneuver", maneuver);
                data.putString("viewId", viewId);
                data.putInt("width", width);
                data.putInt("height", height);
                data.putLong("renderGeneration", renderGeneration);
                data.putLong("sourceSequence", -1L);
                data.putByteArray("png", png);
                delivered = sendTo(client, MESSAGE_MANEUVER_BITMAP, data);
            }
            Log.i(TAG, "MANEUVER_BITMAP|maneuver=" + maneuver
                    + "|renderGeneration=" + renderGeneration
                    + "|width=" + width + "|height=" + height
                    + "|bytes=" + png.length + "|delivered=" + delivered);
        } catch (Throwable error) {
            Log.w(TAG, "MANEUVER_BITMAP_FAILED|maneuver=" + maneuver
                    + "|type=" + error.getClass().getSimpleName()
                    + "|message=" + clean(error.getMessage()));
        } finally {
            if (drawable != null && oldBounds != null) drawable.setBounds(oldBounds);
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static void rememberBitmap(String maneuver, String viewId, byte[] png,
            int width, int height, long sourceElapsedMs, long renderGeneration, long epoch,
            long sourceSequence) {
        synchronized (BITMAP_LOCK) {
            if (epoch != STATE_EPOCH.get()) return;
            lastManeuverName = maneuver;
            lastManeuverViewId = viewId;
            lastManeuverPng = png.clone();
            lastManeuverWidth = width;
            lastManeuverHeight = height;
            lastManeuverSourceElapsedMs = sourceElapsedMs;
            lastManeuverRenderGeneration = renderGeneration;
            lastManeuverSourceSequence = sourceSequence;
        }
    }

    private static void write(Event event) throws Exception {
        if (!isCurrentEvent(event)) return;
        boolean modelReady = true;
        if (event.model != null) {
            try {
                modelReady = renderModelBitmap(event);
                if (!modelReady) {
                    invalidateBitmap(event.model.maneuver);
                    Log.w(TAG, "MANEUVER_MODEL_FALLBACK|sequence=" + event.sequence
                            + "|maneuver=" + event.model.maneuver);
                }
            } catch (Throwable error) {
                modelReady = false;
                invalidateBitmap(event.model.maneuver);
                Log.w(TAG, "MANEUVER_MODEL_FALLBACK|sequence=" + event.sequence
                        + "|type=" + error.getClass().getSimpleName()
                        + "|message=" + clean(error.getMessage()));
            }
        }
        if (!isCurrentEvent(event)) return;
        if (!modelReady && noClient()) {
            requeueForRetry(event);
            return;
        }
        synchronized (STATE_LOCK) {
            if (!isCurrentEvent(event)) return;
            Messenger target = client;
            boolean bitmapRequired = modelReady && event.model != null;
            if (!sendMatchingBitmap(event, target, bitmapRequired)) {
                requeueForRetry(event);
                return;
            }
            if (!sendFrameEvent(event, target)) {
                requeueForRetry(event);
                return;
            }
            LAST_FRAME_FOR_REPLAY.set(event);
            PENDING_FRAME_FOR_REPLAY.compareAndSet(event, null);
        }
    }

    private static void requeueForRetry(Event event) {
        if (event == null || event.epoch != STATE_EPOCH.get() || !routeActive) return;
        PENDING_FRAME_FOR_REPLAY.compareAndSet(null, event);
    }

    private static boolean isCurrentEvent(Event event) {
        if (event == null || !routeActive || event.epoch != STATE_EPOCH.get()) return false;
        Event pending = LATEST_FRAME.get();
        return pending == null || pending.sequence <= event.sequence;
    }

    private static boolean renderModelBitmap(Event event) throws Exception {
        ModelToken model = event.model;
        if (model.epoch != STATE_EPOCH.get() || !isCurrentEvent(event)) return false;
        Context context = applicationContext;
        if (context == null) throw new IllegalStateException("application_context_missing");
        ClassLoader loader = model.value.getClass().getClassLoader();
        Class<?> renderer = Class.forName("bqyl", true, loader);
        Method render = null;
        for (Method candidate : renderer.getDeclaredMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (!"c".equals(candidate.getName()) || parameters.length != 3
                    || !Resources.class.equals(parameters[0])
                    || !parameters[1].isAssignableFrom(model.value.getClass())
                    || !int.class.equals(parameters[2])) continue;
            render = candidate;
            break;
        }
        if (render == null) throw new NoSuchMethodException("bqyl.c(Resources,Lbqmm,int)");
        render.setAccessible(true);
        Object rendered = render.invoke(null, context.getResources(), model.value, -1);
        Bitmap bitmap = rendered instanceof Bitmap ? (Bitmap) rendered : null;
        if (rendered instanceof Drawable) bitmap = drawableBitmap((Drawable) rendered);
        if (bitmap == null) throw new IllegalStateException("renderer_returned_no_bitmap");
        try {
            byte[] png = toPng(bitmap);
            if (png.length == 0 || png.length > MAX_FRAME_BYTES) {
                throw new IllegalStateException("png_size=" + png.length);
            }
            if (!isCurrentEvent(event)) return false;
            rememberBitmap(model.maneuver, "bqyl", png,
                    bitmap.getWidth(), bitmap.getHeight(), SystemClock.elapsedRealtime(),
                    model.renderGeneration, event.epoch, event.sequence);
            return true;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static Bitmap drawableBitmap(Drawable drawable) {
        int sourceWidth = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 1;
        int sourceHeight = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 1;
        float scale = Math.min(1f, MAX_BITMAP_DIMENSION_PX
                / (float) Math.max(sourceWidth, sourceHeight));
        int width = Math.max(1, Math.round(sourceWidth * scale));
        int height = Math.max(1, Math.round(sourceHeight * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Rect oldBounds = new Rect(drawable.getBounds());
        drawable.setBounds(0, 0, width, height);
        drawable.draw(new Canvas(bitmap));
        drawable.setBounds(oldBounds);
        return bitmap;
    }

    private static byte[] toPng(Bitmap bitmap) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw new IllegalStateException("png_encode_failed");
        }
        return output.toByteArray();
    }

    private static void invalidateBitmap(String maneuver) {
        synchronized (BITMAP_LOCK) {
            if (maneuver == null || maneuver.equals(lastManeuverName)) {
                lastManeuverName = "";
                lastManeuverViewId = "";
                lastManeuverPng = null;
                lastManeuverSourceSequence = -1L;
            }
        }
    }

    private static boolean sendFrameEvent(Event event) throws Exception {
        return sendFrameEvent(event, client);
    }

    private static boolean sendFrameEvent(Event event, Messenger target) throws Exception {
        if (target == null || event == null || !routeActive
                || event.epoch != STATE_EPOCH.get()) return false;
        Object message = event.message;
        Object payload = readField(message, "c");
        Object caseValue = readField(message, "b");
        byte[] bytes = serialize(message);
        if (bytes.length > MAX_FRAME_BYTES) {
            Log.w(TAG, "FRAME_DROPPED|seq=" + event.sequence + "|reason=too_large|bytes="
                    + bytes.length);
            return false;
        }
        if (event.epoch != STATE_EPOCH.get()) return false;
        Bundle data = baseBundle(event.epoch, true);
        data.putLong("sequence", event.sequence);
        data.putLong("sourceElapsedMs", event.elapsedMs);
        data.putString("case", clean(String.valueOf(caseValue)));
        data.putString("messageClass", className(message));
        data.putString("payloadClass", className(payload));
        data.putByteArray("payload", bytes);
        boolean delivered = send(target, MESSAGE_FRAME, data);
        if (!delivered && target == client) {
            clearClient("frame_send_failed", target.getBinder());
        }
        Log.d(TAG, "FRAME|seq=" + event.sequence + "|case="
                + clean(String.valueOf(caseValue)) + "|bytes=" + bytes.length
                + "|delivered=" + delivered);
        return delivered;
    }

    private static void replayCurrentState() {
        synchronized (STATE_LOCK) {
            replayCurrentStateLocked(new RouteSnapshot(STATE_EPOCH.get(), routeActive));
        }
    }

    private static void replayCurrentStateLocked(RouteSnapshot snapshot) {
        replayCurrentStateLocked(snapshot, client);
    }

    private static boolean replayCurrentStateLocked(RouteSnapshot snapshot, Messenger target) {
        final long epoch = snapshot.epoch;
        if (!snapshot.active) return true;
        Bundle start = baseBundle(epoch, true);
        start.putString("event", "replay-start");
        start.putString("argumentClass", "replay");
        if (!sendTo(target, MESSAGE_START, start)) return false;
        Event replayEvent = LAST_FRAME_FOR_REPLAY.get();
        if (replayEvent == null || replayEvent.epoch != epoch) return true;
        if (!sendMatchingBitmap(replayEvent, target, false)) return false;
        try {
            if (!sendFrameEvent(replayEvent, target)) return false;
        } catch (Throwable error) {
            Log.w(TAG, "REPLAY_FRAME_FAILED|type=" + error.getClass().getSimpleName());
            return false;
        }
        synchronized (SPEED_LOCK) {
            if (lastSpeedLimit != Integer.MIN_VALUE
                    && epoch == STATE_EPOCH.get() && routeActive) {
                Bundle speed = baseBundle(epoch, true);
                speed.putInt("speedLimit", lastSpeedLimit);
                speed.putInt("speedLimitKph", lastSpeedLimitKph);
                speed.putString("speedUnit", lastSpeedUnit);
                if (!sendTo(target, MESSAGE_SPEED_LIMIT, speed)) return false;
            }
        }
        return true;
    }

    private static boolean sendMatchingBitmap(
            Event event, Messenger target, boolean required) {
        synchronized (BITMAP_LOCK) {
            boolean maneuverMatches = event.model == null
                    || event.model.maneuver.equals(lastManeuverName);
            boolean sequenceMatches = lastManeuverSourceSequence < 0L
                    || lastManeuverSourceSequence == event.sequence;
            if (lastManeuverPng != null && lastManeuverPng.length > 0
                    && maneuverMatches && sequenceMatches
                    && event.epoch == STATE_EPOCH.get() && routeActive) {
                Bundle bitmap = baseBundle(event.epoch, true);
                bitmap.putString("maneuver", lastManeuverName);
                bitmap.putString("viewId", lastManeuverViewId);
                bitmap.putInt("width", lastManeuverWidth);
                bitmap.putInt("height", lastManeuverHeight);
                bitmap.putLong("bridgeElapsedMs", lastManeuverSourceElapsedMs);
                bitmap.putLong("renderGeneration", lastManeuverRenderGeneration);
                bitmap.putLong("sourceSequence", lastManeuverSourceSequence);
                bitmap.putByteArray("png", lastManeuverPng.clone());
                if (!sendTo(target, MESSAGE_MANEUVER_BITMAP, bitmap)) return false;
                return true;
            }
            return !required;
        }
    }

    private static void sendControl(int what, String event, String argumentClass) {
        sendControl(what, event, argumentClass, STATE_EPOCH.get(), routeActive);
    }

    private static void sendControl(
            int what, String event, String argumentClass, long epoch, boolean active) {
        Bundle data = baseBundle(epoch, active);
        data.putString("event", event);
        data.putString("argumentClass", argumentClass);
        send(what, data);
        Log.i(TAG, "LIFECYCLE|event=" + event + "|elapsedMs="
                + SystemClock.elapsedRealtime());
    }

    private static Bundle baseBundle() {
        return baseBundle(STATE_EPOCH.get(), routeActive);
    }

    private static Bundle baseBundle(long epoch, boolean active) {
        Bundle data = new Bundle();
        data.putInt("protocolVersion", PROTOCOL_VERSION);
        data.putLong("producerEpoch", PROCESS_EPOCH);
        data.putLong("routeGeneration", epoch);
        data.putLong("stateEpoch", epoch);
        data.putBoolean("routeActive", active);
        data.putInt("capabilities", capabilities());
        if (!clientChannelId.isEmpty()) data.putString("channelId", clientChannelId);
        data.putLong("bridgeElapsedMs", SystemClock.elapsedRealtime());
        return data;
    }

    private static int capabilities() {
        return CAP_STATE_REPLAY | CAP_HEARTBEAT | CAP_BITMAP_GENERATION | CAP_ROUTE_GENERATION;
    }

    private static boolean send(int what, Bundle data) {
        Messenger target = client;
        if (target == null) return false;
        boolean delivered = send(target, what, data);
        if (!delivered) clearClient("send_failed", target.getBinder());
        return delivered;
    }

    private static boolean send(Messenger target, int what, Bundle data) {
        if (target == null) return false;
        Message message = Message.obtain(null, what);
        message.setData(data);
        try {
            target.send(message);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "SEND_FAILED|what=" + what + "|type="
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean sendTo(Messenger target, int what, Bundle data) {
        return send(target, what, data);
    }

    private static void clearClient(String reason, IBinder expectedBinder) {
        synchronized (CLIENT_LOCK) {
            if (expectedBinder != null && clientBinder != expectedBinder) return;
            unlinkCurrentClientLocked();
            client = null;
            clientBinder = null;
            clientDeathRecipient = null;
            clientChannelId = "";
        }
        Log.i(TAG, "CLIENT_DISCONNECTED|reason=" + clean(reason));
    }

    private static void resetTransientState() {
        STATE_EPOCH.incrementAndGet();
        LATEST_FRAME.set(null);
        LAST_FRAME_FOR_REPLAY.set(null);
        PENDING_FRAME_FOR_REPLAY.set(null);
        FRAME_MODEL.remove();
        routeActive = false;
        synchronized (BITMAP_LOCK) {
            lastManeuverName = "";
            lastManeuverViewId = "";
            lastManeuverPng = null;
            lastManeuverWidth = 0;
            lastManeuverHeight = 0;
            lastManeuverSourceElapsedMs = 0L;
            lastManeuverRenderGeneration = 0L;
            lastManeuverSourceSequence = -1L;
            PENDING_CAPTURES.clear();
        }
        synchronized (SPEED_LOCK) {
            lastSpeedStep = null;
            lastSpeedProgress = -1;
            lastSpeedLimit = Integer.MIN_VALUE;
            lastSpeedLimitKph = Integer.MIN_VALUE;
            lastSpeedUnit = "";
            speedReflectionErrorLogged = false;
        }
    }

    private static void clearSpeedLimitLocked(RouteSnapshot snapshot) {
        lastSpeedStep = null;
        lastSpeedProgress = -1;
        publishSpeedLimitLocked(0, 0, "", snapshot);
    }

    private static void publishSpeedLimitLocked(
            int limit, int kph, String unit, RouteSnapshot snapshot) {
        if (lastSpeedLimit == limit && lastSpeedLimitKph == kph && lastSpeedUnit.equals(unit)) {
            return;
        }
        // The caller captured epoch+active atomically before entering SPEED_LOCK. Do not
        // read STATE_EPOCH and routeActive separately here: a concurrent STOP could
        // otherwise produce an impossible mixed snapshot. The receiver fences a late
        // event by its explicit routeGeneration before applying it.
        if (snapshot == null || !snapshot.active) return;
        // A newer route may have reset the speed cache while this sample waited for
        // SPEED_LOCK. Never let that old value repopulate the new route's replay state.
        if (snapshot.epoch != STATE_EPOCH.get()) return;
        Bundle data = baseBundle(snapshot.epoch, true);
        data.putInt("speedLimit", limit);
        data.putInt("speedLimitKph", kph);
        data.putString("speedUnit", unit);
        lastSpeedLimit = limit;
        lastSpeedLimitKph = kph;
        lastSpeedUnit = unit;
        boolean delivered = send(MESSAGE_SPEED_LIMIT, data);
        Log.i(TAG, "SPEED_LIMIT|value=" + limit + "|kph=" + kph
                + "|unit=" + unit + "|delivered=" + delivered);
    }

    private static String speedUnit(int code, List<?> countries) {
        if (code == 1) return "km/h";
        if (code == 2) return "mph";
        if (countries.size() != 1) return "";
        String country = String.valueOf(countries.get(0));
        if ("US".equals(country)) return "mph";
        return "AU".equals(country) || "BR".equals(country) || "CA".equals(country)
                ? "km/h" : "";
    }

    private static String speedUnitV26(int code, List<?> countries) {
        if (code == 1) return "km/h";
        if (code == 2) return "mph";
        if (countries.size() != 1) return "";
        String country = String.valueOf(countries.get(0)).toUpperCase(java.util.Locale.US);
        if (country.length() != 2) return "";
        return "US".equals(country) || "MM".equals(country)
                || "LR".equals(country) || "GB".equals(country) ? "mph" : "km/h";
    }

    private static void unlinkCurrentClientLocked() {
        IBinder binder = clientBinder;
        IBinder.DeathRecipient recipient = clientDeathRecipient;
        if (binder != null && recipient != null) {
            try {
                binder.unlinkToDeath(recipient, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable error) {
                return "field_error:" + error.getClass().getSimpleName();
            }
        }
        return "field_missing";
    }

    private static Object requiredField(Object target, String name) throws Exception {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static Object elementAt(Object value, int index) throws Exception {
        if (value == null || index < 0) throw new IndexOutOfBoundsException("index=" + index);
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            if (index >= length) throw new IndexOutOfBoundsException("index=" + index);
            return java.lang.reflect.Array.get(value, index);
        }
        if (value instanceof List) return ((List<?>) value).get(index);
        throw new IllegalStateException("not an indexed model container");
    }

    private static int intField(Object target, String name) throws Exception {
        Object value = requiredField(target, name);
        if (!(value instanceof Number)) throw new IllegalStateException(name + " is not numeric");
        return ((Number) value).intValue();
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Object value = requiredField(target, name);
        if (!(value instanceof Boolean)) throw new IllegalStateException(name + " is not boolean");
        return (Boolean) value;
    }

    private static boolean invokeBoolean(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                Object value = method.invoke(target);
                if (!(value instanceof Boolean)) {
                    throw new IllegalStateException(name + " is not boolean");
                }
                return (Boolean) value;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static Object invokeObject(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static byte[] serialize(Object target) throws Exception {
        if (target == null) throw new IllegalArgumentException("message is null");
        Method fallback = null;
        Class<?> type = target.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0
                        || method.getReturnType() != byte[].class
                        || Modifier.isStatic(method.getModifiers())) continue;
                if ("toByteArray".equals(method.getName())) {
                    method.setAccessible(true);
                    return (byte[]) method.invoke(target);
                }
                if (fallback == null) fallback = method;
            }
            type = type.getSuperclass();
        }
        if (fallback == null) throw new NoSuchMethodException("no byte[] serializer on "
                + target.getClass().getName());
        fallback.setAccessible(true);
        return (byte[]) fallback.invoke(target);
    }

    private static String className(Object value) {
        return value == null ? "null" : clean(value.getClass().getName());
    }

    private static String enumName(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : clean(String.valueOf(value));
    }

    private static String resourceName(ImageView view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return String.valueOf(view.getId());
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    private static RouteSnapshot routeSnapshot() {
        synchronized (STATE_LOCK) {
            return new RouteSnapshot(STATE_EPOCH.get(), routeActive);
        }
    }

    private static final class PendingCapture {
        String maneuver;
        long epoch;
        long renderGeneration;

        PendingCapture(String maneuver, long epoch, long renderGeneration) {
            this.maneuver = maneuver;
            this.epoch = epoch;
            this.renderGeneration = renderGeneration;
        }
    }

    private static final class Event {
        final long sequence;
        final long elapsedMs;
        final long epoch;
        final Object message;
        final ModelToken model;

        Event(long sequence, long elapsedMs, long epoch, Object message, ModelToken model) {
            this.sequence = sequence;
            this.elapsedMs = elapsedMs;
            this.epoch = epoch;
            this.message = message;
            this.model = model;
        }
    }

    private static final class ModelToken {
        final Object value;
        final String maneuver;
        final long epoch;
        final long renderGeneration;

        ModelToken(Object value, String maneuver, long epoch, long renderGeneration) {
            this.value = value;
            this.maneuver = maneuver;
            this.epoch = epoch;
            this.renderGeneration = renderGeneration;
        }
    }

    private static final class RouteSnapshot {
        final long epoch;
        final boolean active;

        RouteSnapshot(long epoch, boolean active) {
            this.epoch = epoch;
            this.active = active;
        }
    }
}
