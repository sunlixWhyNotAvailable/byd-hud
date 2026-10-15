package com.bydhud.app;

import java.util.*;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;

/** Shared by the offline recipe and on-device patcher; upgrades preserve existing hooks. */
public final class WazeRenderPatch {
    public static final String OWNER = "Lcom/bydhud/mapcapture/WazeRenderScheduler;";
    private static final String BACKGROUND = "Lcom/bydhud/mapcapture/BackgroundCapture;";
    private static final String WRAPPER = "Lcom/waze/map/opengl/w;->l()Z";
    private static final String THREAD = "Lcom/waze/bj/e;->run()V";
    private static final String STOP = "Lcom/waze/bj/e;->h()V";
    private static final String SURFACE = "Lcom/waze/bj/r;->l(Lcom/waze/realtime_report_feedback/e;)V";
    private static final String CALLBACK = "Lcom/waze/bj/q;->e(Ljava/lang/Runnable;)V";
    private static final String HOST = "Lcom/waze/car_lib/WazeCarAppService;->onDestroy()V";
    public static Set<String> owners() {
        return new HashSet<>(Arrays.asList("Lcom/waze/map/opengl/w;", "Lcom/waze/bj/e;",
                "Lcom/waze/bj/r;", "Lcom/waze/bj/q;", "Lcom/waze/car_lib/WazeCarAppService;"));
    }
    public static Set<String> owners(boolean modern) {
        Set<String> result = owners();
        if (!modern) return result;
        Set<String> renamed = new HashSet<>();
        for (String owner : result) renamed.add(owner.replace("Lcom/waze/bj/", "Lcom/waze/bl/"));
        return renamed;
    }
    public static String key(MethodReference m) {
        StringBuilder b = new StringBuilder(m.getDefiningClass()).append("->").append(m.getName()).append('(');
        for (CharSequence p : m.getParameterTypes()) b.append(p);
        return b.append(')').append(m.getReturnType()).toString().replace("Lcom/waze/bl/", "Lcom/waze/bj/");
    }
    public static boolean extension(MethodReference m) {
        return OWNER.equals(m.getDefiningClass()) || key(m).equals(BACKGROUND + "->wazeHostDestroyed(Ljava/lang/Object;)Z");
    }
    private static MethodReference called(Instruction i) {
        return i instanceof ReferenceInstruction && ((ReferenceInstruction)i).getReference() instanceof MethodReference
                ? (MethodReference)((ReferenceInstruction)i).getReference() : null;
    }
    private static ImmutableMethodReference ref(String name, int args, String result) {
        return new ImmutableMethodReference(name.equals("wazeHostDestroyed") ? BACKGROUND : OWNER,
                name, Collections.nCopies(args, "Ljava/lang/Object;"), result);
    }
    private static boolean is(Instruction i, String name) {
        MethodReference m = called(i);
        return m != null && extension(m) && m.getName().equals(name);
    }
    private static void insert(MutableMethodImplementation body, int index, int receiver, String name) {
        var location = body.getInstructions().get(index).getLocation();
        List<Label> labels = new ArrayList<>(location.getLabels());
        var hook = new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, receiver, 1, ref(name, 1, "V"));
        body.addInstruction(index, hook);
        for (Label label : labels) { location.getLabels().remove(label); hook.getLocation().getLabels().add(label); }
    }
    public static Method patch(Method method) {
        return patch(method, false);
    }
    public static Method patch(Method method, boolean modern) {
        String owner = method.getDefiningClass();
        if (owner.startsWith(modern ? "Lcom/waze/bj/" : "Lcom/waze/bl/")) return method;
        String key = key(method);
        if (!Arrays.asList(WRAPPER, THREAD, SURFACE, CALLBACK, STOP, HOST).contains(key)
                || method.getImplementation() == null) return method;
        List<Instruction> before = new ArrayList<>(); method.getImplementation().getInstructions().forEach(before::add);
        if (before.stream().anyMatch(i -> called(i) != null && extension(called(i)))) {
            verify(method); return method;
        }
        int receiver = method.getImplementation().getRegisterCount() - 1 - method.getParameterTypes().size();
        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
        int anchors = 0;
        if (key.equals(WRAPPER)) {
            if (receiver < 1 || receiver > 255) throw new IllegalStateException("Waze draw registers changed");
            for (int i = body.getInstructions().size() - 1; i >= 0; i--) {
                MethodReference call = called(body.getInstructions().get(i));
                if (call == null || !key(call).equals("Lcom/waze/bj/h;->l()Z")) continue;
                if (body.getInstructions().get(i+1).getOpcode() != Opcode.MOVE_RESULT)
                    throw new IllegalStateException("Waze render result changed");
                insert(body, i+2, receiver, "end"); insert(body, i, receiver, "begin"); anchors++;
            }
            if (anchors != 1) throw new IllegalStateException("Waze native render anchor missing");
            Label original = body.newLabelForIndex(0);
            body.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, receiver, 1, ref("allow", 1, "Z")));
            body.addInstruction(1, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
            body.addInstruction(2, new BuilderInstruction21t(Opcode.IF_NEZ, 0, original));
            body.addInstruction(3, new BuilderInstruction11n(Opcode.CONST_4, 0, 0));
            body.addInstruction(4, new BuilderInstruction11x(Opcode.RETURN, 0));
        } else if (key.equals(THREAD)) {
            for (int i = 0; i < body.getInstructions().size(); i++) {
                Instruction op = body.getInstructions().get(i); MethodReference call = called(op);
                if (call == null || !key(call).equals("Ljava/lang/Object;->wait(J)V")) continue;
                if (!(op instanceof FiveRegisterInstruction) || receiver > 15)
                    throw new IllegalStateException("Waze wait registers changed");
                int monitor = ((FiveRegisterInstruction)op).getRegisterC();
                body.replaceInstruction(i, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2,
                        monitor, receiver, 0, 0, 0, ref("await", 2, "V"))); anchors++;
            }
            if (anchors != 1) throw new IllegalStateException("Waze idle wait anchor missing");
        } else if (key.equals(HOST)) {
            MethodReference call = called(body.getInstructions().get(0));
            if (receiver < 1 || call == null || !call.getName().equals("onDestroy")
                    || body.getInstructions().get(0).getOpcode() != Opcode.INVOKE_SUPER)
                throw new IllegalStateException("Waze host cleanup anchor changed");
            Label original = body.newLabelForIndex(1);
            body.addInstruction(1, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, receiver, 1, ref("wazeHostDestroyed", 1, "Z")));
            body.addInstruction(2, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
            body.addInstruction(3, new BuilderInstruction21t(Opcode.IF_EQZ, 0, original));
            body.addInstruction(4, new BuilderInstruction10x(Opcode.RETURN_VOID));
        } else {
            for (int i = body.getInstructions().size()-1; i >= 0; i--)
                if (body.getInstructions().get(i).getOpcode() == Opcode.RETURN_VOID)
                    insert(body, i, receiver, key.equals(SURFACE) ? "surfaceQueued" : key.equals(STOP) ? "threadStopping" : "callbackQueued");
        }
        Method changed = new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
        verify(changed); return changed;
    }
    private static boolean branch(List<Instruction> body, int from, int to) {
        int address = 0; for (int i = from; i < to; i++) address += body.get(i).getCodeUnits();
        return body.get(from) instanceof OffsetInstruction && ((OffsetInstruction)body.get(from)).getCodeOffset() == address;
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalStateException("Invalid Waze scheduling hook placement"); }
    public static void verify(Method method) {
        if (method.getImplementation() == null) return;
        List<Instruction> body = new ArrayList<>(); method.getImplementation().getInstructions().forEach(body::add);
        Map<String,Integer> actual = new TreeMap<>();
        int receiver = method.getImplementation().getRegisterCount()-1-method.getParameterTypes().size();
        for (Instruction op : body) {
            MethodReference m = called(op); if (m == null || !extension(m)) continue;
            actual.put(m.getName(), actual.getOrDefault(m.getName(), 0)+1);
            if (!m.getName().equals("await")) require(op.getOpcode()==Opcode.INVOKE_STATIC_RANGE && op instanceof RegisterRangeInstruction
                    && ((RegisterRangeInstruction)op).getStartRegister() == receiver
                    && ((RegisterRangeInstruction)op).getRegisterCount() == 1
                    && key(m).equals(key(ref(m.getName(), 1,
                            m.getName().equals("allow") || m.getName().equals("wazeHostDestroyed") ? "Z" : "V"))));
        }
        if (actual.isEmpty()) return;
        String key = key(method);
        if (key.equals(WRAPPER)) {
            require(actual.size()==3 && actual.getOrDefault("allow",0)==1
                    && actual.getOrDefault("begin",0)==1 && actual.getOrDefault("end",0)==1);
            require(body.size() >= 9);
            require(is(body.get(0),"allow") && body.get(1).getOpcode()==Opcode.MOVE_RESULT
                    && ((OneRegisterInstruction)body.get(1)).getRegisterA()==0
                    && body.get(2).getOpcode()==Opcode.IF_NEZ && ((OneRegisterInstruction)body.get(2)).getRegisterA()==0
                    && branch(body,2,5) && body.get(3).getOpcode()==Opcode.CONST_4
                    && ((OneRegisterInstruction)body.get(3)).getRegisterA()==0 && ((NarrowLiteralInstruction)body.get(3)).getNarrowLiteral()==0
                    && body.get(4).getOpcode()==Opcode.RETURN && ((OneRegisterInstruction)body.get(4)).getRegisterA()==0);
            for (int i=0;i<body.size();i++) if (is(body.get(i),"begin")) {
                require(i+3 < body.size());
                require(called(body.get(i+1)) != null && key(called(body.get(i+1))).equals("Lcom/waze/bj/h;->l()Z")
                        && body.get(i+2).getOpcode()==Opcode.MOVE_RESULT && is(body.get(i+3),"end"));
            }
        } else if (key.equals(THREAD)) {
            require(actual.equals(Collections.singletonMap("await",1)));
            for (int i=0;i<body.size();i++) if (is(body.get(i),"await")) {
                require(i >= 2 && i+1 < body.size());
                Instruction op=body.get(i); require(op instanceof FiveRegisterInstruction
                        && op.getOpcode()==Opcode.INVOKE_STATIC && ((FiveRegisterInstruction)op).getRegisterCount()==2
                        && ((FiveRegisterInstruction)op).getRegisterD()==receiver
                        && key(called(op)).equals(key(ref("await",2,"V")))
                        && body.get(i-2).getOpcode()==Opcode.MONITOR_ENTER
                        && ((OneRegisterInstruction)body.get(i-2)).getRegisterA()==((FiveRegisterInstruction)op).getRegisterC()
                        && body.get(i+1).getOpcode()==Opcode.MONITOR_EXIT);
            }
        } else if (key.equals(HOST)) {
            require(actual.equals(Collections.singletonMap("wazeHostDestroyed",1)));
            require(body.size() >= 6);
            require(body.get(0).getOpcode()==Opcode.INVOKE_SUPER
                    && key(called(body.get(0))).equals("Landroidx/car/app/CarAppService;->onDestroy()V")
                    && ((FiveRegisterInstruction)body.get(0)).getRegisterC()==receiver
                    && is(body.get(1),"wazeHostDestroyed")
                    && body.get(2).getOpcode()==Opcode.MOVE_RESULT && body.get(3).getOpcode()==Opcode.IF_EQZ
                    && ((OneRegisterInstruction)body.get(2)).getRegisterA()==0 && ((OneRegisterInstruction)body.get(3)).getRegisterA()==0
                    && body.get(4).getOpcode()==Opcode.RETURN_VOID && branch(body,3,5));
        } else {
            String name=key.equals(SURFACE) ? "surfaceQueued" : key.equals(CALLBACK) ? "callbackQueued" : key.equals(STOP) ? "threadStopping" : "invalid";
            int returns=0;
            for(int i=0;i<body.size();i++) if(body.get(i).getOpcode()==Opcode.RETURN_VOID) { returns++; require(i>0 && is(body.get(i-1),name)); }
            require(returns>0 && actual.equals(Collections.singletonMap(name,returns)));
        }
    }
    public static boolean verifySet(Iterable<? extends Method> methods) {
        Set<String> scheduled = new HashSet<>(); boolean host=false;
        for (Method m:methods) {
            verify(m); if(m.getImplementation()==null) continue;
            for(Instruction i:m.getImplementation().getInstructions()) {
                MethodReference ref=called(i); if(ref==null || !extension(ref)) continue;
                if(ref.getName().equals("wazeHostDestroyed")) host=true; else scheduled.add(key(m));
            }
        }
        if(scheduled.isEmpty()) return false;
        require(host && scheduled.equals(new HashSet<>(Arrays.asList(WRAPPER, THREAD, SURFACE, CALLBACK, STOP))));
        return true;
    }
}
