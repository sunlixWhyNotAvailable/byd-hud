package com.bydhud.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Shell-UID implementation of the fixed BYD Instrument navigation contract. */
final class InstrumentNavigationProxyService extends IInstrumentNavigationProxy.Stub {
    private static final String TAG = "BydHudInstrumentProxy";
    private static final int FID_NAV_STATUS = 1_138_753_594;
    private static final int FID_SIMPLE_ICON = 1_139_806_224;
    private static final int FID_DUAL_ICON = 1_139_806_256;
    private static final int FID_DISTANCE = 1_139_806_232;
    private static final int FID_ROAD = 1_140_461_576;
    private static final int FID_LANE_COUNT = 427_827_416;
    private static final int FID_LANE_GUIDE_BASE = 427_827_288;
    private static final int FID_LANE_STATE_BASE = 427_827_296;
    private static final int FID_LANE_VISIBLE_BASE = 427_827_300;
    private static final int SETTING_LANE_DISTANCE = 1_285_554_184;
    private static final int SETTING_LANE_COUNT = 1_285_554_200;
    private static final int[] SETTING_LANE_STATES = {
            1_285_554_208, 1_285_554_212, 1_285_554_216, 1_285_554_220,
            1_285_554_224, 1_285_554_228, 1_285_554_232, 1_285_554_236,
            1_285_554_260, 1_285_554_264, 1_285_554_268, 1_285_554_272
    };
    private static final String INSTRUMENT_CLASS =
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";
    private static final String SETTING_CLASS =
            "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    private static final String EVENT_VALUE_CLASS =
            "android.hardware.bydauto.BYDAutoEventValue";
    private static final String INSTRUMENT_COMMON_PERMISSION =
            "android.permission.BYDAUTO_INSTRUMENT_COMMON";
    private static final String INSTRUMENT_GET_PERMISSION =
            "android.permission.BYDAUTO_INSTRUMENT_GET";
    private static final String INSTRUMENT_SET_PERMISSION =
            "android.permission.BYDAUTO_INSTRUMENT_SET";
    private static final String SETTING_COMMON_PERMISSION =
            "android.permission.BYDAUTO_SETTING_COMMON";
    private static final String SETTING_GET_PERMISSION =
            "android.permission.BYDAUTO_SETTING_GET";
    private static final String SETTING_SET_PERMISSION =
            "android.permission.BYDAUTO_SETTING_SET";
    private static final int VENDOR_READ_ERROR = -2_147_482_648;
    private static final int EVENT_VALUE_UNSET = -999_999_999;
    private final long generation;
    private final String nonce;
    private final int allowedUid;
    private final String launchToken;
    private final int appVersionCode;
    private final Context systemContext;
    private final Object operationLock = new Object();
    private volatile InstrumentApi instrument;
    private volatile IInstrumentNavigationClient client;
    private volatile boolean connected;
    private volatile int activeCapabilities;

    InstrumentNavigationProxyService(
            Context systemContext, long generation, String nonce, int allowedUid,
            String launchToken, int appVersionCode) {
        this.generation = generation;
        this.nonce = nonce;
        this.allowedUid = allowedUid;
        this.launchToken = launchToken;
        this.appVersionCode = appVersionCode;
        this.systemContext = systemContext;
    }

    @Override
    public void connect(long requestGeneration, String requestNonce,
            IInstrumentNavigationClient requestClient) {
        enforceCaller();
        if (requestClient == null) {
            stop("missing client");
            return;
        }
        Bundle result;
        if (requestGeneration != generation || !nonce.equals(requestNonce)) {
            result = connectionResult(false, "handoff rejected");
        } else {
            try {
                requestClient.asBinder().linkToDeath(this::onClientDied, 0);
                InstrumentApi current = instrument();
                int capabilities = InstrumentProxyContract.CAP_SYSTEM_CONTEXT;
                Readiness fidReadiness = current == null
                        ? new Readiness(false, "Instrument API unavailable")
                        : current.probeFidReadiness();
                if (fidReadiness.ready) {
                    capabilities |= InstrumentProxyContract.CAP_DIRECT_FID;
                }
                if (current != null && current.sdkCapable()) {
                    capabilities |= InstrumentProxyContract.CAP_INSTRUMENT_SDK;
                }
                if (current != null && current.laneCapable()) {
                    capabilities |= InstrumentProxyContract.CAP_INSTRUMENT_LANES;
                }
                activeCapabilities = capabilities;
                boolean ready = hasNavigationCapability(capabilities);
                if (ready) {
                    client = requestClient;
                    connected = true;
                }
                result = connectionResult(ready, ready ? ""
                        : "Instrument capabilities unavailable: " + fidReadiness.detail);
            } catch (RemoteException error) {
                result = connectionResult(
                        false, "client already dead");
            }
        }
        long identity = Binder.clearCallingIdentity();
        try {
            try {
                requestClient.onProxyConnected(generation, result);
            } catch (RemoteException ignored) {
                connected = false;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (!InstrumentProxyContract.isReady(result) || !connected) {
            stop("handoff rejected");
        }
    }

    private Bundle connectionResult(boolean ready, String error) {
        return InstrumentProxyContract.connectionResult(
                ready, error, generation, nonce, Process.myPid(), Process.myUid(),
                appVersionCode, launchToken, currentProcessStartTimeTicks(),
                activeCapabilities);
    }

    @Override
    public void ping(long requestGeneration, long token) {
        enforceSession(requestGeneration);
        IInstrumentNavigationClient current = client;
        if (current != null) {
            long identity = Binder.clearCallingIdentity();
            try {
                try {
                    current.onProxyPong(generation, token);
                } catch (RemoteException error) {
                    stop("ping callback failed");
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public Bundle sendNavigationStatus(long requestGeneration, int status) {
        enforceSession(requestGeneration);
        if (!InstrumentProxyContract.validStatus(status)) {
            throw new IllegalArgumentException("invalid navigation status");
        }
        synchronized (operationLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                List<InstrumentProxyContract.Operation> operations = new ArrayList<>(2);
                InstrumentApi current = instrument();
                boolean fidSucceeded = false;
                boolean sdkSucceeded = false;
                if (hasCapability(InstrumentProxyContract.CAP_DIRECT_FID)) {
                    InstrumentProxyContract.Operation operation =
                            setInt(FID_NAV_STATUS, status);
                    operations.add(operation);
                    fidSucceeded = succeeded(operation);
                }
                if (hasCapability(InstrumentProxyContract.CAP_INSTRUMENT_SDK)) {
                    InstrumentProxyContract.Operation operation = invoke(
                            "instrument_sdk:sendAutoNaviStatus",
                            current == null ? null : current.status, status);
                    operations.add(operation);
                    sdkSucceeded = succeeded(operation);
                }
                return finishOperations(operations, fidSucceeded || sdkSucceeded);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public Bundle sendGuidance(long requestGeneration, int icon,
            int distanceMeters, String road,
            int[] laneDirections, int[] laneRecommendations) {
        enforceSession(requestGeneration);
        String roadText = road == null ? "" : road;
        if (!InstrumentProxyContract.validGuidance(
                icon, distanceMeters, roadText, laneDirections, laneRecommendations)) {
            throw new IllegalArgumentException("invalid guidance frame");
        }
        int[] directions = laneDirections.clone();
        int[] recommendations = laneRecommendations.clone();
        synchronized (operationLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                List<InstrumentProxyContract.Operation> operations = new ArrayList<>(21);
                InstrumentApi current = instrument();
                boolean fidSucceeded = false;
                boolean sdkSucceeded = false;
                if (hasCapability(InstrumentProxyContract.CAP_DIRECT_FID)) {
                    int first = operations.size();
                    operations.add(setInt(FID_SIMPLE_ICON, icon));
                    operations.add(setInt(FID_DUAL_ICON, icon));
                    operations.add(setInt(FID_DISTANCE, distanceMeters));
                    operations.add(setBytes(FID_ROAD,
                            roadText.getBytes(StandardCharsets.UTF_16LE)));
                    fidSucceeded = succeeded(operations, first, 4);
                }
                if (hasCapability(InstrumentProxyContract.CAP_INSTRUMENT_SDK)) {
                    int first = operations.size();
                    operations.add(invoke("instrument_sdk:sendSimpleGuidanceInfo",
                            current == null ? null : current.simple, icon, distanceMeters));
                    operations.add(invoke("instrument_sdk:sendNextPathName",
                            current == null ? null : current.next, roadText));
                    sdkSucceeded = succeeded(operations, first, 2);
                }
                if (hasCapability(InstrumentProxyContract.CAP_INSTRUMENT_LANES)) {
                    addLaneOperations(operations, directions, recommendations,
                            directions.length == 0 ? -1 : Math.max(0, distanceMeters));
                }
                return finishOperations(operations, fidSucceeded || sdkSucceeded);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void shutdown(long requestGeneration) {
        enforceSession(requestGeneration);
        stop("app shutdown");
    }

    private void enforceSession(long requestGeneration) {
        enforceCaller();
        if (!connected || requestGeneration != generation) {
            throw new SecurityException("inactive Instrument proxy session");
        }
    }

    private void enforceCaller() {
        if (Binder.getCallingUid() != allowedUid) {
            throw new SecurityException("unexpected Instrument proxy caller");
        }
    }

    private InstrumentProxyContract.Operation setInt(int featureId, int value) {
        return setFeature(featureId, value, null);
    }

    private InstrumentProxyContract.Operation setBytes(int featureId, byte[] bytes) {
        return setFeature(featureId, 0, bytes == null ? new byte[0] : bytes);
    }

    private void addLaneOperations(List<InstrumentProxyContract.Operation> operations,
            int[] directions, int[] recommendations, int distanceMeters) {
        InstrumentApi current = instrument();
        if (current != null && current.settingWriter != null) {
            operations.add(setSettingInt(SETTING_LANE_COUNT, directions.length));
            for (int index = 0; index < SETTING_LANE_STATES.length; index++) {
                operations.add(setSettingInt(SETTING_LANE_STATES[index],
                        index < directions.length ? directions[index] : 255));
            }
            operations.add(setSettingInt(SETTING_LANE_DISTANCE, distanceMeters));
        }

        int[] featureIds = new int[25];
        int[] values = new int[25];
        featureIds[0] = FID_LANE_COUNT;
        values[0] = directions.length;
        for (int index = 0; index < HudLaneModel.MAX_LANES; index++) {
            int offset = index * 16;
            boolean present = index < directions.length;
            int recommendation = present ? recommendations[index] : 255;
            featureIds[index + 1] = FID_LANE_GUIDE_BASE + offset;
            values[index + 1] = present
                    ? laneGuideValue(directions[index], recommendation) : -1;
            featureIds[index + 9] = FID_LANE_STATE_BASE + offset;
            values[index + 9] = present
                    ? recommendation == 255 ? 5 : 0 : 14;
            featureIds[index + 17] = FID_LANE_VISIBLE_BASE + offset;
            values[index + 17] = present ? 1 : -1;
        }
        operations.add(setInstrumentLaneBatch(featureIds, values));
    }

    private InstrumentProxyContract.Operation setSettingInt(int featureId, int value) {
        long startedAt = SystemClock.elapsedRealtime();
        InstrumentApi current = instrument();
        DirectWriter writer = current == null ? null : current.settingWriter;
        String name = "setting_fid:" + featureId;
        if (writer == null) return operation(name, -1, startedAt, "unavailable");
        try {
            Object eventValue = writer.constructor.newInstance();
            writer.intField.setInt(eventValue, value);
            Object result = writer.set.invoke(writer.device, new int[]{featureId}, eventValue);
            return operation(name, resultCode(result), startedAt,
                    success(result) ? "" : "call returned failure");
        } catch (Throwable error) {
            return operation(name, -1, startedAt, describe(error));
        }
    }

    private InstrumentProxyContract.Operation setInstrumentLaneBatch(
            int[] featureIds, int[] values) {
        long startedAt = SystemClock.elapsedRealtime();
        InstrumentApi current = instrument();
        DirectWriter writer = current == null ? null : current.writer;
        String name = "instrument_lane:sendLaneGuidanceInfo";
        if (writer == null || writer.intArrayField == null) {
            return operation(name, -1, startedAt, "unavailable");
        }
        try {
            Object eventValue = writer.constructor.newInstance();
            writer.intArrayField.set(eventValue, values);
            Object result = writer.set.invoke(writer.device, featureIds, eventValue);
            return operation(name, resultCode(result), startedAt,
                    success(result) ? "" : "call returned failure");
        } catch (Throwable error) {
            return operation(name, -1, startedAt, describe(error));
        }
    }

    private static int laneGuideValue(int direction, int recommendation) {
        if (isComplexLane(direction)) {
            int complex = complexLaneGuide(direction, recommendation);
            if (complex >= 0) return complex;
        } else if (recommendation != 255 && recommendation != -1) {
            return simpleFrontLane(recommendation);
        }
        return simpleBackLane(direction);
    }

    static int laneGuideValueForTest(int direction, int recommendation) {
        return laneGuideValue(direction, recommendation);
    }

    private static boolean isComplexLane(int direction) {
        switch (direction) {
            case 2:
            case 4:
            case 6:
            case 7:
            case 9:
            case 10:
            case 11:
            case 12:
            case 16:
            case 17:
            case 18:
            case 19:
            case 20:
                return true;
            default:
                return false;
        }
    }

    private static int complexLaneGuide(int direction, int recommendation) {
        switch (direction) {
            case 2: return recommendation == 0 ? 51 : recommendation == 1 ? 52 : -1;
            case 4: return recommendation == 0 ? 53 : recommendation == 3 ? 54 : -1;
            case 6: return recommendation == 1 ? 55 : recommendation == 3 ? 56 : -1;
            case 7: return recommendation == 0 ? 57
                    : recommendation == 1 ? 58 : recommendation == 3 ? 59 : -1;
            case 9: return recommendation == 0 ? 60 : recommendation == 5 ? 61 : -1;
            case 10: return recommendation == 0 ? 62 : recommendation == 8 ? 63 : -1;
            case 11: return recommendation == 1 ? 64 : recommendation == 5 ? 65 : -1;
            case 12: return recommendation == 3 ? 66 : recommendation == 8 ? 67 : -1;
            case 16: return recommendation == 0 ? 70
                    : recommendation == 1 ? 71 : recommendation == 5 ? 72 : -1;
            case 17: return recommendation == 3 ? 73 : recommendation == 5 ? 74 : -1;
            case 18: return recommendation == 1 ? 75
                    : recommendation == 3 ? 76 : recommendation == 5 ? 77 : -1;
            case 19: return recommendation == 0 ? 78
                    : recommendation == 3 ? 79 : recommendation == 5 ? 80 : -1;
            case 20: return recommendation == 1 ? 81 : recommendation == 8 ? 82 : -1;
            default: return -1;
        }
    }

    private static int simpleBackLane(int direction) {
        if (direction >= 0 && direction < 13) return direction + 1;
        return direction >= 16 && direction < 255 ? direction : -1;
    }

    private static int simpleFrontLane(int direction) {
        if (direction >= 0 && direction < 13) return direction + 26;
        return direction >= 16 && direction < 255 ? direction + 25 : -1;
    }

    private InstrumentProxyContract.Operation setFeature(
            int featureId, int intValue, byte[] bytes) {
        long startedAt = SystemClock.elapsedRealtime();
        String name = "instrument_fid:" + featureId;
        InstrumentApi current = instrument();
        if (current == null || current.writer == null) {
            return operation(name, -1, startedAt, "unavailable");
        }
        try {
            Object eventValue = current.writer.constructor.newInstance();
            if (bytes == null) {
                current.writer.intField.setInt(eventValue, intValue);
            } else {
                current.writer.bytesField.set(eventValue, bytes);
            }
            Object result = current.writer.set.invoke(
                    current.writer.device, new int[]{featureId}, eventValue);
            return operation(name, resultCode(result), startedAt,
                    success(result) ? "" : "call returned failure");
        } catch (Throwable error) {
            return operation(name, -1, startedAt, describe(error));
        }
    }

    private InstrumentProxyContract.Operation invoke(
            String name, Method method, Object... arguments) {
        long startedAt = SystemClock.elapsedRealtime();
        InstrumentApi current = instrument();
        if (current == null || current.device == null || method == null) {
            return operation(name, -1, startedAt, "unavailable");
        }
        try {
            Object result = method.invoke(current.device, arguments);
            return operation(name, resultCode(result), startedAt,
                    success(result) ? "" : "call returned failure");
        } catch (Throwable error) {
            return operation(name, -1, startedAt, describe(error));
        }
    }

    private Bundle finishOperations(
            List<InstrumentProxyContract.Operation> operations, boolean anyPlaneSucceeded) {
        for (InstrumentProxyContract.Operation operation : operations) {
            if (!succeeded(operation)) {
                instrument = null;
                break;
            }
        }
        if (anyPlaneSucceeded) {
            return InstrumentProxyContract.operationResult(operations, "");
        }
        return InstrumentProxyContract.operationResult(
                operations, "all available Instrument planes failed");
    }

    private boolean hasCapability(int capability) {
        return (activeCapabilities & capability) != 0;
    }

    private static boolean hasNavigationCapability(int capabilities) {
        return (capabilities & (InstrumentProxyContract.CAP_DIRECT_FID
                | InstrumentProxyContract.CAP_INSTRUMENT_SDK)) != 0;
    }

    private static boolean succeeded(InstrumentProxyContract.Operation operation) {
        return operation != null && operation.result == 0 && operation.error.isEmpty();
    }

    private static boolean succeeded(List<InstrumentProxyContract.Operation> operations,
            int first, int count) {
        if (first < 0 || count <= 0 || first + count > operations.size()) return false;
        for (int index = first; index < first + count; index++) {
            if (!succeeded(operations.get(index))) return false;
        }
        return true;
    }

    private static InstrumentProxyContract.Operation operation(
            String name, int result, long startedAt, String error) {
        return new InstrumentProxyContract.Operation(name, result,
                Math.max(0L, SystemClock.elapsedRealtime() - startedAt), error);
    }

    private InstrumentApi instrument() {
        InstrumentApi current = instrument;
        if (current != null) return current;
        synchronized (operationLock) {
            current = instrument;
            if (current == null) {
                current = InstrumentApi.open(systemContext);
                instrument = current;
            }
            return current;
        }
    }

    private void onClientDied() {
        stop("client died");
    }

    boolean isConnected() {
        return connected;
    }

    void stopIfUnconnected() {
        if (!connected) stop("handoff timeout");
    }

    private static long currentProcessStartTimeTicks() {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/self/stat"))) {
            return InstrumentProxyContract.processStartTimeTicks(reader.readLine());
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private void stop(String reason) {
        connected = false;
        IInstrumentNavigationClient current = client;
        client = null;
        if (current != null && current.asBinder().isBinderAlive()) {
            long identity = Binder.clearCallingIdentity();
            try {
                try {
                    current.onProxyStopping(generation, reason);
                } catch (RemoteException ignored) {
                    // The app process may already be gone.
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        Log.i(TAG, "stopping generation=" + generation + " reason=" + reason);
        Looper.getMainLooper().quitSafely();
    }

    private static boolean success(Object result) {
        if (result instanceof Number) return ((Number) result).intValue() == 0;
        if (result instanceof Boolean) return (Boolean) result;
        return false;
    }

    private static int resultCode(Object result) {
        if (result instanceof Number) return ((Number) result).intValue();
        if (result instanceof Boolean) return (Boolean) result ? 0 : -1;
        return -1;
    }

    private static String describe(Throwable error) {
        Throwable cause = error != null && error.getCause() != null
                ? error.getCause() : error;
        if (cause == null) return "unknown";
        String message = cause.getMessage() == null ? "" : cause.getMessage().trim();
        return cause.getClass().getSimpleName()
                + (message.isEmpty() ? "" : ": " + message);
    }

    private static final class InstrumentApi {
        final Object device;
        final Method status;
        final Method simple;
        final Method next;
        final Method reader;
        final DirectWriter writer;
        final DirectWriter settingWriter;

        private InstrumentApi(Object device, Method status, Method simple,
                Method next, Method reader, DirectWriter writer,
                DirectWriter settingWriter) {
            this.device = device;
            this.status = status;
            this.simple = simple;
            this.next = next;
            this.reader = reader;
            this.writer = writer;
            this.settingWriter = settingWriter;
        }

        boolean sdkCapable() {
            return device != null && status != null && simple != null && next != null;
        }

        boolean laneCapable() {
            return writer != null && writer.intArrayField != null;
        }

        Readiness probeFidReadiness() {
            if (device == null || reader == null || writer == null) {
                return new Readiness(false, "direct FID API unavailable");
            }
            try {
                Object value = reader.invoke(
                        device, new int[]{FID_NAV_STATUS}, Integer.TYPE);
                int current = writer.intField.getInt(value);
                if (!validVendorRead(current)) {
                    return new Readiness(false, "navigation_status=" + current);
                }
                Object unchangedValue = writer.constructor.newInstance();
                writer.intField.setInt(unchangedValue, current);
                Object result = writer.set.invoke(
                        writer.device, new int[]{FID_NAV_STATUS}, unchangedValue);
                if (!success(result)) {
                    return new Readiness(false,
                            "navigation_status_write=" + resultCode(result));
                }
                return new Readiness(true, "navigation_status=" + current);
            } catch (Throwable error) {
                return new Readiness(false, describe(error));
            }
        }

        @SuppressLint("PrivateApi")
        static InstrumentApi open(Context context) {
            try {
                Class<?> instrumentClass = Class.forName(INSTRUMENT_CLASS);
                Method getInstance = instrumentClass.getMethod("getInstance", Context.class);
                Object device = getInstance.invoke(null, new BydPermissionContext(context));
                Method status = optionalMethod(
                        instrumentClass, "sendAutoNaviStatus", int.class);
                Method simple = optionalMethod(
                        instrumentClass, "sendSimpleGuidanceInfo", int.class, int.class);
                Method next = optionalMethod(
                        instrumentClass, "sendNextPathName", String.class);
                Method reader = null;
                DirectWriter writer = null;
                DirectWriter settingWriter = null;
                try {
                    Class<?> eventClass = Class.forName(EVENT_VALUE_CLASS);
                    reader = instrumentClass.getMethod("get", int[].class, Class.class);
                    Method set = instrumentClass.getMethod("set", int[].class, eventClass);
                    Constructor<?> constructor = eventClass.getConstructor();
                    Field intField = eventClass.getField("intValue");
                    Field bytesField = eventClass.getField("bufferDataValue");
                    Field intArrayField;
                    try {
                        intArrayField = eventClass.getField("intArrayValue");
                    } catch (NoSuchFieldException unavailable) {
                        intArrayField = null;
                    }
                    writer = new DirectWriter(
                            device, set, constructor, intField, bytesField, intArrayField);
                } catch (Throwable directUnavailable) {
                    Log.w(TAG, "Instrument direct FID API unavailable", directUnavailable);
                }
                if (writer != null && writer.intArrayField != null) {
                    try {
                        Class<?> eventClass = Class.forName(EVENT_VALUE_CLASS);
                        Class<?> settingClass = Class.forName(SETTING_CLASS);
                        Object settingDevice = settingClass
                                .getMethod("getInstance", Context.class)
                                .invoke(null, new BydPermissionContext(context));
                        if (settingDevice != null) {
                            settingWriter = new DirectWriter(settingDevice,
                                    settingClass.getMethod("set", int[].class, eventClass),
                                    writer.constructor, writer.intField,
                                    writer.bytesField, writer.intArrayField);
                        }
                    } catch (Throwable laneUnavailable) {
                        Log.w(TAG, "Setting lane FID API unavailable", laneUnavailable);
                    }
                }
                return new InstrumentApi(device, status, simple, next, reader,
                        writer, settingWriter);
            } catch (Throwable error) {
                Log.w(TAG, "Instrument API unavailable", error);
                return null;
            }
        }

        private static Method optionalMethod(
                Class<?> type, String name, Class<?>... parameters) {
            try {
                return type.getMethod(name, parameters);
            } catch (ReflectiveOperationException unavailable) {
                return null;
            }
        }

        private static boolean validVendorRead(int value) {
            return value != VENDOR_READ_ERROR && value != EVENT_VALUE_UNSET;
        }
    }

    private static final class Readiness {
        final boolean ready;
        final String detail;

        Readiness(boolean ready, String detail) {
            this.ready = ready;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final class DirectWriter {
        final Object device;
        final Method set;
        final Constructor<?> constructor;
        final Field intField;
        final Field bytesField;
        final Field intArrayField;

        DirectWriter(Object device, Method set, Constructor<?> constructor,
                Field intField, Field bytesField, Field intArrayField) {
            this.device = device;
            this.set = set;
            this.constructor = constructor;
            this.intField = intField;
            this.bytesField = bytesField;
            this.intArrayField = intArrayField;
        }
    }

    private static final class BydPermissionContext extends ContextWrapper {
        BydPermissionContext(Context base) {
            super(base);
        }

        @Override public int checkCallingOrSelfPermission(String permission) {
            return isBydPermission(permission)
                    ? PackageManager.PERMISSION_GRANTED
                    : super.checkCallingOrSelfPermission(permission);
        }

        @Override public int checkCallingPermission(String permission) {
            return isBydPermission(permission)
                    ? PackageManager.PERMISSION_GRANTED
                    : super.checkCallingPermission(permission);
        }

        @Override public int checkPermission(String permission, int pid, int uid) {
            return isBydPermission(permission)
                    ? PackageManager.PERMISSION_GRANTED
                    : super.checkPermission(permission, pid, uid);
        }

        @Override public void enforceCallingOrSelfPermission(String permission, String message) {
            if (!isBydPermission(permission)) {
                super.enforceCallingOrSelfPermission(permission, message);
            }
        }

        @Override public void enforceCallingPermission(String permission, String message) {
            if (!isBydPermission(permission)) {
                super.enforceCallingPermission(permission, message);
            }
        }

        @Override public void enforcePermission(
                String permission, int pid, int uid, String message) {
            if (!isBydPermission(permission)) {
                super.enforcePermission(permission, pid, uid, message);
            }
        }

        private static boolean isBydPermission(String permission) {
            return INSTRUMENT_COMMON_PERMISSION.equals(permission)
                    || INSTRUMENT_GET_PERMISSION.equals(permission)
                    || INSTRUMENT_SET_PERMISSION.equals(permission)
                    || SETTING_COMMON_PERMISSION.equals(permission)
                    || SETTING_GET_PERMISSION.equals(permission)
                    || SETTING_SET_PERMISSION.equals(permission);
        }
    }
}
