package com.bydhud.app;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.Label;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11n;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction12x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21s;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22b;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10t;
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
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.jf.dexlib2.iface.value.IntEncodedValue;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableField;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodParameter;
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
import org.jf.dexlib2.writer.io.FileDataStore;
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
    private final boolean modern;
    private final String WAZE_CLASS;
    private final String WAZE_METHOD;
    private final String HOST_VALIDATOR;
    private final String HOST_VALIDATOR_BUILDER;
    private final String APPLICATION_CLASS;
    private final String ROUTE_CLASS;
    private final String ROUTE_ENUM;
    private final String ROUTE_FLOW;
    private final String SPEED_CLASS;
    private final String CLUSTER_TRIP_CLASS;
    private final String BRIDGE_CLASS;
    private final String LEGACY_BRIDGE_CLASS;
    private final String ALERT_SESSION_CLASS;
    private final String ALERT_ONCE_CLASS;
    private final String ALERT_ONCE_STATE_CLASS;
    private final String ALERT_ONCE_MODE_CLASS;
    private final String LANE_FRAME_CLASS;
    private final String LANE_PRODUCER_CLASS;
    private final String LANE_ADAPTER_CLASS;
    private final String NAVIGATION_LANE_LIST;
    private final String LANE_BUILDER_CLASS;
    private final String LANE_DIRECTION_CLASS;
    private final String NAVIGATION_LANE_CLASS;
    private final String NAVIGATION_LANE_ANGLE_CLASS;
    private final ImmutableFieldReference LANE_FRAME_FIELD;
    private final ImmutableMethodReference LANE_FRAME_CTOR_STOCK;
    private final ImmutableMethodReference LANE_FRAME_CTOR_PATCHED;
    private final ImmutableMethodReference LANE_FRAME_GETTER;
    private final ImmutableMethodReference LANE_FRAME_BITMAP;
    private final ImmutableMethodReference OBJECT_HASH;
    private final ImmutableMethodReference OBJECT_EQUALS;
    private final ImmutableMethodReference LANE_PRODUCER_TARGET;
    private final ImmutableMethodReference LANE_SOURCE;
    private final ImmutableFieldReference LANE_SOURCE_FIELD;
    private final ImmutableFieldReference COLLECTIONS_EMPTY_LIST;
    private final ImmutableMethodReference LANE_ADAPTER_TARGET;
    private final ImmutableMethodReference LANE_HELPER;
    private final ImmutableMethodReference LANE_ANGLE_MAPPER;
    private final ImmutableMethodReference LANE_BUILDER_CTOR;
    private final ImmutableMethodReference LANE_DIRECTION_CREATE;
    private final ImmutableMethodReference LANE_ADD_DIRECTION;
    private final ImmutableMethodReference LANE_BUILD;
    private final ImmutableMethodReference STEP_ADD_LANE;
    private final ImmutableMethodReference LIST_SIZE;
    private final ImmutableMethodReference LIST_ITERATOR;
    private final ImmutableMethodReference ITERATOR_HAS_NEXT;
    private final ImmutableMethodReference ITERATOR_NEXT;
    private final ImmutableMethodReference LIST_ADD;
    private final ImmutableMethodReference NAVIGATION_ANGLE_LIST;
    private final ImmutableMethodReference NAVIGATION_ANGLE;
    private final ImmutableMethodReference NAVIGATION_SELECTED;
    private final ImmutableMethodReference ARRAY_LIST_CTOR;
    private final ImmutableFieldReference ALERT_ONCE_STATE;
    private final ImmutableFieldReference ALERT_ONCE_LISTENER;
    private final ImmutableFieldReference ALERT_ONCE_VALUE;
    private final ImmutableMethodReference ALERT_ONCE_MODE;
    private final ImmutableMethodReference ALERT_ONCE_UPDATE;
    private final ImmutableMethodReference ALERT_ONCE_NATIVE_START;
    private final ImmutableFieldReference ALERT_GUARD;
    private final ImmutableMethodReference ALERT_ANCHOR;
    private final ImmutableMethodReference ALERT_HELPER;
    private final ImmutableMethodReference ALERT_PRODUCER;
    private final ImmutableMethodReference ALERT_COLLECTOR;
    private final ImmutableMethodReference ALERT_TRIP_PUBLISHER;
    private final ImmutableMethodReference BRIDGE_INIT;
    private final ImmutableMethodReference BRIDGE_EMIT;
    private final ImmutableMethodReference BRIDGE_EMIT_V1;
    private final ImmutableMethodReference BRIDGE_SPEED;
    private final ImmutableMethodReference SPEED_STATE_CONSTRUCTOR;
    private final ImmutableMethodReference CLUSTER_OEM_EXCLUSION;
    private final ImmutableMethodReference CLUSTER_OEM_LIST_PRODUCER;
    private final ImmutableMethodReference CLUSTER_EMPTY_OEM_LIST;
    private final ImmutableMethodReference BOOLEAN_VALUE;
    private final ImmutableMethodReference TRIP_ADD_DESTINATION;
    private final ImmutableMethodReference TRIP_BUILD;
    private final ImmutableMethodReference NAVIGATION_UPDATE_TRIP;
    private final ImmutableMethodReference LEGACY_BRIDGE_INIT;
    private final ImmutableMethodReference LEGACY_BRIDGE_EMIT;
    private final ImmutableMethodReference LEGACY_BRIDGE_SPEED;

    private WazePatchEngine(boolean modern) {
        this.modern = modern;
        WAZE_CLASS = "Lcom/waze/car_lib/WazeCarAppService;";
        WAZE_METHOD = "createHostValidator";
        HOST_VALIDATOR = "Landroidx/car/app/validation/HostValidator;";
        HOST_VALIDATOR_BUILDER = "Landroidx/car/app/validation/HostValidator$Builder;";
        APPLICATION_CLASS = "Lcom/waze/mobile/WazeMobileApplication;";
        ROUTE_CLASS = (modern ? "Lcom/waze/navigate/dd;" : "Lcom/waze/navigate/dt;");
        ROUTE_ENUM = (modern ? "Lcom/waze/navigate/ea;" : "Lcom/waze/navigate/ee;");
        ROUTE_FLOW = (modern ? "Lkotlinx/coroutines/b/bj;" : "Lkotlinx/coroutines/b/bn;");
        SPEED_CLASS = "Lcom/waze/location/LocationSensorListener;";
        CLUSTER_TRIP_CLASS = (modern ? "Lcom/waze/car_lib/j/k;" : "Lcom/waze/car_lib/j/l;");
        BRIDGE_CLASS = "Lcom/waze/bydhud/RouteStateBridgeV2;";
        LEGACY_BRIDGE_CLASS = "Lcom/waze/bydhud/RouteStateBridge;";
        ALERT_SESSION_CLASS = (modern ? "Lcom/waze/car_lib/e/p;" : "Lcom/waze/car_lib/e/q;");
        ALERT_ONCE_CLASS = "Lcom/waze/alerters/a/q;";
        ALERT_ONCE_STATE_CLASS = "Lcom/waze/alerters/a/p;";
        ALERT_ONCE_MODE_CLASS = "Lcom/waze/alerters/a/o;";
        LANE_FRAME_CLASS = (modern ? "Lcom/waze/car_lib/r/bx;" : "Lcom/waze/car_lib/s/cf;");
        LANE_PRODUCER_CLASS = (modern ? "Lcom/waze/car_lib/r/bk;" : "Lcom/waze/car_lib/s/br;");
        LANE_ADAPTER_CLASS = (modern ? "Lcom/waze/a/ag;" : "Lcom/waze/i/a;");
        NAVIGATION_LANE_LIST = "Ljava/util/List;";
        LANE_BUILDER_CLASS = "Landroidx/car/app/navigation/model/Lane$Builder;";
        LANE_DIRECTION_CLASS = "Landroidx/car/app/navigation/model/LaneDirection;";
        NAVIGATION_LANE_CLASS = "Lcom/waze/jni/protos/navigate/NavigationLane;";
        NAVIGATION_LANE_ANGLE_CLASS = "Lcom/waze/jni/protos/navigate/NavigationLaneAngle;";
        LANE_FRAME_FIELD = new ImmutableFieldReference(LANE_FRAME_CLASS, "i", NAVIGATION_LANE_LIST);
        LANE_FRAME_CTOR_STOCK = method(
            LANE_FRAME_CLASS, "<init>", java.util.Arrays.asList("I", (modern ? "Lcom/waze/car_lib/r/cg;" : "Lcom/waze/t/b/bw;"),
                    "Landroid/text/SpannableStringBuilder;", "Ljava/lang/String;",
                    "Landroid/graphics/Bitmap;", "Ljava/lang/Integer;", "Ljava/util/Collection;",
                    "Ljava/lang/Long;"), "V");
        LANE_FRAME_CTOR_PATCHED = method(
            LANE_FRAME_CLASS, "<init>", java.util.Arrays.asList("I", (modern ? "Lcom/waze/car_lib/r/cg;" : "Lcom/waze/t/b/bw;"),
                    "Landroid/text/SpannableStringBuilder;", "Ljava/lang/String;",
                    "Landroid/graphics/Bitmap;", "Ljava/lang/Integer;", "Ljava/util/Collection;",
                    "Ljava/lang/Long;", NAVIGATION_LANE_LIST), "V");
        LANE_FRAME_GETTER = method(
            LANE_FRAME_CLASS, "i", Collections.emptyList(), NAVIGATION_LANE_LIST);
        LANE_FRAME_BITMAP = method(
            LANE_FRAME_CLASS, "b", Collections.emptyList(), "Landroid/graphics/Bitmap;");
        OBJECT_HASH = method(
            "Ljava/lang/Object;", "hashCode", Collections.emptyList(), "I");
        OBJECT_EQUALS = method(
            "Lh/g/b/x;", modern ? "m" : "n", java.util.Arrays.asList("Ljava/lang/Object;", "Ljava/lang/Object;"), "Z");
        LANE_PRODUCER_TARGET = method(
            LANE_PRODUCER_CLASS, "e", java.util.Arrays.asList((modern ? "Lcom/waze/navigate/cm;" : "Lcom/waze/navigate/cf;"),
                    "Landroid/content/Context;", "Lcom/waze/ui/navbar/LaneGuidanceView;",
                    (modern ? "Lcom/waze/navigate/bs;" : "Lcom/waze/navigate/bl;"), "Lcom/waze/navbar/InstructionView;", "Z"),
            LANE_FRAME_CLASS);
        LANE_SOURCE = method(
            (modern ? "Lcom/waze/navigate/cm;" : "Lcom/waze/navigate/cf;"), "b", Collections.emptyList(), (modern ? "Lcom/waze/navigate/df;" : "Lcom/waze/navigate/da;"));
        LANE_SOURCE_FIELD = new ImmutableFieldReference(
            (modern ? "Lcom/waze/navigate/df;" : "Lcom/waze/navigate/da;"), "a", "Ljava/lang/Object;");
        COLLECTIONS_EMPTY_LIST = new ImmutableFieldReference(
            "Ljava/util/Collections;", "EMPTY_LIST", NAVIGATION_LANE_LIST);
        LANE_ADAPTER_TARGET = method(
            LANE_ADAPTER_CLASS, modern ? "i" : "d", java.util.Arrays.asList(LANE_FRAME_CLASS,
                    "Landroid/content/Context;"), "Landroidx/car/app/navigation/model/Step;");
        LANE_HELPER = method(
            LANE_ADAPTER_CLASS, "h", Collections.singletonList(LANE_FRAME_CLASS), NAVIGATION_LANE_LIST);
        LANE_ANGLE_MAPPER = method(
            LANE_ADAPTER_CLASS, "i", Collections.singletonList("I"), "I");
        LANE_BUILDER_CTOR = method(
            LANE_BUILDER_CLASS, "<init>", Collections.emptyList(), "V");
        LANE_DIRECTION_CREATE = method(
            LANE_DIRECTION_CLASS, "create", java.util.Arrays.asList("I", "Z"), LANE_DIRECTION_CLASS);
        LANE_ADD_DIRECTION = method(
            LANE_BUILDER_CLASS, "addDirection", Collections.singletonList(LANE_DIRECTION_CLASS),
            LANE_BUILDER_CLASS);
        LANE_BUILD = method(
            LANE_BUILDER_CLASS, "build", Collections.emptyList(),
            "Landroidx/car/app/navigation/model/Lane;");
        STEP_ADD_LANE = method(
            "Landroidx/car/app/navigation/model/Step$Builder;", "addLane",
            Collections.singletonList("Landroidx/car/app/navigation/model/Lane;"),
            "Landroidx/car/app/navigation/model/Step$Builder;");
        LIST_SIZE = method(
            "Ljava/util/List;", "size", Collections.emptyList(), "I");
        LIST_ITERATOR = method(
            "Ljava/util/List;", "iterator", Collections.emptyList(), "Ljava/util/Iterator;");
        ITERATOR_HAS_NEXT = method(
            "Ljava/util/Iterator;", "hasNext", Collections.emptyList(), "Z");
        ITERATOR_NEXT = method(
            "Ljava/util/Iterator;", "next", Collections.emptyList(), "Ljava/lang/Object;");
        LIST_ADD = method(
            "Ljava/util/List;", "add", Collections.singletonList("Ljava/lang/Object;"), "Z");
        NAVIGATION_ANGLE_LIST = method(
            NAVIGATION_LANE_CLASS, "getAngleList", Collections.emptyList(), "Ljava/util/List;");
        NAVIGATION_ANGLE = method(
            NAVIGATION_LANE_ANGLE_CLASS, "getAngle", Collections.emptyList(), "I");
        NAVIGATION_SELECTED = method(
            NAVIGATION_LANE_ANGLE_CLASS, "getIsSelected", Collections.emptyList(), "Z");
        ARRAY_LIST_CTOR = method(
            "Ljava/util/ArrayList;", "<init>", Collections.singletonList("I"), "V");
        ALERT_ONCE_STATE = new ImmutableFieldReference(ALERT_ONCE_CLASS, "g", ALERT_ONCE_STATE_CLASS);
        ALERT_ONCE_LISTENER = new ImmutableFieldReference(
                    ALERT_ONCE_CLASS, "d", "Lcom/waze/alerters/a/l;");
        ALERT_ONCE_VALUE = new ImmutableFieldReference(ALERT_ONCE_CLASS, "a", (modern ? "Lcom/waze/l;" : "Lcom/waze/k;"));
        ALERT_ONCE_MODE = new ImmutableMethodReference(
            ALERT_ONCE_STATE_CLASS, "c", Collections.emptyList(), ALERT_ONCE_MODE_CLASS);
        ALERT_ONCE_UPDATE = new ImmutableMethodReference(
            ALERT_ONCE_STATE_CLASS, "e", java.util.Arrays.asList(
            ALERT_ONCE_STATE_CLASS, (modern ? "Lcom/waze/k;" : "Lcom/waze/j;"), ALERT_ONCE_MODE_CLASS, "I", "I"),
            ALERT_ONCE_STATE_CLASS);
        ALERT_ONCE_NATIVE_START = new ImmutableMethodReference("Lcom/waze/alerters/a/l;", "d",
                    Collections.singletonList((modern ? "Lcom/waze/l;" : "Lcom/waze/k;")), "V");
        ALERT_GUARD = new ImmutableFieldReference(ALERT_SESSION_CLASS, "g", "Z");
        ALERT_ANCHOR = new ImmutableMethodReference(
            "Lcom/waze/car_lib/i/f;", "b", java.util.Arrays.asList(
            (modern ? "Lcom/waze/car_lib/ap;" : "Lcom/waze/car_lib/aq;"), "Landroidx/lifecycle/Lifecycle;",
            "Lcom/waze/car_lib/i/a/a;", (modern ? "Lkotlinx/coroutines/ae;" : "Lkotlinx/coroutines/aj;"),
            "Landroidx/car/app/CarContext;"), "V");
        ALERT_HELPER = new ImmutableMethodReference(
            ALERT_SESSION_CLASS, "c", Collections.emptyList(), "V");
        ALERT_PRODUCER = new ImmutableMethodReference(
            (modern ? "Lcom/waze/car_lib/a/ak;" : "Lcom/waze/car_lib/b/ap;"), modern ? "S" : "L", java.util.Arrays.asList(
            "Landroidx/car/app/CarContext;", (modern ? "Lkotlinx/coroutines/ae;" : "Lkotlinx/coroutines/aj;")), "V");
        ALERT_COLLECTOR = new ImmutableMethodReference(
            (modern ? "Lcom/waze/car_lib/a/f;" : "Lcom/waze/car_lib/b/f;"), modern ? "g" : "h", java.util.Arrays.asList(
            "Landroidx/lifecycle/Lifecycle;", "Landroidx/car/app/CarContext;"), "V");
        ALERT_TRIP_PUBLISHER = new ImmutableMethodReference((modern ? "Lcom/waze/car_lib/j/q;" : "Lcom/waze/car_lib/j/r;"), modern ? "i" : "h",
                    java.util.Arrays.asList((modern ? "Lkotlinx/coroutines/ae;" : "Lkotlinx/coroutines/aj;"),
                            "Landroidx/lifecycle/Lifecycle;",
                            "Landroidx/car/app/CarContext;"), "V");
        BRIDGE_INIT = new ImmutableMethodReference(
            BRIDGE_CLASS, "init", Collections.singletonList("Landroid/content/Context;"), "V");
        BRIDGE_EMIT = new ImmutableMethodReference(
            BRIDGE_CLASS, "emit", java.util.Arrays.asList("Z", "I"), "V");
        BRIDGE_EMIT_V1 = new ImmutableMethodReference(
            BRIDGE_CLASS, "emit", Collections.singletonList("Z"), "V");
        BRIDGE_SPEED = new ImmutableMethodReference(
            BRIDGE_CLASS, "emitSpeedLimit",
            java.util.Arrays.asList("I", "Ljava/lang/String;"), "V");
        SPEED_STATE_CONSTRUCTOR = new ImmutableMethodReference((modern ? "Lcom/waze/bv/o;" : "Lcom/waze/bs/o;"), "<init>",
                    java.util.Arrays.asList("I", "Ljava/lang/String;", "I", "D"), "V");
        CLUSTER_OEM_EXCLUSION = new ImmutableMethodReference((modern ? "Lcom/waze/car_lib/q/i;" : "Lcom/waze/car_lib/r/g;"), modern ? "f" : "b",
                    java.util.Arrays.asList("Ljava/util/List;", "Lh/c/e;"),
                    "Ljava/lang/Object;");
        CLUSTER_OEM_LIST_PRODUCER = new ImmutableMethodReference("Lcom/waze/car_lib/e/a;", "a",
                    Collections.emptyList(), "Ljava/util/List;");
        CLUSTER_EMPTY_OEM_LIST = new ImmutableMethodReference("Ljava/util/Collections;", "emptyList",
                    Collections.emptyList(), "Ljava/util/List;");
        BOOLEAN_VALUE = new ImmutableMethodReference("Ljava/lang/Boolean;", "booleanValue",
                    Collections.emptyList(), "Z");
        TRIP_ADD_DESTINATION = new ImmutableMethodReference("Landroidx/car/app/navigation/model/Trip$Builder;",
                    "addDestination", java.util.Arrays.asList(
                    "Landroidx/car/app/navigation/model/Destination;",
                    "Landroidx/car/app/navigation/model/TravelEstimate;"),
                    "Landroidx/car/app/navigation/model/Trip$Builder;");
        TRIP_BUILD = new ImmutableMethodReference("Landroidx/car/app/navigation/model/Trip$Builder;",
                    "build", Collections.emptyList(),
                    "Landroidx/car/app/navigation/model/Trip;");
        NAVIGATION_UPDATE_TRIP = new ImmutableMethodReference("Landroidx/car/app/navigation/NavigationManager;",
                    "updateTrip", Collections.singletonList(
                    "Landroidx/car/app/navigation/model/Trip;"), "V");
        LEGACY_BRIDGE_INIT = new ImmutableMethodReference(
            LEGACY_BRIDGE_CLASS, "init",
            Collections.singletonList("Landroid/content/Context;"), "V");
        LEGACY_BRIDGE_EMIT = new ImmutableMethodReference(
            LEGACY_BRIDGE_CLASS, "emit", Collections.singletonList("Z"), "V");
        LEGACY_BRIDGE_SPEED = new ImmutableMethodReference(LEGACY_BRIDGE_CLASS, "emitSpeedLimit",
                    java.util.Arrays.asList("I", "Ljava/lang/String;"), "V");
    }

    static final String PATCHABLE_STOCK = "PATCHABLE_STOCK";
    static final String ALREADY_PATCHED = "ALREADY_PATCHED";
    static final String PARTIAL = "PARTIAL";
    static final String UNSUPPORTED = "UNSUPPORTED";
    static final int ROUTE_EXPORT_PAYLOAD_REVISION =
            com.bydhud.navexport.WazeRouteExport.PAYLOAD_REVISION;

    private static final WazePatchEngine LEGACY = new WazePatchEngine(false);
    private static final WazePatchEngine WAZE_525 = new WazePatchEngine(true);

    private static WazePatchEngine forVersion(String version) {
        return "5.25.0.1".equals(version) ? WAZE_525 : LEGACY;
    }

    static CompositeInspection inspectComposite(byte[] dex) throws IOException {
        return LEGACY.inspectCompositeImpl(dex);
    }

    static CompositeInspection inspectComposite(byte[] dex, String version) throws IOException {
        return forVersion(version).inspectCompositeImpl(dex);
    }

    static WazeInspection inspectWaze(byte[] dex) throws IOException {
        return LEGACY.inspectWazeImpl(dex);
    }

    static WazeInspection inspectWaze(byte[] dex, String version) throws IOException {
        return forVersion(version).inspectWazeImpl(dex);
    }

    static void patchWazeAllowlist(byte[] inputDex, File outputDex) throws IOException {
        LEGACY.patchWazeAllowlistImpl(inputDex, outputDex);
    }

    static void patchWazeAllowlist(byte[] inputDex, File outputDex, String version) throws IOException {
        forVersion(version).patchWazeAllowlistImpl(inputDex, outputDex);
    }

    static LifecycleInspection inspectLifecycle(byte[] dex) throws IOException {
        return LEGACY.inspectLifecycleImpl(dex);
    }

    static LifecycleInspection inspectLifecycle(byte[] dex, String version) throws IOException {
        return forVersion(version).inspectLifecycleImpl(dex);
    }

    static void patchLifecycle(byte[] inputDex, File outputDex) throws IOException {
        LEGACY.patchLifecycleImpl(inputDex, outputDex);
    }

    static void patchLifecycle(byte[] inputDex, File outputDex, String version) throws IOException {
        forVersion(version).patchLifecycleImpl(inputDex, outputDex);
    }

    static LaneInspection inspectLane(byte[] dex) throws IOException {
        return LEGACY.inspectLaneImpl(dex);
    }

    static LaneInspection inspectLane(byte[] dex, String version) throws IOException {
        return forVersion(version).inspectLaneImpl(dex);
    }

    static void patchLanes(byte[] inputDex, File outputDex) throws IOException {
        LEGACY.patchLanesImpl(inputDex, outputDex);
    }

    static void patchLanes(byte[] inputDex, File outputDex, String version) throws IOException {
        forVersion(version).patchLanesImpl(inputDex, outputDex);
    }

    static AlertInspection inspectAlertHook(byte[] dex) throws IOException {
        return LEGACY.inspectAlertHookImpl(dex);
    }

    static AlertInspection inspectAlertHook(byte[] dex, String version) throws IOException {
        return forVersion(version).inspectAlertHookImpl(dex);
    }

    static void patchAlertHook(byte[] inputDex, File outputDex) throws IOException {
        LEGACY.patchAlertHookImpl(inputDex, outputDex);
    }

    static void patchAlertHook(byte[] inputDex, File outputDex, String version) throws IOException {
        forVersion(version).patchAlertHookImpl(inputDex, outputDex);
    }

    static void patchAlertComponents(byte[] inputDex, File outputDex, boolean patchUi, boolean patchAlertOnce) throws IOException {
        LEGACY.patchAlertComponentsImpl(inputDex, outputDex, patchUi, patchAlertOnce);
    }

    static void patchAlertComponents(byte[] inputDex, File outputDex, boolean patchUi, boolean patchAlertOnce, String version) throws IOException {
        forVersion(version).patchAlertComponentsImpl(inputDex, outputDex, patchUi, patchAlertOnce);
    }

    static String inspectClusterEtaGuard(Method method) {
        return LEGACY.inspectClusterEtaGuardImpl(method);
    }

    static String inspectClusterEtaGuard(Method method, String version) {
        return forVersion(version).inspectClusterEtaGuardImpl(method);
    }

    static final class CompositeInspection {
        final WazeInspection allowlist;
        final LaneInspection lane;
        final LifecycleInspection lifecycle;
        final AlertInspection alert;

        CompositeInspection(WazeInspection allowlist, LaneInspection lane,
                LifecycleInspection lifecycle, AlertInspection alert) {
            this.allowlist = allowlist;
            this.lane = lane;
            this.lifecycle = lifecycle;
            this.alert = alert;
        }
    }

    private CompositeInspection inspectCompositeImpl(byte[] dex) throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(dex));
        return new CompositeInspection(inspectWazeImpl(file), inspectLaneImpl(file),
                inspectLifecycleImpl(file), inspectAlertHookImpl(file));
    }

    private WazeInspection inspectWazeImpl(byte[] dex) throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(dex));
        return inspectWazeImpl(file);
    }

    private WazeInspection inspectWazeImpl(DexBackedDexFile file) throws IOException {
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

    private void patchWazeAllowlistImpl(byte[] inputDex, File outputDex) throws IOException {
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
        WazeInspection verified = inspectWazeImpl(Files.readAllBytes(outputDex.toPath()));
        if (verified.targetCount != 1 || !ALREADY_PATCHED.equals(verified.classification)) {
            throw new IOException("Waze rewritten DEX verification failed: " + verified.reason);
        }
    }

    private LifecycleInspection inspectLifecycleImpl(byte[] dex) throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(dex));
        return inspectLifecycleImpl(file);
    }

    private LifecycleInspection inspectLifecycleImpl(DexBackedDexFile file) throws IOException {
        LifecycleInspection result = new LifecycleInspection();
        for (ClassDef classDef : file.getClasses()) {
            if (BRIDGE_CLASS.equals(classDef.getType())) result.bridgeClassCount++;
            if (isRouteExportPayloadClass(classDef.getType())) {
                result.geometryPayloadClassCount++;
            }
            if ("Lcom/bydhud/navexport/WazeRouteExport;".equals(classDef.getType())) {
                result.geometryPayloadCount++;
                result.geometryPayloadRevision = routeExportRevision(classDef);
            }
            if ("Lcom/bydhud/navexport/RouteExport;".equals(classDef.getType())) result.routeExportClassCount++;
            if ("Lcom/bydhud/navexport/RouteData;".equals(classDef.getType())) result.routeDataClassCount++;
            if ("Lcom/bydhud/navexport/WazeManeuverRecovery;".equals(classDef.getType())) {
                result.geometryRecoveryClassCount++;
            }
            if (LEGACY_BRIDGE_CLASS.equals(classDef.getType())) result.legacyBridgeClassCount++;
            for (Method method : classDef.getMethods()) {
                ImmutableMethodReference geometry = geometryHook(method);
                if (geometry != null) {
                    result.geometryTargetCount++;
                    int hooks = countCall(method, geometry);
                    result.geometryHookCount += hooks;
                    if (method.getImplementation() == null || hooks > 1) result.geometryInvalid = true;
                }
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
                    result.v1RouteHookCount += countCall(method, BRIDGE_EMIT_V1);
                    result.legacyRouteHookCount += countCall(method, LEGACY_BRIDGE_EMIT);
                    result.routeGuard = inspectRouteGuard(method);
                }
                if (matchesSpeed(method)) {
                    result.speedTargetCount++;
                    result.speedHookCount += countCall(method, BRIDGE_SPEED);
                    result.legacySpeedHookCount += countCall(method, LEGACY_BRIDGE_SPEED);
                    result.speedGuard = inspectSpeedGuard(method);
                }
                if (matchesClusterEta(method)) {
                    result.clusterEtaTargetCount++;
                    result.clusterEtaGuard = inspectClusterEtaGuardImpl(method);
                    if ("patched".equals(result.clusterEtaGuard)) {
                        result.clusterEtaPatchCount++;
                    }
                }
            }
        }
        return result;
    }

    private static boolean isRouteExportPayloadClass(String type) {
        return underPayloadPrefix(type, "Lcom/bydhud/navexport/WazeRouteExport")
                || underPayloadPrefix(type, "Lcom/bydhud/navexport/RouteExport")
                || underPayloadPrefix(type, "Lcom/bydhud/navexport/RouteData")
                || underPayloadPrefix(type, "Lcom/bydhud/navexport/WazeManeuverRecovery");
    }

    private static boolean underPayloadPrefix(String type, String prefix) {
        return type.equals(prefix + ";") || type.startsWith(prefix + "$");
    }

    private static int routeExportRevision(ClassDef classDef) {
        int markers = 0;
        int revision = -1;
        for (Field field : classDef.getFields()) {
            if (!"PAYLOAD_REVISION".equals(field.getName())) continue;
            markers++;
            if (!"I".equals(field.getType())
                    || !AccessFlags.STATIC.isSet(field.getAccessFlags())
                    || !AccessFlags.FINAL.isSet(field.getAccessFlags())
                    || !(field.getInitialValue() instanceof IntEncodedValue)) return -1;
            revision = ((IntEncodedValue) field.getInitialValue()).getValue();
        }
        return markers == 0 ? 0 : markers == 1 ? revision : -1;
    }

    static void replaceRouteExportPayload(byte[] inputDex, byte[] payloadDex, File output)
            throws IOException {
        DexBackedDexFile input = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(inputDex));
        DexBackedDexFile payload = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(payloadDex));
        DexPool pool = new DexPool(Opcodes.forApi(29));
        java.util.Set<String> classes = new java.util.HashSet<>();
        boolean foundOldPayload = false;
        for (ClassDef classDef : input.getClasses()) {
            if (isRouteExportPayloadClass(classDef.getType())) {
                if ("Lcom/bydhud/navexport/WazeRouteExport;".equals(classDef.getType())) {
                    foundOldPayload = true;
                }
                continue;
            }
            if (!classes.add(classDef.getType())) {
                throw new IOException("duplicate Waze class while replacing route payload: "
                        + classDef.getType());
            }
            pool.internClass(classDef);
        }
        java.util.Set<String> required = new java.util.HashSet<>(java.util.Arrays.asList(
                "Lcom/bydhud/navexport/WazeRouteExport;",
                "Lcom/bydhud/navexport/RouteExport;",
                "Lcom/bydhud/navexport/RouteData;",
                "Lcom/bydhud/navexport/WazeManeuverRecovery;"));
        for (ClassDef classDef : payload.getClasses()) {
            String type = classDef.getType();
            if (isRouteExportPayloadClass(type) && required.contains(type)) required.remove(type);
            if (!classes.add(type)) {
                throw new IOException("duplicate Waze class in replacement route payload: " + type);
            }
            pool.internClass(classDef);
        }
        if (!foundOldPayload || !required.isEmpty()) {
            throw new IOException("route payload replacement incomplete: old=" + foundOldPayload
                    + ", missing=" + required);
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        FileDataStore store = new FileDataStore(output);
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
        LifecycleInspection verified = WAZE_525.inspectLifecycleImpl(
                Files.readAllBytes(output.toPath()));
        if (verified.geometryPayloadCount != 1
                || verified.geometryPayloadRevision != ROUTE_EXPORT_PAYLOAD_REVISION) {
            throw new IOException("route payload replacement revision check failed");
        }
    }

    private void patchLifecycleImpl(byte[] inputDex, File outputDex) throws IOException {
        DexBackedDexFile input = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(inputDex));
        AtomicInteger applicationMatches = new AtomicInteger();
        AtomicInteger routeMatches = new AtomicInteger();
        AtomicInteger speedMatches = new AtomicInteger();
        AtomicInteger clusterEtaMatches = new AtomicInteger();
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> {
                    ImmutableMethodReference geometry = geometryHook(method);
                    if (geometry != null) method = injectGeometry(method, geometry);
                    if (matchesApplication(method)) {
                        applicationMatches.incrementAndGet();
                        if (countCall(method, BRIDGE_INIT) == 1
                                || countCall(method, LEGACY_BRIDGE_INIT) == 1) return method;
                        return injectApplicationInit(method);
                    }
                    if (matchesRoute(method)) {
                        routeMatches.incrementAndGet();
                        if (countCall(method, BRIDGE_EMIT) == 1
                                || countCall(method, BRIDGE_EMIT_V1) == 1
                                || countCall(method, LEGACY_BRIDGE_EMIT) == 1) return method;
                        return injectRouteEmit(method);
                    }
                    if (matchesSpeed(method)) {
                        speedMatches.incrementAndGet();
                        if (countCall(method, BRIDGE_SPEED) == 1
                                || countCall(method, LEGACY_BRIDGE_SPEED) == 1) return method;
                        return injectSpeedLimit(method);
                    }
                    if (matchesClusterEta(method)) {
                        clusterEtaMatches.incrementAndGet();
                        String guard = inspectClusterEtaGuardImpl(method);
                        if ("patched".equals(guard)) {
                            return method;
                        }
                        if (!"stock".equals(guard)) {
                            throw new IllegalStateException(
                                    "Waze cluster ETA target is not stock: " + guard);
                        }
                        return enableClusterEta(method);
                    }
                    return method;
                };
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(input);
        DexPool.writeTo(outputDex.getAbsolutePath(), rewritten);
        if (applicationMatches.get() > 1 || routeMatches.get() > 1
                || speedMatches.get() > 1 || clusterEtaMatches.get() > 1) {
            throw new IOException("Waze lifecycle rewrite counts app="
                    + applicationMatches.get() + ", route=" + routeMatches.get()
                    + ", speed=" + speedMatches.get()
                    + ", clusterEta=" + clusterEtaMatches.get());
        }
        if (clusterEtaMatches.get() == 1) {
            LifecycleInspection verified = inspectLifecycleImpl(
                    Files.readAllBytes(outputDex.toPath()));
            if (!verified.clusterEtaPatched()) {
                throw new IOException("Waze cluster ETA verification failed: "
                        + verified.clusterEtaGuard);
            }
        }
    }

    private LaneInspection inspectLaneImpl(byte[] dex) throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(Opcodes.forApi(29),
                new ByteArrayInputStream(dex));
        return inspectLaneImpl(file);
    }

    private LaneInspection inspectLaneImpl(DexBackedDexFile file) throws IOException {
        LaneInspection result = new LaneInspection();
        for (ClassDef c : file.getClasses()) {
            if (LANE_FRAME_CLASS.equals(c.getType())) {
                result.frameClassCount++;
                for (Field f : c.getFields()) if (sameField(f, LANE_FRAME_FIELD)) result.frameFieldCount++;
                for (Method m : c.getMethods()) {
                    if (sameMethod(m, LANE_FRAME_CTOR_STOCK)) result.frameStockCtorCount++;
                    if (sameMethod(m, LANE_FRAME_CTOR_PATCHED)) result.framePatchedCtorCount++;
                    if (sameMethod(m, LANE_FRAME_GETTER)) result.frameGetterCount++;
                    if ("equals".equals(m.getName()) && m.getParameterTypes().size() == 1
                            && "Ljava/lang/Object;".equals(m.getParameterTypes().get(0).toString())
                            && "Z".equals(m.getReturnType())) {
                        result.frameEqualsFieldCount += countFieldOpcode(
                                m, LANE_FRAME_FIELD, Opcode.IGET_OBJECT);
                    }
                    if ("hashCode".equals(m.getName()) && m.getParameterTypes().isEmpty()
                            && "I".equals(m.getReturnType())) {
                        result.frameHashFieldCount += countFieldOpcode(
                                m, LANE_FRAME_FIELD, Opcode.IGET_OBJECT);
                    }
                }
            } else if (LANE_PRODUCER_CLASS.equals(c.getType())) {
                for (Method m : c.getMethods()) if (sameMethod(m, LANE_PRODUCER_TARGET)) {
                    result.producerTargetCount++;
                    result.producerSourceCount += countCall(m, LANE_SOURCE);
                    result.producerFieldCount += countFieldOpcode(m, LANE_SOURCE_FIELD, Opcode.IGET_OBJECT);
                    result.producerEmptyCount += countFieldOpcode(m, COLLECTIONS_EMPTY_LIST, Opcode.SGET_OBJECT);
                    result.producerTypeCount += countTypeOpcode(m, NAVIGATION_LANE_LIST, Opcode.INSTANCE_OF);
                    result.producerCastCount += countLaneCast(m);
                    result.producerStockCtorCount += countCall(m, LANE_FRAME_CTOR_STOCK);
                    result.producerPatchedCtorCount += countCall(m, LANE_FRAME_CTOR_PATCHED);
                }
            } else if (LANE_ADAPTER_CLASS.equals(c.getType())) {
                for (Method m : c.getMethods()) {
                    if (sameMethod(m, LANE_ADAPTER_TARGET)) {
                        result.adapterTargetCount++;
                        result.adapterSentinelCount += countLaneSentinel(m);
                        result.adapterHelperCallCount += countCall(m, LANE_HELPER);
                    }
                    if (sameMethod(m, LANE_HELPER)) result.adapterHelperCount++;
                    if (sameMethod(m, LANE_ANGLE_MAPPER)) result.adapterMapperCount++;
                }
            }
        }
        if (result.stockShape()) {
            result.classification = PATCHABLE_STOCK;
            result.reason = "exact stock Waze lane targets";
        } else if (result.patchedShape() || result.publishedShape()) {
            result.classification = ALREADY_PATCHED;
            result.reason = "exact patched Waze lane targets";
        } else {
            boolean marker = result.frameFieldCount > 0 || result.framePatchedCtorCount > 0
                    || result.frameGetterCount > 0 || result.frameEqualsFieldCount > 0
                    || result.frameHashFieldCount > 0
                    || result.producerPatchedCtorCount > 0 || result.adapterHelperCount > 0
                    || result.adapterMapperCount > 0 || result.adapterHelperCallCount > 0;
            result.classification = marker ? PARTIAL : UNSUPPORTED;
            result.reason = marker ? "lane patch is partial" : "lane targets missing or ambiguous";
        }
        return result;
    }

    private void patchLanesImpl(byte[] inputDex, File outputDex) throws IOException {
        LaneInspection input = inspectLaneImpl(inputDex);
        if (!PATCHABLE_STOCK.equals(input.classification)) {
            throw new IOException("Waze lane target is not compatible stock: " + input.summary());
        }
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(Opcodes.forApi(29),
                new ByteArrayInputStream(inputDex));
        List<ClassDef> classes = new ArrayList<>();
        int frames = 0, producers = 0, adapters = 0;
        for (ClassDef c : file.getClasses()) {
            if (LANE_FRAME_CLASS.equals(c.getType())) { classes.add(rewriteLaneFrame(c)); frames++; }
            else if (LANE_PRODUCER_CLASS.equals(c.getType())) { classes.add(rewriteLaneProducer(c)); producers++; }
            else if (LANE_ADAPTER_CLASS.equals(c.getType())) { classes.add(rewriteLaneAdapter(c)); adapters++; }
            else classes.add(ImmutableClassDef.of(c));
        }
        if (frames != 1 || producers != 1 || adapters != 1) {
            throw new IOException("Waze lane rewrite counts frame=" + frames + ", producer="
                    + producers + ", adapter=" + adapters);
        }
        DexPool.writeTo(outputDex.getAbsolutePath(), new ImmutableDexFile(file.getOpcodes(), classes));
        LaneInspection verified = inspectLaneImpl(Files.readAllBytes(outputDex.toPath()));
        if (!ALREADY_PATCHED.equals(verified.classification)) {
            throw new IOException("Waze lane verification failed: " + verified.summary());
        }
    }

    private AlertInspection inspectAlertHookImpl(byte[] dex) throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(dex));
        return inspectAlertHookImpl(file);
    }

    private AlertInspection inspectAlertHookImpl(DexBackedDexFile file) throws IOException {
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
                    result.tripPublisherCallCount += countCall(
                            method, ALERT_TRIP_PUBLISHER);
                    result.guardReadCount += countFieldOpcode(
                            method, ALERT_GUARD, Opcode.IGET_BOOLEAN);
                    result.guardWriteCount += countFieldOpcode(
                            method, ALERT_GUARD, Opcode.IPUT_BOOLEAN);
                    result.logMarkerCount += countString(
                            method, "cluster alert producer/collector attached");
                }
            }
        }
        for (ClassDef classDef : file.getClasses()) {
            if (!ALERT_ONCE_CLASS.equals(classDef.getType())) continue;
            result.alertOnceClassCount++;
            for (Method method : classDef.getMethods()) {
                if (matchesAlertOnceTarget(method)) {
                    result.alertOnceTargetMethodCount++;
                    result.alertOnceStateReadCount += countFieldOpcode(
                            method, ALERT_ONCE_STATE, Opcode.IGET_OBJECT);
                    result.alertOnceModeReadCount += countCall(method, ALERT_ONCE_MODE);
                    result.alertOnceStateUpdateCount += countCall(method, ALERT_ONCE_UPDATE);
                    result.alertOnceNativeStartCount += countCall(
                            method, ALERT_ONCE_NATIVE_START);
                    result.alertOnceGuardCount += countAlertOnceGuards(method);
                    AlertOnceInspection structure = inspectAlertOnceMethod(method);
                    if (structure.stock) result.alertOnceStockShapeCount++;
                    if (structure.patched) result.alertOncePatchedShapeCount++;
                }
                if (matchesAlertOnceMethod(method, "i")
                        || matchesAlertOnceMethod(method, "j")) {
                    result.alertOnceUnchangedMethodCount++;
                }
            }
        }
        return result;
    }

    private void patchAlertHookImpl(byte[] inputDex, File outputDex) throws IOException {
        patchAlertComponentsImpl(inputDex, outputDex, true, true);
    }

    private void patchAlertComponentsImpl(byte[] inputDex, File outputDex,
            boolean patchUi, boolean patchAlertOnce) throws IOException {
        if (!patchUi && !patchAlertOnce) {
            throw new IOException("Waze alert component selection is empty");
        }
        AlertInspection inspection = inspectAlertHookImpl(inputDex);
        if (!inspection.patchableComponents()) {
            throw new IOException("Waze alert-hook target is not compatible stock: "
                    + inspection.summary());
        }
        DexBackedDexFile input = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(inputDex));
        List<ClassDef> classes = new ArrayList<>();
        int rewritten = 0;
        for (ClassDef classDef : input.getClasses()) {
            if (ALERT_SESSION_CLASS.equals(classDef.getType())) {
                classes.add(patchUi && inspection.stockTargets()
                        ? rewriteAlertClass(classDef) : ImmutableClassDef.of(classDef));
                if (patchUi && inspection.stockTargets()) rewritten++;
            } else if (ALERT_ONCE_CLASS.equals(classDef.getType())) {
                classes.add(patchAlertOnce && inspection.alertOnceStockTargets()
                        ? rewriteAlertOnceClass(classDef) : ImmutableClassDef.of(classDef));
                if (patchAlertOnce && inspection.alertOnceStockTargets()) rewritten++;
            } else {
                classes.add(ImmutableClassDef.of(classDef));
            }
        }
        if (rewritten == 0) {
            throw new IOException("Waze alert-hook class rewrite count=0");
        }
        DexPool.writeTo(outputDex.getAbsolutePath(),
                new ImmutableDexFile(input.getOpcodes(), classes));
        AlertInspection verified = inspectAlertHookImpl(Files.readAllBytes(outputDex.toPath()));
        if (!verified.patchedComponents(inspection, patchUi, patchAlertOnce)) {
            throw new IOException("Waze alert-hook verification failed: " + verified.summary());
        }
    }

    private ClassDef rewriteLaneFrame(ClassDef c) {
        List<Field> fields = new ArrayList<>(); c.getFields().forEach(fields::add);
        fields.add(new ImmutableField(LANE_FRAME_CLASS, "i", NAVIGATION_LANE_LIST,
                AccessFlags.PRIVATE.getValue() | AccessFlags.FINAL.getValue(), null,
                Collections.emptySet(), Collections.emptySet()));
        List<Method> methods = new ArrayList<>(); int count = 0;
        for (Method m : c.getMethods()) {
            if (sameMethod(m, LANE_FRAME_CTOR_STOCK)) { methods.add(injectLaneFrameCtor(m)); count++; }
            else if ("equals".equals(m.getName()) && m.getParameterTypes().size() == 1
                    && "Ljava/lang/Object;".equals(m.getParameterTypes().get(0).toString())
                    && "Z".equals(m.getReturnType())) {
                methods.add(injectLaneFrameEquals(m));
            } else if ("hashCode".equals(m.getName()) && m.getParameterTypes().isEmpty()
                    && "I".equals(m.getReturnType())) {
                methods.add(injectLaneFrameHash(m));
            }
            else methods.add(ImmutableMethod.of(m));
        }
        if (count != 1) throw new IllegalStateException("Waze lane constructor count=" + count);
        MethodImplementationBuilder code = new MethodImplementationBuilder(1);
        code.addInstruction(new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 0, LANE_FRAME_FIELD));
        code.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        methods.add(new ImmutableMethod(LANE_FRAME_CLASS, "i", Collections.emptyList(), NAVIGATION_LANE_LIST,
                AccessFlags.PUBLIC.getValue() | AccessFlags.FINAL.getValue(), Collections.emptySet(),
                Collections.emptySet(), code.getMethodImplementation()));
        return new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(),
                c.getSourceFile(), c.getAnnotations(), fields, methods);
    }

    private Method injectLaneFrameCtor(Method m) {
        MethodImplementation src = m.getImplementation(); if (src == null) throw new IllegalStateException("lane ctor body missing");
        List<Instruction> ins = new ArrayList<>(toList(src)); int ret = -1;
        for (int i = 0; i < ins.size(); i++) if (ins.get(i).getOpcode() == Opcode.RETURN_VOID) {
            if (ret >= 0) throw new IllegalStateException("lane ctor return ambiguous"); ret = i;
        }
        if (ret < 0) throw new IllegalStateException("lane ctor return missing");
        int params = 1; for (CharSequence t : m.getParameterTypes()) params += ("J".contentEquals(t) || "D".contentEquals(t)) ? 2 : 1;
        int thiz = src.getRegisterCount() - params;
        ins.add(ret, new org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c(
                Opcode.IPUT_OBJECT, src.getRegisterCount(), thiz, LANE_FRAME_FIELD));
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), laneParameters(), m.getReturnType(),
                m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(),
                new ImmutableMethodImplementation(src.getRegisterCount() + 1, ins,
                        src.getTryBlocks(), src.getDebugItems()));
    }

    private List<ImmutableMethodParameter> laneParameters() {
        return parameters(java.util.Arrays.asList("I", (modern ? "Lcom/waze/car_lib/r/cg;" : "Lcom/waze/t/b/bw;"),
                "Landroid/text/SpannableStringBuilder;", "Ljava/lang/String;", "Landroid/graphics/Bitmap;",
                "Ljava/lang/Integer;", "Ljava/util/Collection;", "Ljava/lang/Long;", NAVIGATION_LANE_LIST));
    }

    private Method injectLaneFrameEquals(Method m) {
        MethodImplementation src = m.getImplementation();
        if (src == null) throw new IllegalStateException("lane equals body missing");
        MutableMethodImplementation out = new MutableMethodImplementation(src);
        int thisRegister = src.getRegisterCount() - 2;
        int otherRegister = thisRegister + 1;
        int anchor = -1;
        int anchors = 0;
        for (int i = 0; i < out.getInstructions().size(); i++) {
            Instruction x = out.getInstructions().get(i);
            if (x.getOpcode() == Opcode.IGET_OBJECT && x instanceof ReferenceInstruction
                    && sameField((FieldReference) ((ReferenceInstruction) x).getReference(),
                    new ImmutableFieldReference(LANE_FRAME_CLASS, "g", "Ljava/lang/Long;"))) {
                if (anchor < 0) anchor = i;
                anchors++;
            }
        }
        if (anchors != 2) throw new IllegalStateException("lane equals anchor count=" + anchors);
        Label continueLabel;
        int at;
        if (modern) {
            org.jf.dexlib2.builder.BuilderInstruction longField = out.getInstructions().get(anchor);
            out.replaceInstruction(anchor, new BuilderInstruction22c(Opcode.IGET_OBJECT, 1,
                    thisRegister, LANE_FRAME_FIELD));
            out.addInstruction(anchor + 1, longField);
            continueLabel = out.newLabelForIndex(anchor + 1);
            at = anchor + 1;
        } else {
            continueLabel = out.newLabelForIndex(anchor);
            at = anchor;
            out.addInstruction(at++, new BuilderInstruction22c(Opcode.IGET_OBJECT, 1,
                    thisRegister, LANE_FRAME_FIELD));
        }
        out.addInstruction(at++, new BuilderInstruction22c(Opcode.IGET_OBJECT, 3,
                otherRegister, LANE_FRAME_FIELD));
        out.addInstruction(at++, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2,
                1, 3, 0, 0, 0, OBJECT_EQUALS));
        out.addInstruction(at++, new BuilderInstruction11x(Opcode.MOVE_RESULT, 1));
        out.addInstruction(at++, new BuilderInstruction21t(Opcode.IF_NEZ, 1, continueLabel));
        out.addInstruction(at, new BuilderInstruction11x(Opcode.RETURN, 2));
        return immutable(m, out);
    }

    private Method injectLaneFrameHash(Method m) {
        MethodImplementation src = m.getImplementation();
        if (src == null) throw new IllegalStateException("lane hash body missing");
        MutableMethodImplementation out = new MutableMethodImplementation(src);
        int thisRegister = src.getRegisterCount() - 1;
        int anchor = -1;
        FieldReference expected = new ImmutableFieldReference(
                LANE_FRAME_CLASS, "g", "Ljava/lang/Long;");
        for (int i = 0; i < out.getInstructions().size(); i++) {
            Instruction x = out.getInstructions().get(i);
            if (x.getOpcode() == Opcode.IGET_OBJECT && x instanceof ReferenceInstruction
                    && sameField((FieldReference) ((ReferenceInstruction) x).getReference(), expected)) {
                if (anchor >= 0) throw new IllegalStateException("lane hash anchor ambiguous");
                anchor = i;
            }
        }
        if (anchor < 0) throw new IllegalStateException("lane hash anchor missing");
        int at = anchor;
        out.addInstruction(at++, new BuilderInstruction22c(Opcode.IGET_OBJECT, 0,
                thisRegister, LANE_FRAME_FIELD));
        out.addInstruction(at++, new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL,
                1, 0, 0, 0, 0, 0, OBJECT_HASH));
        out.addInstruction(at++, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        out.addInstruction(at++, new BuilderInstruction12x(Opcode.ADD_INT_2ADDR, 2, 0));
        out.addInstruction(at, new BuilderInstruction22b(Opcode.MUL_INT_LIT8, 2, 2, 31));
        return immutable(m, out);
    }

    private ClassDef rewriteLaneProducer(ClassDef c) {
        List<Method> methods = new ArrayList<>(); int count = 0;
        for (Method m : c.getMethods()) { if (sameMethod(m, LANE_PRODUCER_TARGET)) { methods.add(injectLaneProducer(m)); count++; } else methods.add(ImmutableMethod.of(m)); }
        if (count != 1) throw new IllegalStateException("Waze lane producer count=" + count);
        return new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods);
    }

    private Method injectLaneProducer(Method m) {
        MethodImplementation src = m.getImplementation(); if (src == null) throw new IllegalStateException("lane producer body missing");
        List<Instruction> original = new ArrayList<>(toList(src)); int branch = -1, ctor = -1;
        for (int i = 0; i < original.size(); i++) {
            Instruction x = original.get(i);
            if (x.getOpcode() == Opcode.CONST_4 && x instanceof OneRegisterInstruction && ((OneRegisterInstruction)x).getRegisterA() == 6 && literal(x, 0)
                    && i + 1 < original.size() && original.get(i + 1).getOpcode() == Opcode.IF_NE) { if (branch >= 0) throw new IllegalStateException("lane producer entry ambiguous"); branch = i + 1; }
            if (isCall(x, LANE_FRAME_CTOR_STOCK)) { if (ctor >= 0) throw new IllegalStateException("lane producer ctor ambiguous"); ctor = i; }
        }
        if (branch < 0 || ctor < 0) throw new IllegalStateException("lane producer anchors missing");
        MutableMethodImplementation out = new MutableMethodImplementation(src);
        Label ready = out.newLabelForIndex(ctor);
        int p0 = src.getRegisterCount() - 6;
        org.jf.dexlib2.iface.instruction.RegisterRangeInstruction stockCall =
                (org.jf.dexlib2.iface.instruction.RegisterRangeInstruction) original.get(ctor);
        int ctorStart = stockCall.getStartRegister();
        int lanesRegister = ctorStart + stockCall.getRegisterCount();
        if (stockCall.getRegisterCount() != 9 || ctorStart < 2 || lanesRegister >= p0)
            throw new IllegalStateException("lane producer register layout unsupported");
        int at = ctor;
        out.addInstruction(at++, new BuilderInstruction21c(
                Opcode.SGET_OBJECT, lanesRegister, COLLECTIONS_EMPTY_LIST));
        out.addInstruction(at++, new BuilderInstruction3rc(
                Opcode.INVOKE_VIRTUAL_RANGE, p0, 1, LANE_SOURCE));
        out.addInstruction(at++, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1));
        out.addInstruction(at++, new BuilderInstruction21t(Opcode.IF_EQZ, 1, ready));
        out.addInstruction(at++, new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 1, LANE_SOURCE_FIELD));
        out.addInstruction(at++, new BuilderInstruction22c(Opcode.INSTANCE_OF, 1, 0,
                new ImmutableTypeReference(NAVIGATION_LANE_LIST)));
        out.addInstruction(at++, new BuilderInstruction21t(Opcode.IF_EQZ, 1, ready));
        out.addInstruction(at++, new BuilderInstruction21c(Opcode.CHECK_CAST, 0,
                new ImmutableTypeReference(NAVIGATION_LANE_LIST)));
        out.addInstruction(at++, new BuilderInstruction22x(Opcode.MOVE_OBJECT_FROM16, lanesRegister, 0));
        out.replaceInstruction(at, new BuilderInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, ctorStart, stockCall.getRegisterCount() + 1,
                LANE_FRAME_CTOR_PATCHED));
        return immutable(m, out);
    }

    private ClassDef rewriteLaneAdapter(ClassDef c) {
        List<Method> methods = new ArrayList<>(); int count = 0;
        for (Method m : c.getMethods()) {
            if (sameMethod(m, LANE_ADAPTER_TARGET)) { methods.add(injectLaneAdapter(m)); count++; }
            else if (sameMethod(m, LANE_HELPER) || sameMethod(m, LANE_ANGLE_MAPPER)) throw new IllegalStateException("lane helper duplicate");
            else methods.add(ImmutableMethod.of(m));
        }
        if (count != 1) throw new IllegalStateException("Waze lane adapter count=" + count);
        methods.add(buildLaneHelper()); methods.add(buildLaneMapper());
        return new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getFields(), methods);
    }

    private Method injectLaneAdapter(Method m) {
        MethodImplementation src = m.getImplementation(); if (src == null) throw new IllegalStateException("lane adapter body missing");
        List<? extends Instruction> ins = toList(src); int[] s = findSentinel(ins); if (s == null) throw new IllegalStateException("lane sentinel missing");
        int p0 = src.getRegisterCount() - 2;
        int frameSave = -1;
        for (int i = 0; i < s[0]; i++) {
            if (isCall(ins.get(i), LANE_FRAME_BITMAP)) {
                if (frameSave < 0) frameSave = i;
            }
        }
        if (frameSave < 0) throw new IllegalStateException("lane frame bitmap anchor missing");
        MutableMethodImplementation out = new MutableMethodImplementation(src);
        if (modern) {
            // Branch labels must execute the frame save before the bitmap call.
            org.jf.dexlib2.builder.BuilderInstruction bitmapCall = out.getInstructions().get(frameSave);
            out.replaceInstruction(frameSave, new BuilderInstruction12x(Opcode.MOVE_OBJECT, 7, p0));
            out.addInstruction(frameSave + 1, bitmapCall);
        } else {
            out.addInstruction(frameSave, new BuilderInstruction12x(Opcode.MOVE_OBJECT, 7, p0));
        }
        int shift = frameSave <= s[0] ? 1 : 0;
        s[0] += shift;
        s[1] += shift;
        for (int i = s[1]; i >= s[0]; i--) out.removeInstruction(i);
        int at = s[0];
        out.addInstruction(at++, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 7, 0, 0, 0, 0, LANE_HELPER));
        out.addInstruction(at++, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2));
        out.addInstruction(at++, new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 2, 0, 0, 0, 0, LIST_ITERATOR));
        out.addInstruction(at++, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2));
        out.addInstruction(at++, new BuilderInstruction10x(Opcode.NOP));
        Label loop = out.newLabelForIndex(at - 1);
        Label done = out.newLabelForIndex(at);
        out.addInstruction(at++, new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 2, 0, 0, 0, 0, ITERATOR_HAS_NEXT));
        out.addInstruction(at++, new BuilderInstruction11x(Opcode.MOVE_RESULT, 3));
        out.addInstruction(at++, new BuilderInstruction21t(Opcode.IF_EQZ, 3, done));
        out.addInstruction(at++, new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 2, 0, 0, 0, 0, ITERATOR_NEXT));
        out.addInstruction(at++, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3));
        out.addInstruction(at++, new BuilderInstruction21c(Opcode.CHECK_CAST, 3, new ImmutableTypeReference("Landroidx/car/app/navigation/model/Lane;")));
        out.addInstruction(at++, new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 3, 0, 0, 0, STEP_ADD_LANE));
        out.addInstruction(at++, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        out.addInstruction(at, new BuilderInstruction10t(Opcode.GOTO, loop));
        return immutable(m, out);
    }

    private int[] findSentinel(List<? extends Instruction> x) {
        for (int i = 0; i + 9 < x.size(); i++) if (isType(x.get(i), LANE_BUILDER_CLASS)
                && isCall(x.get(i + 1), LANE_BUILDER_CTOR)
                && literal(x.get(i + 2), 6) && literal(x.get(i + 3), 1)
                && isCall(x.get(i + 4), LANE_DIRECTION_CREATE)
                && x.get(i + 5).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                && isCall(x.get(i + 6), LANE_ADD_DIRECTION)
                && isCall(x.get(i + 7), LANE_BUILD)
                && x.get(i + 8).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                && isCall(x.get(i + 9), STEP_ADD_LANE)) return new int[]{i, i + 9};
        return null;
    }

    private int countLaneSentinel(Method m) {
        MethodImplementation implementation = m.getImplementation();
        if (implementation == null) return 0;
        List<? extends Instruction> instructions = toList(implementation);
        int count = 0;
        for (int i = 0; i + 9 < instructions.size(); i++) {
            if (findSentinel(instructions.subList(i, i + 10)) != null) count++;
        }
        return count;
    }

    private Method buildLaneHelper() {
        MethodImplementationBuilder c = new MethodImplementationBuilder(10);
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 9, 0, 0, 0, 0, LANE_FRAME_GETTER));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 9));
        c.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE, 0, new ImmutableTypeReference("Ljava/util/ArrayList;")));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 9, 0, 0, 0, 0, LIST_SIZE));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 1));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2, 0, 1, 0, 0, 0, method("Ljava/util/ArrayList;", "<init>", Collections.singletonList("I"), "V")));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 9, 0, 0, 0, 0, LIST_ITERATOR));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1)); c.addLabel("outer");
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1, 0, 0, 0, 0, ITERATOR_HAS_NEXT));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 2)); c.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 2, c.getLabel("done")));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1, 0, 0, 0, 0, ITERATOR_NEXT));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2)); c.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST, 2, new ImmutableTypeReference(NAVIGATION_LANE_CLASS)));
        c.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE, 3, new ImmutableTypeReference(LANE_BUILDER_CLASS)));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 3, 0, 0, 0, 0, LANE_BUILDER_CTOR));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0, NAVIGATION_ANGLE_LIST));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 4, 0, 0, 0, 0, LIST_SIZE));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 6));
        c.addInstruction(new BuilderInstruction21t(Opcode.IF_NEZ, 6, c.getLabel("angleIterator")));
        c.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 6, 1));
        c.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 7, 0));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 6, 7, 0, 0, 0, LANE_DIRECTION_CREATE));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 6));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 6, 0, 0, 0, LANE_ADD_DIRECTION));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3));
        c.addLabel("angleIterator");
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 4, 0, 0, 0, 0, LIST_ITERATOR));
        c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 5)); c.addLabel("angles");
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 5, 0, 0, 0, 0, ITERATOR_HAS_NEXT)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 6));
        c.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 6, c.getLabel("angleDone")));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 5, 0, 0, 0, 0, ITERATOR_NEXT)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 6));
        c.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST, 6, new ImmutableTypeReference(NAVIGATION_LANE_ANGLE_CLASS)));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 6, 0, 0, 0, 0, NAVIGATION_ANGLE)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 7));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 7, 0, 0, 0, 0, LANE_ANGLE_MAPPER)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 7));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 6, 0, 0, 0, 0, NAVIGATION_SELECTED)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 8));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 7, 8, 0, 0, 0, LANE_DIRECTION_CREATE)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 6));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 6, 0, 0, 0, LANE_ADD_DIRECTION)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3));
        c.addInstruction(new BuilderInstruction10t(Opcode.GOTO, c.getLabel("angles"))); c.addLabel("angleDone");
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0, LANE_BUILD)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 2, 0, 2, 0, 0, 0, LIST_ADD)); c.addInstruction(new BuilderInstruction10t(Opcode.GOTO, c.getLabel("outer"))); c.addLabel("done");
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0, LIST_SIZE)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 1));
        c.addInstruction(new BuilderInstruction21t(Opcode.IF_NEZ, 1, c.getLabel("return")));
        c.addInstruction(new BuilderInstruction21c(Opcode.NEW_INSTANCE, 1, new ImmutableTypeReference(LANE_BUILDER_CLASS))); c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 1, 0, 0, 0, 0, LANE_BUILDER_CTOR));
        c.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 2, 1)); c.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 3, 0)); c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 2, 3, 0, 0, 0, LANE_DIRECTION_CREATE)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2));
        c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, LANE_ADD_DIRECTION)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1)); c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, LANE_BUILD)); c.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1)); c.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_INTERFACE, 2, 0, 1, 0, 0, 0, LIST_ADD)); c.addLabel("return"); c.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        return new ImmutableMethod(LANE_ADAPTER_CLASS, "h", parameters(Collections.singletonList(LANE_FRAME_CLASS)), NAVIGATION_LANE_LIST, AccessFlags.PRIVATE.getValue() | AccessFlags.STATIC.getValue(), Collections.emptySet(), Collections.emptySet(), c.getMethodImplementation());
    }

    private Method buildLaneMapper() {
        MethodImplementationBuilder c = new MethodImplementationBuilder(2);
        c.addInstruction(new BuilderInstruction21t(Opcode.IF_LTZ, 1, c.getLabel("neg"))); c.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 1, c.getLabel("straight")));
        c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, 89)); c.addInstruction(new BuilderInstruction22t(Opcode.IF_LE, 1, 0, c.getLabel("slightR"))); c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, 90)); c.addInstruction(new BuilderInstruction22t(Opcode.IF_EQ, 1, 0, c.getLabel("normalR"))); c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, 179)); c.addInstruction(new BuilderInstruction22t(Opcode.IF_LE, 1, 0, c.getLabel("sharpR"))); c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, 180)); c.addInstruction(new BuilderInstruction22t(Opcode.IF_EQ, 1, 0, c.getLabel("uturnR"))); c.addInstruction(new BuilderInstruction10t(Opcode.GOTO, c.getLabel("unknown")));
        c.addLabel("neg"); c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, -180)); c.addInstruction(new BuilderInstruction22t(Opcode.IF_EQ, 1, 0, c.getLabel("uturnL"))); c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, -179)); c.addInstruction(new BuilderInstruction22t(Opcode.IF_LT, 1, 0, c.getLabel("unknown"))); c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, -91)); c.addInstruction(new BuilderInstruction22t(Opcode.IF_LE, 1, 0, c.getLabel("sharpL"))); c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, -90)); c.addInstruction(new BuilderInstruction22t(Opcode.IF_EQ, 1, 0, c.getLabel("normalL"))); c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, -1)); c.addInstruction(new BuilderInstruction22t(Opcode.IF_LE, 1, 0, c.getLabel("slightL"))); c.addInstruction(new BuilderInstruction10t(Opcode.GOTO, c.getLabel("unknown")));
        addMapperReturn(c, "unknown", 1); addMapperReturn(c, "straight", 2); addMapperReturn(c, "slightL", 3); addMapperReturn(c, "slightR", 4); addMapperReturn(c, "normalL", 5); addMapperReturn(c, "normalR", 6); addMapperReturn(c, "sharpL", 7); addMapperReturn(c, "sharpR", 8); addMapperReturn(c, "uturnL", 9); addMapperReturn(c, "uturnR", 10);
        return new ImmutableMethod(LANE_ADAPTER_CLASS, "i", parameters(Collections.singletonList("I")), "I", AccessFlags.PRIVATE.getValue() | AccessFlags.STATIC.getValue(), Collections.emptySet(), Collections.emptySet(), c.getMethodImplementation());
    }

    private void addMapperReturn(MethodImplementationBuilder c, String label, int value) { c.addLabel(label); c.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 0, value)); c.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0)); }
    private ClassDef rewriteAlertClass(ClassDef classDef) {
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

    private ClassDef rewriteAlertOnceClass(ClassDef classDef) {
        List<Method> methods = new ArrayList<>();
        int targetCount = 0;
        for (Method method : classDef.getMethods()) {
            if (matchesAlertOnceTarget(method)) {
                methods.add(injectAlertOnceGuard(method));
                targetCount++;
            } else {
                methods.add(ImmutableMethod.of(method));
            }
        }
        if (targetCount != 1) {
            throw new IllegalStateException("Waze alert-once target count=" + targetCount);
        }
        return new ImmutableClassDef(
                classDef.getType(), classDef.getAccessFlags(), classDef.getSuperclass(),
                classDef.getInterfaces(), classDef.getSourceFile(), classDef.getAnnotations(),
                classDef.getFields(), methods);
    }

    private Method injectAlertOnceGuard(Method method) {
        AlertOnceInspection inspection = inspectAlertOnceMethod(method);
        if (!inspection.stock) {
            throw new IllegalStateException(
                    "Waze alert-once target is not compatible stock: " + inspection.reason);
        }
        MethodImplementation source = method.getImplementation();
        MutableMethodImplementation mutable = new MutableMethodImplementation(source);
        int thisRegister = source.getRegisterCount() - 1;
        mutable.addInstruction(0, new BuilderInstruction22c(
                Opcode.IGET_OBJECT, 4, thisRegister, ALERT_ONCE_STATE));
        mutable.addInstruction(1, new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 1, 4, 0, 0, 0, 0, ALERT_ONCE_MODE));
        mutable.addInstruction(2, new BuilderInstruction11x(
                Opcode.MOVE_RESULT_OBJECT, 4));

        int update = -1;
        int nativeStart = -1;
        for (int index = 0; index < mutable.getInstructions().size(); index++) {
            Instruction instruction = mutable.getInstructions().get(index);
            if (isCall(instruction, ALERT_ONCE_UPDATE)) {
                if (update >= 0) throw new IllegalStateException("Waze alert-once update is ambiguous");
                update = index;
            }
            if (isCall(instruction, ALERT_ONCE_NATIVE_START)) {
                if (nativeStart >= 0) {
                    throw new IllegalStateException("Waze alert-once native start is ambiguous");
                }
                nativeStart = index;
            }
        }
        if (update < 1 || nativeStart < 1) {
            throw new IllegalStateException("Waze alert-once update or native start is missing");
        }
        Instruction updateInstruction = mutable.getInstructions().get(update);
        if (!(updateInstruction instanceof FiveRegisterInstruction)) {
            throw new IllegalStateException("Waze alert-once update register shape mismatch");
        }
        FiveRegisterInstruction updateRegisters = (FiveRegisterInstruction) updateInstruction;
        if (updateRegisters.getRegisterD() != 4) {
            throw new IllegalStateException("Waze alert-once update does not use stock null register");
        }
        mutable.replaceInstruction(update, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, updateRegisters.getRegisterCount(),
                updateRegisters.getRegisterC(), 2, updateRegisters.getRegisterE(),
                updateRegisters.getRegisterF(), updateRegisters.getRegisterG(),
                ALERT_ONCE_UPDATE));
        int nullRegister = -1;
        for (int index = update - 1; index >= 0; index--) {
            Instruction instruction = mutable.getInstructions().get(index);
            if (instruction.getOpcode() == Opcode.CONST_4
                    && instruction instanceof org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
                    && ((org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction) instruction)
                    .getNarrowLiteral() == 0
                    && instruction instanceof OneRegisterInstruction
                    && ((OneRegisterInstruction) instruction).getRegisterA() == 4) {
                nullRegister = index;
                break;
            }
        }
        if (nullRegister < 0) {
            throw new IllegalStateException("Waze alert-once null register is missing");
        }
        mutable.removeInstruction(nullRegister);
        if (nullRegister < nativeStart) nativeStart--;

        int listenerLoad = nativeStart - 2;
        if (listenerLoad < 0
                || !isAlertOnceFieldLoad(
                mutable.getInstructions().get(listenerLoad), ALERT_ONCE_LISTENER, 0, thisRegister)
                || !isAlertOnceFieldLoad(
                mutable.getInstructions().get(listenerLoad + 1),
                ALERT_ONCE_VALUE, thisRegister, thisRegister)) {
            throw new IllegalStateException("Waze alert-once native receiver shape mismatch");
        }
        Label skip = mutable.newLabelForIndex(nativeStart + 1);
        mutable.addInstruction(listenerLoad, new BuilderInstruction21c(
                Opcode.SGET_OBJECT, 0,
                new ImmutableFieldReference(ALERT_ONCE_MODE_CLASS, "b", ALERT_ONCE_MODE_CLASS)));
        mutable.addInstruction(listenerLoad + 1, new BuilderInstruction22t(
                Opcode.IF_EQ, 4, 0, skip));
        mutable.addInstruction(listenerLoad + 2, new BuilderInstruction21c(
                Opcode.SGET_OBJECT, 0,
                new ImmutableFieldReference(ALERT_ONCE_MODE_CLASS, "c", ALERT_ONCE_MODE_CLASS)));
        mutable.addInstruction(listenerLoad + 3, new BuilderInstruction22t(
                Opcode.IF_EQ, 4, 0, skip));
        return immutable(method, mutable);
    }

    private Method injectAlertHelperCall(Method method) {
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

    private Method buildAlertHelper() {
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
                Opcode.CHECK_CAST, 2, new ImmutableTypeReference((modern ? "Lkotlinx/coroutines/ae;" : "Lkotlinx/coroutines/aj;"))));
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
        addKoinLookup(code, 3, 4, 5, 4, true, (modern ? "Lcom/waze/car_lib/a/ak;" : "Lcom/waze/car_lib/b/ap;"));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 3, 4, 0, 2, 0, 0, ALERT_PRODUCER));
        addKoinLookup(code, 3, 4, 5, 4, false, (modern ? "Lcom/waze/car_lib/j/q;" : "Lcom/waze/car_lib/j/r;"));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 4, 4, 2, 1, 0, 0, ALERT_TRIP_PUBLISHER));
        addKoinLookup(code, 3, 4, 5, 3, false, (modern ? "Lcom/waze/car_lib/a/f;" : "Lcom/waze/car_lib/b/f;"));
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

    private void addKoinLookup(MethodImplementationBuilder code, int rootRegister,
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

    private ImmutableMethodReference method(
            String owner, String name, List<String> parameters, String returnType) {
        return new ImmutableMethodReference(owner, name, parameters, returnType);
    }

    private Method injectApplicationInit(Method method) {
        if (countCall(method, BRIDGE_INIT) != 0
                || countCall(method, LEGACY_BRIDGE_INIT) != 0
                || method.getImplementation() == null) {
            throw new IllegalStateException("Waze application lifecycle hook is not stock");
        }
        MutableMethodImplementation mutable = new MutableMethodImplementation(
                method.getImplementation());
        int thisRegister = method.getImplementation().getRegisterCount() - 1;
        mutable.addInstruction(0, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 1, thisRegister, 0, 0, 0, 0, BRIDGE_INIT));
        return immutable(method, mutable);
    }

    private Method injectRouteEmit(Method method) {
        if (countCall(method, BRIDGE_EMIT) != 0
                || countCall(method, BRIDGE_EMIT_V1) != 0
                || countCall(method, LEGACY_BRIDGE_EMIT) != 0
                || !"ok".equals(inspectRouteGuard(method))) {
            throw new IllegalStateException("Waze route lifecycle hook is not stock");
        }
        MethodImplementation source = method.getImplementation();
        MutableMethodImplementation mutable = new MutableMethodImplementation(source);
        int parameterBase = source.getRegisterCount() - 3;
        int stateRegister = parameterBase + 1;
        int reasonRegister = parameterBase + 2;
        if (routeAnchorIndex(method, false) < 0) {
            throw new IllegalStateException("Waze route transition missing");
        }
        // The stock method reuses the reason register for its state-change result.
        mutable.addInstruction(0, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 2, stateRegister, reasonRegister, 0, 0, 0,
                BRIDGE_EMIT));
        return immutable(method, mutable);
    }

    private Method injectSpeedLimit(Method method) {
        if (countCall(method, BRIDGE_SPEED) != 0
                || countCall(method, LEGACY_BRIDGE_SPEED) != 0
                || !"ok".equals(inspectSpeedGuard(method))) {
            throw new IllegalStateException("Waze speed-limit hook is not stock");
        }
        MethodImplementation source = method.getImplementation();
        MutableMethodImplementation mutable = new MutableMethodImplementation(source);
        int parameterBase = source.getRegisterCount() - 6;
        int unitRegister = parameterBase + 2;
        int limitRegister = parameterBase + 3;
        mutable.addInstruction(0, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 2, limitRegister, unitRegister, 0, 0, 0, BRIDGE_SPEED));
        return immutable(method, mutable);
    }

    private Method enableClusterEta(Method method) {
        if (!"stock".equals(inspectClusterEtaGuardImpl(method))) {
            throw new IllegalStateException("Waze cluster ETA target is not stock");
        }
        int producerIndex = clusterEtaListProducerIndex(
                method, CLUSTER_OEM_LIST_PRODUCER);
        if (producerIndex < 0) {
            throw new IllegalStateException("Waze cluster ETA OEM-list producer missing");
        }
        MutableMethodImplementation mutable = new MutableMethodImplementation(
                method.getImplementation());
        mutable.removeInstruction(producerIndex);
        // Both invoke forms occupy three code units, so every stock branch offset stays intact.
        mutable.addInstruction(producerIndex, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, CLUSTER_EMPTY_OEM_LIST));
        return immutable(method, mutable);
    }

    private Method immutable(Method method, MethodImplementation implementation) {
        return new ImmutableMethod(
                method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                method.getHiddenApiRestrictions(), implementation);
    }

    private boolean matchesApplication(Method method) {
        return APPLICATION_CLASS.equals(method.getDefiningClass())
                && "onCreate".equals(method.getName())
                && method.getParameterTypes().isEmpty()
                && "V".equals(method.getReturnType());
    }

    private ImmutableMethodReference geometryHook(Method method) {
        if (!modern || !"V".equals(method.getReturnType())) return null;
        if (matchesApplication(method)) {
            return new ImmutableMethodReference("Lcom/bydhud/navexport/WazeRouteExport;",
                    "init", Collections.singletonList("Landroid/content/Context;"), "V");
        }
        if (!"Lcom/waze/navigate/NavigationInfoNativeManager;".equals(method.getDefiningClass())) return null;
        String name;
        List<String> source;
        List<String> target;
        switch (method.getName()) {
            case "onNavigationRouteChanged":
                name = "route";
                source = Collections.singletonList("Lcom/waze/jni/protos/navigate/NavigationRoute;");
                target = java.util.Arrays.asList("Ljava/lang/Object;", "Ljava/lang/Object;");
                break;
            case "onRouteGeometryUpdated":
                name = "geometry";
                source = Collections.singletonList("Lcom/waze/jni/protos/navigate/PolylineGeometry;");
                target = java.util.Arrays.asList("Ljava/lang/Object;", "Ljava/lang/Object;");
                break;
            case "onNavigationStateChanged":
                name = "navigating";
                source = java.util.Arrays.asList("Z", "I");
                target = java.util.Arrays.asList("Ljava/lang/Object;", "Z", "I");
                break;
            case "onStreetNameChanged":
                name = "currentStreet";
                source = Collections.singletonList("Lcom/waze/jni/protos/navigate/CurrentStreetNameUpdate;");
                target = Collections.singletonList("Ljava/lang/Object;");
                break;
            case "onNextNameChanged":
                name = "nextStreet";
                source = Collections.singletonList("Lcom/waze/jni/protos/navigate/NextStreetNameUpdate;");
                target = Collections.singletonList("Ljava/lang/Object;");
                break;
            case "onCurrentInstructionChanged":
                name = "currentInstruction";
                source = target = Collections.singletonList("I");
                break;
            case "onNextInstructionChanged":
                name = "nextInstruction";
                source = target = Collections.singletonList("I");
                break;
            default: return null;
        }
        if (source.size() != method.getParameterTypes().size()) return null;
        for (int i = 0; i < source.size(); i++) {
            if (!source.get(i).contentEquals(method.getParameterTypes().get(i))) return null;
        }
        return new ImmutableMethodReference("Lcom/bydhud/navexport/WazeRouteExport;", name, target, "V");
    }

    private Method injectGeometry(Method method, ImmutableMethodReference hook) {
        int calls = countCall(method, hook);
        if (calls == 1) return method;
        if (calls != 0 || method.getImplementation() == null
                || AccessFlags.STATIC.isSet(method.getAccessFlags())) {
            throw new IllegalStateException("Unsupported Waze geometry hook: " + method.getName());
        }
        MutableMethodImplementation code = new MutableMethodImplementation(method.getImplementation());
        int count = hook.getParameterTypes().size();
        int first = code.getRegisterCount() - count;
        code.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, first, count, hook));
        return immutable(method, code);
    }

    private boolean matchesRoute(Method method) {
        return ROUTE_CLASS.equals(method.getDefiningClass())
                && (modern ? "p" : "m").equals(method.getName())
                && method.getParameterTypes().size() == 2
                && "Z".equals(method.getParameterTypes().get(0).toString())
                && "I".equals(method.getParameterTypes().get(1).toString())
                && "V".equals(method.getReturnType());
    }

    private boolean matchesSpeed(Method method) {
        return SPEED_CLASS.equals(method.getDefiningClass())
                && "updateSpeedometer".equals(method.getName())
                && method.getParameterTypes().size() == 4
                && "I".equals(method.getParameterTypes().get(0).toString())
                && "Ljava/lang/String;".equals(method.getParameterTypes().get(1).toString())
                && "I".equals(method.getParameterTypes().get(2).toString())
                && "D".equals(method.getParameterTypes().get(3).toString())
                && "V".equals(method.getReturnType());
    }

    private boolean matchesClusterEta(Method method) {
        return CLUSTER_TRIP_CLASS.equals(method.getDefiningClass())
                && "invokeSuspend".equals(method.getName())
                && method.getParameterTypes().size() == 1
                && "Ljava/lang/Object;".equals(
                method.getParameterTypes().get(0).toString())
                && "Ljava/lang/Object;".equals(method.getReturnType());
    }

    private String inspectSpeedGuard(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null || implementation.getRegisterCount() < 6) {
            return "missing implementation";
        }
        int constructor = countCall(method, SPEED_STATE_CONSTRUCTOR);
        int stateFlow = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof FieldReference)) continue;
            FieldReference field = (FieldReference) reference;
            if (SPEED_CLASS.equals(field.getDefiningClass())
                    && "speedometerStateFlow".equals(field.getName())
                    && ROUTE_FLOW.equals(field.getType())) stateFlow++;
        }
        return constructor == 1 && stateFlow == 1
                ? "ok" : "speed guard mismatch constructor=" + constructor
                + ", stateFlow=" + stateFlow;
    }

    private String inspectClusterEtaGuardImpl(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return "missing implementation";
        int exclusion = countCall(method, CLUSTER_OEM_EXCLUSION);
        int stockProducer = countCall(method, CLUSTER_OEM_LIST_PRODUCER);
        int emptyProducer = countCall(method, CLUSTER_EMPTY_OEM_LIST);
        int addDestination = countCall(method, TRIP_ADD_DESTINATION);
        int tripBuild = countCall(method, TRIP_BUILD);
        int updateTrip = countCall(method, NAVIGATION_UPDATE_TRIP);
        if (exclusion != 1 || addDestination != 1 || tripBuild != 1 || updateTrip != 1) {
            return "cluster ETA guard mismatch exclusion=" + exclusion
                    + ", addDestination=" + addDestination
                    + ", tripBuild=" + tripBuild + ", updateTrip=" + updateTrip;
        }
        int branch = clusterEtaBranchIndex(method);
        if (branch < 0) {
            return clusterEtaLegacyNopIndex(method) >= 0
                    ? "legacy NOP patch" : "cluster ETA decision sequence missing";
        }
        if (stockProducer == 1 && emptyProducer == 0
                && clusterEtaListProducerIndex(method, CLUSTER_OEM_LIST_PRODUCER) >= 0) {
            return "stock";
        }
        if (stockProducer == 0 && emptyProducer == 1
                && clusterEtaListProducerIndex(method, CLUSTER_EMPTY_OEM_LIST) >= 0) {
            return "patched";
        }
        return "cluster ETA producer mismatch stock=" + stockProducer
                + ", empty=" + emptyProducer;
    }

    private int clusterEtaListProducerIndex(
            Method method, MethodReference producer) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return -1;
        List<? extends Instruction> instructions = toList(implementation);
        int producerIndex = uniqueCallIndex(instructions, producer);
        int exclusionIndex = uniqueCallIndex(instructions, CLUSTER_OEM_EXCLUSION);
        if (producerIndex < 0 || exclusionIndex < 0 || producerIndex >= exclusionIndex
                || producerIndex + 1 >= instructions.size()) return -1;
        Instruction call = instructions.get(producerIndex);
        Instruction result = instructions.get(producerIndex + 1);
        Instruction exclusion = instructions.get(exclusionIndex);
        if (!(call instanceof FiveRegisterInstruction)
                || !(result instanceof OneRegisterInstruction)
                || !(exclusion instanceof FiveRegisterInstruction)
                || result.getOpcode() != Opcode.MOVE_RESULT_OBJECT
                || call.getCodeUnits() != 3) return -1;
        FiveRegisterInstruction callRegisters = (FiveRegisterInstruction) call;
        FiveRegisterInstruction exclusionRegisters = (FiveRegisterInstruction) exclusion;
        boolean stock = sameMethod(producer, CLUSTER_OEM_LIST_PRODUCER);
        if (stock && (call.getOpcode() != Opcode.INVOKE_INTERFACE
                || callRegisters.getRegisterCount() != 1)) return -1;
        if (!stock && (call.getOpcode() != Opcode.INVOKE_STATIC
                || callRegisters.getRegisterCount() != 0)) return -1;
        return ((OneRegisterInstruction) result).getRegisterA()
                == exclusionRegisters.getRegisterD() ? producerIndex : -1;
    }

    private int clusterEtaBranchIndex(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return -1;
        List<? extends Instruction> instructions = toList(implementation);
        int[] addresses = instructionAddresses(instructions);
        int addDestination = uniqueCallIndex(instructions, TRIP_ADD_DESTINATION);
        int tripBuild = uniqueCallIndex(instructions, TRIP_BUILD);
        int exclusion = uniqueCallIndex(instructions, CLUSTER_OEM_EXCLUSION);
        if (exclusion < 0 || addDestination < 0 || tripBuild < 0
                || !(exclusion < addDestination && addDestination < tripBuild)) return -1;
        for (int index = exclusion + 2; index + 2 < addDestination; index++) {
            if (!isCall(instructions.get(index), BOOLEAN_VALUE)) continue;
            Instruction result = instructions.get(index + 1);
            Instruction branch = instructions.get(index + 2);
            if (result.getOpcode() != Opcode.MOVE_RESULT
                    || !(result instanceof OneRegisterInstruction)
                    || branch.getOpcode() != Opcode.IF_NEZ
                    || !(branch instanceof OneRegisterInstruction)
                    || !(branch instanceof OffsetInstruction)
                    || ((OneRegisterInstruction) result).getRegisterA()
                    != ((OneRegisterInstruction) branch).getRegisterA()) continue;
            int targetAddress = addresses[index + 2]
                    + ((OffsetInstruction) branch).getCodeOffset();
            if (targetAddress == addresses[tripBuild]) return index + 2;
        }
        return -1;
    }

    private int clusterEtaLegacyNopIndex(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return -1;
        List<? extends Instruction> instructions = toList(implementation);
        int addDestination = uniqueCallIndex(instructions, TRIP_ADD_DESTINATION);
        int exclusion = uniqueCallIndex(instructions, CLUSTER_OEM_EXCLUSION);
        if (exclusion < 0 || addDestination < 0 || exclusion >= addDestination) return -1;
        for (int index = exclusion + 2; index + 2 < addDestination; index++) {
            if (!isCall(instructions.get(index), BOOLEAN_VALUE)) continue;
            Instruction result = instructions.get(index + 1);
            if (result.getOpcode() == Opcode.MOVE_RESULT
                    && instructions.get(index + 2).getOpcode() == Opcode.NOP) {
                return index + 2;
            }
        }
        return -1;
    }

    private boolean matchesAlertTarget(Method method) {
        return ALERT_SESSION_CLASS.equals(method.getDefiningClass())
                && "onCreateScreen".equals(method.getName())
                && method.getParameterTypes().size() == 1
                && "Landroid/content/Intent;".equals(
                method.getParameterTypes().get(0).toString())
                && "Landroidx/car/app/Screen;".equals(method.getReturnType());
    }

    private boolean matchesAlertHelper(Method method) {
        return ALERT_SESSION_CLASS.equals(method.getDefiningClass())
                && "c".equals(method.getName())
                && method.getParameterTypes().isEmpty()
                && "V".equals(method.getReturnType());
    }

    private boolean matchesAlertOnceTarget(Method method) {
        return matchesAlertOnceMethod(method, "k");
    }

    private boolean matchesAlertOnceMethod(Method method, String name) {
        return ALERT_ONCE_CLASS.equals(method.getDefiningClass())
                && name.equals(method.getName())
                && method.getParameterTypes().isEmpty()
                && "V".equals(method.getReturnType());
    }

    private AlertOnceInspection inspectAlertOnceMethod(Method method) {
        AlertOnceInspection result = new AlertOnceInspection();
        if (!matchesAlertOnceTarget(method)) {
            result.reason = "alert-once target method missing";
            return result;
        }
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) {
            result.reason = "alert-once target has no implementation";
            return result;
        }
        result.stateReadCount = countFieldOpcode(method, ALERT_ONCE_STATE, Opcode.IGET_OBJECT);
        result.modeReadCount = countCall(method, ALERT_ONCE_MODE);
        result.stateUpdateCount = countCall(method, ALERT_ONCE_UPDATE);
        result.nativeStartCount = countCall(method, ALERT_ONCE_NATIVE_START);
        result.guardCount = countAlertOnceGuards(method);
        result.stock = implementation.getRegisterCount() == 6
                && result.stateReadCount == 2
                && result.modeReadCount == 0
                && result.stateUpdateCount == 1
                && result.nativeStartCount == 1
                && result.guardCount == 0
                && hasAlertOnceStockNullUpdate(method);
        result.patched = implementation.getRegisterCount() == 6
                && result.stateReadCount == 3
                && result.modeReadCount == 1
                && result.stateUpdateCount == 1
                && result.nativeStartCount == 1
                && result.guardCount == 2
                && hasAlertOnceModeSnapshot(method)
                && hasAlertOncePatchedUpdate(method);
        result.reason = result.stock ? "stock alert-once target"
                : result.patched ? "patched alert-once target"
                : "alert-once structural guard mismatch: stateRead=" + result.stateReadCount
                + ", modeRead=" + result.modeReadCount + ", update=" + result.stateUpdateCount
                + ", nativeStart=" + result.nativeStartCount + ", guards=" + result.guardCount;
        return result;
    }

    private int countAlertOnceGuards(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return 0;
        List<? extends Instruction> instructions = toList(implementation);
        int nativeStart = uniqueCallIndex(instructions, ALERT_ONCE_NATIVE_START);
        if (nativeStart < 6 || nativeStart + 1 >= instructions.size()) return 0;
        if (!isAlertOnceModeField(instructions.get(nativeStart - 6), "b")
                || !isAlertOnceModeField(instructions.get(nativeStart - 4), "c")
                || !isAlertOnceFieldLoad(
                instructions.get(nativeStart - 2), ALERT_ONCE_LISTENER, 0, 5)
                || !isAlertOnceFieldLoad(
                instructions.get(nativeStart - 1), ALERT_ONCE_VALUE, 5, 5)) {
            return 0;
        }
        Instruction first = instructions.get(nativeStart - 5);
        Instruction second = instructions.get(nativeStart - 3);
        if (!isAlertOnceGuard(first) || !isAlertOnceGuard(second)) return 0;
        List<Integer> addresses = new ArrayList<>();
        int address = 0;
        for (Instruction instruction : instructions) {
            addresses.add(address);
            address += instruction.getCodeUnits();
        }
        int firstTarget = addresses.get(nativeStart - 5)
                + ((OffsetInstruction) first).getCodeOffset();
        int secondTarget = addresses.get(nativeStart - 3)
                + ((OffsetInstruction) second).getCodeOffset();
        int skipTarget = addresses.get(nativeStart + 1);
        return firstTarget == secondTarget && firstTarget == skipTarget ? 2 : 0;
    }

    private boolean isAlertOnceGuard(Instruction instruction) {
        return instruction.getOpcode() == Opcode.IF_EQ
                && instruction instanceof org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
                && ((org.jf.dexlib2.iface.instruction.TwoRegisterInstruction) instruction)
                .getRegisterA() == 4
                && ((org.jf.dexlib2.iface.instruction.TwoRegisterInstruction) instruction)
                .getRegisterB() == 0
                && instruction instanceof OffsetInstruction;
    }

    private boolean isAlertOnceModeField(Instruction instruction, String name) {
        if (instruction.getOpcode() != Opcode.SGET_OBJECT
                || !(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof FieldReference)) return false;
        FieldReference field = (FieldReference) reference;
        return ALERT_ONCE_MODE_CLASS.equals(field.getDefiningClass())
                && name.equals(field.getName())
                && ALERT_ONCE_MODE_CLASS.equals(field.getType());
    }

    private boolean isAlertOnceFieldLoad(
            Instruction instruction, FieldReference expected,
            int destinationRegister, int objectRegister) {
        if (instruction.getOpcode() != Opcode.IGET_OBJECT
                || !(instruction instanceof org.jf.dexlib2.iface.instruction.TwoRegisterInstruction)
                || !(instruction instanceof ReferenceInstruction)) return false;
        org.jf.dexlib2.iface.instruction.TwoRegisterInstruction registers =
                (org.jf.dexlib2.iface.instruction.TwoRegisterInstruction) instruction;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        return registers.getRegisterA() == destinationRegister
                && registers.getRegisterB() == objectRegister
                && reference instanceof FieldReference
                && sameField((FieldReference) reference, expected);
    }

    private boolean hasAlertOnceStockNullUpdate(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return false;
        List<? extends Instruction> instructions = toList(implementation);
        int update = uniqueCallIndex(instructions, ALERT_ONCE_UPDATE);
        return update >= 1
                && hasAlertOnceUpdateRegisters(instructions.get(update), 4)
                && isConst4(instructions.get(update - 1), 4, 0)
                && hasAlertOnceNativeReceiver(instructions);
    }

    private boolean hasAlertOncePatchedUpdate(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return false;
        List<? extends Instruction> instructions = toList(implementation);
        int update = uniqueCallIndex(instructions, ALERT_ONCE_UPDATE);
        return update >= 0
                && hasAlertOnceUpdateRegisters(instructions.get(update), 2)
                && countConst4(instructions, 4, 0) == 0;
    }

    private boolean hasAlertOnceModeSnapshot(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return false;
        List<? extends Instruction> instructions = toList(implementation);
        if (instructions.size() < 3
                || !isAlertOnceFieldLoad(instructions.get(0), ALERT_ONCE_STATE, 4, 5)
                || !isCall(instructions.get(1), ALERT_ONCE_MODE)
                || !(instructions.get(1) instanceof FiveRegisterInstruction)
                || ((FiveRegisterInstruction) instructions.get(1)).getRegisterCount() != 1
                || ((FiveRegisterInstruction) instructions.get(1)).getRegisterC() != 4) {
            return false;
        }
        Instruction result = instructions.get(2);
        return result.getOpcode() == Opcode.MOVE_RESULT_OBJECT
                && result instanceof OneRegisterInstruction
                && ((OneRegisterInstruction) result).getRegisterA() == 4;
    }

    private boolean hasAlertOnceNativeReceiver(
            List<? extends Instruction> instructions) {
        int nativeStart = uniqueCallIndex(instructions, ALERT_ONCE_NATIVE_START);
        return nativeStart >= 2
                && isAlertOnceFieldLoad(
                instructions.get(nativeStart - 2), ALERT_ONCE_LISTENER, 0, 5)
                && isAlertOnceFieldLoad(
                instructions.get(nativeStart - 1), ALERT_ONCE_VALUE, 5, 5);
    }

    private boolean hasAlertOnceUpdateRegisters(
            Instruction instruction, int nullableArgumentRegister) {
        if (!(instruction instanceof FiveRegisterInstruction)) return false;
        FiveRegisterInstruction call = (FiveRegisterInstruction) instruction;
        return call.getRegisterCount() == 5
                && call.getRegisterC() == 1
                && call.getRegisterD() == nullableArgumentRegister
                && call.getRegisterE() == 0
                && call.getRegisterF() == 2
                && call.getRegisterG() == 3;
    }

    private boolean isConst4(Instruction instruction, int register, int value) {
        return instruction.getOpcode() == Opcode.CONST_4
                && instruction instanceof OneRegisterInstruction
                && ((OneRegisterInstruction) instruction).getRegisterA() == register
                && instruction instanceof org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
                && ((org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction) instruction)
                .getNarrowLiteral() == value;
    }

    private int countConst4(List<? extends Instruction> instructions, int register, int value) {
        int count = 0;
        for (Instruction instruction : instructions) {
            if (!isConst4(instruction, register, value)) continue;
            count++;
        }
        return count;
    }

    private String inspectRouteGuard(Method method) {
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
        int v2Calls = countCall(method, BRIDGE_EMIT);
        int v1Calls = countCall(method, BRIDGE_EMIT_V1);
        int legacyCalls = countCall(method, LEGACY_BRIDGE_EMIT);
        boolean bridgeCallValid = v2Calls + v1Calls + legacyCalls == 0
                || v2Calls == 1 && v1Calls == 0 && legacyCalls == 0
                && countExactRouteCall(method, BRIDGE_EMIT, true) == 1
                || v2Calls == 0 && v1Calls == 1 && legacyCalls == 0
                && countExactRouteCall(method, BRIDGE_EMIT_V1, false) == 1
                || v2Calls == 0 && v1Calls == 0 && legacyCalls == 1
                && countExactRouteCall(method, LEGACY_BRIDGE_EMIT, false) == 1;
        int anchor = routeAnchorIndex(method, true);
        int legacyAnchor = legacyRouteAnchorIndex(method);
        return (anchor >= 0 || legacyAnchor >= 0)
                && activeEnumCount == 1 && inactiveEnumCount == 1 && bridgeCallValid
                ? "ok"
                : "route guard mismatch anchor=" + (anchor >= 0 ? 1 : 0)
                + ", legacyAnchor=" + (legacyAnchor >= 0 ? 1 : 0)
                + ", active=" + activeEnumCount + ", inactive=" + inactiveEnumCount
                + ", v2=" + v2Calls + ", v1=" + v1Calls + ", legacy=" + legacyCalls;
    }

    private int legacyRouteAnchorIndex(Method method) {
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

    private int routeAnchorIndex(Method method, boolean allowBridge) {
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
                    || isCall(instructions.get(next), BRIDGE_EMIT_V1)
                    || isCall(instructions.get(next), LEGACY_BRIDGE_EMIT))) next++;
            if (next >= instructions.size() || !isRouteStateField(instructions.get(next))) {
                continue;
            }
            if (match >= 0) return -1;
            match = index;
        }
        return match;
    }

    private int countExactRouteCall(
            Method method, MethodReference expected, boolean includesReason) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null || implementation.getRegisterCount() < 3) return 0;
        int stateRegister = implementation.getRegisterCount() - 2;
        int reasonRegister = implementation.getRegisterCount() - 1;
        int count = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (!isCall(instruction, expected) || !(instruction instanceof FiveRegisterInstruction)) {
                continue;
            }
            FiveRegisterInstruction registers = (FiveRegisterInstruction) instruction;
            if (registers.getRegisterCount() == (includesReason ? 2 : 1)
                    && registers.getRegisterC() == stateRegister
                    && (!includesReason || registers.getRegisterD() == reasonRegister)) {
                count++;
            }
        }
        return count;
    }

    private boolean isRouteFlowUpdate(Instruction instruction) {
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

    private boolean isRouteStateField(Instruction instruction) {
        if (instruction.getOpcode() != Opcode.IGET_OBJECT
                || !(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof FieldReference)) return false;
        FieldReference field = (FieldReference) reference;
        return ROUTE_CLASS.equals(field.getDefiningClass())
                && (modern ? "j" : "i").equals(field.getName())
                && ROUTE_FLOW.equals(field.getType());
    }

    private boolean isCall(Instruction instruction, MethodReference expected) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        return reference instanceof MethodReference
                && sameMethod((MethodReference) reference, expected);
    }

    private int countCall(Method method, MethodReference expected) {
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

    private int countAdjacentCalls(
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

    private int countFieldOpcode(
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

    private int countTypeOpcode(Method method, String expectedType, Opcode opcode) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return 0;
        int count = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (instruction.getOpcode() != opcode || !(instruction instanceof ReferenceInstruction)) {
                continue;
            }
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof TypeReference
                    && expectedType.equals(((TypeReference) reference).getType())) count++;
        }
        return count;
    }

    private int countLaneCast(Method method) {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) return 0;
        boolean checked = false;
        int count = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (instruction.getOpcode() == Opcode.INSTANCE_OF && instruction instanceof ReferenceInstruction
                    && ((ReferenceInstruction) instruction).getReference() instanceof TypeReference
                    && NAVIGATION_LANE_LIST.equals(((TypeReference) ((ReferenceInstruction) instruction).getReference()).getType())) {
                checked = true;
            } else if (instruction.getOpcode() == Opcode.CHECK_CAST && instruction instanceof ReferenceInstruction
                    && ((ReferenceInstruction) instruction).getReference() instanceof TypeReference
                    && NAVIGATION_LANE_LIST.equals(((TypeReference) ((ReferenceInstruction) instruction).getReference()).getType())) {
                if (checked) count++;
            }
        }
        return count;
    }

    private boolean literal(Instruction instruction, int expected) {
        return instruction instanceof NarrowLiteralInstruction
                && ((NarrowLiteralInstruction) instruction).getNarrowLiteral() == expected;
    }

    private int countString(Method method, String expected) {
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

    private boolean sameMethod(MethodReference first, MethodReference second) {
        return first.getDefiningClass().equals(second.getDefiningClass())
                && first.getName().equals(second.getName())
                && first.getParameterTypes().equals(second.getParameterTypes())
                && first.getReturnType().equals(second.getReturnType());
    }

    private boolean sameMethod(Method first, MethodReference second) {
        return first != null && sameMethod((MethodReference) first, second);
    }

    private boolean sameField(FieldReference first, FieldReference second) {
        return first.getDefiningClass().equals(second.getDefiningClass())
                && first.getName().equals(second.getName())
                && first.getType().equals(second.getType());
    }

    private boolean sameField(Field first, FieldReference second) {
        return first != null && first.getDefiningClass().equals(second.getDefiningClass())
                && first.getName().equals(second.getName())
                && first.getType().equals(second.getType());
    }

    private List<ImmutableMethodParameter> parameters(List<String> types) {
        List<ImmutableMethodParameter> result = new ArrayList<>();
        for (String type : types) {
            result.add(new ImmutableMethodParameter(type, Collections.emptySet(), null));
        }
        return result;
    }

    private List<? extends Instruction> toList(MethodImplementation implementation) {
        List<Instruction> result = new ArrayList<>();
        implementation.getInstructions().forEach(result::add);
        return result;
    }

    private int[] instructionAddresses(List<? extends Instruction> instructions) {
        int[] addresses = new int[instructions.size()];
        int address = 0;
        for (int index = 0; index < instructions.size(); index++) {
            addresses[index] = address;
            address += instructions.get(index).getCodeUnits();
        }
        return addresses;
    }

    private int uniqueCallIndex(
            List<? extends Instruction> instructions, MethodReference expected) {
        int match = -1;
        for (int index = 0; index < instructions.size(); index++) {
            if (!isCall(instructions.get(index), expected)) continue;
            if (match >= 0) return -1;
            match = index;
        }
        return match;
    }

    private WazeInspection inspectWazeMethod(Method method) {
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

    private Method rewriteWazeMethod(Method method, AtomicInteger matches) {
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

    private boolean isControlFlow(Opcode opcode) {
        String name = opcode.name();
        return name.startsWith("IF_") || name.startsWith("GOTO")
                || opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH;
    }

    private boolean isTerminal(Opcode opcode) {
        String name = opcode.name();
        return name.startsWith("RETURN") || opcode == Opcode.THROW;
    }

    private boolean isField(Instruction instruction, String owner, String name) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof FieldReference)) return false;
        FieldReference field = (FieldReference) reference;
        return owner.equals(field.getDefiningClass()) && name.equals(field.getName());
    }

    private boolean isType(Instruction instruction, String type) {
        if (instruction.getOpcode() != Opcode.NEW_INSTANCE
                || !(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        return reference instanceof TypeReference && type.equals(((TypeReference) reference).getType());
    }

    private boolean isMethod(
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

    static final class LaneInspection {
        int frameClassCount;
        int frameFieldCount;
        int frameStockCtorCount;
        int framePatchedCtorCount;
        int frameGetterCount;
        int frameEqualsFieldCount;
        int frameHashFieldCount;
        int producerTargetCount;
        int producerSourceCount;
        int producerFieldCount;
        int producerEmptyCount;
        int producerTypeCount;
        int producerCastCount;
        int producerStockCtorCount;
        int producerPatchedCtorCount;
        int adapterTargetCount;
        int adapterSentinelCount;
        int adapterHelperCallCount;
        int adapterHelperCount;
        int adapterMapperCount;
        String classification = UNSUPPORTED;
        String reason = "not found";

        boolean stockShape() {
            return frameClassCount == 1 && frameFieldCount == 0
                    && frameStockCtorCount == 1 && framePatchedCtorCount == 0
                    && frameGetterCount == 0
                    && frameEqualsFieldCount == 0 && frameHashFieldCount == 0
                    && producerTargetCount == 1 && producerSourceCount == 1
                    && producerFieldCount == 1 && producerEmptyCount == 0
                    && producerTypeCount == 0 && producerCastCount == 0
                    && producerStockCtorCount == 1 && producerPatchedCtorCount == 0
                    && adapterTargetCount == 1 && adapterSentinelCount == 1
                    && adapterHelperCallCount == 0 && adapterHelperCount == 0
                    && adapterMapperCount == 0;
        }

        boolean patchedShape() {
            return frameClassCount == 1 && frameFieldCount == 1
                    && frameStockCtorCount == 0 && framePatchedCtorCount == 1
                    && frameGetterCount == 1
                    && frameEqualsFieldCount == 2 && frameHashFieldCount == 1
                    && producerTargetCount == 1 && producerSourceCount == 2
                    && producerFieldCount == 2 && producerEmptyCount == 1
                    && producerTypeCount == 1 && producerCastCount == 1
                    && producerStockCtorCount == 0 && producerPatchedCtorCount == 1
                    && adapterTargetCount == 1 && adapterSentinelCount == 0
                    && adapterHelperCallCount == 1 && adapterHelperCount == 1
                    && adapterMapperCount == 1;
        }

        boolean publishedShape() {
            return frameClassCount == 1 && frameFieldCount == 1
                    && frameStockCtorCount == 0 && framePatchedCtorCount == 1
                    && frameGetterCount == 1
                    && frameEqualsFieldCount == 2 && frameHashFieldCount == 1
                    && producerTargetCount == 1 && producerSourceCount == 1
                    && producerFieldCount == 1 && producerEmptyCount == 1
                    && producerTypeCount == 0 && producerCastCount == 0
                    && producerStockCtorCount == 0 && producerPatchedCtorCount == 1
                    && adapterTargetCount == 1 && adapterSentinelCount == 0
                    && adapterHelperCallCount == 1 && adapterHelperCount == 1
                    && adapterMapperCount == 1;
        }

        boolean stockTargets() {
            return PATCHABLE_STOCK.equals(classification);
        }

        boolean patchedTargets() {
            return ALREADY_PATCHED.equals(classification);
        }

        boolean patchable() {
            return stockTargets();
        }

        String summary() {
            return "frame=" + frameClassCount + "/" + frameFieldCount
                    + "/" + frameStockCtorCount + "/" + framePatchedCtorCount
                    + "/" + frameGetterCount + "/" + frameEqualsFieldCount
                    + "/" + frameHashFieldCount
                    + ", producer=" + producerTargetCount + "/" + producerSourceCount
                    + "/" + producerFieldCount + "/" + producerEmptyCount
                    + "/" + producerTypeCount + "/" + producerCastCount
                    + "/" + producerStockCtorCount + "/" + producerPatchedCtorCount
                    + ", adapter=" + adapterTargetCount + "/" + adapterSentinelCount
                    + "/" + adapterHelperCallCount + "/" + adapterHelperCount
                    + "/" + adapterMapperCount + ", classification=" + classification
                    + ", reason=" + reason;
        }
    }

    static final class LifecycleInspection {
        int geometryTargetCount;
        int geometryHookCount;
        int geometryPayloadCount;
        int geometryPayloadClassCount;
        int geometryPayloadRevision;
        int routeExportClassCount;
        int routeDataClassCount;
        int geometryRecoveryClassCount;
        boolean geometryInvalid;
        int applicationTargetCount;
        int applicationHookCount;
        int legacyApplicationHookCount;
        String applicationGuard = "not found";
        int routeTargetCount;
        int routeHookCount;
        int v1RouteHookCount;
        int legacyRouteHookCount;
        String routeGuard = "not found";
        int speedTargetCount;
        int speedHookCount;
        int legacySpeedHookCount;
        String speedGuard = "not found";
        int clusterEtaTargetCount;
        int clusterEtaPatchCount;
        String clusterEtaGuard = "not found";
        int bridgeClassCount;
        int legacyBridgeClassCount;

        boolean clusterEtaStock() {
            return clusterEtaTargetCount == 1 && clusterEtaPatchCount == 0
                    && "stock".equals(clusterEtaGuard);
        }

        boolean clusterEtaPatched() {
            return clusterEtaTargetCount == 1 && clusterEtaPatchCount == 1
                    && "patched".equals(clusterEtaGuard);
        }

        boolean stockTargets() {
            return applicationTargetCount == 1 && applicationHookCount == 0
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 0
                    && v1RouteHookCount == 0 && legacyRouteHookCount == 0
                    && "ok".equals(routeGuard)
                    && speedTargetCount == 1 && speedHookCount == 0
                    && legacySpeedHookCount == 0
                    && "ok".equals(speedGuard)
                    && bridgeClassCount == 0;
        }

        boolean patchedTargets() {
            return applicationTargetCount == 1 && applicationHookCount == 1
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 1
                    && v1RouteHookCount == 0 && legacyRouteHookCount == 0
                    && "ok".equals(routeGuard)
                    && speedTargetCount == 1 && speedHookCount == 1
                    && legacySpeedHookCount == 0
                    && "ok".equals(speedGuard)
                    && bridgeClassCount == 1;
        }

        boolean v1PatchedTargets() {
            return applicationTargetCount == 1 && applicationHookCount == 1
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 0
                    && v1RouteHookCount == 1 && legacyRouteHookCount == 0
                    && "ok".equals(routeGuard)
                    && speedTargetCount == 1 && speedHookCount == 1
                    && legacySpeedHookCount == 0
                    && "ok".equals(speedGuard)
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
        int tripPublisherCallCount;
        int guardReadCount;
        int guardWriteCount;
        int logMarkerCount;
        int alertOnceClassCount;
        int alertOnceTargetMethodCount;
        int alertOnceStateReadCount;
        int alertOnceModeReadCount;
        int alertOnceStateUpdateCount;
        int alertOnceNativeStartCount;
        int alertOnceGuardCount;
        int alertOnceUnchangedMethodCount;
        int alertOnceStockShapeCount;
        int alertOncePatchedShapeCount;

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
                    && collectorCallCount == 1 && tripPublisherCallCount == 1
                    && guardReadCount == 1
                    && guardWriteCount == 1 && logMarkerCount == 1;
        }

        boolean alertOnceStockTargets() {
            return alertOnceClassCount == 1 && alertOnceTargetMethodCount == 1
                    && alertOnceUnchangedMethodCount == 2
                    && alertOnceStockShapeCount == 1
                    && alertOnceStateReadCount == 2 && alertOnceModeReadCount == 0
                    && alertOnceStateUpdateCount == 1 && alertOnceNativeStartCount == 1
                    && alertOnceGuardCount == 0;
        }

        boolean alertOncePatchedTargets() {
            return alertOnceClassCount == 1 && alertOnceTargetMethodCount == 1
                    && alertOnceUnchangedMethodCount == 2
                    && alertOncePatchedShapeCount == 1
                    && alertOnceStateReadCount == 3 && alertOnceModeReadCount == 1
                    && alertOnceStateUpdateCount == 1 && alertOnceNativeStartCount == 1
                    && alertOnceGuardCount == 2;
        }

        boolean patchableComponents() {
            boolean uiKnown = classCount == 0 || stockTargets() || patchedTargets();
            boolean onceKnown = alertOnceClassCount == 0
                    || alertOnceStockTargets() || alertOncePatchedTargets();
            return uiKnown && onceKnown
                    && (stockTargets() || alertOnceStockTargets());
        }

        boolean patchedComponents(AlertInspection input, boolean patchUi,
                boolean patchAlertOnce) {
            boolean uiDone = classCount == 0
                    || (input.stockTargets() ? (!patchUi || patchedTargets()) : patchedTargets());
            boolean onceDone = alertOnceClassCount == 0
                    || (input.alertOnceStockTargets()
                    ? (!patchAlertOnce || alertOncePatchedTargets())
                    : alertOncePatchedTargets());
            return uiDone && onceDone;
        }

        String summary() {
            return "class=" + classCount + ", anchorField=" + fieldAnchorCount
                    + ", field=" + guardFieldCount
                    + ", target=" + targetMethodCount + ", anchor=" + anchorCount
                    + ", hook=" + hookCallCount + ", adjacent=" + hookAfterAnchorCount
                    + ", helper=" + helperMethodCount + ", producer=" + producerCallCount
                    + ", collector=" + collectorCallCount
                    + ", trip=" + tripPublisherCallCount
                    + ", guardRead=" + guardReadCount
                    + ", guardWrite=" + guardWriteCount + ", marker=" + logMarkerCount
                    + ", alertOnceClass=" + alertOnceClassCount
                    + ", alertOnceTarget=" + alertOnceTargetMethodCount
                    + ", alertOnceStateRead=" + alertOnceStateReadCount
                    + ", alertOnceModeRead=" + alertOnceModeReadCount
                    + ", alertOnceUpdate=" + alertOnceStateUpdateCount
                    + ", alertOnceNativeStart=" + alertOnceNativeStartCount
                    + ", alertOnceGuards=" + alertOnceGuardCount;
        }
    }

    static final class AlertOnceInspection {
        int stateReadCount;
        int modeReadCount;
        int stateUpdateCount;
        int nativeStartCount;
        int guardCount;
        boolean stock;
        boolean patched;
        String reason = "not found";

    }
}
