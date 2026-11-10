package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Contract policy only; no substitute PackageManager, Binder or vehicle responses. */
public final class NaviautoLayoutTest {
    @Test public void ordinaryMeterPermitsTheRecordedNativeFullTbtCycle() {
        assertEquals("", NaviautoLayout.rejection(4, 2, 1));
        assertEquals("", NaviautoLayout.rejection(2, 2, 4));
        assertEquals("", NaviautoLayout.rejection(1, 2, 2));
    }

    @Test public void unsupportedPartialAndUnresolvedBackendCannotAuthorizeAWrite() {
        assertFalse(NaviautoLayout.rejection(3, 2, 1).isEmpty());
        assertFalse(NaviautoLayout.rejection(2, 1, 1).isEmpty());
        assertFalse(NaviautoLayout.rejection(4, 0, 1).isEmpty());
        assertFalse(NaviautoLayout.rejection(4, -2_147_482_648, 1).isEmpty());
        assertFalse(NaviautoLayout.rejection(4, 2, -999_999_999).isEmpty());
        assertFalse(NaviautoLayout.rejection(4, 2, 0).isEmpty());
        assertFalse(NaviautoLayout.rejection(0, 2, 1).isEmpty());
    }
}
