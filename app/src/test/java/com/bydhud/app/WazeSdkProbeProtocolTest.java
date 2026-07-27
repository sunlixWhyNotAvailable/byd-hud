package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WazeSdkProbeProtocolTest {
    @Test
    public void classifiesNavigationAndErrorCallbacks() {
        assertEquals("AUTHORIZED_IS_NAVIGATING",
                WazeSdkProbeProtocol.callbackVerdict(710, true));
        assertEquals("AUTHORIZED_PAYLOAD_INVALID",
                WazeSdkProbeProtocol.callbackVerdict(710, false));
        assertEquals("REJECTED_SDK_ERROR",
                WazeSdkProbeProtocol.callbackVerdict(502, false));
        assertEquals("CALLBACK_OTHER",
                WazeSdkProbeProtocol.callbackVerdict(1, false));
    }
}
