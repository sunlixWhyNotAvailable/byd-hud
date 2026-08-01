package com.bydhud.gmapsdiag;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Production GMaps navigation bridge. The class name is fixed by the guarded hooks. */
public final class NavInfoLogger {
    public static final String ACTION_UNREGISTER = "com.bydhud.gmapsbridge.UNREGISTER";
    public static final String EXTRA_CLIENT = "com.bydhud.gmapsbridge.CLIENT";
    public static final String EXTRA_PROTOCOL_VERSION =
            "com.bydhud.gmapsbridge.PROTOCOL_VERSION";
    public static final String EXTRA_IDENTITY = "com.bydhud.gmapsbridge.IDENTITY";
    public static final int PROTOCOL_VERSION = 2;
    public static final int MESSAGE_HELLO = 1;
    public static final int MESSAGE_START = 2;
    public static final int MESSAGE_FRAME = 3;
    public static final int MESSAGE_STOP = 4;
    public static final int MESSAGE_MANEUVER_BITMAP = 5;

    private static final String TAG = "BYD_GMAPS_BRIDGE";
    private static final String CLIENT_PACKAGE = "com.bydhud.app";
    private static final int MAX_FRAME_BYTES = 512 * 1024;
    private static final int MAX_BITMAP_DIMENSION_PX = 256;
    private static final Object FRAME_SIGNAL = new Object();
    private static final Object CLIENT_LOCK = new Object();
    private static final Object BITMAP_LOCK = new Object();
    private static final AtomicLong NEXT_SEQUENCE = new AtomicLong();
    private static final AtomicLong STATE_EPOCH = new AtomicLong();
    private static final AtomicReference<Event> LATEST_FRAME = new AtomicReference<>();

    private static volatile Messenger client;
    private static volatile IBinder clientBinder;
    private static volatile IBinder.DeathRecipient clientDeathRecipient;
    private static String lastManeuverName = "";
    private static byte[] lastManeuverPng;

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
        if (protocol != PROTOCOL_VERSION || candidate == null) {
            Log.w(TAG, "CLIENT_REJECTED|reason=protocol_or_messenger|protocol=" + protocol);
            return;
        }
        installClient(candidate);
    }

    /** The producer's existing IF_NEZ branch skips serialization while no client is alive. */
    public static boolean noClient() {
        IBinder binder = clientBinder;
        return client == null || binder == null || !binder.isBinderAlive();
    }

    public static void log(Object message) {
        if (noClient()) return;
        Event event = new Event(
                NEXT_SEQUENCE.incrementAndGet(), SystemClock.elapsedRealtime(),
                STATE_EPOCH.get(), message);
        Event replaced;
        synchronized (FRAME_SIGNAL) {
            replaced = LATEST_FRAME.getAndSet(event);
            FRAME_SIGNAL.notifyAll();
        }
        if (replaced != null) {
            Log.w(TAG, "FRAME_REPLACED|oldSeq=" + replaced.sequence
                    + "|newSeq=" + event.sequence);
        }
    }

    public static void sessionStart(Object session) {
        resetTransientState();
        sendControl(MESSAGE_START, "start", className(session));
    }

    public static void sessionOutputChanged(boolean enabled) {
        Log.i(TAG, "OUTPUT_CHANGED|elapsedMs=" + SystemClock.elapsedRealtime()
                + "|enabled=" + enabled);
    }

    public static void sessionStop() {
        resetTransientState();
        sendControl(MESSAGE_STOP, "stop", "");
    }

    /** Captures each changed maneuver after GMaps has rendered its turn-card ImageView. */
    public static void captureManeuverView(Object viewValue, Object maneuverValue) {
        if (noClient() || !(viewValue instanceof ImageView) || maneuverValue == null) return;
        final ImageView view = (ImageView) viewValue;
        final String maneuver = enumName(readField(maneuverValue, "a"));
        final long epoch = STATE_EPOCH.get();
        if (maneuver.isEmpty() || "UNKNOWN".equals(maneuver)) return;
        view.post(new Runnable() {
            @Override
            public void run() {
                captureRenderedManeuver(view, maneuver, epoch);
            }
        });
    }

    private static void installClient(Messenger candidate) {
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
            client = candidate;
            clientBinder = binder;
            clientDeathRecipient = deathRecipient;
        }
        resetTransientState();
        Bundle data = baseBundle();
        data.putString("bridge", "gmaps-navinfo");
        send(MESSAGE_HELLO, data);
        Log.i(TAG, "CLIENT_CONNECTED|protocol=" + PROTOCOL_VERSION);
    }

    private static boolean isTrustedSender(PendingIntent identity) {
        return identity != null && CLIENT_PACKAGE.equals(identity.getCreatorPackage());
    }

    private static void captureRenderedManeuver(
            ImageView view, String maneuver, long epoch) {
        if (noClient() || epoch != STATE_EPOCH.get()) return;
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
            if (epoch != STATE_EPOCH.get() || isDuplicateBitmap(maneuver, png)) return;
            Bundle data = baseBundle();
            data.putString("maneuver", maneuver);
            data.putString("viewId", resourceName(view));
            data.putInt("width", width);
            data.putInt("height", height);
            data.putByteArray("png", png);
            boolean delivered = send(MESSAGE_MANEUVER_BITMAP, data);
            if (delivered) rememberBitmap(maneuver, png, epoch);
            Log.i(TAG, "MANEUVER_BITMAP|maneuver=" + maneuver
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

    private static boolean isDuplicateBitmap(String maneuver, byte[] png) {
        synchronized (BITMAP_LOCK) {
            return maneuver.equals(lastManeuverName) && Arrays.equals(png, lastManeuverPng);
        }
    }

    private static void rememberBitmap(String maneuver, byte[] png, long epoch) {
        synchronized (BITMAP_LOCK) {
            if (epoch != STATE_EPOCH.get()) return;
            lastManeuverName = maneuver;
            lastManeuverPng = png.clone();
        }
    }

    private static void write(Event event) throws Exception {
        if (noClient() || event.epoch != STATE_EPOCH.get()) return;
        Object message = event.message;
        Object payload = readField(message, "c");
        Object caseValue = readField(message, "b");
        byte[] bytes = serialize(message);
        if (bytes.length > MAX_FRAME_BYTES) {
            Log.w(TAG, "FRAME_DROPPED|seq=" + event.sequence + "|reason=too_large|bytes="
                    + bytes.length);
            return;
        }
        if (event.epoch != STATE_EPOCH.get()) return;
        Bundle data = baseBundle();
        data.putLong("sequence", event.sequence);
        data.putLong("sourceElapsedMs", event.elapsedMs);
        data.putString("case", clean(String.valueOf(caseValue)));
        data.putString("messageClass", className(message));
        data.putString("payloadClass", className(payload));
        data.putByteArray("payload", bytes);
        boolean delivered = send(MESSAGE_FRAME, data);
        Log.d(TAG, "FRAME|seq=" + event.sequence + "|case="
                + clean(String.valueOf(caseValue)) + "|bytes=" + bytes.length
                + "|delivered=" + delivered);
    }

    private static void sendControl(int what, String event, String argumentClass) {
        Bundle data = baseBundle();
        data.putString("event", event);
        data.putString("argumentClass", argumentClass);
        send(what, data);
        Log.i(TAG, "LIFECYCLE|event=" + event + "|elapsedMs="
                + SystemClock.elapsedRealtime());
    }

    private static Bundle baseBundle() {
        Bundle data = new Bundle();
        data.putInt("protocolVersion", PROTOCOL_VERSION);
        data.putLong("bridgeElapsedMs", SystemClock.elapsedRealtime());
        return data;
    }

    private static boolean send(int what, Bundle data) {
        Messenger target = client;
        if (target == null) return false;
        Message message = Message.obtain(null, what);
        message.setData(data);
        try {
            target.send(message);
            return true;
        } catch (Throwable error) {
            clearClient("send_failed:" + error.getClass().getSimpleName(), target.getBinder());
            return false;
        }
    }

    private static void clearClient(String reason, IBinder expectedBinder) {
        synchronized (CLIENT_LOCK) {
            if (expectedBinder != null && clientBinder != expectedBinder) return;
            unlinkCurrentClientLocked();
            client = null;
            clientBinder = null;
            clientDeathRecipient = null;
        }
        resetTransientState();
        Log.i(TAG, "CLIENT_DISCONNECTED|reason=" + clean(reason));
    }

    private static void resetTransientState() {
        STATE_EPOCH.incrementAndGet();
        LATEST_FRAME.set(null);
        synchronized (BITMAP_LOCK) {
            lastManeuverName = "";
            lastManeuverPng = null;
        }
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

    private static final class Event {
        final long sequence;
        final long elapsedMs;
        final long epoch;
        final Object message;

        Event(long sequence, long elapsedMs, long epoch, Object message) {
            this.sequence = sequence;
            this.elapsedMs = elapsedMs;
            this.epoch = epoch;
            this.message = message;
        }
    }
}
