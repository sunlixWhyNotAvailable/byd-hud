package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ShanghaiFooterStatusTest {
    @Test
    public void cleanupFailureAndPartialCoverageHaveStablePriority() {
        assertEquals(ShanghaiTestState.FooterStatus.CLEANUP_PENDING,
                state(ShanghaiTestState.Phase.ERROR, ShanghaiTestState.CaptureStatus.FAILED, true).footerStatus());
        assertEquals(ShanghaiTestState.FooterStatus.FAILURE,
                state(ShanghaiTestState.Phase.ERROR, ShanghaiTestState.CaptureStatus.PARTIAL, false).footerStatus());
        assertEquals(ShanghaiTestState.FooterStatus.PARTIAL,
                state(ShanghaiTestState.Phase.DRIVING, ShanghaiTestState.CaptureStatus.PARTIAL, false).footerStatus());
        assertEquals(ShanghaiTestState.FooterStatus.NORMAL,
                state(ShanghaiTestState.Phase.DRIVING, ShanghaiTestState.CaptureStatus.READY, false).footerStatus());
    }

    private static ShanghaiTestState state(ShanghaiTestState.Phase phase,
            ShanghaiTestState.CaptureStatus captureStatus, boolean cleanupPending) {
        return new ShanghaiTestState(phase, 0, "", "", captureStatus, cleanupPending, "", "");
    }
}
