package com.bydhud.app;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class WazePatchEngine {
    private static final String WAZE_CLASS = "Lcom/waze/car_lib/WazeCarAppService;";
    private static final String WAZE_METHOD = "createHostValidator";
    private static final String HOST_VALIDATOR = "Landroidx/car/app/validation/HostValidator;";
    private static final String HOST_VALIDATOR_BUILDER =
            "Landroidx/car/app/validation/HostValidator$Builder;";
    private static final String APPLICATION_CLASS =
            "Lcom/waze/mobile/WazeMobileApplication;";
    private static final String ROUTE_CLASS = "Lcom/waze/navigate/dt;";
    private static final String ROUTE_ENUM = "Lcom/waze/navigate/ee;";
    private static final String ROUTE_FLOW = "Lkotlinx/coroutines/b/bn;";
    private static final String BRIDGE_CLASS = "Lcom/waze/bydhud/RouteStateBridgeV2;";
    private static final ImmutableMethodReference BRIDGE_INIT = new ImmutableMethodReference(
            BRIDGE_CLASS, "init", Collections.singletonList("Landroid/content/Context;"), "V");
    private static final ImmutableMethodReference BRIDGE_EMIT = new ImmutableMethodReference(
            BRIDGE_CLASS, "emit", Collections.singletonList("Z"), "V");
    static final String PATCHABLE_STOCK = "PATCHABLE_STOCK";
    static final String ALREADY_PATCHED = "ALREADY_PATCHED";
    static final String UNSUPPORTED = "UNSUPPORTED";

    private WazePatchEngine() {
    }

    static WazeInspection inspectWaze(byte[] dex) throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(dex));
        int matches = 0;
        WazeInspection result = null;
        for (ClassDef classDef : file.getClasses()) {
            if (!WAZE_CLASS.equals(classDef.getType())) continue;
            for (Method method : classDef.getMethods()) {
                if (WAZE_METHOD.equals(method.getName())
                        && method.getParameterTypes().isEmpty()
                        && HOST_VALIDATOR.equals(method.getReturnType())) {
                    matches++;
                    result = inspectWazeMethod(method);
                }
            }
        }
        if (matches == 0) return new WazeInspection(0, UNSUPPORTED, "target method missing");
        if (matches != 1) {
            return new WazeInspection(matches, UNSUPPORTED,
                    "target method count=" + matches + ", expected=1");
        }
        return new WazeInspection(1, result.classification, result.reason);
    }

    static void patchWazeAllowlist(byte[] inputDex, File outputDex) throws IOException {
        DexBackedDexFile input = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(inputDex));
        AtomicInteger matches = new AtomicInteger();
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> rewriteWazeMethod(method, matches);
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(input);
        DexPool.writeTo(outputDex.getAbsolutePath(), rewritten);
        if (matches.get() != 1) {
            throw new IOException("Waze allowlist rewrite count=" + matches.get() + ", expected=1");
        }
        WazeInspection verified = inspectWaze(Files.readAllBytes(outputDex.toPath()));
        if (verified.targetCount != 1 || !ALREADY_PATCHED.equals(verified.classification)) {
            throw new IOException("Waze rewritten DEX verification failed: " + verified.reason);
        }
    }

    static LifecycleInspection inspectLifecycle(byte[] dex) throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(dex));
        LifecycleInspection result = new LifecycleInspection();
        for (ClassDef classDef : file.getClasses()) {
            if (BRIDGE_CLASS.equals(classDef.getType())) result.bridgeClassCount++;
            for (Method method : classDef.getMethods()) {
                if (matchesApplication(method)) {
                    result.applicationTargetCount++;
                    result.applicationHookCount += countCall(method, BRIDGE_INIT);
                    result.applicationGuard = method.getImplementation() == null
                            ? "missing implementation" : "ok";
                }
                if (matchesRoute(method)) {
                    result.routeTargetCount++;
                    result.routeHookCount += countCall(method, BRIDGE_EMIT);
                    result.routeGuard = inspectRouteGuard(method);
                }
            }
        }
        return result;
    }

    static void patchLifecycle(byte[] inputDex, File outputDex) throws IOException {
        DexBackedDexFile input = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(inputDex));
        AtomicInteger applicationMatches = new AtomicInteger();
        AtomicInteger routeMatches = new AtomicInteger();
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> {
                    if (matchesApplication(method)) {
                        applicationMatches.incrementAndGet();
                        return injectApplicationInit(method);
                    }
                    if (matchesRoute(method)) {
                        routeMatches.incrementAndGet();
                        return injectRouteEmit(method);
                    }
                    return method;
                };
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(input);
        DexPool.writeTo(outputDex.getAbsolutePath(), rewritten);
        if (applicationMatches.get() > 1 || routeMatches.get() > 1) {
            throw new IOException("Waze lifecycle rewrite counts app="
                    + applicationMatches.get() + ", route=" + routeMatches.get());
        }
    }

    private static Method injectApplicationInit(Method method) {
        if (countCall(method, BRIDGE_INIT) != 0 || method.getImplementation() == null) {
            throw new IllegalStateException("Waze application lifecycle hook is not stock");
        }
        MutableMethodImplementation mutable = new MutableMethodImplementation(
                method.getImplementation());
        int thisRegister = method.getImplementation().getRegisterCount() - 1;
        mutable.addInstruction(0, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 1, thisRegister, 0, 0, 0, 0, BRIDGE_INIT));
        return immutable(method, mutable);
    }

    private static Method injectRouteEmit(Method method) {
        if (countCall(method, BRIDGE_EMIT) != 0 || !"ok".equals(inspectRouteGuard(method))) {
            throw new IllegalStateException("Waze route lifecycle hook is not stock");
        }
        MethodImplementation source = method.getImplementation();
        MutableMethodImplementation mutable = new MutableMethodImplementation(source);
        int parameterBase = source.getRegisterCount() - 3;
        int stateRegister = parameterBase + 1;
        int insertAfter = routeAnchorIndex(method, false) + 1;
        if (insertAfter <= 0) throw new IllegalStateException("Waze route transition missing");
        mutable.addInstruction(insertAfter, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 1, stateRegister, 0, 0, 0, 0, BRIDGE_EMIT));
        return immutable(method, mutable);
    }

    private static Method immutable(Method method, MethodImplementation implementation) {
        return new ImmutableMethod(
                method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                method.getHiddenApiRestrictions(), implementation);
    }

    private static boolean matchesApplication(Method method) {
        return APPLICATION_CLASS.equals(method.getDefiningClass())
                && "onCreate".equals(method.getName())
                && method.getParameterTypes().isEmpty()
                && "V".equals(method.getReturnType());
    }

    private static boolean matchesRoute(Method method) {
        return ROUTE_CLASS.equals(method.getDefiningClass())
                && "m".equals(method.getName())
                && method.getParameterTypes().size() == 2
                && "Z".equals(method.getParameterTypes().get(0).toString())
                && "I".equals(method.getParameterTypes().get(1).toString())
                && "V".equals(method.getReturnType());
    }

    private static String inspectRouteGuard(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null || implementation.getRegisterCount() < 3) {
            return "missing implementation";
        }
        int activeEnumCount = 0;
        int inactiveEnumCount = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (isField(instruction, ROUTE_ENUM, "b")) activeEnumCount++;
            if (isField(instruction, ROUTE_ENUM, "a")) inactiveEnumCount++;
        }
        int anchor = routeAnchorIndex(method, true);
        return anchor >= 0 && activeEnumCount == 1 && inactiveEnumCount == 1
                ? "ok"
                : "route guard mismatch anchor=" + (anchor >= 0 ? 1 : 0)
                + ", active=" + activeEnumCount + ", inactive=" + inactiveEnumCount;
    }

    private static int routeAnchorIndex(Method method, boolean allowBridge) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null || implementation.getRegisterCount() < 3) return -1;
        int transitionRegister = implementation.getRegisterCount() - 1;
        List<? extends Instruction> instructions = toList(implementation);
        int match = -1;
        for (int index = 2; index < instructions.size(); index++) {
            Instruction branch = instructions.get(index);
            if (branch.getOpcode() != Opcode.IF_EQZ
                    || !(branch instanceof OneRegisterInstruction)
                    || ((OneRegisterInstruction) branch).getRegisterA() != transitionRegister) {
                continue;
            }
            Instruction result = instructions.get(index - 1);
            if (result.getOpcode() != Opcode.MOVE_RESULT
                    || !(result instanceof OneRegisterInstruction)
                    || ((OneRegisterInstruction) result).getRegisterA() != transitionRegister
                    || !isRouteFlowUpdate(instructions.get(index - 2))) {
                continue;
            }
            int next = index + 1;
            if (allowBridge && next < instructions.size()
                    && isCall(instructions.get(next), BRIDGE_EMIT)) next++;
            if (next >= instructions.size() || !isRouteStateField(instructions.get(next))) {
                continue;
            }
            if (match >= 0) return -1;
            match = index;
        }
        return match;
    }

    private static boolean isRouteFlowUpdate(Instruction instruction) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof MethodReference)) return false;
        MethodReference method = (MethodReference) reference;
        return ROUTE_FLOW.equals(method.getDefiningClass())
                && "f".equals(method.getName())
                && method.getParameterTypes().size() == 2
                && "Ljava/lang/Object;".equals(method.getParameterTypes().get(0).toString())
                && "Ljava/lang/Object;".equals(method.getParameterTypes().get(1).toString())
                && "Z".equals(method.getReturnType());
    }

    private static boolean isRouteStateField(Instruction instruction) {
        if (instruction.getOpcode() != Opcode.IGET_OBJECT
                || !(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof FieldReference)) return false;
        FieldReference field = (FieldReference) reference;
        return ROUTE_CLASS.equals(field.getDefiningClass())
                && "i".equals(field.getName())
                && ROUTE_FLOW.equals(field.getType());
    }

    private static boolean isCall(Instruction instruction, MethodReference expected) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        return reference instanceof MethodReference
                && sameMethod((MethodReference) reference, expected);
    }

    private static int countCall(Method method, MethodReference expected) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return 0;
        int count = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof MethodReference
                    && sameMethod((MethodReference) reference, expected)) count++;
        }
        return count;
    }

    private static boolean sameMethod(MethodReference first, MethodReference second) {
        return first.getDefiningClass().equals(second.getDefiningClass())
                && first.getName().equals(second.getName())
                && first.getParameterTypes().equals(second.getParameterTypes())
                && first.getReturnType().equals(second.getReturnType());
    }

    private static List<? extends Instruction> toList(MethodImplementation implementation) {
        List<Instruction> result = new ArrayList<>();
        implementation.getInstructions().forEach(result::add);
        return result;
    }

    private static WazeInspection inspectWazeMethod(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) {
            return new WazeInspection(1, UNSUPPORTED, "target method has no implementation");
        }
        List<Instruction> instructions = new ArrayList<>();
        implementation.getInstructions().forEach(instructions::add);
        int[] addresses = new int[instructions.size()];
        int address = 0;
        int allowAllIndex = -1;
        int allowAllCount = 0;
        int builderIndex = -1;
        int builderCount = 0;
        int configCount = 0;
        int booleanValueCount = 0;
        int addAllowedHostsCount = 0;
        int buildCount = 0;
        int returnAfterAllowAll = 0;
        boolean allowAllReturnsDirectly = false;
        int controlFlowBeforeAllowAll = 0;
        int controlFlowTotal = 0;
        int terminalBeforeAllowAll = 0;
        int guardedBranchIndex = -1;
        int guardedBranchCount = 0;

        for (int index = 0; index < instructions.size(); index++) {
            Instruction instruction = instructions.get(index);
            addresses[index] = address;
            address += instruction.getCodeUnits();
            if (isField(instruction, HOST_VALIDATOR, "ALLOW_ALL_HOSTS_VALIDATOR")) {
                allowAllIndex = index;
                allowAllCount++;
            }
            if (isType(instruction, HOST_VALIDATOR_BUILDER)) {
                builderIndex = index;
                builderCount++;
            }
            if (isField(instruction, "Lcom/waze/config/ConfigValues;",
                    "CONFIG_VALUE_CAR_LIB_VALIDATE_HOST_CONNECTIONS")) configCount++;
            if (isMethod(instruction, "Ljava/lang/Boolean;", "booleanValue", "Z")) {
                booleanValueCount++;
            }
            if (isMethod(instruction, HOST_VALIDATOR_BUILDER, "addAllowedHosts",
                    HOST_VALIDATOR_BUILDER)) addAllowedHostsCount++;
            if (isMethod(instruction, HOST_VALIDATOR_BUILDER, "build", HOST_VALIDATOR)) {
                buildCount++;
            }
            if (instruction.getOpcode() == Opcode.IF_NEZ) {
                guardedBranchIndex = index;
                guardedBranchCount++;
            }
            if (isControlFlow(instruction.getOpcode())) controlFlowTotal++;
        }

        if (allowAllCount != 1 || allowAllIndex < 0) {
            return new WazeInspection(1, UNSUPPORTED,
                    "ALLOW_ALL_HOSTS_VALIDATOR count=" + allowAllCount);
        }
        if (allowAllIndex + 1 < instructions.size()) {
            Instruction load = instructions.get(allowAllIndex);
            Instruction returned = instructions.get(allowAllIndex + 1);
            allowAllReturnsDirectly = load instanceof OneRegisterInstruction
                    && returned.getOpcode() == Opcode.RETURN_OBJECT
                    && returned instanceof OneRegisterInstruction
                    && ((OneRegisterInstruction) load).getRegisterA()
                    == ((OneRegisterInstruction) returned).getRegisterA();
        }
        for (int index = 0; index < instructions.size(); index++) {
            Opcode opcode = instructions.get(index).getOpcode();
            if (index < allowAllIndex && isControlFlow(opcode)) controlFlowBeforeAllowAll++;
            if (index < allowAllIndex && isTerminal(opcode)) terminalBeforeAllowAll++;
            if (index > allowAllIndex && opcode == Opcode.RETURN_OBJECT) {
                returnAfterAllowAll++;
                break;
            }
        }
        if (allowAllReturnsDirectly && returnAfterAllowAll == 1
                && controlFlowTotal == 0 && terminalBeforeAllowAll == 0) {
            return new WazeInspection(1, ALREADY_PATCHED,
                    "ALLOW_ALL_HOSTS_VALIDATOR is reached unconditionally");
        }

        boolean exactMarkers = configCount == 1
                && booleanValueCount == 1
                && builderCount == 1
                && addAllowedHostsCount == 1
                && buildCount == 1
                && guardedBranchCount == 1
                && controlFlowTotal == 1
                && controlFlowBeforeAllowAll == 1
                && terminalBeforeAllowAll == 0
                && allowAllReturnsDirectly
                && returnAfterAllowAll == 1;
        if (!exactMarkers || guardedBranchIndex <= 0 || builderIndex < 0
                || guardedBranchIndex + 1 != allowAllIndex
                || !implementation.getTryBlocks().isEmpty()) {
            return new WazeInspection(1, UNSUPPORTED,
                    "stock structural guard mismatch: config=" + configCount
                            + ", boolean=" + booleanValueCount
                            + ", ifNez=" + guardedBranchCount
                            + ", flowBeforeAllowAll=" + controlFlowBeforeAllowAll
                            + ", builder=" + builderCount
                            + ", addHosts=" + addAllowedHostsCount
                            + ", build=" + buildCount);
        }
        Instruction branch = instructions.get(guardedBranchIndex);
        Instruction previous = instructions.get(guardedBranchIndex - 1);
        if (previous.getOpcode() != Opcode.MOVE_RESULT
                || !(previous instanceof OneRegisterInstruction)
                || !(branch instanceof OneRegisterInstruction)
                || !(branch instanceof OffsetInstruction)
                || ((OneRegisterInstruction) previous).getRegisterA()
                != ((OneRegisterInstruction) branch).getRegisterA()) {
            return new WazeInspection(1, UNSUPPORTED, "guard register flow mismatch");
        }
        int branchTarget = addresses[guardedBranchIndex]
                + ((OffsetInstruction) branch).getCodeOffset();
        if (branchTarget != addresses[builderIndex]) {
            return new WazeInspection(1, UNSUPPORTED,
                    "guard target=" + branchTarget + ", builder=" + addresses[builderIndex]);
        }
        return new WazeInspection(1, PATCHABLE_STOCK,
                "exact IF_NEZ host-validation guard resolved");
    }

    private static Method rewriteWazeMethod(Method method, AtomicInteger matches) {
        if (!WAZE_CLASS.equals(method.getDefiningClass())
                || !WAZE_METHOD.equals(method.getName())
                || !method.getParameterTypes().isEmpty()
                || !HOST_VALIDATOR.equals(method.getReturnType())) return method;
        WazeInspection inspection = inspectWazeMethod(method);
        if (!PATCHABLE_STOCK.equals(inspection.classification)) {
            throw new IllegalStateException("Waze target is not patchable stock: " + inspection.reason);
        }
        MethodImplementation source = method.getImplementation();
        List<Instruction> rewritten = new ArrayList<>();
        int replaced = 0;
        for (Instruction instruction : source.getInstructions()) {
            if (instruction.getOpcode() == Opcode.IF_NEZ) {
                rewritten.add(new ImmutableInstruction10x(Opcode.NOP));
                rewritten.add(new ImmutableInstruction10x(Opcode.NOP));
                replaced++;
            } else {
                rewritten.add(instruction);
            }
        }
        if (replaced != 1) throw new IllegalStateException("Waze guarded branch count=" + replaced);
        matches.incrementAndGet();
        MethodImplementation implementation = new ImmutableMethodImplementation(
                source.getRegisterCount(), rewritten, source.getTryBlocks(), source.getDebugItems());
        return new ImmutableMethod(
                method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                method.getHiddenApiRestrictions(), implementation);
    }

    private static boolean isControlFlow(Opcode opcode) {
        String name = opcode.name();
        return name.startsWith("IF_") || name.startsWith("GOTO")
                || opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH;
    }

    private static boolean isTerminal(Opcode opcode) {
        String name = opcode.name();
        return name.startsWith("RETURN") || opcode == Opcode.THROW;
    }

    private static boolean isField(Instruction instruction, String owner, String name) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof FieldReference)) return false;
        FieldReference field = (FieldReference) reference;
        return owner.equals(field.getDefiningClass()) && name.equals(field.getName());
    }

    private static boolean isType(Instruction instruction, String type) {
        if (instruction.getOpcode() != Opcode.NEW_INSTANCE
                || !(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        return reference instanceof TypeReference && type.equals(((TypeReference) reference).getType());
    }

    private static boolean isMethod(
            Instruction instruction, String owner, String name, String returnType) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof MethodReference)) return false;
        MethodReference method = (MethodReference) reference;
        return owner.equals(method.getDefiningClass())
                && name.equals(method.getName())
                && returnType.equals(method.getReturnType());
    }

    static final class WazeInspection {
        final int targetCount;
        final String classification;
        final String reason;

        WazeInspection(int targetCount, String classification, String reason) {
            this.targetCount = targetCount;
            this.classification = classification;
            this.reason = reason;
        }
    }

    static final class LifecycleInspection {
        int applicationTargetCount;
        int applicationHookCount;
        String applicationGuard = "not found";
        int routeTargetCount;
        int routeHookCount;
        String routeGuard = "not found";
        int bridgeClassCount;

        boolean stockTargets() {
            return applicationTargetCount == 1 && applicationHookCount == 0
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 0
                    && "ok".equals(routeGuard)
                    && bridgeClassCount == 0;
        }

        boolean patchedTargets() {
            return applicationTargetCount == 1 && applicationHookCount == 1
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 1
                    && "ok".equals(routeGuard)
                    && bridgeClassCount == 1;
        }
    }
}
