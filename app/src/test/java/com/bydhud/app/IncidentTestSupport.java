package com.bydhud.app;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Clear process singletons between isolated Robolectric application/storage lifetimes. */
final class IncidentTestSupport {
    static void restartProcessDiagnostics() throws Exception {
        Field instance = field(RuntimeIncidentRecorder.class, "instance");
        Object recorder = instance.get(null);
        if (recorder != null) {
            ((ScheduledThreadPoolExecutor) field(RuntimeIncidentRecorder.class, "executor").get(recorder)).shutdownNow();
        }
        instance.set(null, null);
        for (String name : new String[]{"instance", "mapInstance"}) {
            Field owner = field(WazeCaptureDebugWriter.class, name);
            WazeCaptureDebugWriter writer = (WazeCaptureDebugWriter) owner.get(null);
            if (writer != null) {
                if (!writer.awaitCheckpoint(2000)) throw new AssertionError("writer still busy");
                android.os.HandlerThread thread = (android.os.HandlerThread) field(WazeCaptureDebugWriter.class, "thread").get(writer);
                thread.quitSafely(); thread.join(2000);
            }
            owner.set(null, null);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
