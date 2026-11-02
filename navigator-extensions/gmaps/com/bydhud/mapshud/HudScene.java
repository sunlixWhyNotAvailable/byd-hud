package com.bydhud.mapshud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.ContextThemeWrapper;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import com.bydhud.mapcapture.MapPalette;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Version-pinned second-scene experiment, executed with the Maps app's UID. */
public final class HudScene {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadLocal<Boolean> CREATING = ThreadLocal.withInitial(() -> false);
    private static final Map<Object, Boolean> OWNED = new WeakHashMap<>();
    private static final Map<Object, Boolean> VIEWS = new WeakHashMap<>();
    private static WeakReference<Object> tablet = new WeakReference<>(null);
    private static Object factory, component;
    private static volatile Scene scene;
    private static MapPalette requestedPalette = MapPalette.DEFAULT;
    private static long leaseUntil;
    private static boolean tickScheduled, failed;
    private static String state = "waiting_for_hud";
    private static long nextGeneration;
    private static long lastDestroyedGeneration;
    private static final int WIDTH = 640, HEIGHT = 384;
    private static final int TRACE_GATE = 1, TRACE_DISPATCH = 2, TRACE_TARGET = 4, TRACE_SAMPLE = 8, TRACE_WRITE = 16,
            TRACE_RENDER_FRAME_ENTRY = 32, TRACE_RENDER_FRAME_RETURN = 64;
    private static final long CAMERA_TRACE_INTERVAL_MS=2000L, CAMERA_TRACE_WINDOW_MS=2000L;

    private static final class PendingSnapshot {
        final Scene scene;
        final Bundle request;
        final long generation, requestId, requiredCameraCommandId, captureFrame;
        final AtomicBoolean delivered=new AtomicBoolean(), queued=new AtomicBoolean();
        volatile Object proxy;
        volatile long lastQueuedFrame;
        volatile long callbackFrame, callbackCommandId, callbackTargetCommandId, callbackRevision;
        volatile Object callbackTarget, callbackPose, callbackReadbackPose;
        volatile Throwable callbackObservationFailure;
        volatile boolean callbackOnGlThread, callbackFrameApplicationMatch;
        volatile int attempts;
        final boolean traceSelected;
        final long traceId, traceFrame;
        PendingSnapshot(Scene scene,Bundle request,long requestId,long requiredCameraCommandId,long captureFrame,
                boolean traceSelected,long traceId,long traceFrame) {
            this.scene=scene;this.request=request;this.generation=scene.generation;this.requestId=requestId;
            this.requiredCameraCommandId=requiredCameraCommandId;this.captureFrame=captureFrame;
            this.traceSelected=traceSelected;this.traceId=traceId;this.traceFrame=traceFrame;
        }
    }

    private static final class CameraTarget {
        final long commandId;
        final Object pose;
        CameraTarget(long commandId,Object pose) { this.commandId=commandId;this.pose=pose; }
    }

    static final class CameraCommands {
        private static final class Command extends WeakReference<Object> {
            final long id;
            Command(Object pose,long id) { super(pose);this.id=id; }
        }
        private final List<Command> pending=new ArrayList<>();
        synchronized void record(Object pose,long id) {
            for(int i=pending.size()-1;i>=0;i--) {
                Object key=pending.get(i).get();
                if(key==null || key==pose) pending.remove(i);
            }
            // The stock gate owns queued commands; superseded commands need no strong owner here.
            pending.add(new Command(pose,id));
        }
        synchronized long take(Object pose) {
            for(int i=pending.size()-1;i>=0;i--) {
                Command command=pending.get(i);
                Object key=command.get();
                if(key==null) pending.remove(i);
                else if(key==pose) {
                    pending.remove(i);
                    return command.id;
                }
            }
            return -1L;
        }
        synchronized void clear() { pending.clear(); }
    }

    private static final class Scene {
        final MapPalette palette = requestedPalette;
        final long generation = ++nextGeneration;
        final long previousGeneration = lastDestroyedGeneration;
        Object map, surfaceOwner, renderer, container;
        Object cameraController, cameraGate, cameraConsumer, cameraAnimator;
        volatile Object hudContext, cameraRegistrationOwner, cameraRegistrationProduct, cameraRegistrationController, cameraRegistrationAnimator;
        volatile String cameraRegistrationPrevious = "unobserved";
        volatile Throwable cameraRegistrationFailure;
        volatile boolean cameraRegistrationApplied;
        volatile Object snapshotQueue;
        volatile Object cameraBackend;
        boolean lowGl;
        HudPresentation presentation;
        volatile HudCamera follow;
        Thread glThread;
        View view;
        ImageReader reader;
        HandlerThread drain;
        Method cameraReadback;
        Object pose, seedPose, hudSeedPose, lastSourcePose, lastOwnedPose;
        boolean started, stopping, pending, ready, cleanupFailed;
        long frames, sourcePoseRevision, ownedReadbackRevision, setterCalls, lastCameraNoteMs;
        String lastCameraSignature, lastObservationError;
        Boolean lastTargetMatch, lastLoggedTargetMatch;
        final CameraCommands cameraCommands = new CameraCommands();
        volatile long activeCameraCommandId, lastGateCommandId, lastDispatchCommandId, lastTargetCommandId;
        volatile long latestCameraCommandId, lastSampledCameraCommandId=-1L;
        volatile long lastRendererFrameSequence, lastRendererFrameCommandId=-1L, lastRendererFrameTargetCommandId=-1L;
        volatile long lastRendererFrameRevision, cameraFrameEntryRevision, lastPoseWriteCommandId=-1L;
        volatile Object lastSampledCameraTargetPose, lastRendererFrameTargetPose, lastRendererFramePose;
        volatile Object lastPoseWriteEffectivePose;
        volatile boolean lastRendererFrameApplicationMatch;
        volatile CameraTarget acceptedCameraTarget;
        volatile long nextCameraCommandId, activeCaptureRequestId=-1L, traceRequestId = -1L, traceSnapshotSeq;
        volatile long traceWindowEndsMs, nextCameraTraceMs;
        volatile long traceStartGateCalls,traceStartDispatchCalls,traceStartTargetCalls,traceStartSampleCalls,traceStartPoseWriteCalls,
                traceStartRenderFrameEntries,traceStartRenderFrameReturns;
        final AtomicLong traceSampleCount=new AtomicLong();
        final AtomicInteger traceMask = new AtomicInteger();
        final AtomicLong cameraSampleSequence = new AtomicLong(), cameraPoseRevision = new AtomicLong();
        final AtomicLong cameraGateCalls=new AtomicLong(), cameraDispatchCalls=new AtomicLong(), cameraTargetCalls=new AtomicLong();
        final AtomicLong cameraSampleCalls=new AtomicLong(), cameraPoseWriteCalls=new AtomicLong();
        final AtomicLong cameraRenderFrameEntries=new AtomicLong(),cameraRenderFrameReturns=new AtomicLong();
        volatile PendingSnapshot pendingSnapshot;
        volatile boolean traceActive;
        volatile boolean traceTimeoutWon, lastGateCompleted, lastDispatchCompleted;
        Object lastRendererPose;
        volatile Object lastGatePose,lastDispatchPose,lastTargetPose,lastSampledPose,lastPoseWriteTarget;
        volatile String lastGateSummary = "unobserved", lastDispatchSummary = "unobserved", lastTargetSummary = "unobserved";
        String lastCameraTraceError;
    }
    private static void main(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else MAIN.post(r);
    }
    public static void builder(Object b) {
        if (CREATING.get()) return;
        try { Object f=get(b,"p"), c=get(get(b,"u"),"a"); main(() -> { factory=f; component=c; }); }
        catch (Throwable e) { error("factory_observer",e); }
    }
    public static void paint(Object builder,Object map) throws Throwable {
        if(CREATING.get()) {
            scene.map=map;
            synchronized(OWNED) { OWNED.put(map,true); }
            try { call(builder,"bydHudOriginal_b",map); }
            catch(Throwable e) { throw root(e); }
            return;
        }
        if(!owned(map) && !CREATING.get()) {
            try { call(builder,"bydHudOriginal_b",map); }
            catch(Throwable e) { throw root(e); }
            return;
        }
        main(() -> {
            Scene s=scene;
            if(s==null || s.stopping || s.map!=map) return;
            try { call(builder,"bydHudOriginal_b",map); }
            catch(Throwable e) { fail("hud_initial_paint",e); }
        });
    }
    public static void track(Object map) {
        if (map == null) return;
        if (CREATING.get()) { synchronized (OWNED) { OWNED.put(map,true); } return; }
        main(() -> {
            if (owned(map)) return;
            Object previous=tablet.get();
            if (previous != map) {
                tablet=new WeakReference<>(map);
                failed=false;
                event("tablet_selected",map,"");
            }
        });
    }
    public static void started(Object map) { track(map); }
    public static void stopping(Object map) {
        if (!owned(map)) event("tablet_stopped",map,"HUD does not resume or take its Surface");
    }
    public static void gone(Object map) {
        if (owned(map)) return;
        main(() -> {
            if (tablet.get()!=map) return;
            tablet.clear(); failed=false;
        });
    }
    public static void lease(boolean active) {
        main(() -> {
            try {
                leaseUntil=active ? SystemClock.elapsedRealtime()+((Number)bridge("leaseMs")).longValue() : 0;
                if (!active) { failed=false; stop("lease_end"); }
                else drive();
                schedule();
            } catch(Throwable e) { fail("lease",e); }
        });
    }
    private static boolean active() { return SystemClock.elapsedRealtime()<leaseUntil; }
    public static void palette(int[] wire) {
        MapPalette next = MapPalette.decode(wire);
        main(() -> { requestedPalette = next; });
    }
    static MapPalette paletteFor(Object map) {
        Scene s = scene;
        return s != null && s.map == map ? s.palette : MapPalette.DEFAULT;
    }
    private static void schedule() {
        if (tickScheduled || (!active() && scene==null) || (scene!=null && scene.cleanupFailed)) return;
        tickScheduled=true;
        MAIN.postDelayed(() -> {
            tickScheduled=false;
            try { if (!active()) stop("lease_expired"); else drive(); }
            catch(Throwable e) { fail("drive",e); }
            schedule();
        },250);
    }
    private static void drive() throws Throwable {
        if (!active() || failed) return;
        if (scene != null && !scene.palette.equals(requestedPalette)) {
            stop("palette_changed");
            if (scene != null) return;
        }
        if (scene==null) {
            Object source=tablet.get();
            if (factory==null || source==null) { state="waiting_for_tablet_map"; return; }
            if ((Boolean)call(factory,"i")) throw new IllegalStateException("Phoenix/GL test: configured engine is GeoXP");
            create(source);
        }
        Scene s=scene;
        if (s==null || s.stopping || !s.started) return;
        if(s.presentation!=null) s.presentation.tick();
    }
    private static void create(Object source) throws Throwable {
        Scene s=new Scene(); scene=s; state="creating";
        CREATING.set(true);
        try {
            Context sourceContext=(Context)get(source,"a");
            Context app=sourceContext.getApplicationContext();
            Display display=((DisplayManager)app.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
            if (display==null) throw new IllegalStateException("default_display_unavailable");
            ContextThemeWrapper context=new ContextThemeWrapper(app.createDisplayContext(display),0);
            s.hudContext=context;
            // Route presenters need Maps theme attributes; copy without sharing the tablet's Theme.
            context.getTheme().setTo(sourceContext.getTheme());
            Object b=call(factory,"j");
            set(b,"a",context); set(b,"b",call(call(type("bofu"),"a"),"a"));
            set(b,"l",2); set(b,"i",true); set(b,"j",get(type("cnex"),"h"));
            Object size=type("bygo").getConstructor().newInstance();
            call(size,"b",new Point(WIDTH,HEIGHT)); set(b,"n",size);
            s.seedPose=call(call(source,"m"),"a");
            s.hudSeedPose=centeredHudPose(s.seedPose);
            set(b,"k",s.hudSeedPose);
            observeSeedBeforeBuild(s,source);
            s.map=call(b,"a");
            synchronized(OWNED) { OWNED.put(s.map,true); }
            if ((Boolean)call(s.map,"ac")) throw new IllegalStateException("HUD factory did not select Phoenix/GL");
            call(call(s.map,"i"),"f");
            Object flags=call(s.map,"af");
            if ((Boolean)call(flags,"B") && !(Boolean)call(flags,"R")) call(s.map,"A");
            call(size,"b",new Point(WIDTH,HEIGHT));
            HudStyle.prepare(); // Allocate the shared blank shield before Phoenix can enter its render callbacks.
            s.container=call(s.map,"c");
            s.renderer=get(call(s.container,"c"),"k");
            s.view=(View)call(s.map,"j");
            synchronized(VIEWS) { VIEWS.put(s.view,true); }
            s.surfaceOwner=get(s.view,"g");
            s.view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT,View.MeasureSpec.EXACTLY));
            s.view.layout(0,0,WIDTH,HEIGHT);
            observeCamera(s,"after_view_sized",source,s.seedPose,true);
            s.reader=ImageReader.newInstance(WIDTH,HEIGHT,ImageFormat.PRIVATE,3);
            s.drain=new HandlerThread("MapsHudDrain"); s.drain.start();
            s.reader.setOnImageAvailableListener(reader -> {
                try (Image image=reader.acquireLatestImage()) { }
                catch(Throwable e) { MAIN.post(() -> { if(scene==s && !s.stopping) fail("surface_drain",e); }); }
            },new Handler(s.drain.getLooper()));
            call(s.surfaceOwner,"l",s.reader.getSurface());
            call(s.surfaceOwner,"n",WIDTH,HEIGHT);
            call(s.map,"X",Enum.valueOf((Class)type("cnfm"),"NAVIGATION_LOW_LIGHT"));
            HudStyle.register(s.map,s.container,s.renderer);
            // D can partially start before throwing; E must still be attempted.
            s.started=true; call(s.map,"D");
            bindCameraTrace(s);
            s.glThread=(Thread)get(s.surfaceOwner,"t");
            s.presentation=new HudPresentation(component,s.map,s.container,size,s.view);
            s.presentation.start();
            set(type("com.bydhud.mapcapture.CaptureBridge"),"maps",new WeakReference<>(s.map));
            state="waiting_for_first_snapshot";
            observeCameraFromSource(s,"after_scene_started",source,true);
            event("hud_created",s.map,"Phoenix/GL generation="+s.generation+" previous="+s.previousGeneration+" size="+WIDTH+"x"+HEIGHT+" tablet="+identity(source)+" palette="+s.palette);
        } finally { CREATING.set(false); }
    }
    private static void stop(String reason) {
        Scene s=scene;
        if(s==null) return;
        if(s.cleanupFailed) return;
        if(!s.stopping) {
            s.stopping=true; state="stopping:"+reason;
            try { set(type("com.bydhud.mapcapture.CaptureBridge"),"maps",new WeakReference<>(null)); }
            catch(Throwable e) { error("clear_capture_owner",e); }
            event("hud_stop",s.map,reason+" pending="+s.pending);
            try { if(s.presentation!=null)s.presentation.stop(); }
            catch(Throwable e) { error("presentation_stop",e); s.cleanupFailed=true; return; }
            try { if(s.renderer!=null)call(get(s.renderer,"h"),"c",true); }
            catch(Throwable e) { error("snapshot_cancel",e); s.cleanupFailed=true; return; }
        }
        // Phoenix completes queued captures with null; retain ownership until delivered.
        if(s.pending) return;
        destroy(s);
    }
    private static void destroy(Scene s) {
        if(scene!=s) return;
        observeCameraFromSource(s,"before_destroy",tablet.get(),true);
        if(s.started) {
            s.started=false;
            try { call(s.map,"E"); }
            catch(Throwable e) { error("hud_stop_cleanup",e); }
        }
        boolean destroyed=false;
        try {
            if(s.map!=null) {
                call(s.map,"B");
            }
            destroyed=true;
        } catch(Throwable e) { s.cleanupFailed=true; error("hud_destroy",e); state="cleanup_failed"; failed=true; }
        if(!destroyed) return; // Preserve the Surface if native destruction was not confirmed.
        try { if(s.reader!=null) { s.reader.close(); s.reader=null; } }
        catch(Throwable e) { error("reader_close",e); }
        finally { if(s.drain!=null) { s.drain.quitSafely(); s.drain=null; } }
        HudStyle.unregister(s.map);
        s.cameraCommands.clear();
        scene=null;
        if(!failed) state="idle";
        event("hud_destroyed",s.map,"generation="+s.generation+" frames="+s.frames);
        lastDestroyedGeneration=s.generation;
    }

    private static boolean owned(Object o) { synchronized(OWNED) { return OWNED.containsKey(o); } }

    public static void capture(Bundle request) {
        Scene s=scene;
        if(!active() || s==null || s.stopping || !s.started || !s.palette.equals(requestedPalette) || s.follow==null || !s.follow.ready()) {
            finish(request,null,"waiting_for_owned_camera_input:"+state,s); return;
        }
        try {
            if(!s.cameraRegistrationApplied || s.cameraController==null || s.cameraAnimator==null
                    || get(s.cameraController,"d")!=s.cameraAnimator || get(s.cameraBackend,"L")!=s.cameraController) {
                Throwable failure=s.cameraRegistrationFailure!=null?s.cameraRegistrationFailure:
                        new IllegalStateException("HUD camera handler registration no longer matches its renderer");
                finish(request,null,"camera_registration_lost:"+root(failure),s);
                main(() -> { if(scene==s && !s.stopping)fail("camera_registration_lost",failure); });
                return;
            }
        } catch(Throwable e) {
            Throwable failure=root(e);
            finish(request,null,"camera_registration_check:"+failure,s);
            main(() -> { if(scene==s && !s.stopping)fail("camera_registration_check",failure); });
            return;
        }
        if(s.pending) { finish(request,null,"snapshot_waiting_for_previous_callback",s); return; }
        request.putIntArray("hudPaletteApplied", s.palette.encode());
        s.pending=true;
        long requestId=request.getLong("id",-1L);
        s.activeCaptureRequestId=requestId;
        long captureFrame=s.frames+1;
        boolean selectedTrace=startCameraTrace(s,requestId,captureFrame);
        final long traceId=selectedTrace?requestId:-1L;
        final long traceFrame=selectedTrace?s.traceSnapshotSeq:captureFrame;
        s.traceTimeoutWon=false;
        PendingSnapshot pending=new PendingSnapshot(s,request,requestId,s.latestCameraCommandId,captureFrame,
                selectedTrace,traceId,traceFrame);
        pending.lastQueuedFrame=s.cameraRenderFrameReturns.get();
        s.pendingSnapshot=pending;
        try {
            request.putString("mapsState","independent-hud-phoenix-r2 generation="+s.generation+" state="+state
                    +" nextFrameAtReq="+(s.frames+1)+" requestId="+request.getLong("id",-1L)
                    +" sourcePoseRevAtReq="+s.sourcePoseRevision+" ownedReadbackRevAtReq="+s.ownedReadbackRevision
                    +" setterCallsAtReq="+s.setterCalls+" sourceNullAtReq="+(s.lastSourcePose==null?1:0)
                    +" ownedNullAtReq="+(s.lastOwnedPose==null?1:0)+" targetEqAtReq="+targetMatch(s.lastTargetMatch)
                    +" requiredCameraCommand="+pending.requiredCameraCommandId
                    +" sampledCameraCommand="+s.lastSampledCameraCommandId
                    +" snapshotQueue="+identity(s.snapshotQueue)
                    +" registeredAnimator="+(s.cameraRegistrationApplied && s.cameraController!=null
                            && get(s.cameraController,"d")==s.cameraAnimator?1:0));
            Class<?> callback=type("boon");
            Object proxy=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(p,m,a) -> {
                if(m.getDeclaringClass()==Object.class) {
                    if(m.getName().equals("hashCode")) return System.identityHashCode(p);
                    if(m.getName().equals("equals")) return p==a[0];
                    return "MapsHudSnapshot";
                }
                if(!m.getName().equals("a")) return null;
                Bitmap copy=null;
                Throwable failure=null;
                pending.callbackFrame=-1L;pending.callbackCommandId=-1L;pending.callbackTargetCommandId=-1L;
                pending.callbackRevision=-1L;pending.callbackTarget=null;pending.callbackPose=null;
                pending.callbackReadbackPose=null;pending.callbackObservationFailure=null;
                pending.callbackOnGlThread=false;pending.callbackFrameApplicationMatch=false;
                try { if(a!=null && a.length==1 && a[0] instanceof Bitmap) copy=((Bitmap)a[0]).copy(Bitmap.Config.ARGB_8888,false); }
                catch(Throwable e) { failure=e; }
                pending.callbackFrame=s.lastRendererFrameSequence;
                pending.callbackCommandId=s.lastRendererFrameCommandId;
                pending.callbackTargetCommandId=s.lastRendererFrameTargetCommandId;
                pending.callbackRevision=s.lastRendererFrameRevision;
                pending.callbackTarget=s.lastRendererFrameTargetPose;
                pending.callbackPose=s.lastRendererFramePose;
                pending.callbackFrameApplicationMatch=s.lastRendererFrameApplicationMatch;
                pending.callbackOnGlThread=Thread.currentThread()==s.glThread;
                try { pending.callbackReadbackPose=readCameraPose(s); }
                catch(Throwable e) { pending.callbackObservationFailure=root(e);cameraTraceError(s,"snapshot_pose_ack",e); }
                final Bitmap result=copy; final Throwable problem=failure;
                MAIN.post(() -> {
                    handleSnapshotCallback(pending,result,problem);
                });
                return null;
            });
            pending.proxy=proxy;
            request.putLong("captureStartNs",SystemClock.elapsedRealtimeNanos());
            MAIN.postDelayed(() -> snapshotTimeout(pending),Math.max(100,Math.min(30000,request.getLong("frameTimeoutMs",2500))));
            queueSnapshot(pending,"initial");
        } catch(Throwable e) {
            s.pending=false;s.pendingSnapshot=null;if(s.activeCaptureRequestId==requestId)s.activeCaptureRequestId=-1L; error("snapshot_request",e);
            if(selectedTrace && s.traceRequestId==requestId)s.traceActive=false;
            if(pending.delivered.compareAndSet(false,true)) finish(request,null,"snapshot_request:"+e,s);
        }
    }

    private static void queueSnapshot(PendingSnapshot pending,String reason) {
        Scene s=pending.scene;
        if(scene!=s || s.stopping || pending.delivered.get() || pending.queued.get()) return;
        pending.lastQueuedFrame=s.cameraRenderFrameReturns.get();
        pending.queued.set(true);
        pending.attempts++;
        try {
            call(s.map,"L",pending.proxy);
            if(pending.attempts>1)event("hud_snapshot_camera_requeued",s.map,"generation="+pending.generation
                    +" requestId="+pending.requestId+" reason="+reason+" attempt="+pending.attempts
                    +" requiredCommand="+pending.requiredCameraCommandId+" previousFrame="+pending.lastQueuedFrame);
        } catch(Throwable e) {
            pending.queued.set(false);
            if(pending.delivered.compareAndSet(false,true)) {
                clearPendingSnapshot(pending);
                error("snapshot_request",e);
                finish(pending.request,null,"snapshot_request:"+root(e),s);
                if(s.stopping)destroy(s);
            }
        }
    }

    private static void handleSnapshotCallback(PendingSnapshot pending,Bitmap result,Throwable problem) {
        Scene s=pending.scene;
        pending.queued.set(false);
        boolean current=scene==s && s.generation==pending.generation && s.pendingSnapshot==pending && !s.stopping && active();
        if(pending.delivered.get()) {
            clearPendingSnapshot(pending);
            if(result!=null)result.recycle();
            if(s.stopping)destroy(s);
            return;
        }
        if(problem!=null) {
            if(result!=null)result.recycle();
            completeSnapshot(pending,null,"snapshot_copy:"+root(problem),problem);
            return;
        }
        if(!current || result==null) {
            completeSnapshot(pending,result,current?"null_snapshot":"hud_generation_ended",null);
            return;
        }
        boolean applied=false;
        try {
            applied=s.follow!=null && s.follow.acceptsInput(pending.callbackCommandId) && pending.callbackOnGlThread
                    && pending.callbackFrame>pending.lastQueuedFrame
                    && pending.callbackCommandId>=pending.requiredCameraCommandId
                    && pending.callbackTargetCommandId>=pending.requiredCameraCommandId
                    && pending.callbackFrameApplicationMatch
                    && cameraPoseEquivalent(pending.callbackTarget,pending.callbackPose)
                    && cameraPoseEquivalent(pending.callbackPose,pending.callbackReadbackPose)
                    && pending.callbackObservationFailure==null;
        } catch(Throwable e) {
            pending.callbackObservationFailure=root(e);
            cameraTraceError(s,"snapshot_pose_ack_validate",e);
        }
        if(!applied) {
            result.recycle();
            event("hud_snapshot_camera_frame_rejected",s.map,"generation="+pending.generation+" requestId="+pending.requestId
                    +" requiredCommand="+pending.requiredCameraCommandId+" callbackFrame="+pending.callbackFrame
                    +" previousFrame="+pending.lastQueuedFrame+" sampledCommand="+pending.callbackCommandId
                    +" targetCommand="+pending.callbackTargetCommandId+" glThread="+(pending.callbackOnGlThread?1:0)
                    +" qdeEquivalent="+cameraPoseEquivalentDescription(pending)+" callbackObservationError="
                    +(pending.callbackObservationFailure==null?"none":pending.callbackObservationFailure));
            queueSnapshot(pending,"camera_frame_not_applied");
            return;
        }
        completeSnapshot(pending,result,"ok",null);
    }

    private static String cameraPoseEquivalentDescription(PendingSnapshot pending) {
        try { return (cameraPoseEquivalent(pending.callbackTarget,pending.callbackPose)?1:0)+"/"
                +(cameraPoseEquivalent(pending.callbackPose,pending.callbackReadbackPose)?1:0); }
        catch(Throwable e) { return "error:"+root(e); }
    }

    private static void completeSnapshot(PendingSnapshot pending,Bitmap result,String status,Throwable failure) {
        Scene s=pending.scene;
        boolean callbackWon=pending.delivered.compareAndSet(false,true);
        boolean current=scene==s && s.generation==pending.generation && !s.stopping && active();
        boolean published=callbackWon && current && result!=null && "ok".equals(status);
        clearPendingSnapshot(pending);
        if(callbackWon) {
            if(failure!=null)error("snapshot_copy",failure);
            if(published) {
                s.frames++; if(!s.ready) {
                    s.ready=true; state="ready";
                    String firstFrame="first_ready_frame requestId="+pending.requestId+" frame="+s.frames;
                    observeCameraFromSource(s,firstFrame,tablet.get(),true);
                    event("hud_first_frame",s.map,"generation="+s.generation+" requestId="+pending.requestId+" frame="+s.frames
                            +" cameraCommand="+pending.callbackCommandId+" rendererFrame="+pending.callbackFrame
                            +" qdeWriteRevision="+pending.callbackRevision);
                }
                finish(pending.request,result,"ok",s);
            } else {
                if(result!=null)result.recycle();
                finish(pending.request,null,status,s);
            }
        } else if(result!=null)result.recycle();
        if(pending.traceSelected)event("hud_camera_trace_result",s.map,"generation="+pending.generation+" requestId="+pending.traceId
                +" snapshotSeq="+pending.traceFrame+" callbackBitmap="+(result==null?0:1)+" published="+(published?1:0)
                +" callbackWon="+(callbackWon?1:0)+" timeoutWon="+(s.traceTimeoutWon?1:0)
                +" requiredCommand="+pending.requiredCameraCommandId+" callbackCommand="+pending.callbackCommandId
                +" rendererFrame="+pending.callbackFrame+" sampledCommand="+s.lastSampledCameraCommandId
                +" sampleCalls="+s.traceSampleCount.get()+" stages="+s.traceMask.get()
                +" gateDelta="+(s.cameraGateCalls.get()-s.traceStartGateCalls)
                +" dispatchDelta="+(s.cameraDispatchCalls.get()-s.traceStartDispatchCalls)
                +" targetDelta="+(s.cameraTargetCalls.get()-s.traceStartTargetCalls)
                +" sampleDelta="+(s.cameraSampleCalls.get()-s.traceStartSampleCalls)
                +" qdeWriteDelta="+(s.cameraPoseWriteCalls.get()-s.traceStartPoseWriteCalls)
                +" renderFrameEntryDelta="+(s.cameraRenderFrameEntries.get()-s.traceStartRenderFrameEntries)
                +" renderFrameReturnDelta="+(s.cameraRenderFrameReturns.get()-s.traceStartRenderFrameReturns)
                +" qdeWriteRevision="+s.cameraPoseRevision.get());
        if(s.stopping)destroy(s);
    }

    private static void clearPendingSnapshot(PendingSnapshot pending) {
        Scene s=pending.scene;
        if(s.pendingSnapshot==pending) {
            s.pendingSnapshot=null;
            s.pending=false;
        }
        if(s.activeCaptureRequestId==pending.requestId)s.activeCaptureRequestId=-1L;
    }

    private static void snapshotTimeout(PendingSnapshot pending) {
        Scene s=pending.scene;
        if(!pending.delivered.compareAndSet(false,true))return;
        s.traceTimeoutWon=true;
        event("hud_snapshot_timeout",s.map,"generation="+pending.generation+" requestId="+pending.requestId
                +" snapshotSeq="+pending.captureFrame+" requiredCommand="+pending.requiredCameraCommandId
                +" sampledCommand="+s.lastSampledCameraCommandId+" attempts="+pending.attempts
                +" pending="+(s.pending?1:0)+" recovery=owned_stop_cancel");
        finish(pending.request,null,"snapshot_callback_timeout",s);
        if(scene==s && s.generation==pending.generation && s.pending && s.activeCaptureRequestId==pending.requestId) {
            if(!pending.queued.get())clearPendingSnapshot(pending);
            stop("snapshot_callback_timeout");
        }
    }
    private static void finish(Bundle b,Bitmap bitmap,String status,Scene s) {
        try {
            Class<?> bridge=type("com.bydhud.mapcapture.CaptureBridge");
            Method m=bridge.getDeclaredMethod("finish",Bundle.class,Bitmap.class,String.class,String.class,int.class,int.class);
            m.setAccessible(true);
            Handler worker=(Handler)get(bridge,"worker");
            if(com.bydhud.mapcapture.LifecycleJournalTransport.detailed())
                b.putLong("producerFinishEnqueueNs",SystemClock.elapsedRealtimeNanos());
            if(worker==null || !worker.post(() -> {
                try { m.invoke(null,b,bitmap,status,s==null?"maps":identity(s.map),bitmap==null?0:bitmap.getWidth(),bitmap==null?0:bitmap.getHeight()); }
                catch(Throwable e) { transportFailure(bitmap,e); }
            })) throw new IllegalStateException("capture_worker_rejected_delivery");
        } catch(Throwable e) {
            transportFailure(bitmap,e);
        }
    }
    private static void transportFailure(Bitmap bitmap,Throwable e) {
        if(bitmap!=null && !bitmap.isRecycled()) bitmap.recycle(); error("capture_transport",e);
        try { ((AtomicBoolean)get(type("com.bydhud.mapcapture.CaptureBridge"),"BUSY")).set(false); }
        catch(Throwable secondary) { error("capture_transport_cleanup",secondary); }
    }
    private static void fail(String stage,Throwable e) { failed=true; state=stage+":"+root(e); error(stage,e); stop(stage); }
    private static Throwable root(Throwable e) { return Reflect.root(e); }
    static void error(String stage,Throwable e) { Log.e("MapsHudScene",stage,root(e)); event("hud_error",null,stage+"\n"+Log.getStackTraceString(root(e))); }
    static void event(String name,Object owner,String detail) {
        try { call(type("com.bydhud.mapcapture.BackgroundCapture"),"event",name,owner,detail); }
        catch(Throwable e) { Log.e("MapsHudScene","journal:"+name+" "+detail,root(e)); }
    }
    private static String identity(Object o) { return o==null?"none":o.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(o)); }
    private static Object bridge(String m) throws Exception { return call(type("com.bydhud.mapcapture.CaptureBridge"),m); }
    private static void observeCameraFromSource(Scene s,String phase,Object source,boolean force) {
        try { observeCamera(s,phase,source,source==null?null:call(call(source,"m"),"a"),force); }
        catch(Throwable e) { cameraObservationError(s,phase,e); }
    }
    private static void observeSeedBeforeBuild(Scene s,Object source) {
        if(!com.bydhud.mapcapture.LifecycleJournalTransport.detailed())return;
        try {
            event("hud_camera_seed",source,"phase=before_builder generation="+s.generation+" previous="+s.previousGeneration
                    +" seedNull="+(s.seedPose==null?1:0)+" sourceSeed={"+cameraFields(s.seedPose)+"}"
                    +" hudSeed={"+cameraFields(s.hudSeedPose)+"} sourceView={"+mapViewport(source)+"}");
        } catch(Throwable e) { cameraObservationError(s,"before_builder_seed",e); }
    }
    private static void observeCamera(Scene s,String phase,Object source,Object sourcePose,boolean force) {
        if(!com.bydhud.mapcapture.LifecycleJournalTransport.detailed() && s.lastObservationError==null)return;
        try {
            Object ownedPose=call(call(s.map,"m"),"a");
            String sourceFields=cameraFields(sourcePose), ownedFields=cameraFields(ownedPose);
            String sourceView=mapViewport(source), ownedView=viewport(s.view);
            boolean sourceChanged=!samePose(sourcePose,s.lastSourcePose);
            boolean ownedChanged=!samePose(ownedPose,s.lastOwnedPose);
            if(sourceChanged) s.sourcePoseRevision++;
            if(ownedChanged) s.ownedReadbackRevision++;
            Boolean targetEq=sameTarget(sourcePose,ownedPose);
            boolean targetEqChanged=s.lastLoggedTargetMatch==null ? targetEq!=null : !s.lastLoggedTargetMatch.equals(targetEq);
            long now=SystemClock.elapsedRealtime();
            String signature=sourceFields+"|"+ownedFields+"|"+sourceView+"|"+ownedView;
            boolean changed=!signature.equals(s.lastCameraSignature) || targetEqChanged;
            boolean driveNote="drive".equals(phase) && changed && now-s.lastCameraNoteMs>=1000L;
            if(force || driveNote || (!"drive".equals(phase) && changed)) {
                String detail="phase="+phase+" generation="+s.generation+" previous="+s.previousGeneration
                        +" frames="+s.frames+" sourcePoseRev="+s.sourcePoseRevision+" ownedReadbackRev="+s.ownedReadbackRevision
                        +" setterCalls="+s.setterCalls+" targetEq="+targetMatch(targetEq)
                        +" source={"+sourceFields+"} owned={"+ownedFields+"}"
                        +" sourceView={"+sourceView+"} ownedView={"+ownedView+"}";
                if("after_view_sized".equals(phase)) detail+=" seedNull="+(s.seedPose==null?1:0)
                        +" sourceSeed={"+cameraFields(s.seedPose)+"} hudSeed={"+cameraFields(s.hudSeedPose)+"}";
                event("hud_camera_observation",s.map,detail);
                s.lastCameraSignature=signature;
                s.lastLoggedTargetMatch=targetEq;
                if("drive".equals(phase)) s.lastCameraNoteMs=now;
            }
            s.lastTargetMatch=targetEq;
            s.lastSourcePose=sourcePose;
            s.lastOwnedPose=ownedPose;
            if(s.lastObservationError!=null) {
                event("hud_camera_observation_recovered",s.map,"phase="+phase+" generation="+s.generation);
                s.lastObservationError=null;
            }
        } catch(Throwable e) { cameraObservationError(s,phase,e); }
    }
    private static String cameraFields(Object pose) throws Exception {
        if(pose==null) return "null";
        Object point=get(pose,"a"), lookAhead=get(pose,"f");
        return "targetPresent="+(point==null?0:1)+",zoom="+get(pose,"h")+",tilt="+get(pose,"e")
                +",bearing="+get(pose,"d")+",lookAhead="+(lookAhead==null?"null":get(lookAhead,"b")+","+get(lookAhead,"c"))
                +",targetAltitude="+get(pose,"b")+",targetAltitudeStrength="+get(pose,"c")+",verticalFov="+get(pose,"g");
    }
    private static Object centeredHudPose(Object sourcePose) throws Exception {
        if(sourcePose==null) return null;
        Object lookAhead=get(sourcePose,"f");
        if(lookAhead==null) return sourcePose;
        float horizontal=((Number)get(lookAhead,"b")).floatValue();
        if(horizontal==0f) return sourcePose;
        float vertical=((Number)get(lookAhead,"c")).floatValue();
        Object builder=type("boox").getConstructor(type("booy")).newInstance(sourcePose);
        Object centeredLookAhead=type("bopb").getConstructor(float.class,float.class).newInstance(0f,vertical);
        call(builder,"k",centeredLookAhead);
        return call(builder,"a");
    }
    private static String mapViewport(Object map) throws Exception { return map==null?"none":viewport((View)call(map,"j")); }
    private static String viewport(View view) {
        if(view==null) return "null";
        android.util.DisplayMetrics m=view.getResources().getDisplayMetrics();
        return view.getWidth()+"x"+view.getHeight()+",density="+m.density+",dpi="+m.densityDpi+",attached="+view.isAttachedToWindow();
    }
    private static boolean samePose(Object a,Object b) { return a==b || (a!=null && a.equals(b)); }
    private static Boolean sameTarget(Object a,Object b) throws Exception {
        if(a==null || b==null) return null;
        if(type("bouv").isInstance(a))a=call(type("bouv"),"a",a);
        if(type("bouv").isInstance(b))b=call(type("bouv"),"a",b);
        Object pa=get(a,"a"), pb=get(b,"a");
        return pa==null || pb==null ? null : pa==pb || pa.equals(pb);
    }
    private static boolean cameraPoseEquivalent(Object a,Object b) throws Exception {
        if(samePose(a,b)) return true;
        if(a==null || b==null || !Boolean.TRUE.equals(sameTarget(a,b))) return false;
        return cameraScalars(a).equals(cameraScalars(b));
    }
    private static String targetMatch(Boolean same) { return same==null?"unknown":same?"same":"different"; }

    private static void bindCameraTrace(Scene s) throws Throwable {
        if(s.cameraRegistrationFailure!=null) throw s.cameraRegistrationFailure;
        Object facade=call(s.map,"m");
        s.cameraController=get(facade,"a");
        s.cameraGate=get(facade,"b");
        s.cameraConsumer=call(s.cameraGate,"c");
        s.cameraAnimator=get(s.cameraConsumer,"as");
        s.cameraBackend=get(s.cameraConsumer,"B");
        if(!type("boud").isInstance(s.cameraController) || !type("bofl").isInstance(s.cameraGate)
                || !type("bpcj").isInstance(s.cameraConsumer) || !type("bpls").isInstance(s.cameraAnimator))
            throw new IllegalStateException("pinned Phoenix camera pipeline shape changed");
        if(get(s.cameraConsumer,"j")!=s.cameraController || get(s.cameraAnimator,"a")!=s.cameraController)
            throw new IllegalStateException("pinned Phoenix camera pipeline ownership mismatch");
        if(!s.cameraRegistrationApplied || s.cameraRegistrationOwner==null
                || s.cameraRegistrationController!=s.cameraController
                || s.cameraRegistrationAnimator!=s.cameraAnimator
                || s.cameraRegistrationProduct!=get(s.cameraRegistrationOwner,"an")
                || get(s.cameraRegistrationOwner,"f")!=s.hudContext
                || get(s.cameraController,"d")!=s.cameraAnimator)
            throw new IllegalStateException("HUD-owned Phoenix camera registration did not reach the rendered controller");
        if(s.cameraBackend==null || !type("bqfu").isInstance(s.cameraBackend)
                || get(s.cameraBackend,"L")!=s.cameraController)
            throw new IllegalStateException("HUD camera registration does not match the rendered backend controller");
        s.snapshotQueue=get(s.renderer,"h");
        if(!type("bqeq").isInstance(s.snapshotQueue))
            throw new IllegalStateException("pinned HUD snapshot queue identity changed");
        s.cameraReadback=type("bqde").getDeclaredMethod("u");
        s.cameraReadback.setAccessible(true);
        s.lastRendererPose=readCameraPose(s);
        Object context=get(s.cameraConsumer,"ae");
        if(context instanceof Context) {
            try {
                Object service=((Context)context).getSystemService("activity");
                if(service instanceof android.app.ActivityManager) {
                    android.content.pm.ConfigurationInfo info=((android.app.ActivityManager)service).getDeviceConfigurationInfo();
                    s.lowGl=info!=null && info.reqGlEsVersion<0x20000;
                }
            } catch(Throwable e) { cameraTraceError(s,"gl_capability",e); }
        }
        if(com.bydhud.mapcapture.LifecycleJournalTransport.detailed())event("hud_camera_trace_bound",s.map,"generation="+s.generation+" gate=owned consumer=owned animator=owned controller=owned "+cameraAttachment(s,s.cameraBackend));
    }

    /** Select the camera animator created by this HUD map's bpcr factory, never a shared provider. */
    public static void cameraHandlerRegistration(Object owner,Object result) {
        Scene s=scene;
        if(s==null || s.stopping || owner==null || result==null || s.hudContext==null) return;
        try {
            if(!type("bpcr").isInstance(owner) || get(owner,"f")!=s.hudContext) return;
            Object controller=call(owner,"c"), animator=call(owner,"i");
            if(!type("boud").isInstance(controller) || !type("bpls").isInstance(animator)
                    || get(animator,"a")!=controller)
                throw new IllegalStateException("HUD bpcr camera objects failed pinned ownership check");
            if(s.cameraRegistrationOwner!=null && s.cameraRegistrationOwner!=owner)
                throw new IllegalStateException("multiple bpcr owners matched the unique HUD context");
            Object previous=get(controller,"d");
            set(controller,"d",animator);
            if(get(controller,"d")!=animator)
                throw new IllegalStateException("HUD bpcr animator registration did not stick");
            s.cameraRegistrationOwner=owner;
            s.cameraRegistrationProduct=result;
            s.cameraRegistrationController=controller;
            s.cameraRegistrationAnimator=animator;
            s.cameraRegistrationPrevious=identity(previous);
            s.cameraRegistrationApplied=true;
            event("hud_camera_registration",s.map,"generation="+s.generation+" owner="+identity(owner)
                    +" uniqueContextMatch=1 controller="+identity(controller)+" animator="+identity(animator)
                    +" previous="+s.cameraRegistrationPrevious+" installed=1");
        } catch(Throwable e) {
            s.cameraRegistrationFailure=root(e);
            cameraTraceError(s,"registration",e);
        }
    }
    private static Object readCameraPose(Scene s) throws Exception {
        try { return s.cameraReadback.invoke(s.cameraController,(Object[])null); }
        catch(InvocationTargetException e) { throw new IllegalStateException("pinned camera readback failed",root(e)); }
    }
    private static String cameraScalars(Object pose) throws Exception {
        if(pose==null) return "null";
        Object fields=pose;
        if(type("bouv").isInstance(pose)) fields=call(type("bouv"),"a",pose);
        if(fields==null) return "null";
        Object target=get(fields,"a");
        Object lookAhead=get(fields,"f");
        return "targetPresent="+(target==null?0:1)+",zoom="+get(fields,"h")+",tilt="+get(fields,"e")
                +",bearing="+get(fields,"d")+",targetAltitude="+get(fields,"b")
                +",targetAltitudeStrength="+get(fields,"c")+",lookAhead="
                +(lookAhead==null?"null":get(lookAhead,"b")+","+get(lookAhead,"c"))
                +",verticalFov="+get(fields,"g");
    }
    private static boolean startCameraTrace(Scene s,long requestId,long frame) {
        if(!com.bydhud.mapcapture.LifecycleJournalTransport.detailed())return false;
        long now=SystemClock.elapsedRealtime();
        if(traceWindowOpen(s) || now<s.nextCameraTraceMs)return false;
        s.traceRequestId=requestId;s.traceSnapshotSeq=frame;s.traceSampleCount.set(0);s.traceMask.set(0);
        s.traceStartGateCalls=s.cameraGateCalls.get();s.traceStartDispatchCalls=s.cameraDispatchCalls.get();
        s.traceStartTargetCalls=s.cameraTargetCalls.get();s.traceStartSampleCalls=s.cameraSampleCalls.get();
        s.traceStartPoseWriteCalls=s.cameraPoseWriteCalls.get();
        s.traceStartRenderFrameEntries=s.cameraRenderFrameEntries.get();
        s.traceStartRenderFrameReturns=s.cameraRenderFrameReturns.get();
        s.traceWindowEndsMs=now+CAMERA_TRACE_WINDOW_MS;s.nextCameraTraceMs=now+CAMERA_TRACE_INTERVAL_MS;s.traceActive=true;
        cameraTraceRequest(s,requestId);return true;
    }
    private static boolean traceWindowOpen(Scene s) {
        if(!s.traceActive || !com.bydhud.mapcapture.LifecycleJournalTransport.detailed())return false;
        if(SystemClock.elapsedRealtime()>=s.traceWindowEndsMs){s.traceActive=false;return false;}
        return true;
    }
    private static boolean traceStageOpen(Scene s,int bit) {
        return traceWindowOpen(s) && (s.traceMask.get()&bit)==0;
    }
    private static void cameraTraceRequest(Scene s,long requestId) {
        try {
            Object current=readCameraPose(s);
            event("hud_camera_trace_request",s.map,"generation="+s.generation+" requestId="+requestId
                    +" snapshotSeq="+s.traceSnapshotSeq+" setters="+s.setterCalls
                    +" cameraCommandSequence="+s.nextCameraCommandId
                    +" gateCalls="+s.cameraGateCalls.get()+" gateCommand="+s.lastGateCommandId+" gateRef="+identity(s.lastGatePose)
                    +" dispatchCalls="+s.cameraDispatchCalls.get()+" dispatchCommand="+s.lastDispatchCommandId+" dispatchRef="+identity(s.lastDispatchPose)
                    +" targetCalls="+s.cameraTargetCalls.get()+" targetCommand="+s.lastTargetCommandId+" targetRef="+identity(s.lastTargetPose)
                    +" sampleCalls="+s.cameraSampleCalls.get()+" sampleSeq="+s.cameraSampleSequence.get()+" sampledRef="+identity(s.lastSampledPose)
                    +" qdeWriteCalls="+s.cameraPoseWriteCalls.get()+" postBqdeRevision="+s.cameraPoseRevision.get()
                    +" renderFrameEntries="+s.cameraRenderFrameEntries.get()+" renderFrameReturns="+s.cameraRenderFrameReturns.get()
                    +" attachment={"+cameraAttachment(s,s.cameraBackend)+"}"
                    +" qde={"+cameraScalars(current)+"}");
        } catch(Throwable e) { cameraTraceError(s,"request",e); }
    }
    private static boolean traceOnce(Scene s,int bit) {
        if(!traceWindowOpen(s) || s.stopping) return false;
        return (s.traceMask.getAndUpdate(value->value|bit)&bit)==0;
    }
    private static String cameraAttachment(Scene s,Object backend) throws Exception {
        if(backend==null) return "B=null,L=unavailable,d=unavailable";
        if(!type("bqfu").isInstance(backend))
            return "B="+identity(backend)+",L=unavailable,d=unavailable,backendIsBqfu=0";
        Object handler=get(backend,"L");
        if(handler==null) return "B="+identity(backend)+",L=null,d=unavailable,backendIsBqfu=1";
        if(!type("boud").isInstance(handler))
            return "B="+identity(backend)+",L="+identity(handler)+",d=unavailable,handlerIsBoud=0,backendIsBqfu=1";
        Object callback=handler==null?null:get(handler,"d");
        return "B="+identity(backend)+",L="+identity(handler)+",LMatchesController="+(handler==s.cameraController?1:0)
                +",d="+identity(callback)+",dMatchesAnimator="+(callback==s.cameraAnimator?1:0)+",backendIsBqfu=1";
    }
    public static void cameraRendererFrameBefore(Object backend) {
        Scene s=scene;
        if(s==null || backend!=s.cameraBackend || s.stopping) return;
        if(s.pendingSnapshot!=null) s.cameraFrameEntryRevision=s.cameraPoseRevision.get();
        if(!traceWindowOpen(s)) return;
        s.cameraRenderFrameEntries.incrementAndGet();
        if(!traceOnce(s,TRACE_RENDER_FRAME_ENTRY)) return;
        try {
            Object readback=readCameraPose(s);
            event("hud_camera_render_frame_entry",s.map,"generation="+s.generation+" requestId="+s.traceRequestId
                    +" snapshotSeq="+s.traceSnapshotSeq+" "+cameraAttachment(s,backend)+" qdeReadback={"+cameraScalars(readback)+"}");
        } catch(Throwable e) { cameraTraceError(s,"render_frame_entry",e); }
    }
    public static void cameraRendererFrameAfter(Object backend) {
        Scene s=scene;
        if(s==null || backend!=s.cameraBackend || s.stopping) return;
        long frame=s.cameraRenderFrameReturns.incrementAndGet();
        boolean requestPending=s.pendingSnapshot!=null;
        if(requestPending) {
            try {
                Object readback=readCameraPose(s);
                boolean writeInFrame=s.cameraPoseRevision.get()>s.cameraFrameEntryRevision;
                long appliedCommand=writeInFrame?s.lastPoseWriteCommandId:s.lastSampledCameraCommandId;
                Object appliedPose=writeInFrame?s.lastPoseWriteEffectivePose:s.lastSampledCameraTargetPose;
                s.lastRendererFrameSequence=frame;
                s.lastRendererFrameCommandId=appliedCommand;
                s.lastRendererFrameTargetCommandId=appliedCommand;
                s.lastRendererFrameTargetPose=appliedPose;
                s.lastRendererFramePose=readback;
                s.lastRendererFrameRevision=s.cameraPoseRevision.get();
                s.lastRendererFrameApplicationMatch=s.follow!=null && s.follow.acceptsInput(appliedCommand) && appliedCommand>0L
                        && cameraPoseEquivalent(s.lastRendererFrameTargetPose,readback);
            } catch(Throwable e) {
                s.lastRendererFrameSequence=frame;
                s.lastRendererFrameCommandId=-1L;
                s.lastRendererFrameTargetCommandId=-1L;
                s.lastRendererFrameTargetPose=null;
                s.lastRendererFramePose=null;
                s.lastRendererFrameRevision=s.cameraPoseRevision.get();
                s.lastRendererFrameApplicationMatch=false;
                cameraTraceError(s,"render_frame_application",e);
            }
        }
        if(!traceWindowOpen(s)) return;
        if(!traceOnce(s,TRACE_RENDER_FRAME_RETURN)) return;
        try {
            Object readback=readCameraPose(s);
            event("hud_camera_render_frame_return",s.map,"generation="+s.generation+" requestId="+s.traceRequestId
                    +" snapshotSeq="+s.traceSnapshotSeq+" "+cameraAttachment(s,backend)+" qdeReadback={"+cameraScalars(readback)+"}");
        } catch(Throwable e) { cameraTraceError(s,"render_frame_return",e); }
    }
    public static void cameraGateBefore(Object gate,Object pose,Object callback) {
        Scene s=scene;
        if(s==null || gate!=s.cameraGate || s.stopping) return;
        s.cameraGateCalls.incrementAndGet();s.lastGatePose=pose;
        long command=s.activeCameraCommandId;s.lastGateCommandId=command;
        if(command!=0) s.cameraCommands.record(pose,command);
        if(!traceStageOpen(s,TRACE_GATE))return;
        try {
            s.lastGateCompleted=false;
            boolean ready=(Boolean)get(gate,"j");
            boolean futureDone=(Boolean)call(get(gate,"e"),"isDone");
            int width=s.view==null?0:s.view.getWidth(), height=s.view==null?0:s.view.getHeight();
            String branch=!ready?"retain_latest":futureDone && width>0 && height>0?"direct":"view_post";
            s.lastGateSummary="command="+command+",ready="+(ready?1:0)+",futureDone="+(futureDone?1:0)
                    +",size="+width+"x"+height+",callback="+(callback==null?0:1)+",branch="+branch;
        } catch(Throwable e) { cameraTraceError(s,"gate",e); }
    }
    public static void cameraGateAfter(Object gate,Object pose,Object callback) {
        Scene s=scene;
        if(s==null || gate!=s.cameraGate || s.stopping) return;
        s.lastGateCompleted=true;
        if(traceOnce(s,TRACE_GATE)) event("hud_camera_trace_gate",s.map,"generation="+s.generation+" requestId="+s.traceRequestId
                +" snapshotSeq="+s.traceSnapshotSeq+" "+s.lastGateSummary+",returned=1");
    }
    public static void cameraDispatchBefore(Object consumer,Object pose,Object callback) {
        Scene s=scene;
        if(s==null || consumer!=s.cameraConsumer || s.stopping) return;
        s.cameraDispatchCalls.incrementAndGet();s.lastDispatchPose=pose;
        s.lastDispatchCommandId=s.cameraCommands.take(pose);
        if(!traceStageOpen(s,TRACE_DISPATCH))return;
        try {
            s.lastDispatchCompleted=false;
            boolean ready=(Boolean)get(consumer,"aa");
            Object animator=get(consumer,"l"), controller=get(consumer,"k");
            boolean animatorEnabled=controller!=null && (Boolean)get(controller,"f");
            boolean mainThread=Looper.myLooper()==Looper.getMainLooper();
            String mode=!ready?"not_ready":animator==null?"missing_animator":!animatorEnabled || s.lowGl?"direct_bosy":!mainThread?"executor_then_animator":"animator";
            s.lastDispatchSummary="command="+s.lastDispatchCommandId+",ready="+(ready?1:0)+",animator="+(animator==null?0:1)
                    +",animationEnabled="+(animatorEnabled?1:0)+",mainThread="+(mainThread?1:0)+",lowGl="+(s.lowGl?1:0)
                    +",callback="+(callback==null?0:1)+",mode="+mode;
        } catch(Throwable e) { cameraTraceError(s,"dispatch",e); }
    }
    public static void cameraDispatchAfter(Object consumer,Object pose,Object callback) {
        Scene s=scene;
        if(s==null || consumer!=s.cameraConsumer || s.stopping) return;
        s.lastDispatchCompleted=true;
        if(traceOnce(s,TRACE_DISPATCH)) event("hud_camera_trace_dispatch",s.map,"generation="+s.generation+" requestId="+s.traceRequestId
                +" snapshotSeq="+s.traceSnapshotSeq+" "+s.lastDispatchSummary+",returned=1");
    }
    public static void cameraTarget(Object animator,Object target,int duration,Object interpolator) {
        recordCameraTarget(animator,target,duration,"h_return");
    }
    public static void cameraImmediateTarget(Object animator,Object target) {
        recordCameraTarget(animator,target,0,"j_return");
    }
    private static void recordCameraTarget(Object animator,Object target,int duration,String stage) {
        Scene s=scene;
        if(s==null || animator!=s.cameraAnimator || s.stopping) return;
        s.cameraTargetCalls.incrementAndGet();s.lastTargetPose=target;s.lastTargetCommandId=s.lastDispatchCommandId;
        s.acceptedCameraTarget=new CameraTarget(s.lastDispatchCommandId,target);
        if(!traceStageOpen(s,TRACE_TARGET))return;
        try {
            Object accepted=get(animator,"b");
            boolean matches=accepted==target || accepted!=null && accepted.equals(target);
            s.lastTargetSummary="stage="+stage+",durationMs="+duration+",matches="+(matches?1:0)+",requested={"+cameraScalars(target)+"},accepted={"+cameraScalars(accepted)+"}";
            if(traceOnce(s,TRACE_TARGET)) event("hud_camera_trace_target",s.map,"generation="+s.generation+" requestId="+s.traceRequestId
                    +" snapshotSeq="+s.traceSnapshotSeq+" dispatchCommand="+s.lastDispatchCommandId+" "+s.lastTargetSummary);
        } catch(Throwable e) { cameraTraceError(s,"target",e); }
    }
    public static void cameraSample(Object animator,Object camera,int state) {
        Scene s=scene;
        if(s==null || animator!=s.cameraAnimator || camera!=s.cameraController || s.stopping) return;
        s.cameraSampleCalls.incrementAndGet();
        // The native animation supplies the pose; old tablet setter IDs are never evidence here.
        try {
            Object sampled=get(animator,"b");
            long input=s.follow==null?-1L:s.follow.sampleInput();
            s.lastSampledCameraCommandId=input>0 && cameraPoseEquivalent(s.follow.samplePose(),sampled)?input:-1L;
            s.lastSampledCameraTargetPose=s.lastSampledCameraCommandId>0?sampled:null;
            if(s.lastSampledCameraCommandId>0)s.pose=sampled;
        } catch(Throwable e) {s.lastSampledCameraCommandId=-1L;s.lastSampledCameraTargetPose=null;presentationFailure(s.map,e);}
        long sequence=s.cameraSampleSequence.incrementAndGet();
        if(!traceWindowOpen(s))return;
        s.traceSampleCount.incrementAndGet();
        if(!traceStageOpen(s,TRACE_SAMPLE))return;
        try {
            Object sampled=get(animator,"b"), qde=readCameraPose(s);
            s.lastSampledPose=sampled;
            boolean same=sampled==qde || sampled!=null && sampled.equals(qde);
            if(traceOnce(s,TRACE_SAMPLE))event("hud_camera_trace_sample",s.map,"generation="+s.generation+" requestId="+s.traceRequestId
                    +" snapshotSeq="+s.traceSnapshotSeq+" sampleSeq="+sequence+" sampleState="+state
                    +" nativeInput="+s.lastSampledCameraCommandId
                    +" sampled={"+cameraScalars(sampled)+"} qde={"+cameraScalars(qde)+"}"
                    +" samePose="+(same?1:0)+" postBqdeRevision="+s.cameraPoseRevision.get());
        } catch(Throwable e) { cameraTraceError(s,"sample",e); }
    }
    public static void cameraPoseWrite(Object controller,Object target) {
        Scene s=scene;
        if(s==null || controller!=s.cameraController) return;
        s.cameraPoseWriteCalls.incrementAndGet();s.lastPoseWriteTarget=target;
        long revision=s.cameraPoseRevision.incrementAndGet();
        s.lastPoseWriteCommandId=-1L;
        try {
            long input=s.follow==null?-1L:s.follow.sampleInput();
            if(input>0 && cameraPoseEquivalent(s.follow.samplePose(),target))s.lastPoseWriteCommandId=input;
        } catch(Throwable e) {presentationFailure(s.map,e);}
        s.lastPoseWriteEffectivePose=target;
        if(s.cameraReadback==null || !traceStageOpen(s,TRACE_WRITE))return;
        try {
            Object current=readCameraPose(s);
            s.lastRendererPose=current;
            if(traceOnce(s,TRACE_WRITE))event("hud_camera_trace_pose_write",s.map,"generation="+s.generation
                    +" requestId="+s.traceRequestId+" snapshotSeq="+s.traceSnapshotSeq+" postBqdeRevision="+revision
                    +" target={"+cameraScalars(target)+"} readback={"+cameraScalars(current)+"}");
        } catch(Throwable e) { cameraTraceError(s,"pose_write",e); }
    }
    static Object ownedCameraAnimator(Object map) {
        Scene s=scene;
        if(s==null || s.map!=map || s.cameraAnimator==null)throw new IllegalStateException("Missing owned HUD animator");
        return s.cameraAnimator;
    }
    static void attachCamera(Object map,HudCamera camera) {
        Scene s=scene;
        if(s==null || s.map!=map || s.follow!=null)throw new IllegalStateException("HUD camera owner mismatch");
        s.follow=camera;
    }
    static long commitCameraInput(Object map) {
        Scene s=scene;
        if(s==null || s.map!=map || s.stopping)throw new IllegalStateException("Retired HUD camera input");
        return s.latestCameraCommandId=++s.nextCameraCommandId;
    }
    static void invalidateCameraEvidence(Object map) {
        Scene s=scene;
        if(s==null || s.map!=map)return;
        s.lastSampledCameraCommandId=-1L;s.lastSampledCameraTargetPose=null;
        s.lastPoseWriteCommandId=-1L;s.lastPoseWriteEffectivePose=null;
        s.lastRendererFrameCommandId=-1L;s.lastRendererFrameTargetCommandId=-1L;
        s.lastRendererFrameTargetPose=null;s.lastRendererFramePose=null;s.lastRendererFrameApplicationMatch=false;
    }
    public static boolean ownsCameraSample(Object animator) {
        Scene s=scene;
        return s!=null && s.follow!=null && animator==s.cameraAnimator;
    }
    public static int sampleOwnedCamera(Object animator,Object controller) throws Throwable {
        Scene s=scene;
        if(s==null || s.follow==null || animator!=s.cameraAnimator || controller!=s.cameraController)
            throw new IllegalStateException("HUD sample owner changed");
        return s.follow.sample(controller);
    }
    public static void nativeCameraSample(Object animation) {
        Scene s=scene;
        if(s==null || s.follow==null || !s.follow.ownsAnimation(animation))return;
        try {s.follow.sampled(animation);}catch(Throwable failure){HudCamera.nativeError(failure);presentationFailure(s.map,failure);}
    }
    public static void nativeCameraError(Object error) {HudCamera.nativeError(error);}
    private static void cameraTraceError(Scene s,String phase,Throwable error) {
        String trace=Log.getStackTraceString(root(error));
        String signature=phase+"\n"+trace;
        if(signature.equals(s.lastCameraTraceError)) return;
        s.lastCameraTraceError=signature;
        Log.w("MapsHudScene","camera_trace phase="+phase,error);
        event("hud_camera_trace_error",s.map,"generation="+s.generation+" requestId="+s.traceRequestId+" phase="+phase+"\n"+trace);
    }
    private static void cameraObservationError(Scene s,String phase,Throwable error) {
        String trace=Log.getStackTraceString(error);
        String signature=phase+"\n"+trace;
        if(s!=null && signature.equals(s.lastObservationError)) return;
        if(s!=null) s.lastObservationError=signature;
        Log.w("MapsHudScene","camera_observation phase="+phase, error);
        event("hud_camera_observation_error",s==null?null:s.map,"phase="+phase+" generation="+(s==null?0:s.generation)+"\n"+trace);
    }
    public static void presentationFailure(Throwable e) {
        Scene owner=scene;
        presentationFailure(owner==null?null:owner.map,e);
    }
    static void presentationFailure(Object map,Throwable e) {
        // Never tear down native owners inside their callback or fail a replacement scene.
        MAIN.post(() -> {
            Scene current=scene;
            if(map!=null && current!=null && current.map==map && !current.stopping) fail("presentation",e);
            else error("retired_presentation",e);
        });
    }
    static boolean hudGlThread() { Scene s=scene; return s!=null && s.glThread==Thread.currentThread(); }
    static MapPalette rendererPalette(Object r) { Scene s=scene; return s!=null && s.renderer==r ? s.palette : null; }
    private static Class<?> type(String n) throws ClassNotFoundException { return Class.forName(n,true,HudScene.class.getClassLoader()); }
    private static Field field(Object o,String n) throws Exception {
        Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();
        for(;c!=null;c=c.getSuperclass()) try { Field f=c.getDeclaredField(n); f.setAccessible(true); return f; } catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(n);
    }
    private static Object get(Object o,String n) throws Exception { return field(o,n).get(o instanceof Class?null:o); }
    private static void set(Object o,String n,Object v) throws Exception { field(o,n).set(o instanceof Class?null:o,v); }
    private static Object call(Object o,String n,Object... args) throws Exception {
        Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();
        for(Class<?> k=c;k!=null;k=k.getSuperclass()) for(Method m:k.getDeclaredMethods()) {
            if(!m.getName().equals(n) || m.getParameterTypes().length!=args.length) continue;
            Class<?>[] p=m.getParameterTypes(); boolean match=true;
            for(int i=0;i<p.length;i++) {
                Class<?> t=p[i]; if(t==int.class)t=Integer.class; if(t==boolean.class)t=Boolean.class;
                if(t==long.class)t=Long.class;
                if(args[i]!=null && !t.isInstance(args[i])) { match=false; break; }
            }
            if(!match) continue;
            m.setAccessible(true); return m.invoke(o instanceof Class?null:o,args);
        }
        throw new NoSuchMethodException(c.getName()+"."+n+" args="+args.length);
    }
}
