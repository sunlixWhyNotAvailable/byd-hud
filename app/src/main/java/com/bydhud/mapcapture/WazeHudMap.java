package com.bydhud.mapcapture;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/** A separate cluster scene in the current Waze navigation session. */
final class WazeHudMap {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WazeHudMap current;
    private static boolean failed;
    private static final int WIDTH = 900, HEIGHT = 540;
    private Object scope, controller, presenter, renderer, wrapper, manager, executor, mapState;
    private Object releaseSubscription;
    private ImageReader reader;
    private HandlerThread drain;
    private Surface surface;
    private volatile boolean stopped, ready;
    private volatile String failure = "hud_starting";
    private volatile String mapId;
    private Bundle pending;
    private volatile long renderSerial;
    private long pendingSerial;
    private volatile boolean renderFailed;
    private static boolean waitingForExecutor;

    static boolean owns(Object value) {
        WazeHudMap map = current;
        return map != null && (value == map.wrapper || value == map.renderer);
    }

    static void drive(Context context, boolean active) {
        if (!active) {
            WazeHudMap previous = current;
            current = null;
            if (previous != null) previous.stop();
            failed = false;
            return;
        }
        if (current != null || failed || context == null) return;
        WazeHudMap candidate = new WazeHudMap();
        current = candidate;
        try {
            if (!candidate.start(context)) {
                current = null;
                if (!waitingForExecutor) BackgroundCapture.event("hud_waiting_for_engine", null, "shared render executor not running");
                waitingForExecutor = true;
            } else waitingForExecutor = false;
        }
        catch (Throwable error) { candidate.fail("hud_create", error); }
    }

    static void capture(Bundle request) {
        MAIN.post(() -> {
            WazeHudMap map = current;
            if (map == null || !map.ready || map.stopped) {
                CaptureBridge.wazeUnavailable(request, map == null ? "hud_unavailable" : map.failure);
                return;
            }
            // A successful copy of an old Surface buffer is not proof of a current frame.
            map.pending = request;
            map.pendingSerial = map.renderSerial;
            WazeRenderScheduler.request();
        });
    }

    private void frameAvailable() {
        Bundle request = pending;
        if (request == null || stopped || !ready || current != this || renderFailed
                || renderSerial <= pendingSerial) return;
        pending = null;
        request.putBoolean("independentHudMap", true);
        request.putString("hudMapId", mapId);
        CaptureBridge.wazeSurfaceFrame(request, wrapper, surface, WIDTH, HEIGHT,
                () -> current == this && ready && !stopped && !renderFailed);
    }

    private boolean start(Context context) throws Throwable {
        // shortcut: this private ABI is pinned to Waze 5.25.0.1; other versions retain their existing path.
        Object koin = invoke(invoke(invoke(type("kotlinx.b.a.u"), "i"), "a"), "c");
        executor = invoke(resolve(koin, "com.waze.map.t"), "a");
        if (!(executor instanceof Thread) || !((Thread) executor).isAlive()
                || Boolean.TRUE.equals(field(executor, "d"))) {
            return false;
        }
        WazeHudStyle.load();
        scope = invoke(type("kotlinx.coroutines.ai"), "f");
        Object logger = invoke(type("com.waze.shared_infra.i.a"), "d", "BydHudCluster");
        controller = invoke(resolve(koin, "com.waze.map.b.cg"), "a", logger,
                invoke(type("com.waze.map.b.ce"), "b"));
        Object released = field(type("com.waze.map.dl"), "a");
        mapState = flow(released);
        Object bounds = flow(new Rect(0, 0, WIDTH, HEIGHT));
        Object factory = create("com.waze.map.gf", resolve(koin, "com.waze.map.ci"));
        presenter = invoke(factory, "c", scope, mapState, bounds, context,
                create("com.waze.map.ea"));
        Object viewport = proxy("com.waze.map.cc", (method, args) -> {
            if (!method.equals("a")) throw new UnsupportedOperationException(method);
            return bounds;
        });
        invoke(controller, "B", presenter, viewport);
        Object tracking = create("com.waze.map.b.ck", field(type("com.waze.map.b.ch"), "a"),
                create("com.waze.map.b.dp", new PointF(0.5f, 0.83f)));
        invoke(type("com.waze.design_components.d.b"), "q", controller, null, tracking, false, 13);
        Object navigation = create("com.waze.map.b.dk",
                create("com.waze.map.b.bw", false, true, false, 12));
        Object content = create("com.waze.map.b.bx", true,
                field(type("com.waze.map.b.dr"), "a"), false, false, false, false,
                null, create("com.waze.map.b.di", false, false, false, false, false, false),
                false, false, false, 3840);
        invoke(type("com.waze.design_components.d.b"), "o", controller,
                null, null, null, content, navigation, field(type("com.waze.map.b.bf"), "b"), 31);

        renderer = create("com.waze.map.opengl.WazeMapRenderer", (Object) null);
        releaseSubscription = invoke(renderer, "a", (Runnable) () -> MAIN.post(() -> {
            if (stopped) return;
            fail("hud_renderer_released", new IllegalStateException("Owned renderer released its map"));
        }));
        Object styledRenderer = proxy("com.waze.bl.h", (method, args) -> {
            if ((stopped || renderFailed) && !method.equals("i") && !method.equals("f"))
                return method.equals("l") ? false : null;
            try {
                Object result = invoke(renderer, method, args);
                if (method.equals("h")) {
                    String id = (String) invoke(field(renderer, "e"), "d");
                    WazeHudStyle.attach(id);
                    mapId = id;
                    MAIN.post(() -> {
                        if (stopped || renderFailed || current != this) return;
                        try {
                            invoke(mapState, "e", create("com.waze.map.dm", create("com.waze.map.z", id)));
                            ready = true;
                            failure = "";
                            BackgroundCapture.event("hud_map_ready", renderer,
                                    "mapId=" + id + " size=" + WIDTH + "x" + HEIGHT);
                        } catch (Throwable error) { fail("hud_ready", error); }
                    });
                }
                return result;
            } catch (Throwable error) {
                renderFailed = true;
                MAIN.post(() -> fail("hud_render_" + method, error));
                // Do not let our scene terminate Waze's shared render thread.
                if (method.equals("l")) return false;
                return null;
            }
        });
        wrapper = create("com.waze.map.opengl.w", field(type("com.waze.map.opengl.f"), "b"),
                create("com.waze.map.opengl.l"), styledRenderer,
                invoke(resolve(koin, "com.waze.map.opengl.d"), "b"), false,
                resolve(koin, "com.waze.shared_infra.i.b"));
        // This lower Surface source has no Activity/lifecycle attachment requirement.
        Object sourceView = create("com.waze.map.opengl.z", context, null);
        Object guardedOutput = proxy("com.waze.bl.h", (method, args) -> {
            try {
                Object result = invoke(wrapper, method, args);
                if (method.equals("l") && Boolean.TRUE.equals(result)) {
                    renderSerial++;
                    MAIN.post(this::frameAvailable);
                }
                return result;
            }
            catch (Throwable error) {
                renderFailed = true;
                MAIN.post(() -> fail("hud_output_" + method, error));
                return method.equals("l") ? false : null;
            }
        });
        manager = create("com.waze.bl.r", guardedOutput, field(sourceView, "c"),
                field(type("kotlinx.coroutines.e.ac"), "a"));
        reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.PRIVATE, 3);
        surface = reader.getSurface();
        drain = new HandlerThread("WazeHudDrain");
        drain.start();
        reader.setOnImageAvailableListener(source -> {
            if (stopped) return;
            try (Image image = source.acquireLatestImage()) {
                if (image != null) MAIN.post(this::frameAvailable);
            }
            catch (RuntimeException error) { MAIN.post(() -> { if (!stopped) fail("hud_drain", error); }); }
        }, new Handler(drain.getLooper()));
        BackgroundCapture.registerWazeSource(manager, "waze");
        invoke(executor, "b", manager);
        invoke(manager, "g");
        WazeRenderScheduler.attach(manager, executor, wrapper);
        invoke(field(manager, "k"), "b", surface, WIDTH, HEIGHT, 160);
        WazeRenderScheduler.request();
        BackgroundCapture.event("hud_map_starting", renderer, "independent cluster scene; tablet retained");
        return true;
    }

    private void fail(String stage, Throwable error) {
        if (stopped) return;
        failure = stage + ": " + error.getClass().getName() + ": " + error.getMessage();
        BackgroundCapture.failure(stage, error);
        failed = true;
        stop();
    }

    private void stop() {
        if (stopped) return;
        stopped = true;
        ready = false;
        pending = null;
        WazeRenderScheduler.detach();
        cleanup("hud_presenter_cancel", () -> { if (scope != null) invoke(type("kotlinx.coroutines.ai"), "i", scope); });
        cleanup("hud_release_unsubscribe", () -> { if (releaseSubscription != null) invoke(releaseSubscription, "a"); });
        if (manager != null && executor != null) {
            // Avoid the stock synchronous Surface-release spin on the main thread.
            cleanup("hud_source_unsubscribe", () -> invoke(field(manager, "b"), "j", field(manager, "k")));
            cleanup("hud_manager_remove", () -> invoke(executor, "e", manager));
            // FIFO on the real render executor: remove our manager, detach its EGL target, then clear its map.
            cleanup("hud_renderer_dispose", () -> invoke(executor, "execute", (Runnable) () -> {
                cleanup("hud_surface_detach", () -> invoke(wrapper, "i"));
                cleanup("hud_native_clear", () -> invoke(renderer, "f"));
                MAIN.post(() -> {
                    cleanup("hud_renderer_events_cancel", () -> invoke(type("kotlinx.coroutines.ai"), "i", field(renderer, "b")));
                    closeOutput();
                });
            }));
        } else {
            cleanup("hud_renderer_events_cancel", () -> { if (renderer != null) invoke(type("kotlinx.coroutines.ai"), "i", field(renderer, "b")); });
            closeOutput();
        }
        BackgroundCapture.event("hud_map_stopped", renderer, "mapId=" + mapId);
    }

    private void closeOutput() {
        cleanup("hud_reader_close", () -> { if (reader != null) reader.close(); });
        reader = null;
        if (drain != null) { drain.quitSafely(); drain = null; }
    }

    private interface Action { void run() throws Throwable; }
    private interface Callback { Object call(String method, Object[] args) throws Throwable; }
    private static void cleanup(String stage, Action action) {
        try { action.run(); } catch (Throwable error) { BackgroundCapture.failure(stage, error); }
    }
    private static Object resolve(Object koin, String name) throws Throwable {
        return invoke(koin, "a", create("h.g.b.m", type(name)), null, null);
    }
    private static Object flow(Object initial) throws Throwable {
        return invoke(type("kotlinx.coroutines.b.cf"), "b", initial);
    }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Class<?> c = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name); f.setAccessible(true);
                return f.get(owner instanceof Class ? null : owner);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object create(String name, Object... args) throws Throwable {
        Constructor<?> match = null;
        for (Constructor<?> constructor : type(name).getDeclaredConstructors()) {
            if (!accepts(constructor.getParameterTypes(), args)) continue;
            if (match != null) throw new NoSuchMethodException("Ambiguous constructor " + name);
            match = constructor;
        }
        if (match == null) throw new NoSuchMethodException("Constructor " + name);
        try { match.setAccessible(true); return match.newInstance(args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    private static Object invoke(Object owner, String name, Object... args) throws Throwable {
        Class<?> c = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        for (; c != null; c = c.getSuperclass()) {
            Method match = null;
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name) || !accepts(method.getParameterTypes(), args)
                        || (owner instanceof Class && !Modifier.isStatic(method.getModifiers()))) continue;
                if (match != null) throw new NoSuchMethodException("Ambiguous method " + c.getName() + "." + name);
                match = method;
            }
            if (match == null) continue;
            try { match.setAccessible(true); return match.invoke(owner instanceof Class ? null : owner, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
        throw new NoSuchMethodException(owner + "." + name);
    }
    private static boolean accepts(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) return false;
        for (int i = 0; i < args.length; i++) {
            Class<?> p = parameters[i]; Object value = args[i];
            if (p.isPrimitive()) {
                if (!((p == boolean.class && value instanceof Boolean) || (p == int.class && value instanceof Integer)
                        || (p == float.class && value instanceof Float))) return false;
            } else if (value != null && !p.isInstance(value)) return false;
        }
        return true;
    }
    private static Object proxy(String name, Callback callback) throws ClassNotFoundException {
        Class<?> contract = type(name);
        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("hashCode")) return System.identityHashCode(self);
                if (method.getName().equals("equals")) return self == args[0];
                return "BydHud:" + name;
            }
            return callback.call(method.getName(), args == null ? new Object[0] : args);
        });
    }
}
