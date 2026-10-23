package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class ShanghaiFailureMessageTest {
    @Test public void localizesTheRecordedMockConflictAndKeepsUnknownFailureDetails() {
        String recorded = "java.lang.IllegalStateException: Another app owns the active GPS mock: app.azimutgps";
        String[] languages = {"uk", "en", "ru"};
        String[] actions = {"Зупиніть", "Stop GPS simulation", "Остановите"};
        for (int i = 0; i < languages.length; i++) {
            String message = ShanghaiTestState.failureDetail(languages[i], "FOREIGN_MOCK",
                    "Azimut (app.azimutgps)", recorded);
            assertTrue(message.contains("Azimut (app.azimutgps)"));
            assertTrue(message.contains(actions[i]));
            assertFalse(message.contains("IllegalStateException"));
            assertTrue(ShanghaiTestState.failureDetail(languages[i], "", "", recorded)
                    .contains(recorded));
            String combined = ShanghaiTestState.failureDetail(languages[i], "LOGCAT_STOPPED",
                    "Azimut (app.azimutgps)", "Logcat process exited: 1");
            assertTrue(combined.contains("Logcat process exited: 1"));
            assertTrue(combined.contains("Azimut (app.azimutgps)"));
            assertTrue(combined.contains(actions[i]));
        }
        assertTrue(ShanghaiTestState.failureDetail("uk", "FOREIGN_MOCK", "app.azimutgps", recorded)
                .contains("app.azimutgps"));
    }
}
