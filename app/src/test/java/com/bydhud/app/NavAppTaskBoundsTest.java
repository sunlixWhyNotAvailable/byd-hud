package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class NavAppTaskBoundsTest {
    // Waze task #41, local capture 20261013/live-pull-161438/activities.txt; unrelated config fields omitted.
    private static final String CAPTURED_CONFIG = """
              * Task{391e366 #41 type=standard A=10226:com.waze U=0 visible=false}
                    packageName=com.waze processName=com.waze
                    mLastReportedConfigurations:
                      mGlobalConfig={winConfig={ mBounds=Rect(0, 0 - 1920, 1080) mAppBounds=Rect(0, 0 - 1920, 990) mMaxBounds=Rect(0, 0 - 1920, 1080)}}
                      mOverrideConfig={winConfig={ mBounds=Rect(0, 0 - 1920, 1080) mAppBounds=Rect(0, 0 - 1920, 990) mMaxBounds=Rect(0, 0 - 1920, 1080)}}
                    CurrentConfiguration={winConfig={ mBounds=Rect(0, 0 - 1920, 1080) mAppBounds=Rect(0, 0 - 1920, 990) mMaxBounds=Rect(0, 0 - 1920, 1080)}}
            """;

    @Test public void readsCapturedBoundsAndUsesCurrentConfigurationAfterResize() {
        assertArrayEquals(new int[]{0, 0, 1920, 1080}, NavAppTaskScanner.parseTaskBounds(CAPTURED_CONFIG));

        String resized = CAPTURED_CONFIG.replace(
                "CurrentConfiguration={winConfig={ mBounds=Rect(0, 0 - 1920, 1080)",
                "CurrentConfiguration={winConfig={ mBounds=Rect(240, 0 - 1680, 540)");
        assertArrayEquals(new int[]{240, 0, 1680, 540}, NavAppTaskScanner.parseTaskBounds(resized));
        assertArrayEquals(new int[]{-10, 20, 1000, 800}, NavAppTaskScanner.parseTaskBounds(
                resized + "  mBounds=Rect(-10, 20 - 1000, 800)\n"));
    }

    @Test public void rejectsMissingInvalidOrHistoricalOnlyBounds() {
        assertNull(NavAppTaskScanner.parseTaskBounds(""));
        assertNull(NavAppTaskScanner.parseTaskBounds("mBounds=Rect(0, 0 - 0, 0)"));
        assertNull(NavAppTaskScanner.parseTaskBounds("mBounds=Rect(0, 0 - 2147483648, 1080)"));
        assertNull(NavAppTaskScanner.parseTaskBounds(
                CAPTURED_CONFIG.replace("CurrentConfiguration=", "RequestedOverrideConfiguration=")));
        assertNull(NavAppTaskScanner.parseTaskBounds(
                "CurrentConfiguration={winConfig={ mAppBounds=Rect(0, 0 - 1920, 990)}}"));
    }
}
