package com.bydhud.app;

final class WazeSdkProbeProtocol {
    static final String SERVICE_DESCRIPTOR = "com.waze.sdk.ISdkService";
    static final int TRANSACTION_REGISTER = 1;
    static final int MESSAGE_REQUEST_NAVIGATION_DATA = 102;
    static final int MESSAGE_DISCONNECT = 103;
    static final int CALLBACK_SDK_ERROR = 502;
    static final int CALLBACK_NAVIGATION_STATUS = 710;

    private WazeSdkProbeProtocol() {
    }

    static String callbackVerdict(int what, boolean hasNavigationState) {
        if (what == CALLBACK_NAVIGATION_STATUS && hasNavigationState) {
            return "AUTHORIZED_IS_NAVIGATING";
        }
        if (what == CALLBACK_NAVIGATION_STATUS) {
            return "AUTHORIZED_PAYLOAD_INVALID";
        }
        if (what == CALLBACK_SDK_ERROR) {
            return "REJECTED_SDK_ERROR";
        }
        return "CALLBACK_OTHER";
    }
}
