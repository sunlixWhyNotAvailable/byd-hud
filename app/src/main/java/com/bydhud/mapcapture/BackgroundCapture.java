package com.bydhud.mapcapture;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Version-pinned diagnostic hooks. All target ownership changes run on the main thread. */
public final class BackgroundCapture {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CaptureLease LEASE = new CaptureLease();
    private static final String WVIEW = "com.waze.map.opengl.z";
    private static WeakReference<Object> waze = new WeakReference<>(null), maps = new WeakReference<>(null);
    private static final ArrayList<WeakReference<Object>> cars = new ArrayList<>();
    private static final ArrayList<MapsFollowing> following = new ArrayList<>();
    private static final ArrayList<MapsNavigationCamera> navigationCameras = new ArrayList<>();
    private static final WeakHashMap<Object,Boolean> failedCars = new WeakHashMap<>();
    private static final WeakHashMap<Object,int[]> carSizes = new WeakHashMap<>();
    private static final WeakHashMap<Object,String> wazeSources = new WeakHashMap<>();
    private static final WeakHashMap<Object,int[]> wazeSizes = new WeakHashMap<>();
    private static boolean wazeStopped, mapsStopped, ticking;
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static volatile Target target;
    private static Object failed;
    private static long mapsTransition;
    private static volatile String state = "idle";
    private static volatile String carReadiness = "none";
    private static volatile long lastVisibleWazeFrame;
    private static volatile String lastVisibleWazeSource = "unknown";
    private static final ArrayDeque<JSONObject> EVENTS = new ArrayDeque<>();
    private static long sequence;
    private static final Class<?>[] NONE = {};

    private static final class Target {
        Object owner, driver, callbacks, scheduler, surfaceOwner, resizer, frameSource;
        View view;
        ImageReader reader;
        Surface surface;
        HandlerThread drain;
        String backend;
        int width, height;
        volatile boolean rendered;
        volatile boolean yielding;
        boolean drainFailed;
        boolean ownsPresenter;
        boolean ownsMapsScene;
    }

    public static void lease(boolean active) {
        LEASE.update(active, SystemClock.elapsedRealtime());
        MAIN.post(() -> { drive(); if (!ticking && LEASE.active(SystemClock.elapsedRealtime())) {
            ticking = true; MAIN.postDelayed(BackgroundCapture::tick, 500);
        }});
    }
    private static void tick() {
        ticking = false; drive();
        if (LEASE.active(SystemClock.elapsedRealtime()) || target != null) {
            ticking = true; MAIN.postDelayed(BackgroundCapture::tick, 500);
        }
    }
    private static boolean active() { return LEASE.active(SystemClock.elapsedRealtime()); }
    private static void drive() {
        // Surface callbacks may have arrived while owned output suppressed them.
        // Rebind any still-valid real host now so a later resume can use it.
        if (!active()) { release("lease_end", true); releaseFollowing(null,"lease_end"); failed = null; failedCars.clear(); return; }
        if (target != null) {
            if (target.view != null && !target.view.isAttachedToWindow()) { failed=target.owner; release("window_detached",false); }
            return;
        }
        // Resume the renderer that actually supplied the visible map. A Car App
        // can remain RESUMED without ever owning a visible Surface.
        if ("waze".equals(lastVisibleWazeSource) && wazeStopped && waze.get()!=null
                && waze.get()!=failed && SystemClock.elapsedRealtime()-lastVisibleWazeFrame>1500) {
            startWaze(waze.get());
            if (target!=null) return;
        }
        // ponytail: recent frames identify another active host; add host-lifecycle hooks
        // if a static cluster renderer's >1.5s draw silence causes false fallback.
        if (SystemClock.elapsedRealtime()-lastVisibleWazeFrame>1500) {
            StringBuilder readiness = new StringBuilder();
            for (int i=cars.size()-1;i>=0;i--) {
                Object owner=cars.get(i).get();
                if (owner==null) { cars.remove(i); continue; }
                boolean ready=carReady(owner,readiness);
                if (ready && !failedCars.containsKey(owner)) {
                    startCar(owner);
                    // Missing dimensions must not starve other usable renderers.
                }
                if (target!=null) break;
            }
            carReadiness=readiness.length()==0 ? "none" : readiness.toString();
        }
        if (target != null) return;
        if (wazeStopped && waze.get() != null && waze.get() != failed
                && SystemClock.elapsedRealtime()-lastVisibleWazeFrame>1500) startWaze(waze.get());
        else if (mapsStopped && maps.get() != null && maps.get() != failed) startMaps(maps.get());
        if (target == null) releaseFollowing(null,"no_offscreen_target");
    }

    public static void wazeStopped(Object view) {
        waze = new WeakReference<>(view); wazeStopped = true;
        event("waze_stopped", view, ""); drive();
    }
    public static void wazeStarted(Object view) {
        if (target != null && isWaze(target)) release("waze_resume", false);
        if (failed==view) failed=null;
        waze = new WeakReference<>(view); wazeStopped = false;
        event("waze_started", view, "");
    }
    public static void wazeGone(Object view) {
        if (target != null && target.owner == view) release("waze_view_destroyed", false);
        if (waze.get() == view) { waze.clear(); wazeStopped = false; }
        event("waze_view_destroyed", view, "");
    }
    // Called AFTER the OEM controller release has acknowledged EGL target destruction.
    public static void wazeReleased(Object view) {
        if (!BYPASS.get() && target != null && target.owner == view) {
            Target old = target; target = null; close(old); state = "waze_released";
            event("waze_controller_released", view, "");
        }
    }
    public static boolean wazeLost(Object callback) {
        rememberWazeSize(callback,0,0); // OEM c() clears the manager's size next.
        return wazeCallback(callback, null, "window_surface_lost");
    }
    public static boolean wazeSurface(Object callback, Surface surface, int width, int height, int dpi) {
        rememberWazeSize(callback,width,height);
        return wazeCallback(callback, surface, "window_surface_available");
    }
    public static boolean wazeSize(Object callback, int width, int height, int dpi) {
        rememberWazeSize(callback,width,height);
        return wazeCallback(callback, null, "window_surface_resize");
    }
    private static void rememberWazeSize(Object callback, int width, int height) {
        if (BYPASS.get()) return; // Never replace host dimensions with our ImageReader size.
        try {
            Object manager=get(callback,null,"a");
            android.util.Size current=(android.util.Size)get(manager,null,"i");
            int[] size=CarCapturePolicy.size(width,height,current==null ? null
                    : new int[]{current.getWidth(),current.getHeight()});
            if (size==null) return;
            synchronized (wazeSizes) {
                int[] old=wazeSizes.put(manager,size);
                if (old==null || old[0]!=size[0] || old[1]!=size[1])
                    event("waze_host_size",manager,size[0]+"x"+size[1]);
            }
        } catch (Throwable e) { failure("waze_host_size",e); }
    }
    private static boolean wazeCallback(Object callback, Surface surface, String action) {
        Target t=target;
        if (BYPASS.get() || t == null || !isWaze(t)) return false;
        try {
            if (get(callback, null, "a") != t.driver || surface == t.surface) return false;
            if (t.backend.equals("waze_car") && surface != null) {
                release("car_surface_available",false); return false;
            }
            event(action, t.owner, "offscreen target retained"); return true;
        } catch (Throwable e) { failure(action, e); return false; }
    }
    private static void startWaze(Object view) {
        if (!(view instanceof View) || !((View)view).isAttachedToWindow()) return;
        Target t = new Target(); t.owner = view; t.view = (View)view; t.backend = "waze";
        try {
            // Only the two inspected SurfaceView controllers have the supported ownership path.
            String type = view.getClass().getName();
            if (!type.equals("com.waze.map.gt") && !type.equals("com.waze.map.gg")) return;
            createReader(t);
            BYPASS.set(true);
            call(view, "k"); // Reuses the controller's retained renderer/map ID.
            t.driver = get(view, WVIEW, "b");
            if (t.driver == null) throw new IllegalStateException("waze_no_surface_manager");
            Thread executor = (Thread)get(view,WVIEW,"a");
            if (executor == null || !executor.isAlive() || (Boolean)get(executor,null,"d")) {
                call(t.driver,"e"); // Prevent OEM release waiting for a terminated executor.
                throw new IllegalStateException("waze_render_executor_stopped");
            }
            t.callbacks = get(t.driver, null, "k");
            t.frameSource = get(t.driver,null,"a");
            call(t.driver, "k"); // Existing synchronous release/ack on Waze's render executor.
            target = t;
            call(t.callbacks, "b", new Class<?>[]{Surface.class,int.class,int.class,int.class},
                    t.surface, t.width, t.height, t.view.getResources().getDisplayMetrics().densityDpi);
            state = "waze_offscreen"; event("offscreen_attached", view, wazeIdentity(t));
        } catch (Throwable e) {
            failure("waze_attach", e);
            try { call(view, "t"); } catch (Throwable cleanup) { failure("waze_attach_cleanup", cleanup); }
            target = null; close(t);
            failed = view; // One failed attempt per session, not an endless creation loop.
        } finally { BYPASS.set(false); }
    }
    private static String wazeIdentity(Target t) {
        try {
            Object wrapper = get(t.driver, null, "a"), renderer = get(wrapper, null, "c");
            String id = renderer.getClass().getName().endsWith("WazeMapRenderer")
                    ? String.valueOf(call(get(renderer, null, "e"), "d")) : "legacy";
            return "renderer=" + identity(renderer) + " mapId=" + id + " size=" + t.width + "x" + t.height;
        } catch (Throwable e) { return "identity_error=" + e; }
    }

    // Car App may remain RESUMED after its host destroys the drawing Surface.
    private static void rememberCar(Object owner) {
        cars.removeIf(ref -> ref.get()==null || ref.get()==owner);
        cars.add(new WeakReference<>(owner));
    }
    public static void carPaused(Object owner) {
        rememberCar(owner);
        event("car_paused", owner, "");
        MAIN.post(BackgroundCapture::drive);
    }
    public static void carResumed(Object owner) {
        if (target != null && isWaze(target)) release("car_resume", false);
        failedCars.remove(owner);
        rememberCar(owner);
        event("car_resumed", owner, "");
    }
    public static void carGone(Object owner) {
        carReleasing(owner);
        cars.removeIf(ref -> ref.get()==null || ref.get()==owner);
        failedCars.remove(owner);
        try { carSizes.remove(get(owner,null,"a")); }
        catch (Throwable e) { failure("car_size_cleanup",e); }
        event("car_destroyed", owner, "");
    }
    public static void carReleasing(Object owner) {
        if (BYPASS.get()) return;
        if (target != null && target.owner == owner) release("car_presenter_stop", false);
        event("car_presenter_stop", owner, "");
    }
    public static void carSurfaceAvailable(Object callback, Object container) {
        try {
            Object holder=get(callback,null,"a");
            rememberCarSize(holder, container);
            for (WeakReference<Object> ref : cars) {
                Object owner = ref.get();
                if (owner != null && get(owner,null,"a") == holder) failedCars.remove(owner);
            }
            if (target!=null && target.backend.equals("waze_car") && target.surfaceOwner==holder)
                release("car_surface_available",false);
            event("car_surface_available",holder,"");
        } catch (Throwable e) { failure("car_surface_available",e); }
    }
    public static void carSurfaceLost(Object callback, Object container) {
        try {
            Object holder = get(callback,null,"a");
            rememberCarSize(holder, container);
            event("car_surface_lost",holder,"");
        }
        catch (Throwable e) { failure("car_surface_lost",e); }
        MAIN.post(BackgroundCapture::drive); // Original callback first clears h.c and releases the old target.
    }
    private static void rememberCarSize(Object holder, Object container) throws Exception {
        if (container == null) return;
        int width = (Integer)call(container,"getWidth"), height = (Integer)call(container,"getHeight");
        if (width > 0 && height > 0) carSizes.put(holder,new int[]{width,height});
    }
    public static void wazeRendererReady(Object view) {
        try { registerWazeSource(get(view,WVIEW,"b"),"waze"); }
        catch (Throwable e) { failure("waze_source",e); }
    }
    public static void carRendererReady(Object presenter) {
        try { registerWazeSource(get(presenter,"com.waze.car_lib.i.a.s","f"),"waze_surface"); }
        catch (Throwable e) { failure("car_source",e); }
    }
    private static void registerWazeSource(Object manager, String mode) throws Exception {
        if (manager == null) return;
        Object renderer = get(manager,null,"a");
        synchronized (wazeSources) { wazeSources.put(renderer,mode); }
        event("waze_source_registered",renderer,mode);
    }
    static String wazeSource(Object renderer) {
        synchronized (wazeSources) {
            String mode = wazeSources.get(renderer);
            return mode == null ? "unknown" : mode;
        }
    }
    private static Object carPresenter(Object owner, Object renderer) throws Exception {
        String type=renderer.getClass().getName();
        if (!type.equals("com.waze.car_lib.i.b.p") && !type.equals("com.waze.car_lib.i.b.q")) return null;
        return get(get(owner,null,"b"),null,type.endsWith(".q") ? "b" : "a");
    }
    private static Surface carSurface(Object holder) throws Exception {
        Object container=get(holder,null,"c");
        return container==null ? null : (Surface)call(container,"getSurface");
    }
    private static boolean carReady(Object owner, StringBuilder status) {
        try {
            Object renderer=get(owner,null,"k"), holder=get(owner,null,"a");
            rememberCarSize(holder,get(holder,null,"c"));
            Object presenter=renderer==null ? null : carPresenter(owner,renderer);
            Object manager=presenter==null ? null : get(presenter,"com.waze.car_lib.i.a.s","f");
            Surface surface=carSurface(holder);
            boolean valid=surface!=null && surface.isValid(), resumed=(Boolean)get(owner,null,"d");
            status.append(identity(owner)).append(" holder=").append(identity(holder))
                    .append(" resumed=").append(resumed).append(" surfaceValid=").append(valid)
                    .append(" renderer=").append(identity(renderer)).append(" manager=").append(identity(manager))
                    .append(" failed=").append(failedCars.containsKey(owner)).append(';');
            return CarCapturePolicy.mayAttach(resumed,presenter!=null,valid,manager!=null);
        } catch (Throwable e) { status.append(identity(owner)).append(" error=").append(e).append(';'); return false; }
    }
    private static void startCar(Object owner) {
        Target t = new Target(); t.owner = owner; t.backend = "waze_car";
        try {
            Object renderer = get(owner,null,"k");
            if (renderer == null) return; // Screen disposed; never resurrect a cleared native map.
            Object presenters = get(owner,null,"b"), presenter=carPresenter(owner,renderer);
            if (presenter==null) return;
            t.surfaceOwner = get(owner,null,"a");
            Object dimensions = call(get(t.surfaceOwner,null,"d"),"d");
            int width = (Integer)call(dimensions,"g"), height = (Integer)call(dimensions,"c");
            int[] size = CarCapturePolicy.size(width,height,carSizes.get(t.surfaceOwner));
            t.driver = get(presenter,"com.waze.car_lib.i.a.s","f");
            String sizeSource="holder";
            if (size==null && t.driver!=null) {
                android.util.Size current=(android.util.Size)get(t.driver,null,"i");
                synchronized (wazeSizes) {
                    size=CarCapturePolicy.size(current==null ? 0 : current.getWidth(),
                            current==null ? 0 : current.getHeight(),wazeSizes.get(t.driver));
                }
                sizeSource="renderer";
            }
            if (size == null) {
                if (!"car_waiting_size".equals(state)) event("car_waiting_size",owner,"awaiting host dimensions");
                state = "car_waiting_size";
                return;
            }
            createReader(t,size[0],size[1]);
            event("car_capture_size",owner,size[0]+"x"+size[1]+" source="+sizeSource);
            BYPASS.set(true);
            if (t.driver==null) {
                t.ownsPresenter=true;
                String[] names = {"com.waze.car_lib.screens.d","kotlinx.coroutines.b.bn",
                        "com.waze.car_lib.i.a.h","com.waze.map.ed","com.waze.car_lib.i.a.f"};
                Class<?>[] types = new Class<?>[names.length];
                for (int i=0;i<names.length;i++) types[i]=Class.forName(names[i],false,owner.getClass().getClassLoader());
                call(presenters,"c",types,renderer,get(owner,null,"h"),t.surfaceOwner,get(owner,null,"g"),get(owner,null,"i"));
                t.driver=get(presenter,"com.waze.car_lib.i.a.s","f");
            }
            Thread executor = (Thread)get(presenter,"com.waze.car_lib.i.a.s","e");
            if (t.driver == null || executor == null || !executor.isAlive() || (Boolean)get(executor,null,"d")) {
                if (t.driver != null) call(t.driver,"e");
                throw new IllegalStateException("car_render_executor_stopped");
            }
            t.callbacks = get(t.driver,null,"k"); t.frameSource = get(t.driver,null,"a");
            call(t.driver,"k");
            target = t;
            call(t.callbacks,"b",new Class<?>[]{Surface.class,int.class,int.class,int.class},
                    t.surface,t.width,t.height,CaptureBridge.densityDpi());
            state = "waze_car_offscreen";
            event("offscreen_attached",owner,wazeIdentity(t)+" presenter="+(t.ownsPresenter ? "created" : "borrowed"));
        } catch (Throwable e) {
            failure("car_attach",e);
            try { releaseCar(t,true); } catch (Throwable cleanup) { failure("car_attach_cleanup",cleanup); }
            target=null; close(t); failedCars.put(owner,true);
        } finally { BYPASS.set(false); }
    }
    private static boolean isWaze(Target t) { return t.backend.startsWith("waze"); }
    private static void releaseCar(Target t, boolean restoreVisible) throws Exception {
        if (t.ownsPresenter) { call(t.owner,"h"); return; }
        if (t.driver==null) return;
        call(t.driver,"k"); // Only release our target; the live host still owns its manager/presenter.
        Surface real=carSurface(t.surfaceOwner);
        if (restoreVisible && real!=null && real.isValid()) {
            Object container=get(t.surfaceOwner,null,"c");
            call(t.callbacks,"b",new Class<?>[]{Surface.class,int.class,int.class,int.class},real,
                    call(container,"getWidth"),call(container,"getHeight"),call(container,"getDpi"));
        }
    }

    public static void mapsStarted(Object controller) {
        if (BYPASS.get()) return;
        mapsTransition++;
        if (failed==controller) failed=null;
        if (target != null) release("maps_resume", true);
        releaseFollowing(controller,"maps_resume");
        for (MapsFollowing item : following) if (item.map.get() == controller) item.sceneStarted();
        maps = new WeakReference<>(controller); mapsStopped = false;
        CaptureBridge.trackMaps(controller); event("maps_started", controller, mapsInfo(controller));
    }
    public static void mapsStopping(Object controller) {
        if (BYPASS.get()) return;
        maps = new WeakReference<>(controller); mapsStopped = true;
        long transition = ++mapsTransition;
        CaptureBridge.trackMaps(controller); event("maps_stopping", controller, mapsInfo(controller));
        for (MapsFollowing item : following) if (item.map.get() == controller) {
            try { item.prepareStop(); }
            catch (Throwable e) { failure("following_mode_snapshot",e); }
        }
        // E() reuses p0 internally; capture its receiver at entry, then wait for OEM stop.
        MAIN.post(() -> { if (transition == mapsTransition && maps.get() == controller) drive(); });
    }
    public static void mapsGone(Object controller) {
        mapsTransition++;
        if (target != null && target.owner == controller) release("maps_destroy", false);
        releaseFollowing(controller,"maps_destroy");
        following.removeIf(item -> item.map.get() == controller || item.owner.get() == null);
        navigationCameras.removeIf(item -> item.map.get() == controller || item.owner.get() == null);
        if (maps.get() == controller) { maps.clear(); mapsStopped = false; CaptureBridge.trackMaps(null); }
        event("maps_destroy", controller, "");
    }
    private static MapsFollowing following(Object owner) throws Exception {
        following.removeIf(item -> item.owner.get() == null || item.map.get() == null);
        for (MapsFollowing item : following) if (item.owner.get() == owner) return item;
        MapsFollowing item = new MapsFollowing(owner);
        following.add(item); return item;
    }
    private static MapsNavigationCamera navigationCamera(Object controller) throws Exception {
        navigationCameras.removeIf(item -> item.owner.get() == null || item.map.get() == null);
        for (MapsNavigationCamera item : navigationCameras) if (item.owner.get() == controller) return item;
        Object location = get(get(controller,null,"b"),"bref","u");
        MapsNavigationCamera item = new MapsNavigationCamera(controller,location);
        navigationCameras.add(item); return item;
    }
    public static void mapsNavigationStarted(Object controller) {
        if (BYPASS.get()) return;
        boolean old = BYPASS.get(); BYPASS.set(true);
        try { navigationCamera(controller).starting(); event("navigation_camera_started",controller,""); }
        catch (Throwable e) { failure("navigation_camera_start",e); }
        finally { BYPASS.set(old); }
    }
    public static boolean mapsNavigationStopping(Object controller) {
        if (BYPASS.get()) return false;
        try {
            MapsNavigationCamera item = navigationCamera(controller);
            boolean held = item.stopping(active() && maps.get() != failed,maps.get());
            event(held ? "navigation_camera_stop_deferred" : "navigation_camera_stopped",controller,item.info());
            return held;
        } catch (Throwable e) { failure("navigation_camera_stop",e); return false; }
    }
    public static void mapsNavigationState(Object controller, Object state, Object previous) {
        if (BYPASS.get()) return;
        try { navigationCamera(controller).state(state,previous); }
        catch (Throwable e) { failure("navigation_camera_state",e); }
    }
    public static void mapsNavigationUiStarted(Object ui) {
        if (BYPASS.get()) return;
        boolean old = BYPASS.get(); BYPASS.set(true);
        try {
            for (MapsNavigationCamera item : navigationCameras) if (item.tracker.get() == ui) {
                item.uiStarting(); event("navigation_ui_started",ui,item.info());
            }
        } catch (Throwable e) { failure("navigation_ui_start",e); }
        finally { BYPASS.set(old); }
    }
    public static boolean mapsNavigationUiStopping(Object ui) {
        if (BYPASS.get()) return false;
        for (MapsNavigationCamera item : navigationCameras) if (item.tracker.get() == ui) {
            boolean held = item.uiStopping(active() && maps.get() != failed,maps.get());
            event(held ? "navigation_ui_stop_deferred" : "navigation_ui_stopped",ui,item.info());
            return held;
        }
        return false;
    }
    public static boolean mapsNavigationUiDispatch(Object dispatcher, Object current, Object previous) {
        // During a capture-owned stop, bypass stopped UI consumers, but keep the
        // real tracker -> native camera calculation -> camera sink path alive.
        for (MapsNavigationCamera item : navigationCameras) if (item.dispatcher.get() == dispatcher
                && item.uiOwned && item.owned && item.stopped) {
            if (!active() || maps.get() != item.map.get() || maps.get() == failed) return true;
            try { return item.dispatch(current,previous); }
            catch (Throwable e) { failure("navigation_ui_dispatch",e); return true; }
        }
        return false;
    }
    public static void mapsNavigationApplied(Object sink) {
        for (MapsNavigationCamera item : navigationCameras) if (item.sink.get() == sink) item.applied();
    }
    public static boolean mapsFollowing(Object owner, int phase, Object request) {
        if (phase == 0) mapsFollowingStarted(owner);
        else if (phase == 1) return mapsFollowingStopping(owner);
        else if (phase == 2) mapsFollowingGone(owner);
        else if (phase == 3) {
            try {
                MapsFollowing item = following(owner);
                Object before = call(owner,"b"), requested = MapsFollowing.requestedMode(request);
                boolean internalStart = MapsFollowing.preserveMode(owner);
                boolean selected = maps.get() != null && maps.get() == item.map.get() && maps.get() != failed;
                boolean background = selected && (mapsStopped || item.stopped);
                if (!BYPASS.get() && background) item.prepareStop();
                boolean held = internalStart || (!BYPASS.get() && item.suppressOff(
                        active() && background, request));
                if (held || before != requested) event("following_mode_request",owner,
                        "from=" + before + " requested=" + requested + " deferred=" + held
                                + " reason=" + (internalStart ? "capture_start" : held ? "background_capture" : "oem")
                                + " caller=" + modeCaller());
                return held;
            } catch (Throwable e) { failure("following_mode",e); }
        }
        return false;
    }
    private static String modeCaller() {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("com.bydhud.mapcapture.") || name.startsWith("java.") || name.startsWith("dalvik.")) continue;
            if (out.length() > 0) out.append(" > ");
            out.append(name).append('.').append(frame.getMethodName());
            if (out.length() >= 240) break;
        }
        return out.toString();
    }
    private static void mapsFollowingStarted(Object owner) {
        if (BYPASS.get()) return;
        boolean old = BYPASS.get(); BYPASS.set(true);
        try { following(owner).starting(); event("following_started",owner,""); }
        catch (Throwable e) { failure("following_start",e); }
        finally { BYPASS.set(old); }
    }
    private static boolean mapsFollowingStopping(Object owner) {
        if (BYPASS.get()) return false;
        try {
            MapsFollowing item = following(owner);
            boolean held = item.stopping(active() && maps.get() != failed, maps.get());
            event(held ? "following_stop_deferred" : "following_stopped",owner,"");
            return held;
        } catch (Throwable e) { failure("following_stop",e); return false; }
    }
    private static void mapsFollowingGone(Object owner) {
        boolean old = BYPASS.get(); BYPASS.set(true);
        try {
            for (MapsFollowing item : following) if (item.owner.get() == owner) {
                if (item.map.get() != null) releaseFollowing(item.map.get(),"following_destroy");
                else item.release();
            }
        } catch (Throwable e) { failure("following_destroy",e); }
        finally { following.removeIf(item -> item.owner.get() == owner); BYPASS.set(old); }
    }
    private static void releaseFollowing(Object map, String reason) {
        boolean old = BYPASS.get(); BYPASS.set(true);
        try {
            // Stop native camera subscriptions before releasing its location driver.
            for (MapsNavigationCamera item : navigationCameras) if (item.needsRelease() && (map == null || item.map.get() == map)) {
                try { item.release(); event("navigation_camera_released",item.owner.get(),reason); }
                catch (Throwable e) { failure("navigation_camera_release",e); }
            }
            for (MapsFollowing item : following) if (item.needsRelease() && (map == null || item.map.get() == map)) {
                try { item.release(); event("following_released",item.owner.get(),reason); }
                catch (Throwable e) { failure("following_release",e); }
            }
        } finally { BYPASS.set(old); }
    }
    private static void startMaps(Object controller) {
        Target t = new Target(); t.owner = controller;
        try {
            boolean nativeMap = (Boolean)call(controller, "ac");
            t.backend = nativeMap ? "maps_impress" : "maps_gl";
            if (nativeMap) {
                Object map = call(call(controller, "as"), "c"), holder = get(map, null, "r");
                t.view = (View)get(holder, null, "a");
                t.scheduler = call(holder, "a");
                t.surfaceOwner = get(get(t.scheduler, null, "b"), null, "a");
                t.resizer = get(t.surfaceOwner, null, "g");
            } else {
                Object renderer = get(call(call(get(controller, null, "w"), "a"), "c"), null, "k");
                t.scheduler = get(renderer, null, "p");
                t.view = (View)get(renderer, null, "d");
                t.driver = get(t.view, null, "g");
                if (t.driver == null) throw new IllegalStateException("maps_no_gl_thread");
            }
            if (!t.view.isAttachedToWindow()) return;
            createReader(t); BYPASS.set(true); target = t;
            if (!nativeMap && !(Boolean)get(call(get(controller,null,"w"),"a"),null,"j")) {
                // GL/snapshot resume alone redraws a stopped scene. Reuse the map's
                // complete onStart/onStop pair, without resuming the Activity.
                t.ownsMapsScene=true;
                call(controller,"D");
                event("maps_scene_started",controller,mapsInfo(controller));
            }
            if (nativeMap) {
                call(t.surfaceOwner, "d");
                call(t.surfaceOwner, "c", new Class<?>[]{Surface.class}, t.surface);
                call(t.resizer, "b", new Class<?>[]{int.class,int.class}, t.width,t.height);
                call(t.scheduler, "e");
            } else {
                call(t.driver, "m");
                call(t.driver, "l", new Class<?>[]{Object.class}, t.surface);
                call(t.driver, "n", new Class<?>[]{int.class,int.class}, t.width,t.height);
                call(t.driver, "f");
                call(t.scheduler, "d", new Class<?>[]{boolean.class}, false);
            }
            for (MapsFollowing item : following) if (item.map.get() == controller && item.resume())
                event("following_resumed",item.owner.get(),item.info());
            for (MapsNavigationCamera item : navigationCameras) if (item.map.get() == controller && item.resume())
                event("navigation_camera_resumed",item.owner.get(),item.info());
            state = t.backend + "_offscreen"; event("offscreen_attached", controller, mapsInfo(controller));
        } catch (Throwable e) {
            failure("maps_attach", e);
            if (target == t) release("maps_attach_failed", false); else close(t);
            failed = controller;
        } finally { BYPASS.set(false); }
    }
    public static boolean mapsLost(Object driver) { return suppress(driver, "window_surface_lost"); }
    public static boolean mapsSurface(Object driver, Object surface) {
        Target t=target;
        return t != null && surface != t.surface && suppress(driver, "window_surface_available");
    }
    public static boolean mapsNativeSurface(Object owner, Surface surface) { return mapsSurface(owner, surface); }
    public static boolean mapsSize(Object driver, int width, int height) { return suppress(driver, "window_surface_resize"); }
    public static boolean mapsPause(Object driver) { return suppress(driver, "render_pause_deferred"); }
    public static boolean mapsDisable(Object scheduler, boolean disabled) { return disabled && suppress(scheduler, "snapshot_disable_deferred"); }
    private static boolean suppress(Object object, String action) {
        Target t=target;
        if (BYPASS.get() || t == null || isWaze(t)) return false;
        if (object != t.driver && object != t.scheduler && object != t.surfaceOwner && object != t.resizer) return false;
        event(action, t.owner, identity(object)); return true;
    }

    private static void release(String reason, boolean restoreVisible) {
        Target t = target; if (t == null) return;
        boolean wasBypass = BYPASS.get(); BYPASS.set(true);
        try {
            if (!isWaze(t)) releaseFollowing(t.owner,reason);
            if (t.backend.equals("waze_car")) {
                releaseCar(t,restoreVisible);
            } else if (t.backend.equals("waze")) {
                call(t.owner, "t");
            } else if (t.backend.equals("maps_gl")) {
                if (t.ownsMapsScene) {
                    t.ownsMapsScene=false;
                    try {
                        if ((Boolean)get(call(get(t.owner,null,"w"),"a"),null,"j")) call(t.owner,"E");
                        event("maps_scene_stopped",t.owner,mapsInfo(t.owner));
                    } catch (Throwable e) { failure("maps_scene_stop",e); }
                }
                call(t.scheduler, "d", new Class<?>[]{boolean.class}, true);
                call(t.driver, "g"); call(t.driver, "m");
                Object real = realHost(t.view);
                if (restoreVisible && real != null) {
                    call(t.driver, "l", new Class<?>[]{Object.class}, real);
                    call(t.driver, "n", new Class<?>[]{int.class,int.class}, t.view.getWidth(),t.view.getHeight());
                }
            } else {
                call(t.scheduler, "f"); call(t.surfaceOwner, "d");
                Object real = realHost(t.view);
                if (restoreVisible && real instanceof android.view.SurfaceHolder) {
                    call(t.surfaceOwner, "c", new Class<?>[]{Surface.class}, ((android.view.SurfaceHolder)real).getSurface());
                    call(t.resizer, "b", new Class<?>[]{int.class,int.class}, t.view.getWidth(),t.view.getHeight());
                }
            }
            event("offscreen_released", t.owner, reason);
        } catch (Throwable e) { failure("release_" + reason, e); }
        finally { target = null; close(t); BYPASS.set(wasBypass); state = reason; }
    }
    private static Object realHost(View view) {
        if (view instanceof SurfaceView) {
            android.view.SurfaceHolder holder = ((SurfaceView)view).getHolder();
            return holder.getSurface().isValid() ? holder : null;
        }
        if (view instanceof TextureView && ((TextureView)view).isAvailable()) return ((TextureView)view).getSurfaceTexture();
        return null;
    }
    private static void createReader(Target t) {
        createReader(t,t.view.getWidth(),t.view.getHeight());
    }
    private static void createReader(Target t, int width, int height) {
        if (width<1 || height<1) throw new IllegalStateException("target_size_unavailable");
        int[] size = PixelMath.size(width,height,CaptureBridge.fullSource() ? 1920 : 320);
        t.width = size[0]; t.height = size[1];
        // Pixels come from the navigator snapshot/GL hook; this consumer only drains buffers.
        // PRIVATE accepts the producer's EGL format (Maps emits RGBX, Waze RGBA).
        t.reader = ImageReader.newInstance(t.width, t.height, ImageFormat.PRIVATE, 3);
        t.surface = t.reader.getSurface();
        t.drain = new HandlerThread("MapProbeDrain"); t.drain.start();
        // Drain independently of 1Hz sampling so EGL swap cannot fill the consumer queue.
        t.reader.setOnImageAvailableListener(reader -> {
            try (Image image = reader.acquireLatestImage()) { }
            catch (RuntimeException error) {
                if (t.drainFailed) return;
                t.drainFailed=true;
                MAIN.post(() -> { if (target == t) {
                    if (t.backend.equals("waze_car")) failedCars.put(t.owner,true);
                    failure("reader_drain",error); failed=t.owner; release("reader_drain_failed",true);
                }}); // Ignore a queued callback from an already closed target.
            }
        }, new Handler(t.drain.getLooper()));
    }
    private static void close(Target t) {
        try { if (t.reader != null) t.reader.close(); }
        catch (Throwable e) { failure("reader_close",e); }
        finally { t.reader=null; if (t.drain != null) { t.drain.quitSafely(); t.drain=null; } }
    }

    public static String mapsInfo(Object controller) {
        try {
            boolean nativeMap = (Boolean)call(controller,"ac");
            View view = (View)call(controller,"j");
            String info = "backend=" + (nativeMap ? "impress" : "legacy_gl") + " view=" + identity(view)
                    + " attached=" + view.isAttachedToWindow() + " shown=" + view.isShown();
            if (!nativeMap) {
                Object container=call(get(controller,null,"w"),"a"), scene=call(container,"c");
                Object renderer = get(scene,null,"k");
                info += " containerStarted="+get(container,null,"j")+" sceneStarted="+get(scene,null,"c");
                info += " snapshotDisabled=" + get(get(renderer,null,"h"),null,"a");
                Object thread = get(view,null,"g");
                if (thread != null) info += " paused=" + get(thread,null,"c") + " hasSurface=" + get(get(thread,null,"m"),null,"b");
            }
            boolean found = false;
            for (MapsFollowing item : following) if (item.map.get() == controller) {
                found = true;
                try {
                    info += " followingOwned=" + item.owned + " followingStopped=" + item.stopped;
                    // Keep camera/location details out of mapsState's existing 512-char cap.
                    event("following_state",item.owner.get(),item.info());
                }
                catch (Throwable e) { info += " followingStateError=" + e; }
            }
            for (MapsNavigationCamera item : navigationCameras) if (item.map.get() == controller)
                event("navigation_camera_state",item.owner.get(),item.info());
            return info + (found ? "" : " following=untracked");
        } catch (Throwable e) { return "state_error=" + e; }
    }
    public static Bundle metadata() {
        Bundle data = new Bundle(); data.putString("backgroundState",state);
        data.putString("carReadiness",carReadiness);
        synchronized (EVENTS) { data.putString("lifecycleEvents",new JSONArray(EVENTS).toString()); data.putLong("lifecycleSequence",sequence); }
        return data;
    }
    public static void frame(Object source) {
        Target t=target; if (t==null || t.rendered) return;
        try {
            if (source!=t.owner && source!=t.frameSource) return;
            t.rendered=true; state=t.backend+"_frame_received";
            event("offscreen_frame_received",t.owner,isWaze(t) ? wazeIdentity(t) : t.backend);
        } catch (Throwable e) { failure("frame_identity",e); }
    }
    public static void wazeFrame(Object source) {
        Target t=target;
        if (t==null || source!=t.frameSource) {
            lastVisibleWazeFrame=SystemClock.elapsedRealtime();
            lastVisibleWazeSource=wazeSource(source);
            if (t!=null && isWaze(t) && !t.yielding) {
                t.yielding=true;
                MAIN.post(() -> { if (target==t) {
                    event("visible_renderer_takes_over",source,""); release("visible_renderer",false);
                }});
            }
        }
        frame(source);
    }
    public static void acknowledged(long through) {
        synchronized (EVENTS) { while (!EVENTS.isEmpty() && EVENTS.peek().optLong("sequence") <= through) EVENTS.remove(); }
    }
    private static void event(String name, Object owner, String detail) {
        try { synchronized (EVENTS) {
            JSONObject entry = new JSONObject().put("sequence",++sequence).put("elapsedNs",SystemClock.elapsedRealtimeNanos())
                    .put("event",name).put("owner",identity(owner)).put("detail",detail);
            if (EVENTS.size() == 128) EVENTS.remove(); EVENTS.add(entry);
        }} catch (Throwable ignored) { }
    }
    private static void failure(String action, Throwable e) {
        state = action + ": " + e;
        String trace=android.util.Log.getStackTraceString(e);
        event("background_error",null,action+": "+trace.substring(0,Math.min(4096,trace.length())));
    }
    private static String identity(Object value) { return value == null ? "none" : value.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(value)); }
    private static Object get(Object object, String owner, String name) throws Exception {
        return MapsFollowing.get(object,owner,name);
    }
    private static Object call(Object object, String name) throws Exception { return call(object,name,NONE); }
    private static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        return MapsFollowing.call(object,name,types,args);
    }
}
