package com.bydhud.mapshud;

import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import static com.bydhud.mapshud.Reflect.*;

/** Private native following controller; all native access shares the owned animator monitor. */
final class HudCamera {
    private static final String JNI="com.google.android.libraries.geo.navcore.ui.camera.jni.CameraControllerJni";
    private static final ThreadLocal<HudCamera> OPERATION=new ThreadLocal<>();
    final Object map, animator, controller;
    private final Object app, graph, viewport, routeHint, position, location;
    private final View view;
    private final Executor deliveries;
    private final Runnable changed;
    private final Method originalSample;
    private Object nativeCamera, animation, jni, policy, predictionStream, predictionObserver;
    private Object lastNav, lastFix, lastPrediction;
    private boolean demand, subscribed, running, hadRoute, inputValid, sampling;
    private Throwable nativeFailure;
    private long inputId=-1, sampleId=-1, lastNote;
    private long minimumInputId=Long.MAX_VALUE;
    private Object fixTime;
    private Object nativeSample;
    private String inputState;

    HudCamera(Object app,Object graph,Object map,Object viewport,Object routeHint,Object position,View view,
            Object animator,Object controller,Executor deliveries,Runnable changed) throws Exception {
        this.app=app;this.graph=graph;this.map=map;this.viewport=viewport;this.routeHint=routeHint;
        this.position=position;this.view=view;this.animator=animator;this.controller=controller;
        this.deliveries=deliveries;this.changed=changed;location=value(app,"js");
        originalSample=animator.getClass().getDeclaredMethod("bydHudOriginal_a",type("boud"));
        originalSample.setAccessible(true);
    }
    void start() throws Exception {
        running=true;
        demand=true;call(location,"e");
        predictionStream=call(position,"j");
        if(predictionStream!=null) {
            predictionObserver=proxy("bsca",(p,m,a)->{
                // Leave the stock notification guard before marker updates can publish another prediction.
                if(running)deliveries.execute(()->{if(running)changed.run();});
                return null;
            });
            subscribed=true;call(predictionStream,"d",predictionObserver,deliveries);
        }
        HudScene.event("hud_follow_started",map,"owned location client acquired; tablet camera is not an input");
    }
    private void create() throws Exception {
        checkOwnership();
        Object config=value(graph,"aX"), policyConfig=value(graph,"e"), decoration=value(graph,"ah");
        Object builder=call(get(type("cktl"),"a"),"createBuilder");call(builder,"copyOnWrite");
        Object proto=get(builder,"instance");set(proto,"b",((Number)get(proto,"b")).intValue()|1);
        set(proto,"c",(double)view.getResources().getDisplayMetrics().density);
        Object density=call(builder,"build");
        List<Object> contributors=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=29)contributors.add(get(value(get(app,"a"),"mi"),"c"));
        contributors.add(routeHint);
        Object ownedBridge=value(graph,"aA");
        Object output=proxy("boib",(p,m,a)->{
            if(m.getName().equals("l")) {
                checkOwnership();
                if(a[0]!=animation || animation==null)throw new IllegalStateException("Foreign camera animation on HUD output");
                call(animator,"l",animation);
                return null;
            }
            try {return m.invoke(ownedBridge,a);}catch(Throwable failure){throw root(failure);}
        });
        jni=make(JNI,density,lazy(app,"sV"),lazy(app,"hC"),config,decoration,
                call(type("wgn"),"fj",value(app,"B"),policyConfig));
        nativeCamera=call(type("brex"),"t",config,jni,view.getResources(),
                call(type("j$.util.Optional"),"of",value(graph,"n")),value(graph,"s"),output,viewport,
                call(config,"k"),call(config,"i"),call(config,"d"),lazy(graph,"m"),
                call(type("bcsd"),"b",value(graph,"aB")),contributors,density,decoration);
        animation=get(nativeCamera,"z");
        policy=make("qdc",view.getContext(),config,value(app,"rT"),value(app,"rU"),nativeCamera,
                call(type("cvwu"),"b",provider(viewport)),call(graph,"M"),
                get(call(value(app,"J"),"getVectorMapsParameters"),"p"),value(graph,"cy"),null,null,make("brvj"));
        HudScene.event("hud_follow_created",map,"private brex/qdc/JNI; legacy output bound to owned bpls");
    }
    private void checkOwnership() throws Exception {
        if(get(animator,"a")!=controller || get(controller,"d")!=animator
                || get(call(map,"m"),"a")!=controller)
            throw new IllegalStateException("HUD native camera lost its owned animator/controller");
        if(view.getWidth()<=0 || view.getHeight()<=0)throw new IllegalStateException("HUD camera viewport is empty");
    }
    void update(Object nav,Object fix,boolean fresh) throws Exception {
        if(!running)return;
        synchronized(animator) {
            // B in brex retains navigation state; retire that instance on route end, even without a fix.
            if(hadRoute && nav==null)retire();
            hadRoute=nav!=null;
            if(!fresh || fix==null) {
                invalidateInput();
                note(fix==null?"waiting_position":"stale_position");
                return;
            }
            Object prediction=predictionStream==null?null:call(predictionStream,"c");
            if(inputValid && nav==lastNav && fix==lastFix && prediction==lastPrediction)return;
            inputValid=false;sampleId=-1;nativeSample=null;nativeFailure=null;
            OPERATION.set(this);
            try {
                if(nativeCamera==null) {
                    // Seed the owned readback before breu.n reads it; never animate from a tablet pan.
                    Object seed=make("bous",get(type("bouv"),"a"));
                    call(seed,"f",call(fix,"z"));set(seed,"e",16f);
                    if((Boolean)call(fix,"D"))set(seed,"g",((Number)call(fix,"n")).floatValue());
                    call(animator,"j",call(seed,"a"));
                    call(controller,"A",get(animator,"b"));
                    create();
                }
                checkOwnership();
                boolean routeMode=nav!=null && call(nav,"d")!=null
                        && (Boolean)call(type("breg"),"c",nav,call(graph,"M"));
                // Seed both modes from a usable fix; a current route carries its own snapped position.
                if(nav!=null)call(nativeCamera,"g",nav,routeMode);
                call(nativeCamera,"h",fix);
                call(nativeCamera,"f",prediction);
                if(!(Boolean)call(nativeCamera,"p") || !(Boolean)call(nativeCamera,"n"))
                    throw new IllegalStateException("HUD native camera rejected position or viewport");
                boolean heading=(Boolean)call(fix,"D");
                if(heading)call(policy,"c",nav,routeMode);else call(policy,"b");
                // Explicit refresh proves a real native location/guidance call after mode setup.
                Object refreshed=call(nativeCamera,"j");
                if(nativeFailure!=null)throw new IllegalStateException("HUD native input update",nativeFailure);
                if(!(Boolean)call(refreshed,"isPresent"))
                    throw new IllegalStateException("HUD native input update returned no result after valid mode/position");
                inputId=HudScene.commitCameraInput(map);
                if(minimumInputId==Long.MAX_VALUE)minimumInputId=inputId;
                inputValid=true;lastNav=nav;lastFix=fix;lastPrediction=prediction;
                fixTime=call(fix,"d");
                if(com.bydhud.mapcapture.LifecycleJournalTransport.detailed())note("following nav="+(nav!=null)+" bearingValid="+heading+" prediction="+(prediction!=null));
                wake();
            } catch(Throwable failure) {
                invalidateInput();
                failure=root(failure);
                if(nativeFailure!=null && nativeFailure!=failure && nativeFailure!=failure.getCause())failure.addSuppressed(nativeFailure);
                throw new IllegalStateException("HUD camera update",failure);
            } finally {OPERATION.remove();}
        }
    }
    private void note(String state) {
        if(!com.bydhud.mapcapture.LifecycleJournalTransport.detailed())return;
        long now=SystemClock.elapsedRealtime();
        if(!state.equals(inputState) || now-lastNote>=2000) {
            inputState=state;lastNote=now;
            HudScene.event("hud_follow_input",map,"input="+inputId+" "+state+" fixTime="+fixTime
                    +" navIdentity="+System.identityHashCode(lastNav)+" predictionIdentity="+System.identityHashCode(lastPrediction));
        }
    }
    private void wake() throws Exception {
        Object runnable=get(controller,"g");
        if(runnable!=null)((Runnable)runnable).run();else call(controller,"c",6);
    }
    boolean ready() { synchronized(animator){return running && inputValid;} }
    boolean acceptsInput(long id) { synchronized(animator){return running && inputValid && id>=minimumInputId;} }
    boolean ownsAnimation(Object value) {return value==animation && value!=null;}

    int sample(Object camera) throws Throwable {
        synchronized(animator) {
            sampleId=-1;nativeSample=null;sampling=true;nativeFailure=null;
            OPERATION.set(this);
            try {
                int result=((Number)originalSample.invoke(animator,camera)).intValue();
                if(nativeFailure!=null)throw new IllegalStateException("HUD native camera sample",nativeFailure);
                HudScene.cameraSample(animator,camera,result);
                return result;
            } catch(Throwable failure) {
                invalidateInput();
                Throwable original=root(failure);
                HudScene.presentationFailure(map,original);
                throw original;
            } finally {sampling=false;OPERATION.remove();}
        }
    }
    void sampled(Object value) throws Exception {
        if(!sampling || !ownsAnimation(value))return;
        Object pose=get(value,"d");
        if(running && inputValid && nativeFailure==null && (Boolean)call(pose,"isPresent")) {
            nativeSample=call(pose,"get");sampleId=inputId;
        } else {nativeSample=null;sampleId=-1;}
    }
    long sampleInput() {return OPERATION.get()==this && sampling && nativeFailure==null?sampleId:-1;}
    Object samplePose() {return nativeSample;}
    static void nativeError(Object failure) {
        HudCamera owner=OPERATION.get();
        if(owner==null)return;
        Throwable error=failure instanceof Throwable?(Throwable)failure:new IllegalStateException("Unexpected native camera error: "+failure);
        if(owner.nativeFailure==null)owner.nativeFailure=error;
        else if(owner.nativeFailure!=error)owner.nativeFailure.addSuppressed(error);
        owner.invalidateInput();
    }
    private void invalidateInput() {
        inputValid=false;sampleId=-1;nativeSample=null;
        minimumInputId=Long.MAX_VALUE;
        HudScene.invalidateCameraEvidence(map);
    }
    private void retire() throws Exception {
        invalidateInput();
        if(nativeCamera!=null) {
            call(nativeCamera,"m");
            Object scheduler=get(animator,"m");
            synchronized(scheduler) {
                call(scheduler,"g",animation);
                for(Object channel:(Object[])get(scheduler,"a"))
                    if(channel==animation)throw new IllegalStateException("HUD camera animation still attached; JNI retained");
                if(jni!=null)call(jni,"c");
            }
        } else if(jni!=null)call(jni,"c");
        nativeCamera=null;animation=null;jni=null;policy=null;
        lastNav=null;lastFix=null;lastPrediction=null;
    }
    void stop() throws Exception {
        running=false;
        Throwable failure=null;
        try {if(subscribed){call(predictionStream,"h",predictionObserver);subscribed=false;}}catch(Throwable e){failure=root(e);}
        try {synchronized(animator){retire();}}catch(Throwable e){if(failure==null)failure=root(e);else failure.addSuppressed(root(e));}
        try {if(demand){demand=false;call(location,"i");}}catch(Throwable e){if(failure==null)failure=root(e);else failure.addSuppressed(root(e));}
        if(failure!=null)throw new IllegalStateException("HUD camera cleanup",failure);
    }
}
