package com.bydhud.app;

//Keeps retained projection lifecycle decisions testable without Android runtime objects.
final class ProjectionLifecyclePolicy {
    enum FailedRequestTransition {
        KEEP_VISIBLE_OWNER,
        RETAIN_IDLE,
        RECOVER_INVALID_GEOMETRY
    }

    private ProjectionLifecyclePolicy() {}

    static boolean canReuseResources(
            boolean rootAttached,
            boolean surfaceValid,
            boolean displayValid,
            boolean geometryValid) {
        return rootAttached && surfaceValid && displayValid && geometryValid;
    }

    static boolean canCompleteReturn(
            boolean projectionRequested,
            String activePackage,
            int currentGeneration,
            String returnedPackage,
            int returnGeneration) {
        return projectionRequested
                && currentGeneration == returnGeneration
                && safe(activePackage).equals(safe(returnedPackage));
    }

    static boolean canReveal(
            boolean projectionRequested,
            boolean placementReady,
            boolean resourcesValid,
            String activePackage,
            int currentDisplayId,
            long currentOwnerToken,
            String confirmedPackage,
            int expectedDisplayId,
            long expectedOwnerToken) {
        return projectionRequested
                && placementReady
                && resourcesValid
                && expectedDisplayId > 0
                && expectedDisplayId == currentDisplayId
                && expectedOwnerToken > 0L
                && expectedOwnerToken == currentOwnerToken
                && safe(activePackage).equals(safe(confirmedPackage));
    }

    static boolean canReleaseIdleForShutdown(
            boolean shutdownCurrent,
            boolean projectionRequested,
            String activePackage,
            String pendingPackage,
            long ownerToken) {
        return shutdownCurrent
                && !projectionRequested
                && safe(activePackage).isEmpty()
                && safe(pendingPackage).isEmpty()
                && ownerToken == 0L;
    }

    static boolean requestedGeometrySucceeded(
            boolean resizeSucceeded,
            boolean geometryValid,
            int width,
            int height,
            int bufferWidth,
            int bufferHeight,
            int left,
            int top,
            int expectedWidth,
            int expectedHeight,
            int expectedBufferWidth,
            int expectedBufferHeight,
            int expectedLeft,
            int expectedTop) {
        return resizeSucceeded
                && geometryValid
                && width == expectedWidth
                && height == expectedHeight
                && bufferWidth == expectedBufferWidth
                && bufferHeight == expectedBufferHeight
                && left == expectedLeft
                && top == expectedTop;
    }

    static FailedRequestTransition failedRequestTransition(
            boolean geometryValid, boolean preserveVisibleOwner) {
        if (!geometryValid) return FailedRequestTransition.RECOVER_INVALID_GEOMETRY;
        return preserveVisibleOwner
                ? FailedRequestTransition.KEEP_VISIBLE_OWNER
                : FailedRequestTransition.RETAIN_IDLE;
    }

    static boolean matchesInvalidRecoveryOwner(
            boolean projectionRequested,
            boolean geometryValid,
            String currentPackage,
            int currentGeneration,
            long currentOwnerToken,
            String expectedPackage,
            int expectedGeneration,
            long expectedOwnerToken) {
        return projectionRequested
                && !geometryValid
                && currentGeneration == expectedGeneration
                && currentOwnerToken > 0L
                && currentOwnerToken == expectedOwnerToken
                && safe(currentPackage).equals(safe(expectedPackage));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
