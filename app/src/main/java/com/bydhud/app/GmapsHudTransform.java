package com.bydhud.app;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;

/** Guarded instruction edits ported from our standalone Phoenix HUD patch. */
final class GmapsHudTransform {
    static final String HELPER="Lcom/bydhud/mapshud/HudScene;", OBJ="Ljava/lang/Object;";
    static final String STYLE="Lcom/bydhud/mapshud/HudStyle;";
    static final String ROUTE_CACHE_CTOR="Lbpnk;-><init>(Lbpqr;Lbqdt;Lbjun;Landroid/content/res/Resources;Lbpqo;Lbppg;ILbpor;JJLbpoe;[Lcmyt;Lbhxc;Lbpwl;Lbohp;ZLccdg;Ljava/lang/String;Z)V";
    static final String ROUTE_INLINE="Lbpnp;->i(Ljava/util/List;Lcvqw;F)V";
    static final String MARKER="Lcom/bydhud/mapshud/HudMarker;";
    static final String CAMERA_JNI="Lcom/google/android/libraries/geo/navcore/ui/camera/jni/CameraControllerJni;";
    static final String CAP="Lcom/bydhud/mapcapture/CaptureBridge;", BG="Lcom/bydhud/mapcapture/BackgroundCapture;";
    static final String JOURNAL="Lcom/bydhud/mapcapture/LifecycleJournalBridge;";
    static final Map<String,String> replaced=map(
        CAP+"->trackMaps("+OBJ+")V","track", CAP+"->captureMaps(Landroid/os/Bundle;)V","capture",
        BG+"->lease(Z)V","lease", BG+"->mapsStarted("+OBJ+")V","started",
        BG+"->mapsStopping("+OBJ+")V","stopping", BG+"->mapsGone("+OBJ+")V","gone");
    static final Map<String,String> wrapped=entries(
        entry("Laosa;->a(Lbcox;)V","markerInput"),
        entry("Laosc;->run()V","markerSample"),
        entry("Lbpfk;->b(Lbool;)V","paint"), entry("Lbpli;->e(Lcqsh;)V","labels"),
        entry("Lrqw;->a(Ljava/lang/String;ZLjava/lang/String;Z)Landroid/graphics/Bitmap;","shield"),
        entry("Lrqw;->b(Lbogx;Ljava/lang/String;Ljava/lang/String;ZZ)Lbooq;","shieldObject"),
        entry("Lytn;->run()V","routeDelivery"),
        entry("Lbpdd;->o(J)Lbpqf;","routeStyle"),
        entry("Lbpdc;->j(ILcypu;)Lbpqf;","namedRouteStyle"),
        entry("Lbpcw;->j(ILcypu;)Lbpqf;","namedRouteStyle"),
        entry("Lbqmc;->g(Lbppq;ILcypu;)Lbppb;","routeRow"),
        entry("Lbqmc;->j(Lbppq;Lcypu;)Lbpqf;","lateRouteStyle"),
        entry("Lcom/google/android/libraries/geo/mapcore/internal/legacy/internal/vector/gl/RoadStrokeShaderState$RoadShaderProgram;->a()Ljava/lang/String;","roadShader"),
        entry("Lcom/google/android/libraries/geo/mapcore/internal/legacy/internal/vector/gl/RoadStrokeShaderState$RoadShaderProgram;->b()Ljava/lang/String;","roadVertex"),
        entry("Lrra;->b(Landroid/content/Context;Lajjj;Lajji;Lajjk;ZZLjava/lang/String;IIZZZZZLjava/lang/String;Z)Lcnal;","callout"),
        entry("Lbofl;->k(Lbosz;Lboum;)V","cameraGate"),
        entry("Lbpcj;->f(Lbosz;Lboum;)V","cameraDispatch"),
        entry("Lbqfu;->R()V","cameraRendererFrame"),
        entry("Lbpcr;->l()Lbplu;","cameraHandlerRegistration"),
        entry("Lbpls;->h(Lbouv;ILandroid/animation/TimeInterpolator;)V","cameraTarget"),
        entry("Lbpls;->j(Lbouv;)V","cameraImmediate"),
        entry("Lbpls;->a(Lboud;)I","cameraSample"),
        entry("Lbreu;->a(J)I","nativeCameraSample"),
        entry(CAMERA_JNI+"->d(Laifu;)V","nativeCameraError"),
        entry("Lbqde;->A(Lbouv;)V","cameraPoseWrite"));
    final Map<String,Integer> hits=new TreeMap<>();
    static String sig(MethodReference m) { return m.getDefiningClass()+"->"+m.getName()+"("+String.join("",m.getParameterTypes())+")"+m.getReturnType(); }
    static ImmutableMethodReference ref(String c,String n,List<? extends CharSequence> p,String r) { return new ImmutableMethodReference(c,n,p,r); }
    static ImmutableMethod method(Method m,String name,MethodImplementation i) { return new ImmutableMethod(m.getDefiningClass(),name,m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),i); }
    static int words(Method m) { int n=AccessFlags.STATIC.isSet(m.getAccessFlags())?0:1; for(CharSequence p:m.getParameterTypes()) n+=p.toString().equals("J")||p.toString().equals("D")?2:1; return n; }
    void hit(String s) { hits.merge(s,1,Integer::sum); }
    static BuilderInstruction hook(String n,int... registers) {
        List<String> ps=new ArrayList<>(); for(int i:registers)ps.add(OBJ);
        int[] r=Arrays.copyOf(registers,5);
        return new BuilderInstruction35c(Opcode.INVOKE_STATIC,registers.length,r[0],r[1],r[2],r[3],r[4],ref(HELPER,n,ps,"V"));
    }
    static Method wrapper(Method m,String hook) {
        if(hook.equals("routeRow") || hook.equals("lateRouteStyle")) {
            int n=words(m);MutableMethodImplementation impl=new MutableMethodImplementation(n+1);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,1,n,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
            impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC,3,1,2,0,0,0,
                    ref(STYLE,hook,list(OBJ,OBJ,OBJ),OBJ)));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
            impl.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST,0,new ImmutableTypeReference(m.getReturnType())));
            impl.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT,0));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("routeStyle") || hook.equals("namedRouteStyle")) {
            MutableMethodImplementation impl=new MutableMethodImplementation(7);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,4,3,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,3));
            if(hook.equals("namedRouteStyle")) {
                // Named Phoenix styles bypass bpdd.o; keep the parent map as the ownership boundary.
                String parent=m.getDefiningClass().equals("Lbpdc;")?"a":"e";
                impl.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT,0,4,
                        new ImmutableFieldReference(m.getDefiningClass(),parent,"Lbpdd;")));
                impl.addInstruction(new BuilderInstruction12x(Opcode.INT_TO_LONG,1,5));
            } else {
                impl.addInstruction(new BuilderInstruction12x(Opcode.MOVE_OBJECT,0,4));
                impl.addInstruction(new BuilderInstruction12x(Opcode.MOVE_WIDE,1,5));
            }
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,4,
                    ref(STYLE,hook,list(OBJ,"J",OBJ),OBJ)));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
            impl.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST,0,new ImmutableTypeReference(m.getReturnType())));
            impl.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT,0));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("markerInput")) {
            MutableMethodImplementation impl=new MutableMethodImplementation(4);
            impl.addInstruction(new BuilderInstruction22c(Opcode.IGET,0,2,new ImmutableFieldReference("Laosa;","a","I")));
            impl.addInstruction(new BuilderInstruction11n(Opcode.CONST_4,1,1));
            impl.addInstruction(new BuilderInstruction22t(Opcode.IF_NE,0,1,impl.newLabelForIndex(0)));
            impl.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT,0,2,new ImmutableFieldReference("Lbcov;","d",OBJ)));
            impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC,1,0,0,0,0,0,
                    ref(MARKER,"suppressRawInput",list(OBJ),"Z")));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT,0));
            impl.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,0,impl.newLabelForIndex(0)));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            int forward=impl.getInstructions().size();
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,2,2,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            impl.replaceInstruction(2,new BuilderInstruction22t(Opcode.IF_NE,0,1,impl.newLabelForIndex(forward)));
            impl.replaceInstruction(6,new BuilderInstruction21t(Opcode.IF_EQZ,0,impl.newLabelForIndex(forward)));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("markerSample")) {
            MutableMethodImplementation impl=new MutableMethodImplementation(1);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,0,1,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC,1,0,0,0,0,0,
                    ref(MARKER,"sampled",list(OBJ),"V")));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("routeDelivery")) {
            MutableMethodImplementation impl=new MutableMethodImplementation(1);
            impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC,1,0,0,0,0,0,ref(STYLE,hook,list(OBJ),"V")));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("cameraGate") || hook.equals("cameraDispatch")) {
            int n=words(m); MutableMethodImplementation impl=new MutableMethodImplementation(n);
            String name=hook.equals("cameraGate")?"cameraGate":"cameraDispatch";
            List<String> params=list(OBJ,OBJ,OBJ);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,n,ref(HELPER,name+"Before",params,"V")));
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,0,n,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,n,ref(HELPER,name+"After",params,"V")));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("cameraRendererFrame")) {
            int n=words(m); MutableMethodImplementation impl=new MutableMethodImplementation(n);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,n,
                    ref(HELPER,"cameraRendererFrameBefore",list(OBJ),"V")));
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,0,n,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,n,
                    ref(HELPER,"cameraRendererFrameAfter",list(OBJ),"V")));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("cameraTarget")) {
            int n=words(m); MutableMethodImplementation impl=new MutableMethodImplementation(n);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,0,n,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,n,
                    ref(HELPER,"cameraTarget",list(OBJ,OBJ,"I",OBJ),"V")));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("cameraImmediate")) {
            int n=words(m); MutableMethodImplementation impl=new MutableMethodImplementation(n);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,0,n,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,n,
                    ref(HELPER,"cameraImmediateTarget",list(OBJ,OBJ),"V")));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("cameraPoseWrite")) {
            int n=words(m); MutableMethodImplementation impl=new MutableMethodImplementation(n);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,0,n,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,n,
                    ref(HELPER,"cameraPoseWrite",list(OBJ,OBJ),"V")));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("cameraSample")) {
            int n=words(m); MutableMethodImplementation impl=new MutableMethodImplementation(n+1);
            impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC,1,1,0,0,0,0,
                    ref(HELPER,"ownsCameraSample",list(OBJ),"Z")));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT,0));
            Label tablet=impl.newLabelForIndex(0);
            // Resolve the forward branch after the owned helper's return.
            impl.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ,0,tablet));
            impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC,2,1,2,0,0,0,
                    ref(HELPER,"sampleOwnedCamera",list(OBJ,OBJ),"I")));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT,0));
            impl.addInstruction(new BuilderInstruction11x(Opcode.RETURN,0));
            int tabletAt=impl.getInstructions().size();
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,1,n,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT,0));
            impl.addInstruction(new BuilderInstruction11x(Opcode.RETURN,0));
            impl.replaceInstruction(2,new BuilderInstruction21t(Opcode.IF_EQZ,0,impl.newLabelForIndex(tabletAt)));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("nativeCameraSample")) {
            int n=words(m);MutableMethodImplementation impl=new MutableMethodImplementation(n+1);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,1,n,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT,0));
            impl.addInstruction(hook("nativeCameraSample",1));
            impl.addInstruction(new BuilderInstruction11x(Opcode.RETURN,0));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("nativeCameraError")) {
            MutableMethodImplementation impl=new MutableMethodImplementation(1);
            impl.addInstruction(hook("nativeCameraError",0));
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,1,
                    ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("shieldObject")) {
            int n=words(m); MutableMethodImplementation impl=new MutableMethodImplementation(n+1);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,1,n,ref(STYLE,hook,list(OBJ,OBJ,"Ljava/lang/String;","Ljava/lang/String;","Z","Z"),OBJ)));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
            impl.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST,0,new ImmutableTypeReference(m.getReturnType())));
            impl.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT,0));
            return method(m,m.getName(),impl);
        }
        if(hook.equals("labels") || hook.equals("shield")) {
            int n=words(m); MutableMethodImplementation impl=new MutableMethodImplementation(n+1);
            List<String> params=new ArrayList<>(); params.add(OBJ); for(CharSequence p:m.getParameterTypes())params.add(p.toString());
            if(hook.equals("labels"))params.set(1,OBJ);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,1,n,ref(STYLE,hook,params,m.getReturnType())));
            if(m.getReturnType().equals("V"))impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            else {impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));impl.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT,0));}
            return method(m,m.getName(),impl);
        }
        if(hook.equals("roadShader") || hook.equals("roadVertex") || hook.equals("callout")) {
            int n=words(m),local=3; MutableMethodImplementation impl=new MutableMethodImplementation(n+local);
            impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,local,n,ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
            if(!hook.equals("callout"))impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC,1,0,0,0,0,0,ref(STYLE,hook,list("Ljava/lang/String;"),"Ljava/lang/String;")));
            else impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC,3,3,5,0,0,0,ref(STYLE,hook,list(OBJ,OBJ,OBJ),OBJ)));
            impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
            impl.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST,0,new ImmutableTypeReference(m.getReturnType())));
            impl.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT,0)); return method(m,m.getName(),impl);
        }
        int n=words(m), local=m.getReturnType().equals("V")?0:1;
        MutableMethodImplementation impl=new MutableMethodImplementation(n+local);
        if(hook.equals("paint")) {
            impl.addInstruction(hook("paint",0,1)); impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            return method(m,m.getName(),impl);
        }
        impl.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,local,n,ref(m.getDefiningClass(),"bydHudOriginal_"+m.getName(),m.getParameterTypes(),m.getReturnType())));
        if(local==1) impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
        int[] regs=new int[n+local]; for(int i=0;i<n;i++)regs[i]=local+i;
        if(local==1)regs[n]=0;
        impl.addInstruction(hook(hook,regs));
        impl.addInstruction(local==0?new BuilderInstruction10x(Opcode.RETURN_VOID):new BuilderInstruction11x(Opcode.RETURN_OBJECT,0));
        return method(m,m.getName(),impl);
    }
    static BuilderInstruction replaceInvoke(Instruction in,MethodReference ref) {
        if(in instanceof FiveRegisterInstruction f) return new BuilderInstruction35c(Opcode.INVOKE_STATIC,f.getRegisterCount(),f.getRegisterC(),f.getRegisterD(),f.getRegisterE(),f.getRegisterF(),f.getRegisterG(),ref);
        if(in instanceof RegisterRangeInstruction r) return new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,r.getStartRegister(),r.getRegisterCount(),ref);
        throw new IllegalArgumentException("not invoke: "+in);
    }
    ClassDef transform(ClassDef c) {
        List<Method> result=new ArrayList<>();
        for(Method m:c.getMethods()) {
            String key=sig(m); MethodImplementation original=m.getImplementation();
            if(original==null) { result.add(m); continue; }
            if(replaced.containsKey(key)) {
                hit(key);
                MutableMethodImplementation i=new MutableMethodImplementation(words(m));
                i.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,0,words(m),ref(HELPER,replaced.get(key),m.getParameterTypes(),"V")));
                i.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID)); result.add(method(m,m.getName(),i)); continue;
            }
            if(wrapped.containsKey(key)) {
                hit(key);
                result.add(method(m,"bydHudOriginal_"+m.getName(),original)); result.add(wrapper(m,wrapped.get(key))); continue;
            }
            MutableMethodImplementation i=new MutableMethodImplementation(original);
            int self=original.getRegisterCount()-words(m);
            if(key.equals("Lbpfk;->a()Lbool;")) {
                i.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,original.getRegisterCount()-1,1,ref(HELPER,"builder",list(OBJ),"V"))); hit(key);
            }
            boolean changed=hits.containsKey(key);
            if(key.equals("Laipz;->c(Laipt;ZLaise;)Ljava/util/List;")) {
                i.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,self,2,
                        ref(STYLE,"routeSegments",list(OBJ,OBJ),"V")));
                hit(key);changed=true;
            }
            if(key.equals(ROUTE_CACHE_CTOR)) {
                // Bind before V prepares stroke caches, including work dispatched off the GL thread.
                for(int at=i.getInstructions().size()-1;at>=0;at--)if(i.getInstructions().get(at).getOpcode()==Opcode.RETURN_VOID) {
                    i.replaceInstruction(at,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,self,1,
                            ref(STYLE,"routeCache",list(OBJ),"V")));
                    i.addInstruction(at+1,new BuilderInstruction10x(Opcode.RETURN_VOID));hit(key);
                }
                changed=true;
            }
            if(key.equals(ROUTE_INLINE)) {
                if(original.getRegisterCount()!=14 || self!=10)throw new IllegalStateException("Inline route register layout changed");
                int descriptor=-1,merge=-1;
                for(int at=0;at<i.getInstructions().size();at++) {
                    Instruction in=i.getInstructions().get(at);
                    if(in instanceof ReferenceInstruction ri && ri.getReference() instanceof FieldReference f
                            && f.getDefiningClass().equals("Lbpnn;") && f.getName().equals("b") && f.getType().equals("Lbppq;")) {
                        TwoRegisterInstruction iget=(TwoRegisterInstruction)in;
                        if(descriptor!=-1 || in.getOpcode()!=Opcode.IGET_OBJECT || iget.getRegisterA()!=3 || iget.getRegisterB()!=1)
                            throw new IllegalStateException("Inline route descriptor changed");
                        descriptor=at;
                    }
                    if(in instanceof ReferenceInstruction ri && ri.getReference() instanceof MethodReference mr
                            && sig(mr).equals("Ljava/util/List;->get(I)Ljava/lang/Object;") && merge==-1)merge=at;
                }
                if(descriptor<0 || merge<=descriptor)throw new IllegalStateException("Inline route lookup missing");
                // v9 is dead throughout this pinned lookup. Keep the descriptor after v3 is reused.
                for(int at=descriptor+1;at<merge;at++)if(usesRegister(i.getInstructions().get(at),9))
                    throw new IllegalStateException("Inline route scratch register is live");
                BuilderInstruction anchor=i.getInstructions().get(merge);
                // Replacing the labeled merge instruction makes every lookup branch enter the hook.
                i.replaceInstruction(merge,new BuilderInstruction35c(Opcode.INVOKE_STATIC,3,self,9,1,0,0,
                        ref(STYLE,"routeRow",list(OBJ,OBJ,OBJ),OBJ)));
                i.addInstruction(merge+1,new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,1));
                i.addInstruction(merge+2,new BuilderInstruction21c(Opcode.CHECK_CAST,1,new ImmutableTypeReference("Lbppb;")));
                i.addInstruction(merge+3,anchor);
                i.addInstruction(descriptor+1,new BuilderInstruction12x(Opcode.MOVE_OBJECT,9,3));
                hit(key);changed=true;
            }
            if(key.equals("Laotw;->m(IZLbqdm;IFLjava/lang/Float;)Laotx;")) {
                if(original.getRegisterCount()!=19 || self!=12)throw new IllegalStateException("Accuracy style register layout changed");
                // In the pinned method v3 is dead until the later geometry-construction branches.
                // p1 is overwritten by getColor; retain its resource ID across style construction.
                i.addInstruction(0,new BuilderInstruction12x(Opcode.MOVE,3,self+1));
            }
            if(key.equals("Laotw;-><init>(Landroid/content/res/Resources;Lbooa;Lbchm;Lbool;Lajgu;Ldbga;)V")) {
                if(self>250)throw new IllegalStateException("position constructor register bound");
                // Constructor locals 0/1 are scratch before the original first instruction.
                if(self<2)throw new IllegalStateException("position constructor lacks scratch locals");
                i.addInstruction(0,new BuilderInstruction22x(Opcode.MOVE_OBJECT_FROM16,0,self+1));
                i.addInstruction(1,new BuilderInstruction22x(Opcode.MOVE_OBJECT_FROM16,1,self+4));
                i.addInstruction(2,new BuilderInstruction35c(Opcode.INVOKE_STATIC,2,0,1,0,0,0,ref(STYLE,"positionResources",list("Landroid/content/res/Resources;",OBJ),"Landroid/content/res/Resources;")));
                i.addInstruction(3,new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,self+1));
                for(int at=i.getInstructions().size()-1;at>=0;at--)if(i.getInstructions().get(at).getOpcode()==Opcode.RETURN_VOID)
                    i.addInstruction(at,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,self,1,ref(STYLE,"positionFactory",list(OBJ),"V")));
                hit(key);changed=true;
            }
            if(key.equals("Laotw;->n(Lboke;Laqxd;Ljava/lang/String;IIII)Ljava/lang/Object;")) {
                i.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,self+1,2,ref(STYLE,"caption",list(OBJ,"Ljava/lang/String;"),"Ljava/lang/String;")));
                i.addInstruction(1,new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,self+2));hit(key);changed=true;
            }
            String entry=null;
            if(key.equals("Lbptm;->j()Z"))entry="frame";
            if(key.equals("Lbptm;->b()V"))entry="context";
            if(key.equals("Lbpnk;->u(Lboud;)V"))entry="tile";
            if(key.equals("Lbqgi;->b(Lbqel;Lbqdo;Lbqde;[F[F[F)V"))entry="shaderState";
            if(key.equals("Lbokd;->a()Lbooq;"))entry="shieldBuilder";
            if(entry!=null) {
                i.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,self,1,ref(STYLE,entry,list(OBJ),"V")));hit(key);changed=true;
            }
            if(c.getType().equals("Lbpbm;") && set("v","w","t").contains(m.getName()) && m.getParameterTypes().get(0).toString().equals("Lcnal;")) {
                i.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,self,2,ref(STYLE,"label",list(OBJ,OBJ),OBJ)));
                i.addInstruction(1,new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,self+1));
                i.addInstruction(2,new BuilderInstruction21c(Opcode.CHECK_CAST,self+1,new ImmutableTypeReference("Lcnal;")));hit("label:"+key);changed=true;
            }
            if(key.equals("Lbpbm;->u(Lcnal;)Lbpae;")) {
                i.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,self,2,ref(STYLE,"labelLookup",list(OBJ,OBJ),OBJ)));
                i.addInstruction(1,new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,self+1));
                i.addInstruction(2,new BuilderInstruction21c(Opcode.CHECK_CAST,self+1,new ImmutableTypeReference("Lcnal;")));hit(key);changed=true;
            }
            if(key.equals("Lbpbm;->c(Lbomi;)V")) {
                i.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,self,2,ref(STYLE,"labelRemove",list(OBJ,OBJ),"V")));hit(key);changed=true;
            }
            for(int at=i.getInstructions().size()-1;at>=0;at--) {
                Instruction in=i.getInstructions().get(at);
                if(!(in instanceof ReferenceInstruction ri) || !(ri.getReference() instanceof MethodReference mr)) continue;
                if(key.equals("Laotw;->m(IZLbqdm;IFLjava/lang/Float;)Laotx;")
                        && sig(mr).equals("Landroid/graphics/Color;->argb(IIII)I")) {
                    if(i.getInstructions().get(at+1).getOpcode()!=Opcode.MOVE_RESULT
                            || ((OneRegisterInstruction)i.getInstructions().get(at+1)).getRegisterA()!=2)
                        throw new IllegalStateException("Accuracy outline result register changed");
                    i.addInstruction(at+2,new BuilderInstruction35c(Opcode.INVOKE_STATIC,3,self,3,2,0,0,
                            ref(STYLE,"positionAccuracyOutline",list(OBJ,"I","I"),"I")));
                    i.addInstruction(at+3,new BuilderInstruction11x(Opcode.MOVE_RESULT,2));
                    hit("accuracyOutline:"+key);changed=true;
                }
                if(key.equals("Lbqcz;->a()Landroid/graphics/Bitmap;") && sig(mr).equals("Landroid/graphics/BitmapFactory;->decodeResource(Landroid/content/res/Resources;I)Landroid/graphics/Bitmap;")) {
                    i.replaceInstruction(at,replaceInvoke(in,ref(STYLE,"positionBitmap",mr.getParameterTypes(),mr.getReturnType())));
                    hit("positionBitmap:"+key);changed=true;
                }
                if(c.getType().equals("Lbqel;") && (sig(mr).equals("Landroid/opengl/GLES20;->glShaderSource(ILjava/lang/String;)V") || sig(mr).equals("Landroid/opengl/GLES20;->glClearColor(FFFF)V"))) {
                    String name=mr.getName().equals("glShaderSource")?"shaderSource":"clearColor";
                    i.replaceInstruction(at,replaceInvoke(in,ref(STYLE,name,mr.getParameterTypes(),"V")));hit(name+":"+key);changed=true;
                }

            }
            if(changed) { result.add(method(m,m.getName(),i)); } else result.add(m);
        }
        if(c.getType().equals("Lbpfk;"))result.add(policyFactory());
        return new ImmutableClassDef(c.getType(),c.getAccessFlags(),c.getSuperclass(),c.getInterfaces(),c.getSourceFile(),c.getAnnotations(),c.getFields(),result);
    }
    static Method policyFactory() {
        // R8 removed pux/ufk constructors. Build the stock non-interactive policy at DEX level.
        MutableMethodImplementation i=new MutableMethodImplementation(5);
        i.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE,0,new ImmutableTypeReference("Lpux;")));
        i.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT,1,0,0,0,0,0,ref("Ljava/lang/Object;","<init>",list(),"V")));
        i.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE,1,new ImmutableTypeReference("Lufk;")));
        i.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT,1,1,0,0,0,0,ref("Ljava/lang/Object;","<init>",list(),"V")));
        i.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE,2,new ImmutableTypeReference("Lcbuu;")));
        i.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT,2,2,1,0,0,0,ref("Lcbuu;","<init>",list(OBJ),"V")));
        i.addInstruction(new BuilderInstruction21c(Opcode.SGET_OBJECT,3,new ImmutableFieldReference("Lpvr;","e","Lpvr;")));
        i.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE,4,new ImmutableTypeReference("Lpum;")));
        i.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT,4,4,3,0,2,0,ref("Lpum;","<init>",list("Lpvr;","Lpuy;","Lcbuq;"),"V")));
        i.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT,4));
        return new ImmutableMethod("Lbpfk;","bydHudPolicy",list(),"Lpvu;",AccessFlags.PUBLIC.getValue()|AccessFlags.STATIC.getValue(),set(),set(),i);
    }
    static boolean usesRegister(Instruction in,int register) {
        if(in instanceof RegisterRangeInstruction r)return register>=r.getStartRegister() && register<r.getStartRegister()+r.getRegisterCount();
        if(in instanceof FiveRegisterInstruction r) {
            int[] regs={r.getRegisterC(),r.getRegisterD(),r.getRegisterE(),r.getRegisterF(),r.getRegisterG()};
            for(int n=0;n<r.getRegisterCount();n++)if(regs[n]==register)return true;
        }
        return in instanceof OneRegisterInstruction a && (a.getRegisterA()==register
                || in.getOpcode().setsWideRegister() && a.getRegisterA()+1==register)
                || in instanceof TwoRegisterInstruction b && b.getRegisterB()==register
                || in instanceof ThreeRegisterInstruction c && c.getRegisterC()==register;
    }

    @SafeVarargs static <T> List<T> list(T... values) { return Arrays.asList(values); }
    @SafeVarargs static <T> Set<T> set(T... values) { return new HashSet<>(Arrays.asList(values)); }
    static Map<String,String> map(String... pairs) {
        Map<String,String> result=new LinkedHashMap<>();
        for(int index=0;index<pairs.length;index+=2)result.put(pairs[index],pairs[index+1]);
        return result;
    }
    static Map.Entry<String,String> entry(String key,String value) { return new AbstractMap.SimpleImmutableEntry<>(key,value); }
    @SafeVarargs static Map<String,String> entries(Map.Entry<String,String>... values) {
        Map<String,String> result=new LinkedHashMap<>();
        for(Map.Entry<String,String> value:values)result.put(value.getKey(),value.getValue());
        return result;
    }
}
