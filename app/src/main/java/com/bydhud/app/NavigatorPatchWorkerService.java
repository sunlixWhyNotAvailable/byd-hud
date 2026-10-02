package com.bydhud.app;

/** Payload keys shared by the shell patch worker and its application observer. */
final class NavigatorPatchWorkerService {
    static final int MSG_SCAN = 1;
    static final int MSG_PREPARE = 2;
    static final int MSG_CANCEL = 3;
    static final int MSG_RESULT = 4;
    static final int MSG_ABORT_PROCESS = 5;

    static final String KEY_OPERATION = "operation";
    static final String KEY_PROFILE = "profile";
    static final String KEY_SOURCE = "source";
    static final String KEY_OUTPUT = "output";
    static final String KEY_DIRECTORY = "directory";
    static final String KEY_TRANSACTION = "transaction";
    static final String KEY_EXPECTED = "expected";
    static final String KEY_STATUS = "status";
    static final String KEY_ERROR = "error";
    static final String KEY_SCAN = "scan";
    static final String KEY_INPUT = "input";
    static final String KEY_OUTPUT_RESULT = "output_result";
    static final String KEY_OPTIONAL_APPLIED = "optional_applied";
    static final String STATUS_OK = "OK";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String STATUS_FAILED = "FAILED";

    private NavigatorPatchWorkerService() { }
}
