package com.bydhud.app;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.bydhud.app.IInstrumentNavigationClient;

interface IInstrumentNavigationProxy {
    oneway void connect(long generation, String nonce, IInstrumentNavigationClient client);
    oneway void ping(long generation, long token);
    Bundle sendNavigationStatus(long generation, int status);
    Bundle sendGuidance(long generation, int compatibilityProfile,
            int icon, int distanceMeters, String road,
            in int[] laneDirections, in int[] laneRecommendations,
            boolean includeNavigationFields);
    Bundle sendClusterDebugStatus(long generation, int status);
    Bundle sendClusterDebugGuidance(long generation, int icon,
            int distanceMeters, String road);
    Bundle sendHudCheckTrafficLight(long generation, int sampleIndex);
    oneway void shutdown(long generation);
    Bundle suspendOutput(long generation);
    Bundle resumeOutput(long generation);
    Bundle nativeSpeedOperation(long generation, int operation, int value);
    void setRecoveryEnabled(long generation, boolean enabled);
    Bundle startSystemCapture(long generation, String id, String cursor, in ParcelFileDescriptor destination);
    Bundle systemCaptureState(long generation, String id, boolean stop);
    Bundle registerWork(long generation, String id, String type, String artifactDirectory,
            int boot, in ParcelFileDescriptor request, int offset,
            in ParcelFileDescriptor[] snapshots, boolean complete);
    Bundle workState(long generation, String id);
    void completeWorkSign(long generation, String id, String requestId, boolean ok, String detail);
    void launchWork(long generation, String id);
    boolean cancelWork(long generation, String id, boolean resetAnyMockGps);
    Bundle sendNativeEta(long generation, int durationDays, int durationHours,
            int durationMinutes, int remainingUnder30, int distanceMeters,
            int arrivalDay, int arrivalHour, int arrivalMinute, int arrivalSecond,
            int dayPeriod);
    Bundle retireExportArtifacts(long generation, String requestJson);
}
