package com.bydhud.app;

import java.io.IOException;
import java.util.*;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;

/** Edits the supplied method; no navigator implementation is stored here. */
final class WazeHudTransform {
    private static final Map<String, String> ALERTS = GmapsHudTransform.map(
        "Lcom/waze/car_lib/a/ak;->S(Landroidx/car/app/CarContext;Lkotlinx/coroutines/ae;)V", "producer",
        "Lcom/waze/car_lib/a/ak;->b(Lcom/waze/car_lib/a/ak;Lcom/waze/k;Lh/g/a/a;Lh/g/a/l;Lh/g/a/a;)Lcom/waze/car_lib/a/q;", "input",
        "Lcom/waze/car_lib/a/ak;->K(Lcom/waze/car_lib/a/ak;Lcom/waze/car_lib/a/q;)V", "publish",
        "Lcom/waze/car_lib/a/c;->invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "collect",
        "Lcom/waze/car_lib/a/e;->onResume(Landroidx/lifecycle/LifecycleOwner;)V", "resume",
        "Lcom/waze/car_lib/a/e;->onPause(Landroidx/lifecycle/LifecycleOwner;)V", "pause");

    static Method patch(Method method, String component) throws IOException {
        if (method.getImplementation() == null) throw new IOException("Missing Waze method implementation");
        if (component.equals("audio")) return audio(method);
        String observer = ALERTS.get(NavigatorHudProfile.key(method));
        if (observer == null) {
            Method result = NavigatorMapPatcher.patchCaptureMethod(method, "waze");
            if (NavigatorHudProfile.key(method).equals("Lcom/waze/mobile/WazeMobileApplication;->onCreate()V")) {
                MutableMethodImplementation startup = new MutableMethodImplementation(result.getImplementation());
                List<? extends Instruction> code = startup.getInstructions();
                if (!call(code.get(0), "Lcom/bydhud/mapcapture/CaptureBridge;", "init")
                        || !call(code.get(1), "Lcom/waze/bydhud/RouteStateBridgeV2;", "init")
                        || !call(code.get(2), "Lcom/bydhud/navexport/WazeRouteExport;", "init"))
                    throw new IOException("Waze Direct initialization order differs");
                Instruction capture = code.get(0), route = code.get(1), export = code.get(2);
                startup.replaceInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                        ((RegisterRangeInstruction) export).getStartRegister(), 1,
                        ((ReferenceInstruction) export).getReference()));
                startup.replaceInstruction(1, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                        ((RegisterRangeInstruction) capture).getStartRegister(), 1,
                        ((ReferenceInstruction) capture).getReference()));
                startup.replaceInstruction(2, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1,
                        ((FiveRegisterInstruction) route).getRegisterC(), 0, 0, 0, 0,
                        ((ReferenceInstruction) route).getReference()));
                result = withBody(result, startup);
            }
            return result;
        }
        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
        body.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                body.getRegisterCount() - GmapsHudTransform.words(method), 2,
                new ImmutableMethodReference("Lcom/bydhud/mapcapture/WazeHudDiagnostics;", observer,
                        Arrays.asList("Ljava/lang/Object;", "Ljava/lang/Object;"), "V")));
        return withBody(method, body);
    }

    private static boolean call(Instruction instruction, String owner, String name) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof MethodReference)) return false;
        MethodReference method = (MethodReference) reference;
        return method.getDefiningClass().equals(owner) && method.getName().equals(name);
    }

    private static Method audio(Method method) throws IOException {
        byte[] encoded = NavigatorHudProfile.method(method);
        Method input = new DexBackedDexFile(Opcodes.forApi(35), encoded).getClasses().iterator().next().getMethods().iterator().next();
        List<Instruction> code = new ArrayList<>(); input.getImplementation().getInstructions().forEach(code::add);
        String builder = method.getName().equals("f") ? "Landroid/media/AudioAttributes$Builder;"
                : "Landroidx/media3/common/AudioAttributes$Builder;";
        int calls = 0;
        for (int index = 0; index < code.size(); index++) {
            if (!call(code.get(index), builder, "setContentType")) continue;
            if (++calls != 1) continue;
            if (index < 4 || !call(code.get(index - 4), "Lcom/waze/h/a/a;", "a")
                    || code.get(index - 3).getOpcode() != Opcode.MOVE_RESULT
                    || code.get(index - 2).getOpcode() != Opcode.IF_NE
                    || code.get(index - 1).getOpcode() != Opcode.MOVE)
                throw new IOException("Waze navigation audio selection changed");
            TwoRegisterInstruction branch = (TwoRegisterInstruction) code.get(index - 2);
            TwoRegisterInstruction move = (TwoRegisterInstruction) code.get(index - 1);
            FiveRegisterInstruction content = (FiveRegisterInstruction) code.get(index);
            if (((OffsetInstruction) branch).getCodeOffset() != 3
                    || branch.getRegisterB() != ((OneRegisterInstruction) code.get(index - 3)).getRegisterA()
                    || move.getRegisterB() != branch.getRegisterA() || move.getRegisterA() >= 16
                    || content.getRegisterCount() != 2 || content.getRegisterD() != move.getRegisterA())
                throw new IOException("Waze navigation audio register/branch guard failed");
            // Preserve the three code units and the assistant branch after this navigation selection.
            int start = ((DexBackedInstruction) branch).instructionStart;
            int end = ((DexBackedInstruction) content).instructionStart;
            if (end - start != 6) throw new IOException("Waze navigation audio byte span differs");
            int destination = move.getRegisterA();
            Arrays.fill(encoded, start, end, (byte) 0);
            encoded[start] = 0x12; encoded[start + 1] = (byte) (0x60 | destination);
        }
        if (calls != 2) throw new IOException("Expected navigation and assistant audio builders");
        // Constant-width byte edits retain all input branch, exception and debug offsets.
        return new DexBackedDexFile(Opcodes.forApi(35), encoded).getClasses().iterator().next().getMethods().iterator().next();
    }

    private static Method withBody(Method method, MutableMethodImplementation body) {
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
    }
}
