package com.bydhud.mapshud;

import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.Executor;
import static com.bydhud.mapshud.Reflect.*;

/** Stock marker mechanics with the private camera's selected input and no global override owner. */
public final class HudMarker {
    // Keep weak tombstones after stop so already queued raw deliveries cannot revive a retired marker.
    private static final Set<Object> SELECTED_INPUTS=Collections.newSetFromMap(new WeakHashMap<Object,Boolean>());
    private static final Map<Object,WeakReference<HudMarker>> SAMPLES=new WeakHashMap<>();
    private final Object position,map,handler,animation;
    private Object lastFix,lastStyle;
    private int lastMode;
    private volatile boolean stopped;
    private volatile long inputs;
    private long lastInputNote,lastSampleNote,samples;
    private Object lastDrawPoint;
    private String lastInputState,lastObservationError;

    HudMarker(Object position,Object map,Executor deliveries) throws Exception {
        this.position=position;this.map=map;
        Object busOwner=get(position,"Z");
        handler=make("aosa",1,type("aknp"),busOwner,get(type("bckf"),"a"),deliveries);
        animation=get(position,"av");
        synchronized(SELECTED_INPUTS){SELECTED_INPUTS.add(busOwner);}
        synchronized(SAMPLES){SAMPLES.put(animation,new WeakReference<>(this));}
    }

    /** DEX guard applies only to case1 of aosa; every tablet receiver keeps its original handler. */
    public static boolean suppressRawInput(Object busOwner) {
        synchronized(SELECTED_INPUTS){return SELECTED_INPUTS.contains(busOwner);}
    }

    void update(Object navigation,Object fix,boolean fresh) throws Exception {
        if(stopped)return;
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("HUD marker update outside main lifecycle");
        Object style=get(type("aoul"),navigation==null?"a":"g");
        int mode=navigation==null?3:((Number)call(type("kqe"),"p",call(call(navigation,"c"),"c"))).intValue();
        boolean selection=style!=lastStyle || mode!=lastMode;
        if(selection) {
            call(position,"w",style);
            call(position,"M",mode);
            lastStyle=style;lastMode=mode;
        }
        Object selected=fresh?fix:null;
        if(selection || selected!=lastFix) {
            // Bypass only our raw-event guard, retaining the original smoothing, clocks and redraw path.
            call(handler,"bydHudOriginal_a",make("aknp",selected));
            if(get(position,"Q")!=selected)throw new IllegalStateException("HUD marker did not retain its selected follow input");
            lastFix=selected;inputs++;
            if(selected==null)call(position,"G");
        }
        if(!com.bydhud.mapcapture.LifecycleJournalTransport.detailed())return;
        String state="style="+get(get(position,"t"),"A")+" mode="+get(position,"S")
                +" fresh="+fresh+" nav="+(navigation!=null)+" drawable="+(get(position,"u")!=null);
        long now=SystemClock.elapsedRealtime();
        if(!state.equals(lastInputState) || now-lastInputNote>=2000) {
            lastInputState=state;lastInputNote=now;
            HudScene.event("hud_marker_input",map,"input="+inputs+" "+state
                    +" fixTime="+(selected==null?"none":call(selected,"d")));
        }
    }

    /** Called only after the real stock animation runnable returns normally, never a synthetic draw. */
    public static void sampled(Object animation) {
        if(!com.bydhud.mapcapture.LifecycleJournalTransport.detailed())return;
        HudMarker marker;
        synchronized(SAMPLES){WeakReference<HudMarker> ref=SAMPLES.get(animation);marker=ref==null?null:ref.get();}
        if(marker==null || marker.stopped)return;
        marker.samples++;
        long now=SystemClock.elapsedRealtime();
        if(marker.lastSampleNote!=0 && now-marker.lastSampleNote<2000)return;
        marker.lastSampleNote=now;
        try {
            synchronized(animation) {
                Object output=get(animation,"d"),point=get(output,"a"),drawable=get(animation,"c");
                boolean eligible=(Boolean)get(output,"q");
                boolean moved=point!=null && marker.lastDrawPoint!=null && !point.equals(marker.lastDrawPoint);
                marker.lastDrawPoint=point==null?null:make("bogx",point);
                // aosc.b invokes aote.d exactly when q is true and c exists. This is CPU update, not GPU visibility proof.
                HudScene.event("hud_marker_sample",marker.map,"latestInput="+marker.inputs+" samples="+marker.samples
                        +" drawable="+(drawable==null?"none":drawable.getClass().getName())
                        +" updateReturned="+(eligible && drawable!=null)+" viewportEligible="+eligible
                        +" hasPoint="+(point!=null)+" pointChangedSinceSample="+moved
                        +" bearing="+get(output,"c")+" bearingValid="+get(output,"d")
                        +" fixElapsedMs="+get(output,"m"));
            }
        } catch(Throwable failure) {
            failure=root(failure);
            String trace=Log.getStackTraceString(failure);
            if(!trace.equals(marker.lastObservationError)) {
                marker.lastObservationError=trace;
                HudScene.event("hud_marker_observation_error",marker.map,"stage=stock_animation_return\n"+trace);
            }
        }
    }

    void stop() {
        stopped=true;
        synchronized(SAMPLES){SAMPLES.remove(animation);}
        lastFix=null;
    }
}
