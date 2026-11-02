package com.bydhud.mapshud;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.Executor;
import static com.bydhud.mapshud.Reflect.*;

/** Map-scoped presentation factories; no Activity, shared-service start/stop, or global writes. */
final class HudPresentation {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object app, graph, map, container;
    private final View view;
    private Object route, position, cpp, api, routeService, routeListener, currentRoutes, currentGuidance;
    private HudCamera camera;
    private HudMarker marker;
    private volatile boolean running;
    private boolean routeStarted, positionStarted, subscribed, bootstrapReady;
    private Object seededRoutes;
    private volatile long decorationGeneration;
    private final Executor deliveries=task -> main.post(() -> { if(running) runSafely(task); });

    private void runSafely(Runnable task) {
        try {task.run();}catch(Throwable failure){HudScene.presentationFailure(map,root(failure));}
    }

    HudPresentation(Object app,Object map,Object container,Object size,View view) throws Exception {
        this.app=app; this.map=map; this.container=container; this.view=view;
        // nzv's constructor creates lazy factories only. Unused Activity factories are sealed below.
        graph=make("nzv",app,null);
        try {
        Set<String> permitted=new HashSet<>(Arrays.asList(
            "c","d","j","n","o","p","q","r","t","H","V","W","X","Y",
            "ai","aj","ak","am","an","ao","ap","aq","ar","as","at","au","av","aw","ax",
            "ay","az","aA","aB","aC","aD","aE","aF","aG","aH","aI","aJ","aW","aX",
            "E","F","aS","aT","cy"));
        for(Field f:graph.getClass().getDeclaredFields()) if(f.getType().getName().equals("cvxa") && !permitted.contains(f.getName())) {
            final String name=f.getName(); f.setAccessible(true);
            f.set(graph,proxy("cvxa",(p,m,a)->{ throw new IllegalStateException("HUD requested Activity/unscoped provider nzv."+name); }));
        }
        bind("h",view.getContext()); bind("m",map); bind("s",container); bind("l",size);
        bind("k",get(container,"p"));
        // This is the real stock HUD branch: udf.d selects bqzx for pvr.HUD.
        bind("e",call(type("bpfk"),"bydHudPolicy"));
        bind("ah",make("bqzx"));
        bind("aP",false);
        Object viewport=proxy("rsj",(p,m,a)->{
            switch(m.getName()) {
                case "a":return new Point(view.getWidth(),view.getHeight());
                case "b":case "c":case "d":case "e":case "i":return new Rect(0,0,view.getWidth(),view.getHeight());
                case "f":return view;
                case "g":return view.getWidth()>0 && view.getHeight()>0;
                case "h":return new Rect[]{new Rect(0,0,view.getWidth(),view.getHeight())};
                case "j":return make("bouw",0f,0f);
                default:throw new UnsupportedOperationException("HUD viewport "+m);
            }
        });
        bind("J",viewport);
        cpp=value(graph,"ap"); api=value(graph,"aq");
        route=value(graph,"aH");
        set(route,"g",deliveries);
        Object sharedHint=call(get(route,"aP"),"a");
        Object routeHint=make("yfd",get(sharedHint,"b"));
        set(route,"aP",call(type("cvwu"),"b",provider(routeHint)));
        Object commands=proxy("bqiw",(p,m,args)->{
            if(!running)return null;
            if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("HUD decoration command outside main lifecycle");
            if(((Number)get(args[0],"b")).intValue()==1)decorationGeneration++;
            if(validProgress())call(api,"d",args[0]);
            return null;
        });
        set(route,"aA",make("btot",commands));
        set(route,"aH",proxy("bqiq",(p,m,args)->{
            if(m.getName().equals("a")) {
                Executor ordered=task->{long generation=decorationGeneration;main.post(()->{if(running && generation==decorationGeneration)runSafely(task);});};
                call(api,"a",args[0],ordered);
            } else call(api,"b",args[0]);
            return null;
        }));
        Object routeCallout=value(graph,"at");
        HudStyle.own(routeCallout,map); HudStyle.own(get(routeCallout,"c"),map);
        HudStyle.ownRoute(route,map);
        Object nxg=get(app,"a");
        Object sink=make("ywl",view.getContext(),container,call(map,"k"),value(graph,"V"),value(graph,"W"),
            value(graph,"ai"),null,route,call(graph,"F"),value(app,"af"),value(graph,"d"),value(app,"rU"),
            value(app,"rT"),value(app,"rW"),call(app,"bj"),call(graph,"O"),call(map,"m"),viewport,
            deliveries,value(nxg,"fp"),value(app,"kH"),value(graph,"aD"),value(graph,"aA"),map,value(app,"f"),
            lazy(graph,"aI"),get(type("cbrl"),"a"),value(graph,"aJ"),value(graph,"ah"),true,value(app,"C"),value(app,"B"),value(graph,"aF"));
        bind("aK",sink);
        position=make("aorz",value(app,"fI"),value(nxg,"jM"),value(app,"af"),value(app,"bn"),call(graph,"O"),
            value(app,"f"),value(app,"sI"),value(app,"fF"),value(app,"js"),lazy(nxg,"jO"),value(graph,"d"),
            deliveries,value(app,"u"),call(graph,"H"),value(app,"fC"),value(graph,"aX"),value(app,"rT"),value(app,"B"),
            value(graph,"ah"),call(app,"ct"),call(type("cbtn"),"l",value(graph,"c")),value(app,"wt"),get(type("cbrl"),"a"),
            make("aosd",value(app,"C"),value(app,"aD"),value(app,"f")),
            make("aorm",value(app,"C"),value(app,"f"),call(app,"ct"),value(app,"sk")),get(type("cbrl"),"a"),
            value(app,"kH"),value(app,"sk"),get(type("cbrl"),"a"));
        HudStyle.own(position,map); HudStyle.own(get(position,"t"),map);
        call(position,"N",container,map,value(graph,"W"),lazy(graph,"aB"),lazy(graph,"aD"),lazy(graph,"Y"),
            value(graph,"ak"),value(graph,"aT"),view.getResources());
        marker=new HudMarker(position,map,deliveries);
        routeService=value(app,"bn");
        routeListener=proxy("bqjb",(p,m,a)->{ if(running)main.post(this::updateSafely); return null; });
        camera=new HudCamera(app,graph,map,viewport,routeHint,position,view,
                HudScene.ownedCameraAnimator(map),get(call(map,"m"),"a"),deliveries,this::updateSafely);
        HudScene.attachCamera(map,camera);
        HudScene.event("hud_presentation_created",map,"owned yxh/ywl/aorz and native decoration pair; no projected Activity");
        } catch(Throwable failure) {
            failure=root(failure);
            try {stop();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw new IllegalStateException("HUD presentation construction",root(failure));
        }
    }
    private void bind(String name,Object value) throws Exception { set(graph,name,provider(value)); }
    void start() throws Exception {
        running=true;
        call(value(graph,"d"),"a",true);
        routeStarted=true; call(value(graph,"aK"),"k");
        positionStarted=true; call(position,"o");
        camera.start();
        subscribed=true; call(routeService,"c",routeListener,deliveries);
        // Registration does not replay current input; initialize only this scene after queued deliveries.
        main.post(()->{
            if(!running)return;
            try { bootstrapReady=true; tick(); seedPosition(); tick(); }
            catch(Throwable failure){HudScene.presentationFailure(map,root(failure));}
        });
    }
    private void seedPosition() throws Exception {
        boolean routeMissing=get(route,"Q")==null;
        if(!routeMissing)return;
        Object fix=call(value(app,"js"),"c");
        if(fix==null || !freshPosition(fix)) {
            HudScene.event("hud_position_bootstrap",map,fix==null?"retained position unavailable":"retained position stale");
            return;
        }
        Object event=make("aknp",fix), affinity=get(type("bckf"),"a");
        // Invoke the stock handlers with HUD-owned receivers; never publish to the shared bus.
        call(make("yxi",1,type("aknp"),route,affinity,deliveries),"a",event);
        HudScene.event("hud_position_bootstrap",map,"retained route position delivered; marker uses selected follow input");
    }
    private void updateSafely() {
        try { tick(); } catch(Throwable e) { HudScene.presentationFailure(map,e); }
    }
    void tick() throws Exception {
        if(!running || !bootstrapReady) return;
        Object snapshot=call(type("cfru"),"bs",call(routeService,"a"));
        Object navigation=type("bqyt").isInstance(snapshot) && call(snapshot,"d")!=null?snapshot:null;
        Object fix=call(value(app,"js"),"c");
        Object routeFix=navigation==null?null:get(navigation,"a");
        if(routeFix!=null && freshPosition(routeFix))fix=routeFix;
        boolean fresh=fix!=null && freshPosition(fix);
        marker.update(navigation,fix,fresh);
        camera.update(navigation,fix,fresh);
        if(!type("bqyt").isInstance(snapshot)) {
            if(currentRoutes!=null) { decorationGeneration++; call(value(graph,"aK"),"h"); currentRoutes=null; currentGuidance=null; seededRoutes=null; }
            return;
        }
        Object state=get(snapshot,"k"), routes=get(state,"a"), guidance=call(state,"d");
        Object zap=get(guidance,"c");
        if(routes!=currentRoutes || zap!=currentGuidance) {
            // Preserve qdd's full active-route input, including traffic/incidents and stop policy.
            Object builder=call(type("zci"),"T");
            call(builder,"r",true); call(builder,"w",routes);
            Object selected=call(routes,"f");
            call(builder,"k",call(selected,"L")); set(builder,"a",zap);
            call(builder,"A",false); call(builder,"J",get(type("zbk"),"b"));
            call(builder,"j",get(type("ajjj"),"h")); call(builder,"F",true);
            call(builder,"u",get(type("zcf"),((Number)call(selected,"n")).intValue()>2?"a":"b"));
            call(value(graph,"aK"),"o",call(builder,"a"));
            currentRoutes=routes; currentGuidance=zap;
            if(com.bydhud.mapcapture.LifecycleJournalTransport.detailed())HudScene.event("hud_route_applied",map,"active route snapshot applied");
        }
        Object routePosition=get(route,"Q");
        if(routePosition!=null && currentRoutes!=seededRoutes) {
            Object current=call(route,"g");
            // Force local initial relevance after a late valid position, even while stationary.
            if(current!=null && call(current,"e")==currentRoutes && validProgress()) {
                call(route,"Y",current,true,get(value(graph,"aK"),"r"));
                seededRoutes=currentRoutes;
            }
        }
    }
    private boolean validProgress() throws Exception {
        if(route==null || position==null)return false;
        Object current=call(route,"g"),fix=call(route,"i"),progress=get(route,"N");
        if(current==null || fix==null || progress==null)return false;
        Object selected=call(call(current,"e"),"f"); if(selected==null)return false;
        long id=((Number)get(selected,"ab")).longValue();
        if(call(progress,"c",id)==null && call(progress,"d",id)==null)return false;
        if(!freshPosition(fix))return false;
        Object point=call(fix,"z");
        return call(selected,"C",point,((Number)call(point,"f")).doubleValue()*100d)!=null;
    }
    private boolean freshPosition(Object fix) throws Exception {
        Object clock=value(app,"f");
        boolean stale=(Boolean)get(get(position,"c"),"l")
            ? (Boolean)call(type("bdlt"),"u",fix,get(type("bdcx"),"z"),clock,get(type("j$.time.Duration"),"ZERO"))
            : (Boolean)call(type("bdlt"),"t",call(fix,"d"),call(clock,"f"));
        return !stale;
    }
    void stop() throws Exception {
        running=false; bootstrapReady=false;
        if(route!=null)synchronized(get(route,"Y")) {
            set(get(route,"au"),"b",null);set(get(route,"av"),"b",null);
        }
        Throwable first=null;
        if(marker!=null)marker.stop();
        try {if(camera!=null)camera.stop();}catch(Throwable e){first=e;}
        try { if(subscribed){subscribed=false;call(routeService,"d",routeListener);} } catch(Throwable e){if(first==null)first=e;else first.addSuppressed(e);}
        try { if(positionStarted){positionStarted=false;call(position,"p");call(position,"n");} } catch(Throwable e){if(first==null)first=e;else first.addSuppressed(e);}
        try { if(routeStarted){routeStarted=false;call(value(graph,"aK"),"l");} } catch(Throwable e){if(first==null)first=e;else first.addSuppressed(e);}
        // Listener delivery is posted to main, never inside native dispatch. C removed both input paths.
        if(first==null) {
            if(api!=null) { long h=((Number)get(api,"a")).longValue(); if(h!=0){call(api,"nativeUnsubscribeAndFree",h);set(api,"a",0L);} }
            if(cpp!=null) { long h=((Number)get(cpp,"a")).longValue(); if(h!=0){call(cpp,"nativeFree",h);set(cpp,"a",0L);} }
        }
        if(first!=null) throw new IllegalStateException("HUD presentation cleanup",root(first));
    }
}
