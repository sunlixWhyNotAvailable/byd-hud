package com.bydhud.app;

import static org.junit.Assert.*;
import java.util.*;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.junit.Test;

public class WazeRenderPatchTest {
    private Method draw() {
        return new ImmutableMethod("Lcom/waze/map/opengl/w;", "l", List.of(), "Z", 1, Set.of(), Set.of(),
                new ImmutableMethodImplementation(3, List.of(
                        new ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1,0,0,0,0,
                                new ImmutableMethodReference("Lcom/waze/bj/h;", "l", List.of(), "Z")),
                        new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                        new ImmutableInstruction11x(Opcode.RETURN, 0)), List.of(), List.of()));
    }
    @Test public void drawGuardIsIdempotentAndPreservesRegistersAndNativeResult() {
        Method patched = WazeRenderPatch.patch(draw());
        WazeRenderPatch.verify(patched);
        assertSame(patched, WazeRenderPatch.patch(patched));
        assertEquals(3, patched.getImplementation().getRegisterCount());
        List<Opcode> ops = new ArrayList<>();
        patched.getImplementation().getInstructions().forEach(i -> ops.add(i.getOpcode()));
        assertEquals(List.of(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_NEZ,
                Opcode.CONST_4, Opcode.RETURN, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_INTERFACE,
                Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC_RANGE, Opcode.RETURN), ops);
    }
    @Test public void incompleteSchedulingSetIsRejected() {
        assertThrows(IllegalStateException.class, () -> WazeRenderPatch.verifySet(List.of(WazeRenderPatch.patch(draw()))));
        assertFalse(WazeRenderPatch.verifySet(List.of(draw())));
    }
    @Test public void nonStaticInvocationCannotMasqueradeAsAValidHook() {
        Method patched = WazeRenderPatch.patch(draw());
        var body = new org.jf.dexlib2.builder.MutableMethodImplementation(patched.getImplementation());
        var hook = (org.jf.dexlib2.iface.instruction.ReferenceInstruction)body.getInstructions().get(0);
        body.replaceInstruction(0, new org.jf.dexlib2.builder.instruction.BuilderInstruction3rc(
                Opcode.INVOKE_VIRTUAL_RANGE, 2, 1, hook.getReference()));
        Method broken = new ImmutableMethod(patched.getDefiningClass(), patched.getName(), List.of(), "Z", 1,
                Set.of(), Set.of(), body);
        assertThrows(IllegalStateException.class, () -> WazeRenderPatch.verify(broken));
    }
    @Test public void missingNativeAnchorIsRejected() {
        Method input = draw();
        Method missing = new ImmutableMethod(input.getDefiningClass(), input.getName(), List.of(), "Z", 1, Set.of(), Set.of(),
                new ImmutableMethodImplementation(3, List.of(new ImmutableInstruction11x(Opcode.RETURN,0)), List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> WazeRenderPatch.patch(missing));
    }
}
