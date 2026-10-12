package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.builder.Label;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodParameter;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class NavigatorMapPatcherControlFlowTest {
    @Test public void postCallHookDoesNotBecomeTargetOfBranchThatSkipsCall() throws Exception {
        ImmutableMethodReference anchor = new ImmutableMethodReference("Lbrfd;", "f",
                Arrays.asList("Lcksk;", "Lbrfe;"), "V");
        MutableMethodImplementation body = new MutableMethodImplementation(4);
        body.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        Label originalReturn = body.newLabelForIndex(0);
        body.addInstruction(0, new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL,
                3, 1, 2, 3, 0, 0, anchor));
        body.addInstruction(0, new BuilderInstruction21t(Opcode.IF_EQZ, 0, originalReturn));

        List<ImmutableMethodParameter> parameters = Arrays.asList(
                new ImmutableMethodParameter("Lbrha;", Collections.emptySet(), null),
                new ImmutableMethodParameter("Lbrha;", Collections.emptySet(), null));
        Method source = new ImmutableMethod("Lbrer;", "pF", parameters, "V",
                AccessFlags.PUBLIC.getValue(), Collections.emptySet(), Collections.emptySet(), body);

        Class<?> planType = Arrays.stream(NavigatorMapPatcher.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("PatchPlan")).findFirst().get();
        Constructor<?> constructor = planType.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        Object plan = constructor.newInstance("gmaps");
        java.lang.reflect.Method patchMethod = NavigatorMapPatcher.class.getDeclaredMethod(
                "patchMethod", Method.class, planType);
        patchMethod.setAccessible(true);
        Method changed = (Method) patchMethod.invoke(null, source, plan);

        int branchAddress = -1;
        int branchTarget = -1;
        int appliedHookAddress = -1;
        int returnAddress = -1;
        int address = 0;
        for (Instruction instruction : changed.getImplementation().getInstructions()) {
            if (instruction.getOpcode() == Opcode.IF_EQZ) {
                branchAddress = address;
                branchTarget = address + ((OffsetInstruction) instruction).getCodeOffset();
            }
            if (instruction.getOpcode() == Opcode.RETURN_VOID) returnAddress = address;
            if (instruction instanceof ReferenceInstruction
                    && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference
                    && ((MethodReference) ((ReferenceInstruction) instruction).getReference())
                    .getName().equals("mapsNavigationApplied")) {
                appliedHookAddress = address;
            }
            address += instruction.getCodeUnits();
        }

        assertTrue(branchAddress >= 0);
        assertTrue(appliedHookAddress >= 0);
        assertTrue(returnAddress > appliedHookAddress);
        assertEquals(returnAddress, branchTarget);
        assertFalse("branch bypasses the original call and its post-call hook", branchTarget == appliedHookAddress);
    }
}
