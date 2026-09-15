package com.bydhud.app;

import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Action.CLOSE;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Action.NONE;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Action.RENDER;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Action.START;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Permission.DENIED;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Permission.GRANTED;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Permission.UNKNOWN;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.ServiceStart.SHOW;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.ServiceStart.STOP;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.ServiceStart.WAIT_FOR_PERMISSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DashboardWidgetLifecyclePolicyTest {
    @Test public void stickyColdStartWaitsForAnEmptyPermissionCache() {
        assertEquals(WAIT_FOR_PERMISSION,
                DashboardWidgetLifecyclePolicy.serviceStart(true, UNKNOWN, false, false, false));
        assertEquals(STOP,
                DashboardWidgetLifecyclePolicy.serviceStart(true, UNKNOWN, false, true, false));
        assertEquals(SHOW,
                DashboardWidgetLifecyclePolicy.serviceStart(true, GRANTED, false, false, false));
    }

    @Test public void grantBeforeDestructionQueuesExactlyOneReplacement() {
        DashboardWidgetLifecyclePolicy.State state = activeInstance();
        state = reconcile(state, true, GRANTED, false, false, true, true).state;
        state = reconcile(state, true, GRANTED, false, false, true, true).state;
        assertEquals(1L, state.pendingRestartInstance);

        DashboardWidgetLifecyclePolicy.Transition destroyed =
                DashboardWidgetLifecyclePolicy.serviceDestroyed(state, 1L, true, GRANTED, false);
        assertEquals(START, destroyed.action);
        assertTrue(destroyed.state.startRequested);
        assertEquals(0L, destroyed.state.activeInstance);

        DashboardWidgetLifecyclePolicy.State replacement =
                DashboardWidgetLifecyclePolicy.serviceCreated(destroyed.state, 2L);
        assertEquals(RENDER, reconcile(replacement, true, GRANTED, false, false, false, true).action);
    }

    @Test public void destructionBeforeGrantStartsFromTheLaterPermissionEvent() {
        DashboardWidgetLifecyclePolicy.Transition destroyed =
                DashboardWidgetLifecyclePolicy.serviceDestroyed(
                        activeInstance(), 1L, true, UNKNOWN, false);
        assertEquals(NONE, destroyed.action);

        DashboardWidgetLifecyclePolicy.Transition granted = reconcile(
                destroyed.state, true, GRANTED, false, false, false, true);
        assertEquals(START, granted.action);
        assertTrue(granted.state.startRequested);
    }

    @Test public void offHideShutdownAndRevocationCancelAQueuedReplacement() {
        assertCancellation(reconcile(activeInstance(), false, GRANTED, false, false, true, true));
        assertCancellation(reconcile(activeInstance(), false, GRANTED, false, false, true, true));
        assertCancellation(reconcile(activeInstance(), true, GRANTED, true, false, true, true));
        assertCancellation(reconcile(activeInstance(), true, DENIED, false, false, true, true));
    }

    @Test public void failedUnknownRefreshStopsWithoutALoopAndLaterGrantRetries() {
        DashboardWidgetLifecyclePolicy.Transition failed = reconcile(
                activeInstance(), true, UNKNOWN, false, true, false, false);
        assertEquals(CLOSE, failed.action);
        assertEquals(0L, failed.state.pendingRestartInstance);

        DashboardWidgetLifecyclePolicy.Transition destroyed =
                DashboardWidgetLifecyclePolicy.serviceDestroyed(
                        failed.state, 1L, true, UNKNOWN, false);
        assertEquals(NONE, destroyed.action);
        assertFalse(destroyed.state.startRequested);

        DashboardWidgetLifecyclePolicy.Transition granted = reconcile(
                DashboardWidgetLifecyclePolicy.allowRetry(destroyed.state),
                true, GRANTED, false, false, false, true);
        assertEquals(START, granted.action);
    }

    @Test public void attachmentFailureBlocksDestructionRestartUntilANormalEvent() {
        DashboardWidgetLifecyclePolicy.State queued = reconcile(
                activeInstance(), true, GRANTED, false, false, true, true).state;
        DashboardWidgetLifecyclePolicy.Transition failed =
                DashboardWidgetLifecyclePolicy.attachmentFailed(queued, 1L);
        assertEquals(CLOSE, failed.action);
        assertTrue(failed.state.restartBlocked);
        assertEquals(0L, failed.state.pendingRestartInstance);

        DashboardWidgetLifecyclePolicy.Transition destroyed =
                DashboardWidgetLifecyclePolicy.serviceDestroyed(
                        failed.state, 1L, true, GRANTED, false);
        assertEquals(NONE, destroyed.action);

        DashboardWidgetLifecyclePolicy.Transition retry = reconcile(
                DashboardWidgetLifecyclePolicy.allowRetry(destroyed.state),
                true, GRANTED, false, false, false, true);
        assertEquals(START, retry.action);
    }

    @Test public void oldInstanceDestructionCannotClearOrRestartANewerInstance() {
        DashboardWidgetLifecyclePolicy.State newer =
                DashboardWidgetLifecyclePolicy.serviceCreated(activeInstance(), 2L);
        DashboardWidgetLifecyclePolicy.Transition oldDestroyed =
                DashboardWidgetLifecyclePolicy.serviceDestroyed(newer, 1L, true, GRANTED, false);

        assertEquals(NONE, oldDestroyed.action);
        assertEquals(2L, oldDestroyed.state.activeInstance);
    }

    @Test public void pendingStartIsCancelledBeforeServiceCreation() {
        DashboardWidgetLifecyclePolicy.Transition admitted = reconcile(
                DashboardWidgetLifecyclePolicy.initialState(),
                true, GRANTED, false, false, false, true);
        assertEquals(START, admitted.action);

        DashboardWidgetLifecyclePolicy.Transition off = reconcile(
                admitted.state, false, GRANTED, false, false, false, true);
        assertEquals(CLOSE, off.action);
        assertFalse(off.state.startRequested);
    }

    @Test public void closingOrDeniedServicesNeverRender() {
        assertEquals(STOP,
                DashboardWidgetLifecyclePolicy.serviceStart(true, GRANTED, false, false, true));
        assertEquals(STOP,
                DashboardWidgetLifecyclePolicy.serviceStart(true, DENIED, false, false, false));
        assertEquals(SHOW,
                DashboardWidgetLifecyclePolicy.serviceStart(true, GRANTED, false, false, false));
    }

    private static DashboardWidgetLifecyclePolicy.State activeInstance() {
        return DashboardWidgetLifecyclePolicy.serviceCreated(
                DashboardWidgetLifecyclePolicy.initialState(), 1L);
    }

    private static DashboardWidgetLifecyclePolicy.Transition reconcile(
            DashboardWidgetLifecyclePolicy.State state, boolean visible,
            DashboardWidgetLifecyclePolicy.Permission permission, boolean shutdown,
            boolean refreshFailed, boolean closing, boolean allowRestart) {
        return DashboardWidgetLifecyclePolicy.reconcile(
                state, visible, permission, shutdown, refreshFailed, closing, allowRestart);
    }

    private static void assertCancellation(DashboardWidgetLifecyclePolicy.Transition transition) {
        assertEquals(CLOSE, transition.action);
        assertEquals(0L, transition.state.pendingRestartInstance);
    }
}
