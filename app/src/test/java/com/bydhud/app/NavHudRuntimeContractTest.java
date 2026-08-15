package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Behavioral guards for route ownership, observer lifecycle and teardown tokens. */
public final class NavHudRuntimeContractTest {
    @Test
    public void GmapsProtocolAcceptsLegacyV3AndFencesTokenizedV3() {
        assertTrue(GMapsDirectChannel.acceptsProtocolMessageForTest(3, "new", ""));
        assertTrue(GMapsDirectChannel.acceptsProtocolMessageForTest(3, "new", "new"));
        assertFalse(GMapsDirectChannel.acceptsProtocolMessageForTest(3, "new", "old"));
        assertFalse(GMapsDirectChannel.acceptsProtocolMessageForTest(2, "new", "new"));
    }

    @Test
    public void TbtFramesDoNotStealOwnershipWithoutAStartOrHudPriority() {
        assertTrue(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                false, false, false, false, false));
        assertTrue(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, true, false, false, false));
        assertTrue(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, false, true, false, false));
        assertTrue(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, false, false, true, false));
        assertFalse(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, false, false, false, false));
        assertFalse(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, false, false, true, true));
    }

    @Test
    public void WazeObserverIsRouteGatedButRebindsAfterChannelLoss() {
        assertFalse(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, false, false, false));
        assertTrue(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, true, false, false));
        assertTrue(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, true, true, false));
        assertFalse(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, true, true, true));
    }

    @Test
    public void HudOwnershipTransitionPromotesObserverWithoutStoppingChannel() {
        assertTrue(NavHudLiveSender.shouldPromoteTbtObserverForHudForTest(true, true));
        assertFalse(NavHudLiveSender.shouldPromoteTbtObserverForHudForTest(true, false));
        assertFalse(NavHudLiveSender.shouldPromoteTbtObserverForHudForTest(false, true));
    }

    @Test
    public void DirectPromotionRequiresClaimedCurrentSessionAndHudPriorityForFocus() {
        assertTrue(NavHudLiveSender.acceptsDirectPromotionForTest(
                true, true, false, "com.waze", 8L, "com.waze", 8L));
        assertTrue(NavHudLiveSender.acceptsDirectPromotionForTest(
                true, true, false, GMapsDirectChannel.OWNER_PACKAGE, 9L,
                GMapsDirectChannel.OWNER_PACKAGE, 9L));
        assertFalse(NavHudLiveSender.acceptsDirectPromotionForTest(
                false, true, false, "com.waze", 8L, "com.waze", 8L));
        assertFalse(NavHudLiveSender.acceptsDirectPromotionForTest(
                true, true, false, "com.waze", 9L, "com.waze", 8L));
        assertFalse(NavHudLiveSender.acceptsDirectPromotionForTest(
                true, true, true, "com.waze", 8L, "com.waze", 8L));

        assertTrue(NavHudLiveSender.shouldRequestDashboardForDirectRouteForTest(true, true));
        assertFalse(NavHudLiveSender.shouldRequestDashboardForDirectRouteForTest(false, true));
        assertFalse(NavHudLiveSender.shouldRequestDashboardForDirectRouteForTest(true, false));
    }

    @Test
    public void HudSwitchRetainsOnlyAnActiveRouteWhenTbtWithoutHudIsEnabled() {
        assertTrue(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                true, true, true));
        assertFalse(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                true, true, false));
        assertFalse(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                true, false, true));
        assertFalse(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                false, true, true));
    }

    @Test
    public void NoHudTbtDoesNotRequireLogOnlySelection() {
        assertTrue(NavHudLiveSender.shouldObserveTbtWithoutHudForTest(
                true, false, true, false));
        assertTrue(NavHudLiveSender.shouldObserveTbtWithoutHudForTest(
                true, false, false, true));
        assertFalse(NavHudLiveSender.shouldObserveTbtWithoutHudForTest(
                true, false, false, false));
        assertFalse(NavHudLiveSender.shouldObserveTbtWithoutHudForTest(
                true, true, true, true));
    }

    @Test
    public void SurfaceFramesRequireBothSourceAndPublisherGenerations() {
        assertTrue(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, true, true, true, true));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, false, true, true, true));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, true, false, true, true));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, false, true, true, true, true));
    }

    @Test
    public void GMapsEndCallbackMustBeCurrentEvenForObserver() {
        assertTrue(NavHudLiveSender.acceptsGMapsTeardownForTest(true, 8L, 9L));
        assertFalse(NavHudLiveSender.acceptsGMapsTeardownForTest(false, 8L, 9L));
        assertFalse(NavHudLiveSender.acceptsGMapsTeardownForTest(true, 8L, 8L));
    }

    @Test
    public void WazeAcceptsDirectFenceOrAuthoritativeLifecycleTerminal() {
        assertTrue(NavHudLiveSender.acceptsWazeTeardownForTest(8, 9));
        assertTrue(NavHudLiveSender.acceptsWazeTeardownForTest(8, 8));
        assertFalse(NavHudLiveSender.acceptsWazeTeardownForTest(8, 10));
    }

    @Test
    public void DelayedHudClearMustBelongToTheCurrentSenderGeneration() {
        assertTrue(NavHudLiveSender.acceptsHudStopCallbackForTest(8L, 8L));
        assertFalse(NavHudLiveSender.acceptsHudStopCallbackForTest(8L, 9L));
        assertEquals(9L, NavHudLiveSender.nextObserverLifecycleTokenForTest(8L));
        assertFalse(NavHudLiveSender.acceptsHudStopCallbackForTest(
                8L, NavHudLiveSender.nextObserverLifecycleTokenForTest(8L)));
    }

    @Test
    public void PackageReinitTerminatesTheSelectedOrObservedDirectOwnerBeforeReset()
            throws IOException {
        assertEquals("com.waze", NavHudLiveSender.packageReinitOutputOwnerForTest(
                true, "com.waze", true, GMapsDirectChannel.PACKAGE_NAME));
        assertEquals("com.waze", NavHudLiveSender.packageReinitOutputOwnerForTest(
                false, "", true, "com.waze"));
        assertEquals("", NavHudLiveSender.packageReinitOutputOwnerForTest(
                false, "", false, "com.waze"));

        String sender = source("NavHudLiveSender.java");
        int resetStart = sender.indexOf("private void resetRuntimeAfterPackageReplace");
        int resetEnd = sender.indexOf("\n    //updates shared state here", resetStart);
        assertTrue(resetStart >= 0 && resetEnd > resetStart);
        String reset = sender.substring(resetStart, resetEnd);
        assertTrue(reset.indexOf("hudOutput.endNavigationOutput")
                < reset.indexOf("completeRuntimeResetAfterPackageReplace"));
        assertTrue(reset.indexOf("tbtPublisher.endRoute")
                < reset.indexOf("wazeDirectChannel.hardStop"));
        assertTrue(reset.indexOf("wazeDirectChannel.hardStop")
                < reset.indexOf("hudOutput.resetTransport"));
    }

    @Test
    public void UnsupportedManualTbtMappingIsBlankButVerifiedMappingIsPreserved() {
        assertEquals(11, NavHudLiveSender.manualTbtManeuverForTest(11));
        assertEquals(99, NavHudLiveSender.manualTbtManeuverForTest(4));
        assertEquals(99, NavHudLiveSender.manualTbtManeuverForTest(50));
    }

    @Test
    public void OldTeardownWorkerCannotPublishStatusFourForNewRoute() {
        assertTrue(NavHudLiveSender.shouldApplyTeardownForTest(
                8L, 8L, false, true, true));
        assertFalse(NavHudLiveSender.shouldApplyTeardownForTest(
                8L, 9L, false, true, true));
        assertFalse(NavHudLiveSender.shouldApplyTeardownForTest(
                8L, 8L, true, true, true));
        assertFalse(NavHudLiveSender.shouldApplyTeardownForTest(
                8L, 8L, false, true, false));
    }

    @Test
    public void TeardownRetriesOnlyWhileAuthorizedIdleLifecycleIsCurrent() {
        assertTrue(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 8L, false, true, true, false, false));
        assertFalse(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 9L, false, true, true, false, false));
        assertFalse(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 8L, true, true, true, false, false));
        assertFalse(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 8L, false, false, false, false, false));
        assertFalse(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 8L, false, true, true, true, true));
    }

    @Test
    public void FirstFrameStartsAReplacementLifecycleWhenStartCallbackWasMissing() {
        assertTrue(NavHudLiveSender.shouldAdvanceTbtLifecycleForTest(false));
        assertFalse(NavHudLiveSender.shouldAdvanceTbtLifecycleForTest(true));
    }

    @Test
    public void NewGMapsRouteClearsPriorSpeedEvenWhenFrameArrivesBeforeStart() {
        assertTrue(NavHudLiveSender.shouldClearGMapsSpeedLimitOnDirectStart("start"));
        assertTrue(NavHudLiveSender.shouldClearGMapsSpeedLimitOnDirectStart(
                "frame-missed-start"));
        assertFalse(NavHudLiveSender.shouldClearGMapsSpeedLimitOnDirectStart(
                "first-frame"));
        assertFalse(NavHudLiveSender.shouldClearGMapsSpeedLimitOnDirectStart(
                "start-replay"));
    }

    @Test
    public void TbtRuntimeKeepsGMapsRegistrationAliveWithoutWazeEarlyBind() {
        assertTrue(HudRuntimeSupervisor.shouldKeepTbtRuntimeForTest(
                true, true, false, false, false));
        assertFalse(HudRuntimeSupervisor.shouldKeepTbtRuntimeForTest(
                true, false, false, false, false));
        assertTrue(HudRuntimeSupervisor.shouldKeepTbtRuntimeForTest(
                true, false, false, true, false));
        assertFalse(HudRuntimeSupervisor.shouldKeepTbtRuntimeForTest(
                true, true, true, false, false));
    }

    @Test
    public void RemainingActiveRouteTakesTbtAfterOwnerEnds() {
        assertTrue(NavHudLiveSender.selectRemainingTbtOwnerForTest(
                GMapsDirectChannel.PACKAGE_NAME, true, false, "com.waze", 10L, 20L)
                .equals("com.waze"));
        assertTrue(NavHudLiveSender.selectRemainingTbtOwnerForTest(
                "com.waze", false, true, GMapsDirectChannel.PACKAGE_NAME, 10L, 20L)
                .equals(GMapsDirectChannel.PACKAGE_NAME));
        assertTrue(NavHudLiveSender.selectRemainingTbtOwnerForTest(
                "com.waze", false, false, GMapsDirectChannel.PACKAGE_NAME,
                10L, 20L).isEmpty());
        assertTrue(NavHudLiveSender.selectRemainingTbtOwnerForTest(
                "", true, true, "", 10L, 20L)
                .equals(GMapsDirectChannel.PACKAGE_NAME));
    }

    @Test
    public void ManualStopRestoresTheCurrentSurfaceFrameBeforeClusterFallback() {
        DirectTbtFrame surface = DirectTbtFrame.empty();
        DirectTbtFrame cluster = DirectTbtFrame.empty();

        assertTrue(NavHudLiveSender.selectWazeRestoreFrameForTest(
                true, surface, true, cluster, true) == surface);
        assertTrue(NavHudLiveSender.selectWazeRestoreFrameForTest(
                true, surface, false, cluster, true) == cluster);
        assertTrue(NavHudLiveSender.selectWazeRestoreFrameForTest(
                false, surface, true, cluster, true) == cluster);
        assertTrue(NavHudLiveSender.selectWazeRestoreFrameForTest(
                true, surface, false, cluster, false) == null);
    }

    @Test
    public void AdministrativeTbtStopUsesPublisherOwnerGeneration() {
        assertTrue(NavHudLiveSender.tbtOwnerGenerationForTest(
                "com.waze", "com.waze", 17L, 19L) == 17L);
        assertTrue(NavHudLiveSender.tbtOwnerGenerationForTest(
                "com.waze", GMapsDirectChannel.PACKAGE_NAME, 17L, 19L) == 19L);
    }

    @Test
    public void FinalClearRecoveryHasABoundedTimeout() {
        assertFalse(HudOutputCoordinator.finalClearTimedOutForTest(0L, 10_000L));
        assertFalse(HudOutputCoordinator.finalClearTimedOutForTest(1_000L, 6_999L));
        assertTrue(HudOutputCoordinator.finalClearTimedOutForTest(1_000L, 7_000L));
    }

    @Test
    public void AdministrativeStopCannotClearAnotherDirectOwner() {
        assertTrue(HudOutputCoordinator.shouldClearForAdministrativeStopForTest(
                "com.waze", "com.waze"));
        assertTrue(HudOutputCoordinator.shouldClearForAdministrativeStopForTest(
                "", "com.waze"));
        assertFalse(HudOutputCoordinator.shouldClearForAdministrativeStopForTest(
                GMapsDirectChannel.PACKAGE_NAME, "com.waze"));
    }

    @Test
    public void DirectLifecycleMatrixKeepsRouteEvidenceAndRoadInfoOwnershipSeparate()
            throws IOException {
        // Route-before-app and route-during-startup both use the same bridge snapshot gate.
        assertTrue(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, true, true, false));
        assertTrue(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, true, false, true));

        // HUD OFF retains an active direct route for TBT; HUD ON can promote that route.
        assertTrue(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                true, true, true));
        assertTrue(NavHudLiveSender.acceptsDirectPromotionForTest(
                true, true, false, "com.waze", 7L, "com.waze", 7L));
        assertTrue(NavHudLiveSender.acceptsDirectPromotionForTest(
                true, true, false, GMapsDirectChannel.PACKAGE_NAME, 8L,
                GMapsDirectChannel.PACKAGE_NAME, 8L));

        // Waze<->GMaps transfer keeps the explicitly selected RoadInfo source when it lives;
        // a non-selected terminal callback may only restore the surviving TBT route.
        assertEquals("com.waze", NavHudLiveSender.selectRemainingTbtOwnerForTest(
                GMapsDirectChannel.PACKAGE_NAME, true, false, "com.waze", 10L, 20L));
        assertEquals(GMapsDirectChannel.PACKAGE_NAME,
                NavHudLiveSender.selectRemainingTbtOwnerForTest(
                        "com.waze", false, true, GMapsDirectChannel.PACKAGE_NAME, 10L, 20L));
        assertEquals("", NavHudLiveSender.selectRemainingTbtOwnerForTest(
                "com.waze", false, false, GMapsDirectChannel.PACKAGE_NAME, 10L, 20L));

        // A stale teardown/session callback is rejected in both observer and owner paths.
        assertFalse(NavHudLiveSender.acceptsGMapsTeardownForTest(false, 8L, 9L));
        assertFalse(NavHudLiveSender.acceptsGMapsTeardownForTest(true, 8L, 8L));

        String sender = source("NavHudLiveSender.java");
        int methodStart = sender.indexOf(
                "private boolean ensureGMapsRegisteredWhenTransportReady");
        int methodEnd = sender.indexOf("\n    private static String laneDirections", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart);
        String registration = sender.substring(methodStart, methodEnd);
        assertFalse(registration.contains("hudOutput.isBound"));
        assertTrue(registration.contains("gmapsDirectChannel.ensureRegistered(reason)"));
    }

    @Test
    public void ProducerReplacementClearPrecedesTheCurrentGenerationGate()
            throws IOException {
        String sender = source("NavHudLiveSender.java");
        int methodStart = sender.indexOf(
                "private void onGMapsDirectHandshakeAvailable");
        int methodEnd = sender.indexOf(
                "\n    private void onGMapsDirectHandshakeUnavailable", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart);
        String handshake = sender.substring(methodStart, methodEnd);

        int replacementClear = handshake.indexOf("clearDirectFrameForSupersedingSession");
        int currentGenerationGate = handshake.indexOf("isCurrentGMapsDirectCallback");
        assertTrue(replacementClear >= 0);
        assertTrue(currentGenerationGate > replacementClear);
        assertFalse(handshake.contains("renewDirectLease"));

        int routeStart = sender.indexOf("private void onGMapsDirectNavigationStarted");
        int routeStartEnd = sender.indexOf("\n    private void onGMapsDirectFrame", routeStart);
        assertTrue(routeStart >= 0 && routeStartEnd > routeStart);
        String start = sender.substring(routeStart, routeStartEnd);
        int routeClear = start.indexOf("clearDirectFrameForSupersedingSession");
        int routeCurrentGate = start.indexOf("isCurrentGMapsDirectCallback");
        assertTrue(routeClear >= 0);
        assertTrue(routeCurrentGate > routeClear);
    }

    @Test
    public void TextModeRepublishesOneProjectedFrameWithoutRouteLifecycleMutation()
            throws IOException {
        String sender = source("NavHudLiveSender.java");
        int changeStart = sender.indexOf("private void onOutputPreferenceChangedOnMain");
        int changeEnd = sender.indexOf("\n    private boolean hasLatestDirectFrame", changeStart);
        assertTrue(changeStart >= 0 && changeEnd > changeStart);
        String change = sender.substring(changeStart, changeEnd);
        assertTrue(change.contains("HudPrefs.KEY_TEXT_TRANSLITERATION"));
        assertTrue(change.contains("publishManualOnWorker"));
        assertTrue(change.contains("republishLatestDirectFrame"));
        assertTrue(change.contains("sendLatestIfReady"));
        assertFalse(change.contains("endNavigationOutput"));
        assertFalse(change.contains("selectNavigationSource"));
        assertFalse(change.contains("resetTransport"));

        int effectiveStart = sender.indexOf("private DirectTbtFrame effectiveDirectFrame");
        int effectiveEnd = sender.indexOf("\n    private void selectRemainingTbtRoute", effectiveStart);
        String effective = sender.substring(effectiveStart, effectiveEnd);
        assertTrue(effective.contains("HudTextTransliterator.transformFrame"));

        assertProjectedBeforeBothOutputs(sender, "private void onWazeDirectFrame",
                "\n    private void logWazeDirectTiming");
        assertProjectedBeforeBothOutputs(sender, "private void onGMapsDirectFrame",
                "\n    private void logGMapsDirectTiming");
        assertAlertClearsProjectBeforeRepublish(sender);
        assertTrue(sender.contains("rawRoad=\\\""));
        assertTrue(sender.contains("sentRoad=\\\""));
    }

    private static void assertProjectedBeforeBothOutputs(
            String source, String methodMarker, String endMarker) {
        int start = source.indexOf(methodMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        int projected = method.indexOf("outputFrame = effectiveDirectFrame(outputFrame)");
        int tbt = method.indexOf("tbtPublisher.publishFrame(");
        int roadInfo = method.indexOf("hudOutput.publishDirect(");
        assertTrue(projected >= 0);
        assertTrue(tbt > projected);
        assertTrue(roadInfo > tbt);
    }

    private static void assertAlertClearsProjectBeforeRepublish(String source) {
        int searchFrom = 0;
        int checked = 0;
        while (true) {
            int clear = source.indexOf("hudOutput.clearDirectAlertAndRepublish(", searchFrom);
            if (clear < 0) break;
            int alertCallback = source.lastIndexOf("public void onAlertCleared", clear);
            int projection = source.lastIndexOf(
                    "outputFrame = effectiveDirectFrame(outputFrame)", clear);
            assertTrue(alertCallback >= 0);
            assertTrue(projection > alertCallback);
            checked++;
            searchFrom = clear + 1;
        }
        assertEquals(2, checked);
    }

    private static String source(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/")
                .resolve(name).normalize();
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/")
                    .resolve(name).normalize();
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
