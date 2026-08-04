package com.bydhud.app;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11n;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction12x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableField;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference;
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
    private static final String LEGACY_BRIDGE_CLASS = "Lcom/waze/bydhud/RouteStateBridge;";
    private static final String ALERT_SESSION_CLASS = "Lcom/waze/car_lib/e/q;";
    private static final ImmutableFieldReference ALERT_GUARD =
            new ImmutableFieldReference(ALERT_SESSION_CLASS, "g", "Z");
    private static final ImmutableMethodReference ALERT_ANCHOR = new ImmutableMethodReference(
            "Lcom/waze/car_lib/i/f;", "b", java.util.Arrays.asList(
            "Lcom/waze/car_lib/aq;", "Landroidx/lifecycle/Lifecycle;",
            "Lcom/waze/car_lib/i/a/a;", "Lkotlinx/coroutines/aj;",
            "Landroidx/car/app/CarContext;"), "V");
    private static final ImmutableMethodReference ALERT_HELPER = new ImmutableMethodReference(
            ALERT_SESSION_CLASS, "c", Collections.emptyList(), "V");
    private static final ImmutableMethodReference ALERT_PRODUCER = new ImmutableMethodReference(
            "Lcom/waze/car_lib/b/ap;", "L", java.util.Arrays.asList(
            "Landroidx/car/app/CarContext;", "Lkotlinx/coroutines/aj;"), "V");
    private static final ImmutableMethodReference ALERT_COLLECTOR = new ImmutableMethodReference(
            "Lcom/waze/car_lib/b/f;", "h", java.util.Arrays.asList(
            "Landroidx/lifecycle/Lifecycle;", "Landroidx/car/app/CarContext;"), "V");
    private static final ImmutableMethodReference BRIDGE_INIT = new ImmutableMethodReference(
            BRIDGE_CLASS, "init", Collections.singletonList("Landroid/content/Context;"), "V");
    private static final ImmutableMethodReference BRIDGE_EMIT = new ImmutableMethodReference(
            BRIDGE_CLASS, "emit", Collections.singletonList("Z"), "V");
    private static final ImmutableMethodReference LEGACY_BRIDGE_INIT = new ImmutableMethodReference(
            LEGACY_BRIDGE_CLASS, "init",
            Collections.singletonList("Landroid/content/Context;"), "V");
    private static final ImmutableMethodReference LEGACY_BRIDGE_EMIT = new ImmutableMethodReference(
            LEGACY_BRIDGE_CLASS, "emit", Collections.singletonList("Z"), "V");
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
            if (LEGACY_BRIDGE_CLASS.equals(classDef.getType())) result.legacyBridgeClassCount++;
            for (Method method : classDef.getMethods()) {
                if (matchesApplication(method)) {
                    result.applicationTargetCount++;
                    result.applicationHookCount += countCall(method, BRIDGE_INIT);
                    result.legacyApplicationHookCount += countCall(method, LEGACY_BRIDGE_INIT);
                    result.applicationGuard = method.getImplementation() == null
                            ? "missing implementation" : "ok";
                }
                if (matchesRoute(method)) {
                    result.routeTargetCount++;
                    result.routeHookCount += countCall(method, BRIDGE_EMIT);
                    result.legacyRouteHookCount += countCall(method, LEGACY_BRIDGE_EMIT);
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

    static AlertInspection inspectAlertHook(byte[] dex) throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(dex));
        AlertInspection result = new AlertInspection();
        for (ClassDef classDef : file.getClasses()) {
            if (!ALERT_SESSION_CLASS.equals(classDef.getType())) continue;
            result.classCount++;
            for (Field field : classDef.getFields()) {
                if ("f".equals(field.getName()) && "Lh/f;".equals(field.getType())
                        && AccessFlags.PRIVATE.isSet(field.getAccessFlags())
                        && AccessFlags.FINAL.isSet(field.getAccessFlags())) {
                    result.fieldAnchorCount++;
                }
                if ("g".equals(field.getName()) && "Z".equals(field.getType())) {
                    result.guardFieldCount++;
                }
            }
            for (Method method : classDef.getMethods()) {
                if (matchesAlertTarget(method)) {
                    result.targetMethodCount++;
                    result.anchorCount += countCall(method, ALERT_ANCHOR);
                    result.hookCallCount += countCall(method, ALERT_HELPER);
                    result.hookAfterAnchorCount += countAdjacentCalls(
                            method, ALERT_ANCHOR, ALERT_HELPER);
                }
                if (matchesAlertHelper(method)) {
                    result.helperMethodCount++;
                    result.producerCallCount += countCall(method, ALERT_PRODUCER);
                    result.collectorCallCount += countCall(method, ALERT_COLLECTOR);
                    result.guardReadCount += countFieldOpcode(
                            method, ALERT_GUARD, Opcode.IGET_BOOLEAN);
                    result.guardWriteCount += countFieldOpcode(
                            method, ALERT_GUARD, Opcode.IPUT_BOOLEAN);
                    result.logMarkerCount += countString(
                            method, "cluster alert producer/collector attached");
                }
            }
        }
        return result;
    }

    static void patchAlertHook(byte[] inputDex, File outputDex) throws IOException {
        AlertInspection inspection = inspectAlertHook(inputDex);
        if (!inspection.stockTargets()) {
            throw new IOException("Waze alert-hook target is not compatible stock: "
                    + inspection.summary());
        }
        DexBackedDexFile input = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(inputDex));
        List<ClassDef> classes = new ArrayList<>();
        int rewritten = 0;
        for (ClassDef classDef : input.getClasses()) {
            if (ALERT_SESSION_CLASS.equals(classDef.getType())) {
                classes.add(rewriteAlertClass(classDef));
                rewritten++;
            } else {
                classes.add(ImmutableClassDef.of(classDef));
            }
        }
        if (rewritten != 1) {
            throw new IOException("Waze alert-hook class rewrite count=" + rewritten);
        }
        DexPool.writeTo(outputDex.getAbsolutePath(),
                new ImmutableDexFile(input.getOpcodes(), classes));
        AlertInspection verified = inspectAlertHook(Files.readAllBytes(outputDex.toPath()));
        if (!verified.patchedTargets()) {
            throw new IOException("Waze alert-hook verification failed: " + verified.summary());
        }
    }

    private static ClassDef rewriteAlertClass(ClassDef classDef) {
        List<Field> fields = new ArrayList<>();
        classDef.getFields().forEach(fields::add);
        fields.add(new ImmutableField(
                ALERT_SESSION_CLASS, "g", "Z", AccessFlags.PRIVATE.getValue(),
                null, Collections.emptySet(), Collections.emptySet()));

        List<Method> methods = new ArrayList<>();
        int targetCount = 0;
        for (Method method : classDef.getMethods()) {
            if (matchesAlertTarget(method)) {
                methods.add(injectAlertHelperCall(method));
                targetCount++;
            } else {
                methods.add(ImmutableMethod.of(method));
            }
        }
        if (targetCount != 1) {
            throw new IllegalStateException("Waze alert target count=" + targetCount);
        }
        methods.add(buildAlertHelper());
        return new ImmutableClassDef(
                classDef.getType(), classDef.getAccessFlags(), classDef.getSuperclass(),
                classDef.getInterfaces(), classDef.getSourceFile(), classDef.getAnnotations(),
                fields, methods);
    }

    private static Method injectAlertHelperCall(Method method) {
        MethodImplementation source = method.getImplementation();
        if (source == null || countCall(method, ALERT_HELPER) != 0) {
            throw new IllegalStateException("Waze alert target is not stock");
        }
        List<? extends Instruction> instructions = toList(source);
        int anchor = -1;
        for (int index = 0; index < instructions.size(); index++) {
            if (!isCall(instructions.get(index), ALERT_ANCHOR)) continue;
            if (anchor >= 0) throw new IllegalStateException("Waze alert anchor is ambiguous");
            anchor = index;
        }
        if (anchor < 0) throw new IllegalStateException("Waze alert anchor is missing");
        int parameterWords = 1;
        for (CharSequence type : method.getParameterTypes()) {
            parameterWords += "J".contentEquals(type) || "D".contentEquals(type) ? 2 : 1;
        }
        int thisRegister = source.getRegisterCount() - parameterWords;
        MutableMethodImplementation mutable = new MutableMethodImplementation(source);
        mutable.addInstruction(anchor + 1, new BuilderInstruction35c(
                Opcode.INVOKE_DIRECT, 1, thisRegister, 0, 0, 0, 0, ALERT_HELPER));
        return immutable(method, mutable);
    }

    private static Method buildAlertHelper() {
        MethodImplementationBuilder code = new MethodImplementationBuilder(8);
        code.addInstruction(new BuilderInstruction22c(
                Opcode.IGET_BOOLEAN, 0, 7, ALERT_GUARD));
        code.addInstruction(new BuilderInstruction21t(
                Opcode.IF_NEZ, 0, code.getLabel("return")));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 1, 7, 0, 0, 0, 0,
                method("Landroidx/car/app/Session;", "getCarContext",
                        Collections.emptyList(), "Landroidx/car/app/CarContext;")));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 1, 7, 0, 0, 0, 0,
                method("Landroidx/car/app/Session;", "getLifecycle",
                        Collections.emptyList(), "Landroidx/lifecycle/Lifecycle;")));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1));
        code.addInstruction(new BuilderInstruction12x(Opcode.MOVE_OBJECT, 2, 7));
        code.addInstruction(new BuilderInstruction21c(
                Opcode.CHECK_CAST, 2,
                new ImmutableTypeReference("Landroidx/lifecycle/LifecycleOwner;")));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0,
                method("Landroidx/lifecycle/LifecycleOwnerKt;", "getLifecycleScope",
                        Collections.singletonList("Landroidx/lifecycle/LifecycleOwner;"),
                        "Landroidx/lifecycle/LifecycleCoroutineScope;")));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2));
        code.addInstruction(new BuilderInstruction21c(
                Opcode.CHECK_CAST, 2, new ImmutableTypeReference("Lkotlinx/coroutines/aj;")));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_INTERFACE, 1, 7, 0, 0, 0, 0,
                method("Lorg/a/d/a/a;", "getKoin", Collections.emptyList(), "Lorg/a/d/a;")));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0,
                method("Lorg/a/d/a;", "a", Collections.emptyList(), "Lorg/a/d/j/a;")));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0,
                method("Lorg/a/d/j/a;", "c", Collections.emptyList(), "Lorg/a/d/k/b;")));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3));
        addKoinLookup(code, 3, 4, 5, 4, true, "Lcom/waze/car_lib/b/ap;");
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 3, 4, 0, 2, 0, 0, ALERT_PRODUCER));
        addKoinLookup(code, 3, 4, 5, 3, false, "Lcom/waze/car_lib/b/f;");
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 3, 3, 1, 0, 0, 0, ALERT_COLLECTOR));
        code.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 1));
        code.addInstruction(new BuilderInstruction22c(
                Opcode.IPUT_BOOLEAN, 0, 7, ALERT_GUARD));
        code.addInstruction(new BuilderInstruction21c(
                Opcode.CONST_STRING, 0, new ImmutableStringReference("BYD_WAZE_ALERT")));
        code.addInstruction(new BuilderInstruction21c(
                Opcode.CONST_STRING, 1,
                new ImmutableStringReference("cluster alert producer/collector attached")));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0,
                method("Landroid/util/Log;", "i",
                        java.util.Arrays.asList("Ljava/lang/String;", "Ljava/lang/String;"), "I")));
        code.addLabel("return");
        code.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        return new ImmutableMethod(
                ALERT_SESSION_CLASS, "c", Collections.emptyList(), "V",
                AccessFlags.PRIVATE.getValue() | AccessFlags.FINAL.getValue(),
                Collections.emptySet(), Collections.emptySet(), code.getMethodImplementation());
    }

    private static void addKoinLookup(MethodImplementationBuilder code, int rootRegister,
            int lookupRegister, int classRegister, int resultRegister,
            boolean clearNullRegister, String type) {
        code.addInstruction(new BuilderInstruction21c(
                Opcode.NEW_INSTANCE, lookupRegister, new ImmutableTypeReference("Lh/g/b/m;")));
        code.addInstruction(new BuilderInstruction21c(
                Opcode.CONST_CLASS, classRegister, new ImmutableTypeReference(type)));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_DIRECT, 2, lookupRegister, classRegister, 0, 0, 0,
                method("Lh/g/b/m;", "<init>",
                        Collections.singletonList("Ljava/lang/Class;"), "V")));
        if (clearNullRegister) {
            code.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 6, 0));
        }
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 4, rootRegister, lookupRegister, 6, 6, 0,
                method("Lorg/a/d/k/b;", "a", java.util.Arrays.asList(
                        "Lh/l/c;", "Lorg/a/d/i/a;", "Lh/g/a/a;"),
                        "Ljava/lang/Object;")));
        code.addInstruction(new BuilderInstruction11x(
                Opcode.MOVE_RESULT_OBJECT, resultRegister));
        code.addInstruction(new BuilderInstruction21c(
                Opcode.CHECK_CAST, resultRegister, new ImmutableTypeReference(type)));
    }

    private static ImmutableMethodReference method(
            String owner, String name, List<String> parameters, String returnType) {
        return new ImmutableMethodReference(owner, name, parameters, returnType);
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

    private static boolean matchesAlertTarget(Method method) {
        return ALERT_SESSION_CLASS.equals(method.getDefiningClass())
                && "onCreateScreen".equals(method.getName())
                && method.getParameterTypes().size() == 1
                && "Landroid/content/Intent;".equals(
                method.getParameterTypes().get(0).toString())
                && "Landroidx/car/app/Screen;".equals(method.getReturnType());
    }

    private static boolean matchesAlertHelper(Method method) {
        return ALERT_SESSION_CLASS.equals(method.getDefiningClass())
                && "c".equals(method.getName())
                && method.getParameterTypes().isEmpty()
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
        int legacyAnchor = legacyRouteAnchorIndex(method);
        return (anchor >= 0 || legacyAnchor >= 0)
                && activeEnumCount == 1 && inactiveEnumCount == 1
                ? "ok"
                : "route guard mismatch anchor=" + (anchor >= 0 ? 1 : 0)
                + ", legacyAnchor=" + (legacyAnchor >= 0 ? 1 : 0)
                + ", active=" + activeEnumCount + ", inactive=" + inactiveEnumCount;
    }

    private static int legacyRouteAnchorIndex(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null || implementation.getRegisterCount() < 3) return -1;
        int transitionRegister = implementation.getRegisterCount() - 1;
        int stateRegister = implementation.getRegisterCount() - 2;
        List<? extends Instruction> instructions = toList(implementation);
        int match = -1;
        for (int index = 3; index < instructions.size(); index++) {
            Instruction call = instructions.get(index);
            if (!isCall(call, LEGACY_BRIDGE_EMIT)
                    || !(call instanceof FiveRegisterInstruction)
                    || ((FiveRegisterInstruction) call).getRegisterC() != stateRegister) {
                continue;
            }
            Instruction branch = instructions.get(index - 1);
            Instruction result = instructions.get(index - 2);
            if (branch.getOpcode() != Opcode.IF_EQZ
                    || !(branch instanceof OneRegisterInstruction)
                    || ((OneRegisterInstruction) branch).getRegisterA() != transitionRegister
                    || result.getOpcode() != Opcode.MOVE_RESULT
                    || !(result instanceof OneRegisterInstruction)
                    || ((OneRegisterInstruction) result).getRegisterA() != transitionRegister
                    || !isRouteFlowUpdate(instructions.get(index - 3))) {
                continue;
            }
            if (match >= 0) return -1;
            match = index;
        }
        return match;
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
                    && (isCall(instructions.get(next), BRIDGE_EMIT)
                    || isCall(instructions.get(next), LEGACY_BRIDGE_EMIT))) next++;
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

    private static int countAdjacentCalls(
            Method method, MethodReference first, MethodReference second) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return 0;
        List<? extends Instruction> instructions = toList(implementation);
        int count = 0;
        for (int index = 0; index + 1 < instructions.size(); index++) {
            if (isCall(instructions.get(index), first)
                    && isCall(instructions.get(index + 1), second)) count++;
        }
        return count;
    }

    private static int countFieldOpcode(
            Method method, FieldReference expected, Opcode opcode) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return 0;
        int count = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (instruction.getOpcode() != opcode
                    || !(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof FieldReference
                    && sameField((FieldReference) reference, expected)) count++;
        }
        return count;
    }

    private static int countString(Method method, String expected) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return 0;
        int count = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof StringReference
                    && expected.equals(((StringReference) reference).getString())) count++;
        }
        return count;
    }

    private static boolean sameMethod(MethodReference first, MethodReference second) {
        return first.getDefiningClass().equals(second.getDefiningClass())
                && first.getName().equals(second.getName())
                && first.getParameterTypes().equals(second.getParameterTypes())
                && first.getReturnType().equals(second.getReturnType());
    }

    private static boolean sameField(FieldReference first, FieldReference second) {
        return first.getDefiningClass().equals(second.getDefiningClass())
                && first.getName().equals(second.getName())
                && first.getType().equals(second.getType());
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
        for (int index = 0; index < instructions.size(); index++) {
            Opcode opcode = instructions.get(index).getOpcode();
            if (index < allowAllIndex && isControlFlow(opcode)) controlFlowBeforeAllowAll++;
            if (index < allowAllIndex && isTerminal(opcode)) terminalBeforeAllowAll++;
            if (index > allowAllIndex && opcode == Opcode.RETURN_OBJECT) {
                returnAfterAllowAll++;
                break;
            }
        }
        if (returnAfterAllowAll == 1
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
        int legacyApplicationHookCount;
        String applicationGuard = "not found";
        int routeTargetCount;
        int routeHookCount;
        int legacyRouteHookCount;
        String routeGuard = "not found";
        int bridgeClassCount;
        int legacyBridgeClassCount;

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

    static final class AlertInspection {
        int classCount;
        int fieldAnchorCount;
        int guardFieldCount;
        int targetMethodCount;
        int anchorCount;
        int hookCallCount;
        int hookAfterAnchorCount;
        int helperMethodCount;
        int producerCallCount;
        int collectorCallCount;
        int guardReadCount;
        int guardWriteCount;
        int logMarkerCount;

        boolean stockTargets() {
            return classCount == 1 && fieldAnchorCount == 1 && guardFieldCount == 0
                    && targetMethodCount == 1 && anchorCount == 1
                    && hookCallCount == 0 && helperMethodCount == 0;
        }

        boolean patchedTargets() {
            return classCount == 1 && fieldAnchorCount == 1 && guardFieldCount == 1
                    && targetMethodCount == 1 && anchorCount == 1
                    && hookCallCount == 1 && hookAfterAnchorCount == 1
                    && helperMethodCount == 1 && producerCallCount == 1
                    && collectorCallCount == 1 && guardReadCount == 1
                    && guardWriteCount == 1 && logMarkerCount == 1;
        }

        String summary() {
            return "class=" + classCount + ", anchorField=" + fieldAnchorCount
                    + ", field=" + guardFieldCount
                    + ", target=" + targetMethodCount + ", anchor=" + anchorCount
                    + ", hook=" + hookCallCount + ", adjacent=" + hookAfterAnchorCount
                    + ", helper=" + helperMethodCount + ", producer=" + producerCallCount
                    + ", collector=" + collectorCallCount + ", guardRead=" + guardReadCount
                    + ", guardWrite=" + guardWriteCount + ", marker=" + logMarkerCount;
        }
    }
}
