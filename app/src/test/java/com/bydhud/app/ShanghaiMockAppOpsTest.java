package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ShanghaiMockAppOpsTest {
    @Test
    public void readsPackageGrantSeparatelyFromUidDefault() {
        ShanghaiMockGps.AppOpModes modes = ShanghaiMockGps.parseAppOpModes(
                "Uid mode: MOCK_LOCATION: default\n"
                        + "MOCK_LOCATION: allow; time=+122d19h14m32s634ms ago\n");

        assertEquals("allow", modes.packageMode);
        assertEquals("default", modes.uidMode);
    }

    @Test
    public void distinguishesAnAbsentUidModeFromAnExplicitDefault() {
        ShanghaiMockGps.AppOpModes modes = ShanghaiMockGps.parseAppOpModes(
                "No operations.\nDefault mode: deny\n");

        assertEquals("deny", modes.packageMode);
        assertNull(modes.uidMode);
    }
}
