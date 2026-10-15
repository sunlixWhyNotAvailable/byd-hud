package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class ShellWorkExportsTest {
    @Test public void onlyCollectionProgressCanReachTheConfigurationPhaseParser() {
        for (String phase : new String[] {"INVENTORY", "DIAGNOSTICS", "COPYING", "ARCHIVING"}) {
            assertTrue(ShellWorkExports.isConfigurationCollectionPhase(phase));
            assertNotNull(ConfigurationExportPhase.valueOf(phase));
        }
        for (String phase : new String[] {null, "", "REGISTERED", "RUNNING", "SUCCEEDED",
                "FAILED", "CANCELLED", "INTERRUPTED", "FUTURE_SHELL_STATE"}) {
            assertFalse(ShellWorkExports.isConfigurationCollectionPhase(phase));
        }
    }
}
