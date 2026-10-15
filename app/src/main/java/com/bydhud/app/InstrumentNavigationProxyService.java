package com.bydhud.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
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
    private static final int FID_NATIVE_ETA_DAYS = 1_139_810_340;
    private static final int FID_NATIVE_ETA_HOURS = 1_139_810_320;
    private static final int FID_NATIVE_ETA_MINUTES = 1_139_810_328;
    private static final int FID_NATIVE_ETA_UNDER_30 = 1_139_810_334;
    private static final int FID_NATIVE_ETA_DISTANCE = 1_139_810_344;
    private static final int FID_NATIVE_ETA_ARRIVAL_DAY = 1_139_838_992;
    private static final int FID_NATIVE_ETA_ARRIVAL_HOUR = 1_139_839_000;
    private static final int FID_NATIVE_ETA_ARRIVAL_MINUTE = 1_139_839_008;
    private static final int FID_NATIVE_ETA_ARRIVAL_SECOND = 1_139_839_016;
    private static final int FID_LANE_COUNT = 427_827_416;
    private static final int FID_LANE_GUIDE_BASE = 427_827_288;
    private static final int FID_LANE_STATE_BASE = 427_827_296;
    private static final int FID_LANE_VISIBLE_BASE = 427_827_300;
    private static final int SETTING_LANE_DISTANCE = 1_285_554_184;
    private static final int SETTING_LANE_COUNT = 1_285_554_200;
    private static final int FID_DISTANCE_TO_TRAFFIC_LIGHT = HudCheckTrafficLight.DISTANCE_FID;
    private static final String BODYWORK_CLASS =
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";
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
    private static final String BODYWORK_COMMON_PERMISSION =
            "android.permission.BYDAUTO_BODYWORK_COMMON";
    private static final String BODYWORK_GET_PERMISSION =
            "android.permission.BYDAUTO_BODYWORK_GET";
    private static final String BODYWORK_SET_PERMISSION =
            "android.permission.BYDAUTO_BODYWORK_SET";
    private static final String STATISTIC_COMMON_PERMISSION =
            "android.permission.BYDAUTO_STATISTIC_COMMON";
    private static final String STATISTIC_GET_PERMISSION =
            "android.permission.BYDAUTO_STATISTIC_GET";
    private static final String STATISTIC_SET_PERMISSION =
            "android.permission.BYDAUTO_STATISTIC_SET";
    private static final String STATISTIC_CLASS =
            "android.hardware.bydauto.statistic.BYDAutoStatisticDevice";
    private static final String STATISTIC_FEATURE_IDS_CLASS =
            "android.hardware.bydauto.BYDAutoFeatureIds$Statistic";
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
    private volatile BodyworkApi bodywork;
    private StatisticEtaApi statisticEta;
    private boolean statisticEtaResolved;
    private boolean statisticEtaUnavailableLogged;
    private NativeSpeedApi nativeSpeed;
    private volatile IInstrumentNavigationClient client;
    private volatile boolean connected;
    private volatile int activeCapabilities;
    private final HudCheckTrafficLight.Output trafficLightOutput = new HudCheckTrafficLight.Output();
    private boolean outputSuspended;
    private final Handler recoveryHandler = new Handler(Looper.getMainLooper());
    private volatile boolean recoveryEnabled;
    private volatile boolean stopped;
    private IBinder.DeathRecipient clientDeath;
    private final Runnable recoverClient = this::recoverClient;
    private final ShellLogcatCapture logcatCapture = new ShellLogcatCapture();
    private java.lang.Process recoveryCommand;
    private final java.util.Map<String, java.lang.Process> workProcesses = new java.util.concurrent.ConcurrentHashMap<>();

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
        InstrumentProxyStartupLog.record(generation,
                InstrumentProxyStartupLog.Stage.CONNECT_REQUESTED,
                InstrumentProxyStartupLog.Outcome.STARTED);
        if (requestClient == null) {
            InstrumentProxyStartupLog.record(generation,
                    InstrumentProxyStartupLog.Stage.CONNECT_REJECTED,
                    InstrumentProxyStartupLog.Outcome.MISSING_CLIENT);
            return;
        }
        Bundle result;
        if (requestGeneration != generation || !nonce.equals(requestNonce)) {
            InstrumentProxyStartupLog.record(generation,
                    InstrumentProxyStartupLog.Stage.CONNECT_REJECTED,
                    InstrumentProxyStartupLog.Outcome.IDENTITY_MISMATCH);
            result = connectionResult(false, "handoff rejected");
        } else {
            try {
                synchronized (operationLock) {
                    if (stopped) return;
                    if (client != null && clientDeath != null) {
                        client.asBinder().unlinkToDeath(clientDeath, 0);
                    }
                    client = requestClient;
                    clientDeath = () -> onClientDied(requestClient);
                    requestClient.asBinder().linkToDeath(clientDeath, 0);
                }
                InstrumentProxyStartupLog.record(generation,
                        InstrumentProxyStartupLog.Stage.OEM_INSTRUMENT,
                        InstrumentProxyStartupLog.Outcome.STARTED);
                InstrumentApi current = instrument();
                InstrumentProxyStartupLog.record(generation,
                        InstrumentProxyStartupLog.Stage.OEM_INSTRUMENT,
                        current == null ? InstrumentProxyStartupLog.Outcome.UNAVAILABLE
                                : InstrumentProxyStartupLog.Outcome.AVAILABLE);
                int capabilities = InstrumentProxyContract.CAP_SYSTEM_CONTEXT | InstrumentProxyContract.CAP_SHELL_RUNTIME;
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
                BodyworkApi bodyworkApi = null;
                if (current != null && current.writer != null) {
                    InstrumentProxyStartupLog.record(generation,
                            InstrumentProxyStartupLog.Stage.OEM_BODYWORK,
                            InstrumentProxyStartupLog.Outcome.STARTED);
                    bodyworkApi = bodywork();
                    InstrumentProxyStartupLog.record(generation,
                            InstrumentProxyStartupLog.Stage.OEM_BODYWORK,
                            bodyworkApi == null ? InstrumentProxyStartupLog.Outcome.UNAVAILABLE
                                    : InstrumentProxyStartupLog.Outcome.AVAILABLE);
                }
                if (fidReadiness.ready && current != null && current.writer != null
                        && bodyworkApi != null && bodyworkApi.trafficLightCapable()) {
                    capabilities |= InstrumentProxyContract.CAP_TRAFFIC_LIGHT;
                }
                activeCapabilities = capabilities;
                long nativeIdentity = Binder.clearCallingIdentity();
                InstrumentProxyStartupLog.record(generation,
                        InstrumentProxyStartupLog.Stage.OEM_NATIVE_SPEED,
                        InstrumentProxyStartupLog.Outcome.STARTED);
                try {
                    if (nativeSpeed == null) nativeSpeed = NativeSpeedApi.open(systemContext);
                } finally {
                    Binder.restoreCallingIdentity(nativeIdentity);
                }
                InstrumentProxyStartupLog.record(generation,
                        InstrumentProxyStartupLog.Stage.OEM_NATIVE_SPEED,
                        nativeSpeed == null
                                ? InstrumentProxyStartupLog.Outcome.UNAVAILABLE
                                : InstrumentProxyStartupLog.Outcome.AVAILABLE);
                if (nativeSpeed != null) capabilities |= InstrumentProxyContract.CAP_NATIVE_SPEED;
                activeCapabilities = capabilities;
                boolean ready = InstrumentProxyContract.hasUsableCapability(capabilities);
                if (ready) {
                    synchronized (operationLock) {
                        connected = client == requestClient && requestClient.asBinder().isBinderAlive();
                    }
                    if (connected) recoveryHandler.removeCallbacks(recoverClient);
                } else {
                    InstrumentProxyStartupLog.record(generation,
                            InstrumentProxyStartupLog.Stage.CONNECT_REJECTED,
                            InstrumentProxyStartupLog.Outcome.CAPABILITIES_UNAVAILABLE);
                }
                result = connectionResult(ready, ready ? ""
                        : "Instrument capabilities unavailable: " + fidReadiness.detail);
            } catch (RemoteException error) {
                InstrumentProxyStartupLog.recordException(generation,
                        InstrumentProxyStartupLog.Stage.CONNECT_REQUESTED, error);
                result = connectionResult(
                        false, "client already dead");
            }
        }
        long identity = Binder.clearCallingIdentity();
        try {
            try {
                requestClient.onProxyConnected(generation, result);
            } catch (RemoteException ignored) {
                InstrumentProxyStartupLog.record(generation,
                        InstrumentProxyStartupLog.Stage.CALLBACK_FAILED,
                        InstrumentProxyStartupLog.Outcome.BINDER_REMOTE_EXCEPTION);
                onClientDied(requestClient);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (InstrumentProxyContract.isReady(result) && connected) {
            InstrumentProxyStartupLog.record(generation,
                    InstrumentProxyStartupLog.Stage.READY,
                    InstrumentProxyStartupLog.Outcome.OK);
        }
        if (!InstrumentProxyContract.isReady(result) || !connected) {
            if (!recoveryEnabled) stop("handoff rejected");
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
                    onClientDied(current);
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
                if (outputSuspended) return outputSuspendedResult();
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
                if (outputSuspended) return outputSuspendedResult();
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
                    boolean extendedIcon = ManeuverCompatibilityProfile.isBaiduInstrumentIcon(icon);
                    // The stock SDK rejects extended Baidu icons before reaching Binder.
                    if (!extendedIcon) {
                        operations.add(invoke("instrument_sdk:sendSimpleGuidanceInfo",
                                current == null ? null : current.simple, icon, distanceMeters));
                    }
                    operations.add(invoke("instrument_sdk:sendNextPathName",
                            current == null ? null : current.next, roadText));
                    sdkSucceeded = !extendedIcon && succeeded(operations, first, 2);
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
    public Bundle sendNativeEta(long requestGeneration, int durationDays,
            int durationHours, int durationMinutes, int remainingUnder30,
            int distanceMeters, int arrivalDay, int arrivalHour,
            int arrivalMinute, int arrivalSecond, int dayPeriod) {
        enforceSession(requestGeneration);
        int[] values = {durationDays, durationHours, durationMinutes,
                remainingUnder30, distanceMeters, arrivalDay, arrivalHour,
                arrivalMinute, arrivalSecond, dayPeriod};
        if (!NativeEtaTransportPolicy.validInstrumentValues(values)) {
            throw new IllegalArgumentException("invalid native ETA values");
        }
        synchronized (operationLock) {
            if (!connected || requestGeneration != generation) {
                throw new SecurityException("inactive Instrument proxy session");
            }
            long identity = Binder.clearCallingIdentity();
            try {
                if (outputSuspended) return outputSuspendedResult();
                if (!hasCapability(InstrumentProxyContract.CAP_DIRECT_FID)) {
                    return InstrumentProxyContract.operationResult(new ArrayList<>(),
                            "direct FID capability unavailable");
                }
                List<InstrumentProxyContract.Operation> operations = new ArrayList<>(10);
                addNativeEtaFid(operations, FID_NATIVE_ETA_DAYS, durationDays);
                addNativeEtaFid(operations, FID_NATIVE_ETA_HOURS, durationHours);
                addNativeEtaFid(operations, FID_NATIVE_ETA_MINUTES, durationMinutes);
                addNativeEtaFid(operations, FID_NATIVE_ETA_UNDER_30, remainingUnder30);
                addNativeEtaFid(operations, FID_NATIVE_ETA_DISTANCE, distanceMeters);
                addNativeEtaFid(operations, FID_NATIVE_ETA_ARRIVAL_DAY, arrivalDay);
                addNativeEtaFid(operations, FID_NATIVE_ETA_ARRIVAL_HOUR, arrivalHour);
                addNativeEtaFid(operations, FID_NATIVE_ETA_ARRIVAL_MINUTE, arrivalMinute);
                addNativeEtaFid(operations, FID_NATIVE_ETA_ARRIVAL_SECOND, arrivalSecond);
                if (dayPeriod >= 0) operations.add(setStatisticEtaPeriod(dayPeriod));
                boolean anyFailure = false;
                boolean anySuccess = false;
                for (InstrumentProxyContract.Operation operation : operations) {
                    if (succeeded(operation)) anySuccess = true;
                    else anyFailure = true;
                }
                String error = operations.isEmpty() ? "no native ETA fields available"
                        : anyFailure && !anySuccess ? "all native ETA writes failed" : "";
                return InstrumentProxyContract.operationResult(operations, error);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private void addNativeEtaFid(List<InstrumentProxyContract.Operation> operations,
            int featureId, int value) {
        if (value >= 0) operations.add(setInt(featureId, value));
    }

    private InstrumentProxyContract.Operation setStatisticEtaPeriod(int value) {
        long startedAt = SystemClock.elapsedRealtime();
        StatisticEtaApi current = statisticEta();
        if (current == null) {
            if (!statisticEtaUnavailableLogged) {
                statisticEtaUnavailableLogged = true;
                Log.i(TAG, "native ETA Statistic day-period unavailable: OEM feature id/API missing");
            }
            return operation("statistic_eta_period:unavailable", -1, startedAt,
                    "OEM Statistic day-period feature unavailable");
        }
        try {
            Object eventValue = current.constructor.newInstance();
            current.intField.setInt(eventValue, value);
            Object result = current.set.invoke(current.device,
                    new int[] {current.featureId}, eventValue);
            return operation("statistic_eta_period:" + current.featureId,
                    resultCode(result), startedAt,
                    success(result) ? "" : "call returned failure");
        } catch (Throwable error) {
            return operation("statistic_eta_period:" + current.featureId,
                    -1, startedAt, describe(error));
        }
    }

    private StatisticEtaApi statisticEta() {
        if (statisticEtaResolved) return statisticEta;
        statisticEtaResolved = true;
        statisticEta = StatisticEtaApi.open(systemContext);
        return statisticEta;
    }

    @Override
    public Bundle sendHudCheckTrafficLight(long requestGeneration, int sampleIndex) {
        enforceSession(requestGeneration);
        if (!HudCheckTrafficLight.validSampleIndex(sampleIndex)) {
            throw new IllegalArgumentException("unknown traffic-light sample");
        }
        synchronized (operationLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                if (outputSuspended) return outputSuspendedResult();
                if (!connected) {
                    return InstrumentProxyContract.operationResult(new ArrayList<>(),
                            "inactive Instrument proxy session");
                }
                if (!hasCapability(InstrumentProxyContract.CAP_TRAFFIC_LIGHT)) {
                    return InstrumentProxyContract.operationResult(new ArrayList<>(),
                            "traffic-light capability unavailable");
                }
                List<InstrumentProxyContract.Operation> operations = new ArrayList<>(4);
                boolean allSucceeded = trafficLightOutput.write(sampleIndex, (fullState, values) -> {
                    BodyworkApi currentBodywork = bodywork();
                    if (currentBodywork == null || !currentBodywork.trafficLightCapable()) {
                        operations.add(operation("bodywork_traffic_light", -1,
                                SystemClock.elapsedRealtime(), "unavailable"));
                    } else {
                        // Inactive records and distance belong to setup/cleanup, not 1Hz refresh.
                        for (int intersection = 0;
                                intersection < (fullState ? HudCheckTrafficLight.INTERSECTION_COUNT : 1);
                                intersection++) {
                            operations.add(setBodyworkTrafficLight(currentBodywork,
                                    intersection, intersection == 0
                                            ? values : HudCheckTrafficLight.clearValues()));
                        }
                    }
                    if (fullState) {
                        InstrumentApi currentInstrument = instrument();
                        if (currentInstrument == null || currentInstrument.writer == null) {
                            operations.add(operation("instrument_fid:distance_to_traffic_light", -1,
                                    SystemClock.elapsedRealtime(), "unavailable"));
                        } else {
                            operations.add(setInt(FID_DISTANCE_TO_TRAFFIC_LIGHT,
                                    sampleIndex == HudCheckTrafficLight.CLEAR
                                            ? 0 : HudCheckTrafficLight.DISTANCE_METERS));
                        }
                    }
                    return allOperationsSucceeded(operations);
                });
                return InstrumentProxyContract.operationResult(operations,
                        allSucceeded ? "" : "traffic-light operation failed");
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public Bundle suspendOutput(long requestGeneration) {
        enforceSession(requestGeneration);
        synchronized (operationLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                outputSuspended = true;
                boolean cleared = trafficLightOutput.clearIfOwned(false, this::clearTrafficLightOutputs);
                return InstrumentProxyContract.operationResult(new ArrayList<>(),
                        cleared ? "" : "traffic-light output surrender failed");
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public Bundle resumeOutput(long requestGeneration) {
        enforceSession(requestGeneration);
        synchronized (operationLock) {
            outputSuspended = false;
            return InstrumentProxyContract.operationResult(new ArrayList<>(), "");
        }
    }

    @Override
    public Bundle nativeSpeedOperation(long requestGeneration, int operation, int value) {
        enforceSession(requestGeneration);
        if (!NativeSpeedLimitEngine.validOperation(operation, value)) {
            throw new IllegalArgumentException("invalid native speed operation");
        }
        synchronized (operationLock) {
            long identity = Binder.clearCallingIdentity();
            long startedAt = SystemClock.elapsedRealtime();
            int raw = 0;
            String error = "";
            List<InstrumentProxyContract.Operation> operations = new ArrayList<>();
            try {
                if (outputSuspended || !connected) {
                    error = "output suspended or inactive session";
                } else {
                    if (nativeSpeed == null) nativeSpeed = NativeSpeedApi.open(systemContext);
                    if (nativeSpeed == null) throw new UnsupportedOperationException("native speed API unavailable");
                    if (operation == NativeSpeedLimitEngine.READ) {
                        startedAt = SystemClock.elapsedRealtime();
                        Object event = nativeSpeed.get.invoke(nativeSpeed.adas,
                                new int[]{0x2D500020}, Integer.TYPE);
                        if (event == null) throw new UnsupportedOperationException("ADAS returned null");
                        raw = nativeSpeed.intField.getInt(event);
                        if (raw < 0) {
                            throw new UnsupportedOperationException("ADAS speed raw unsupported: " + raw);
                        }
                    } else {
                        int fid = operation == NativeSpeedLimitEngine.ROAD ? 0x4CA00050 : 0x4CA00040;
                        Object event = nativeSpeed.constructor.newInstance();
                        nativeSpeed.intField.setInt(event, value);
                        startedAt = SystemClock.elapsedRealtime();
                        Object result = nativeSpeed.set.invoke(nativeSpeed.setting, new int[]{fid}, event);
                        if (!success(result)) error = "Setting returned " + resultCode(result);
                    }
                }
            } catch (Throwable failure) {
                error = describe(failure);
                nativeSpeed = null;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            operations.add(operation("native_speed:" + operation, error.isEmpty() ? 0 : -1, startedAt, error));
            Bundle result = InstrumentProxyContract.operationResult(operations, error);
            result.putInt("native_raw", raw);
            result.putLong("native_started", startedAt);
            result.putLong("native_finished", SystemClock.elapsedRealtime());
            return result;
        }
    }

    @Override
    public void shutdown(long requestGeneration) {
        enforceCaller();
        if (requestGeneration != generation) throw new SecurityException("stale runtime shutdown");
        stop("app shutdown");
    }

    @Override public void setRecoveryEnabled(long requestGeneration, boolean enabled) {
        enforceSession(requestGeneration);
        recoveryEnabled = enabled;
    }

    @Override public Bundle startSystemCapture(long requestGeneration, String id, String cursor,
            android.os.ParcelFileDescriptor destination) {
        enforceSession(requestGeneration);
        long identity = Binder.clearCallingIdentity();
        try { return logcatCapture.start(id, cursor, destination); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public Bundle systemCaptureState(long requestGeneration, String id, boolean stop) {
        enforceSession(requestGeneration);
        long identity = Binder.clearCallingIdentity();
        try { return stop ? logcatCapture.stop(id) : logcatCapture.state(); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public Bundle registerWork(long requestGeneration, String id,
            String type, String artifactDirectory, int boot, ParcelFileDescriptor request,
            int offset, ParcelFileDescriptor[] snapshots, boolean complete) {
        enforceSession(requestGeneration);
        long identity = Binder.clearCallingIdentity();
        java.util.List<ShellWorkSnapshots.Source> sources = new java.util.ArrayList<>();
        try (ShellWorkFiles.OperationLock admission = ShellWorkFiles.lockOperation(id)) {
            Context app = shellAppContext();
            if (boot != ShellWorkEntryPoint.bootCount())
                throw new SecurityException("Shell job boot identity changed");
            if (!validWorkType(type) || id == null || !id.matches("[A-Za-z0-9_-]{1,80}"))
                throw new SecurityException("Invalid shell operation identity");
            int count = snapshots == null ? 0 : snapshots.length;
            if (count > ShellWorkSnapshots.BATCH_SIZE)
                throw new SecurityException("Shell input batch is oversized");
            File sharedRoot = ShellWorkFiles.directory(ShellWorkFiles.root(app)).getCanonicalFile();
            File artifact = ShellWorkFiles.inside(sharedRoot, artifactDirectory);
            if (!artifact.getParentFile().equals(sharedRoot)
                    || !artifact.getName().equals(type + "-" + id))
                throw new SecurityException("Invalid shell artifact location");
            org.json.JSONObject original = null;
            org.json.JSONArray declaredPaths = null;
            if (request != null) {
                if (!android.system.OsConstants.S_ISREG(android.system.Os.fstat(request.getFileDescriptor()).st_mode))
                    throw new SecurityException("Shell request must be a regular file");
                try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(request)) {
                    org.json.JSONObject supplied = ShellWorkFiles.readManifest(input);
                    original = supplied.getJSONObject("input");
                    declaredPaths = supplied.getJSONArray("snapshotPaths");
                }
                validateWorkInput(app, id, type, original);
            }
            File control = ShellWorkFiles.controlJob(id);
            if (new File(control, "retired.json").exists())
                throw new SecurityException("Retired operation cannot be registered again");
            File manifestFile = new File(control, "request.json");
            org.json.JSONObject manifest;
            if (manifestFile.isFile()) {
                manifest = ShellWorkFiles.readManifest(manifestFile);
                if (!id.equals(manifest.optString("id")) || !type.equals(manifest.optString("type"))
                        || allowedUid != manifest.optInt("uid", -1)
                        || appVersionCode != manifest.optInt("version", -1)
                        || !app.getApplicationInfo().sourceDir.equals(manifest.optString("apk"))
                        || boot != manifest.optInt("boot", -1)
                        || !artifact.getAbsolutePath().equals(manifest.optString("artifact"))
                        || (original != null && (!original.toString().equals(manifest.getJSONObject("input").toString())
                                || !declaredPaths.toString().equals(manifest.getJSONArray("snapshotPaths").toString()))))
                    throw new SecurityException("Operation ID is already bound to a different request");
                org.json.JSONObject state = readWorkState(control);
                String phase = state.optString("state", manifest.optString("state"));
                if (isTerminalWorkState(phase) || "RUNNING".equals(phase))
                    return registrationState(control, manifest, state);
                // A process can stop between committing the manifest and publishing its state.
                if ("REGISTERED".equals(manifest.optString("state"))) {
                    writeWorkState(control, new org.json.JSONObject().put("state", "REGISTERED")
                            .put("progress", new org.json.JSONObject().put("phase", "REGISTERED")));
                    return registrationState(control, manifest, readWorkState(control));
                }
            } else {
                if (original == null || offset != 0) throw new IOException("Shell registration request is missing");
                if (!declaredPaths.toString().equals(ShellWorkSnapshots.paths(type, original).toString()))
                    throw new SecurityException("Shell input selection changed before registration");
                if (!control.isDirectory() && !control.mkdirs()) throw new IOException("Cannot create private shell operation");
                android.system.Os.chmod(control.getPath(), 0700);
                manifest = new org.json.JSONObject().put("schema", 1).put("id", id).put("type", type)
                        .put("uid", allowedUid).put("version", appVersionCode)
                        .put("apk", app.getApplicationInfo().sourceDir).put("boot", boot)
                        .put("artifact", artifact.getAbsolutePath()).put("input", original)
                        .put("workerInput", new org.json.JSONObject(original.toString()))
                        .put("snapshotPaths", declaredPaths)
                        .put("inputFingerprints", new org.json.JSONArray()).put("nextSnapshot", 0)
                        .put("state", "REGISTERING").put("createdAt", System.currentTimeMillis());
                ShellWorkFiles.write(manifestFile, manifest);
                writeWorkState(control, new org.json.JSONObject().put("state", "REGISTERING")
                        .put("progress", new org.json.JSONObject().put("phase", "COPYING")));
            }
            if (count == 0 && !complete) return registrationState(control, manifest, readWorkState(control));
            for (ParcelFileDescriptor snapshot : snapshots == null ? new ParcelFileDescriptor[0] : snapshots) {
                if (snapshot == null) throw new SecurityException("Missing shell input descriptor");
                android.system.StructStat stat = android.system.Os.fstat(snapshot.getFileDescriptor());
                if (!android.system.OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0)
                    throw new SecurityException("Shell input must be a regular file");
                sources.add(new ShellWorkSnapshots.Source(new ParcelFileDescriptor.AutoCloseInputStream(snapshot), stat.st_size));
            }
            org.json.JSONObject cursor = ShellWorkSnapshots.append(control, manifest, offset, sources, complete);
            synchronized (this) {
                String stage = new File(control, "cancel").exists() ? "CANCELLED" : cursor.getString("state");
                writeWorkState(control, new org.json.JSONObject().put("state", stage)
                        .put("progress", new org.json.JSONObject().put("phase", complete ? "REGISTERED" : "COPYING")
                                .put("files", cursor.getInt("nextSnapshot"))));
                return registrationState(control, manifest, readWorkState(control));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot register trusted shell operation: " + error.getMessage(), error);
        } finally {
            for (ShellWorkSnapshots.Source source : sources) try { source.input.close(); } catch (IOException ignored) { }
            if (request != null) try { request.close(); } catch (IOException ignored) { }
            closeDescriptors(snapshots);
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public Bundle workState(long requestGeneration, String id) {
        enforceSession(requestGeneration);
        long identity = Binder.clearCallingIdentity();
        try {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}"))
                throw new SecurityException("Invalid shell operation ID");
            File control = ShellWorkFiles.controlJob(id);
            org.json.JSONObject request = ShellWorkFiles.readManifest(new File(control, "request.json"));
            if (!id.equals(request.optString("id")) || request.optInt("uid", -1) != allowedUid
                    || request.optInt("version", -1) != appVersionCode)
                throw new SecurityException("Trusted shell operation identity mismatch");
            org.json.JSONObject state = readWorkState(control);
            java.lang.Process worker = workProcesses.get(id);
            int currentBoot = ShellWorkEntryPoint.bootCount();
            if (!isTerminalWorkState(state.optString("state"))
                    && request.optInt("boot", -1) != currentBoot) {
                finishInterruptedWork(control, request);
                state = readWorkState(control);
            } else if ("RUNNING".equals(state.optString("state"))
                    // A launched process can still be starting before it acquires owner.lock.
                    && (worker == null ? !awaitingWorkStart(state) : !worker.isAlive())) {
                finishInterruptedWork(control, request);
                state = readWorkState(control);
            }
            return stateBundle(control, state);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read trusted shell operation state: " + error.getMessage(), error);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized void completeWorkSign(long requestGeneration, String id,
            String requestId, boolean ok, String detail) {
        enforceSession(requestGeneration);
        long identity = Binder.clearCallingIdentity();
        try {
            File control = ShellWorkFiles.controlJob(id);
            org.json.JSONObject sign = ShellWorkFiles.read(new File(control, "sign-request.json"));
            if (!requestId.equals(sign.optString("id")))
                throw new SecurityException("Signing request identity mismatch");
            File response = new File(control, "sign-response-" + requestId + ".json");
            if (response.isFile()) {
                org.json.JSONObject prior = ShellWorkFiles.read(response);
                if (!requestId.equals(prior.optString("id")) || ok != prior.optBoolean("ok"))
                    throw new SecurityException("Conflicting signing completion");
                return;
            }
            ShellWorkFiles.write(response, new org.json.JSONObject().put("id", requestId)
                    .put("ok", ok).put("detail", detail == null ? "" : detail));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot complete trusted signing request", error);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized void launchWork(long requestGeneration, String id) {
        enforceSession(requestGeneration);
        long identity = Binder.clearCallingIdentity();
        try (ShellWorkFiles.OperationLock admission = ShellWorkFiles.lockOperation(id)) {
            Context app = shellAppContext();
            File control = ShellWorkFiles.controlJob(id);
            org.json.JSONObject request = ShellWorkFiles.readManifest(new File(control, "request.json"));
            if (!id.equals(request.optString("id")) || request.optInt("uid", -1) != allowedUid
                    || request.optInt("version", -1) != appVersionCode
                    || !request.optString("apk").equals(app.getApplicationInfo().sourceDir))
                throw new SecurityException("Shell job owner changed");
            workProcesses.entrySet().removeIf(entry -> !entry.getValue().isAlive());
            java.lang.Process existing = workProcesses.get(id);
            if (existing != null && existing.isAlive()) return;
            org.json.JSONObject state = readWorkState(control);
            String phase = state.optString("state");
            if (isTerminalWorkState(phase)) return;
            if (!"REGISTERED".equals(request.optString("state")))
                throw new IOException("Shell input registration is incomplete");
            if ("RUNNING".equals(phase) || new File(control, "started.json").exists()) {
                if (!awaitingWorkStart(state))
                    finishInterruptedWork(control, request);
                return;
            }
            File artifact = ShellWorkFiles.inside(ShellWorkFiles.root(app), request.getString("artifact"));
            if (!artifact.getParentFile().equals(ShellWorkFiles.root(app).getCanonicalFile()))
                throw new SecurityException("Invalid shell artifact directory");
            writeWorkState(control, new org.json.JSONObject().put("id", id).put("state", "RUNNING")
                    .put("startupDeadlineElapsedMs", android.os.SystemClock.elapsedRealtime() + 15_000L)
                    .put("progress", new org.json.JSONObject().put("phase", "STARTING")));
            ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                    ShellWorkEntryPoint.class.getName(), id);
            builder.environment().put("CLASSPATH", app.getApplicationInfo().sourceDir);
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(
                    new File(control, "worker.log")));
            try { workProcesses.put(id, builder.start()); }
            catch (IOException error) {
                writeWorkState(control, new org.json.JSONObject().put("id", id).put("state", "FAILED")
                        .put("result", new org.json.JSONObject().put("ok", false)
                                .put("error", "Cannot start shell worker: " + error.getClass().getSimpleName())));
                throw error;
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot launch trusted shell work: " + error.getMessage(), error);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized boolean cancelWork(long requestGeneration, String id,
            boolean resetAnyMockGps) {
        enforceSession(requestGeneration);
        long identity = Binder.clearCallingIdentity();
        try {
            File control = ShellWorkFiles.controlJob(id);
            org.json.JSONObject request = ShellWorkFiles.readManifest(new File(control, "request.json"));
            if (!id.equals(request.optString("id")) || request.optInt("uid", -1) != allowedUid)
                throw new SecurityException("Shell operation owner changed");
            org.json.JSONObject workState = readWorkState(control);
            if (isTerminalWorkState(workState.optString("state"))) return true;
            if (resetAnyMockGps) {
                org.json.JSONObject input = request.optJSONObject("input");
                if (!"RUNNING".equals(workState.optString("state"))
                        || !"shanghai".equals(request.optString("type"))
                        || input == null || "cleanup".equals(input.optString("action")))
                    throw new SecurityException("Reset-any is only valid for an active Shanghai route");
                File resetAny = new File(control, "reset-any");
                if (!resetAny.exists()) ShellWorkFiles.write(resetAny,
                        new org.json.JSONObject().put("id", id));
            }
            File cancel = new File(control, "cancel");
            if (!cancel.exists()) ShellWorkFiles.write(cancel, new org.json.JSONObject().put("id", id));
            java.lang.Process process = workProcesses.get(id);
            if (process == null || !process.isAlive()) {
                if (!"RUNNING".equals(readWorkState(control).optString("state"))) {
                    writeWorkState(control, new org.json.JSONObject().put("id", id).put("state", "CANCELLED")
                            .put("result", new org.json.JSONObject().put("ok", false).put("cancelled", true)
                                    .put("error", "Cancelled before worker launch")));
                    return true;
                }
                return false;
            }
            if ("shanghai".equals(request.optString("type"))) return true;
            process.destroyForcibly();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) return false;
            writeWorkState(control, new org.json.JSONObject().put("id", id).put("state", "CANCELLED")
                    .put("result", new org.json.JSONObject().put("ok", false).put("cancelled", true)
                            .put("error", "Cancelled by operation owner")));
            return true;
        } catch (Exception error) { return false; }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public Bundle retireExportArtifacts(long requestGeneration, String requestJson) {
        enforceSession(requestGeneration);
        long identity = Binder.clearCallingIdentity();
        try {
            if (requestJson == null || requestJson.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
                throw new IOException("cleanup_batch_oversized");
            org.json.JSONArray results = ShellExportCleanup.run(shellAppContext(), allowedUid,
                    appVersionCode, new org.json.JSONObject(requestJson), workProcesses);
            Bundle reply = new Bundle();
            reply.putInt("schema", 1);
            reply.putString("results", results.toString());
            return reply;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot retire export artifacts: " + error.getMessage(), error);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private Context shellAppContext() throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("Shell runtime owner required");
        Context app = systemContext.createPackageContext("com.bydhud.app", Context.CONTEXT_IGNORE_SECURITY);
        if (app.getApplicationInfo().uid != allowedUid
                || app.getPackageManager().getPackageInfo("com.bydhud.app", 0).versionCode != appVersionCode)
            throw new SecurityException("Shell app installation changed");
        return app;
    }

    private static boolean validWorkType(String type) {
        return "patch".equals(type) || "log".equals(type) || "configuration".equals(type)
                || "upload".equals(type) || "shanghai".equals(type);
    }

    private static void validateWorkInput(Context app, String id, String type, org.json.JSONObject input)
            throws Exception {
        File shared = ShellWorkFiles.root(app).getCanonicalFile();
        File transactions = new File(shared, "transactions").getCanonicalFile();
        File cache = new File(shared, "patch-cache").getCanonicalFile();
        if ("patch".equals(type)) {
            NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(input.optString("profile"));
            if (profile == null || !input.optString("operation").matches("[A-Za-z0-9_-]{1,100}"))
                throw new SecurityException("Invalid patch operation identity");
            int command = input.optInt("command", -1);
            if (command != NavigatorPatchWorkerClient.MSG_SCAN
                    && command != NavigatorPatchWorkerClient.MSG_PREPARE)
                throw new SecurityException("Invalid patch command");
            String source = input.optString("source");
            if (input.optBoolean("directory")) {
                File sourceSet = ShellWorkFiles.ownedPath(transactions, source);
                if (!sourceSet.isDirectory() || !isApkSetDirectory(sourceSet))
                    throw new SecurityException("Invalid nested APK-set inspection path");
            } else if (!source.isEmpty()) {
                // Source bytes arrive via a Binder file descriptor and are replaced by a shell-only copy.
                if (new File(source).isDirectory()) throw new SecurityException("Patch source must be a file");
            }
            String output = input.optString("output");
            if (!output.isEmpty()) {
                boolean installedInspection = command == NavigatorPatchWorkerClient.MSG_SCAN
                        && !input.optBoolean("directory") && source.isEmpty()
                        && input.optString("transaction").isEmpty();
                ShellWorkFiles.patchInspectionOutput(cache, profile.id, installedInspection, output);
            }
            String transaction = input.optString("transaction");
            if (command == NavigatorPatchWorkerClient.MSG_PREPARE) {
                File tx = ShellWorkFiles.ownedPath(transactions, transaction);
                if (!tx.getParentFile().equals(transactions) || !tx.getName().matches("tx-[A-Za-z0-9-]+"))
                    throw new SecurityException("Invalid patch transaction path");
            } else if (!transaction.isEmpty()) throw new SecurityException("Unexpected patch transaction path");
        } else if ("upload".equals(type)) {
            if (!input.optString("operation").matches("[A-Za-z0-9_-]{1,100}")
                    || input.optJSONArray("days") == null)
                throw new SecurityException("Invalid upload descriptor");
        } else if ("log".equals(type)) {
            if (input.optJSONArray("sources") == null || input.optJSONArray("days") == null)
                throw new SecurityException("Invalid log export descriptor");
        } else if ("shanghai".equals(type)) {
            String action = input.optString("action", "route");
            if (!"route".equals(action) && !"reset".equals(action)
                    && !"cleanup".equals(action))
                throw new SecurityException("Invalid Shanghai action");
            if ("route".equals(action) && !id.equals(input.optString("id")))
                throw new SecurityException("Shanghai route identity mismatch");
            String evidence = input.optString("evidence");
            if ("route".equals(action) && evidence.isEmpty())
                throw new SecurityException("Shanghai route evidence path is missing");
            if (!evidence.isEmpty()) {
                File external = ShellWorkFiles.externalFiles(app);
                if (external == null) throw new IOException("Shanghai evidence storage unavailable");
                int user = app.getApplicationInfo().uid / 100000;
                File publicRoot = new File("/storage/emulated/" + user + "/Documents/"
                        + NavigationLogStorage.ROOT_DIR);
                ShellWorkFiles.shanghaiEvidence(publicRoot,
                        new File(external, NavigationLogStorage.ROOT_DIR),
                        input.optString("day"), id, evidence);
            }
        }
    }

    private static Bundle registrationState(File control, org.json.JSONObject manifest, org.json.JSONObject state) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString("state", state.optString("state", manifest.optString("state")));
        bundle.putInt("next_snapshot", ShellWorkSnapshots.progress(control, manifest).getInt("nextSnapshot"));
        return bundle;
    }

    private static org.json.JSONObject readWorkState(File control) throws IOException {
        return ShellWorkFiles.read(new File(control, "state.json"));
    }

    static void writeWorkState(File control, org.json.JSONObject state) throws IOException {
        ShellWorkFiles.write(new File(control, "state.json"), state);
    }

    private static Bundle stateBundle(File control, org.json.JSONObject state) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString("state", state.optString("state", "REGISTERED"));
        bundle.putString("progress_json", state.optJSONObject("progress") == null ? "{}"
                : state.getJSONObject("progress").toString());
        bundle.putString("result_json", state.optJSONObject("result") == null ? "{}"
                : state.getJSONObject("result").toString());
        org.json.JSONObject request = ShellWorkFiles.read(new File(control, "sign-request.json"));
        String signId = request.optString("id");
        bundle.putString("sign_request_json", !signId.isEmpty()
                && !new File(control, "sign-response-" + signId + ".json").isFile()
                ? request.toString() : "{}");
        return bundle;
    }

    private static void deletePrivateTree(File target, File privateRoot) throws IOException {
        File candidate = ShellWorkFiles.ownedPath(privateRoot, target.getPath());
        File[] children = candidate.listFiles();
        if (children != null) for (File child : children) deletePrivateTree(child, privateRoot);
        if (!candidate.delete()) throw new IOException("Cannot reset private shell snapshot");
    }

    private static void finishInterruptedWork(File control, org.json.JSONObject request) throws Exception {
        try (java.io.RandomAccessFile owner = new java.io.RandomAccessFile(new File(control, "owner.lock"), "rw");
             java.nio.channels.FileLock lock = owner.getChannel().tryLock()) {
            if (lock == null || isTerminalWorkState(readWorkState(control).optString("state"))) return;
            writeWorkState(control, new org.json.JSONObject().put("id", request.optString("id"))
                    .put("state", "INTERRUPTED")
                    .put("result", new org.json.JSONObject().put("ok", false)
                            .put("cleanupPending", "shanghai".equals(request.optString("type")))
                            .put("error", "Shell worker exited before returning a result; operation was not replayed")));
        } catch (java.nio.channels.OverlappingFileLockException anotherStatusRead) {
            // Another status reader owns the interruption decision.
        }
    }

    private static boolean awaitingWorkStart(org.json.JSONObject state) {
        org.json.JSONObject progress = state.optJSONObject("progress");
        // A restarted proxy has no Process handle while the child is still initializing.
        return progress != null && "STARTING".equals(progress.optString("phase"))
                && android.os.SystemClock.elapsedRealtime() < state.optLong("startupDeadlineElapsedMs");
    }

    static boolean isTerminalWorkState(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state)
                || "CANCELLED".equals(state) || "INTERRUPTED".equals(state);
    }

    private static boolean isApkSetDirectory(File directory) {
        String name = directory.getName();
        File transaction = directory.getParentFile();
        return ("patched-set".equals(name) || "source-set".equals(name)
                || "map-candidate-set".equals(name)) && transaction != null
                && transaction.getName().matches("tx-[A-Za-z0-9-]+")
                && transaction.getParentFile() != null
                && transaction.getParentFile().getName().equals("transactions")
                && new File(directory, "members").isDirectory();
    }

    private static void closeDescriptors(ParcelFileDescriptor[] descriptors) {
        if (descriptors == null) return;
        for (ParcelFileDescriptor descriptor : descriptors)
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
    }


    private static Bundle outputSuspendedResult() {
        return InstrumentProxyContract.operationResult(new ArrayList<>(),
                "stock Shanghai test owns navigation output");
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

    private InstrumentProxyContract.Operation setBodyworkTrafficLight(
            BodyworkApi current, int intersection, int[] values) {
        long startedAt = SystemClock.elapsedRealtime();
        String name = "bodywork_traffic_light:" + (intersection + 1);
        try {
            Object eventValue = current.writer.constructor.newInstance();
            current.writer.intArrayField.set(eventValue, values.clone());
            Object result = current.writer.set.invoke(current.writer.device,
                    HudCheckTrafficLight.selectors(intersection), eventValue);
            return operation(name, resultCode(result), startedAt,
                    success(result) ? "" : "call returned failure");
        } catch (Throwable error) {
            return operation(name, -1, startedAt, describe(error));
        }
    }

    private static boolean allOperationsSucceeded(
            List<InstrumentProxyContract.Operation> operations) {
        boolean allSucceeded = !operations.isEmpty();
        for (InstrumentProxyContract.Operation operation : operations) {
            if (!succeeded(operation)) {
                allSucceeded = false;
                break;
            }
        }
        return allSucceeded;
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

    private BodyworkApi bodywork() {
        BodyworkApi current = bodywork;
        if (current != null) return current;
        synchronized (operationLock) {
            current = bodywork;
            if (current == null) {
                current = BodyworkApi.open(systemContext);
                bodywork = current;
            }
            return current;
        }
    }

    private void onClientDied(IInstrumentNavigationClient expected) {
        synchronized (operationLock) {
            if (client != expected || stopped) return;
            connected = false;
            client = null;
            clientDeath = null;
        }
        if (!recoveryEnabled) { stop("client died"); return; }
        // The shell runtime and its owned outputs survive. Only Android bindings reattach.
        Log.i(TAG, "client detached; retaining runtime generation=" + generation);
        recoveryHandler.removeCallbacks(recoverClient);
        recoveryHandler.post(recoverClient);
    }

    private void recoverClient() {
        if (stopped || connected || !recoveryEnabled) return;
        try {
            // An explicit shell start also clears the OEM package-stopped state. No Activity.
            android.content.pm.ApplicationInfo owner = systemContext.getPackageManager()
                    .getApplicationInfo("com.bydhud.app", 0);
            String classPath = System.getProperty("java.class.path", "");
            if (owner.uid != allowedUid || !(classPath.equals(owner.sourceDir)
                    || classPath.endsWith(":" + owner.sourceDir))) {
                stop("owner installation changed");
                return;
            }
            if (recoveryCommand == null || !recoveryCommand.isAlive()) recoveryCommand =
                    new ProcessBuilder("/system/bin/am", "start-foreground-service", "--user", "current",
                    "-n", "com.bydhud.app/.HudRuntimeService", "-a",
                    "com.bydhud.app.action.RECOVER_SHELL_RUNTIME")
                    .redirectErrorStream(true).redirectOutput(new java.io.File("/dev/null")).start();
            Intent handoff = new Intent(InstrumentProxyContract.ACTION_CONNECTED)
                    .setComponent(new ComponentName("com.bydhud.app", "com.bydhud.app.InstrumentProxyReceiver"))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("recover_runtime", true)
                    .putExtra(InstrumentProxyContract.EXTRA_GENERATION, generation)
                    .putExtra(InstrumentProxyContract.EXTRA_NONCE, nonce)
                    .putExtra(InstrumentProxyContract.EXTRA_BINDER, new InstrumentProxyBinder(asBinder()));
            systemContext.sendBroadcast(handoff);
            Log.i(TAG, "background recovery requested generation=" + generation);
        } catch (Exception error) {
            Log.w(TAG, "background recovery failed", error);
            if (error instanceof PackageManager.NameNotFoundException) { stop("owner removed"); return; }
        }
        if (!stopped && !connected && recoveryEnabled) recoveryHandler.postDelayed(recoverClient, 2_000L);
    }

    boolean isConnected() {
        return connected;
    }

    void stopIfUnconnected() {
        if (!connected && !recoveryEnabled) stop("handoff timeout");
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
        stopped = true;
        recoveryEnabled = false;
        recoveryHandler.removeCallbacks(recoverClient);
        if (recoveryCommand != null) recoveryCommand.destroy();
        Bundle capture = logcatCapture.state();
        if (capture.getBoolean("running")) logcatCapture.stop(capture.getString("id", ""));
        InstrumentProxyStartupLog.record(generation,
                InstrumentProxyStartupLog.Stage.STOP_REQUESTED,
                InstrumentProxyStartupLog.stopOutcome(reason));
        synchronized (operationLock) {
            connected = false;
            trafficLightOutput.clearIfOwned(outputSuspended, this::clearTrafficLightOutputs);
        }
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
        InstrumentProxyStartupLog.record(generation,
                InstrumentProxyStartupLog.Stage.STOPPED,
                InstrumentProxyStartupLog.Outcome.OK);
        // Android's main looper cannot quit; this standalone shell process has finished cleanup.
        System.exit(0);
    }

    private boolean clearTrafficLightOutputs() {
        List<InstrumentProxyContract.Operation> operations = new ArrayList<>(4);
        BodyworkApi currentBodywork = bodywork;
        if (currentBodywork != null && currentBodywork.trafficLightCapable()) {
            int[] clearValues = HudCheckTrafficLight.clearValues();
            for (int intersection = 0;
                    intersection < HudCheckTrafficLight.INTERSECTION_COUNT;
                    intersection++) {
                operations.add(setBodyworkTrafficLight(currentBodywork, intersection, clearValues));
            }
        } else {
            operations.add(operation("bodywork_traffic_light", -1,
                    SystemClock.elapsedRealtime(), "unavailable"));
        }
        InstrumentApi currentInstrument = instrument();
        if (currentInstrument != null && currentInstrument.writer != null) {
            operations.add(setInt(FID_DISTANCE_TO_TRAFFIC_LIGHT, 0));
        } else {
            operations.add(operation("instrument_fid:distance_to_traffic_light", -1,
                    SystemClock.elapsedRealtime(), "unavailable"));
        }
        return allOperationsSucceeded(operations);
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

    private static final class BodyworkApi {
        final Object device;
        final DirectWriter writer;

        BodyworkApi(Object device, DirectWriter writer) {
            this.device = device;
            this.writer = writer;
        }

        boolean trafficLightCapable() {
            return device != null && writer != null && writer.intArrayField != null;
        }

        @SuppressLint("PrivateApi")
        static BodyworkApi open(Context context) {
            try {
                Class<?> deviceClass = Class.forName(BODYWORK_CLASS);
                Object device = deviceClass.getMethod("getInstance", Context.class)
                        .invoke(null, new BydPermissionContext(context));
                Class<?> eventClass = Class.forName(EVENT_VALUE_CLASS);
                Method set = deviceClass.getMethod("set", int[].class, eventClass);
                Constructor<?> constructor = eventClass.getConstructor();
                Field intField = eventClass.getField("intValue");
                Field bytesField = eventClass.getField("bufferDataValue");
                Field intArrayField = eventClass.getField("intArrayValue");
                return new BodyworkApi(device,
                        new DirectWriter(device, set, constructor,
                                intField, bytesField, intArrayField));
            } catch (Throwable error) {
                Log.w(TAG, "Bodywork traffic-light API unavailable", error);
                return null;
            }
        }
    }

    /** Optional OEM Statistic path used only for its typed map day-period event. */
    private static final class StatisticEtaApi {
        final Object device;
        final Method set;
        final Constructor<?> constructor;
        final Field intField;
        final int featureId;

        StatisticEtaApi(Object device, Method set, Constructor<?> constructor,
                Field intField, int featureId) {
            this.device = device;
            this.set = set;
            this.constructor = constructor;
            this.intField = intField;
            this.featureId = featureId;
        }

        static StatisticEtaApi open(Context context) {
            try {
                Class<?> featureIds = Class.forName(STATISTIC_FEATURE_IDS_CLASS);
                int featureId = featureIds.getField("STATISTICS_MAP_PERIOD_INFOR")
                        .getInt(null);
                if (featureId < 0) return null;
                Class<?> statisticClass = Class.forName(STATISTIC_CLASS);
                Object device = statisticClass.getMethod("getInstance", Context.class)
                        .invoke(null, new BydPermissionContext(context));
                Class<?> eventClass = Class.forName(EVENT_VALUE_CLASS);
                Method set = statisticClass.getMethod("set", int[].class, eventClass);
                Constructor<?> constructor = eventClass.getConstructor();
                Field intField = eventClass.getField("intValue");
                return new StatisticEtaApi(device, set, constructor, intField, featureId);
            } catch (Throwable unavailable) {
                Log.i(TAG, "native ETA Statistic day-period API unavailable: "
                        + describe(unavailable));
                return null;
            }
        }
    }

    /** Setting and ADAS are independent of Instrument's guidance/lane capabilities. */
    private static final class NativeSpeedApi {
        final Object setting, adas;
        final Method set, get;
        final Constructor<?> constructor;
        final Field intField;

        NativeSpeedApi(Object setting, Object adas, Method set, Method get,
                Constructor<?> constructor, Field intField) {
            this.setting = setting;
            this.adas = adas;
            this.set = set;
            this.get = get;
            this.constructor = constructor;
            this.intField = intField;
        }

        static NativeSpeedApi open(Context context) {
            try {
                Context allowed = new BydPermissionContext(context);
                Class<?> setting = Class.forName(SETTING_CLASS);
                Class<?> adas = Class.forName("android.hardware.bydauto.adas.BYDAutoADASDevice");
                Class<?> event = Class.forName(EVENT_VALUE_CLASS);
                Object settingDevice = setting.getMethod("getInstance", Context.class).invoke(null, allowed);
                Object adasDevice = adas.getMethod("getInstance", Context.class).invoke(null, allowed);
                if (settingDevice == null || adasDevice == null) return null;
                return new NativeSpeedApi(settingDevice, adasDevice,
                        setting.getMethod("set", int[].class, event),
                        adas.getMethod("get", int[].class, Class.class), event.getConstructor(),
                        event.getField("intValue"));
            } catch (Throwable unavailable) {
                Log.w(TAG, "native speed API unavailable", unavailable);
                return null;
            }
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
                    || SETTING_SET_PERMISSION.equals(permission)
                    || BODYWORK_COMMON_PERMISSION.equals(permission)
                    || BODYWORK_GET_PERMISSION.equals(permission)
                    || BODYWORK_SET_PERMISSION.equals(permission)
                    || STATISTIC_COMMON_PERMISSION.equals(permission)
                    || STATISTIC_GET_PERMISSION.equals(permission)
                    || STATISTIC_SET_PERMISSION.equals(permission)
                    || "android.permission.BYDAUTO_ADAS_COMMON".equals(permission)
                    || "android.permission.BYDAUTO_ADAS_GET".equals(permission);
        }
    }
}
