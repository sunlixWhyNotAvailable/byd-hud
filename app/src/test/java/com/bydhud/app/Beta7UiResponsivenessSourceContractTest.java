package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Beta7UiResponsivenessSourceContractTest {
    @Test
    public void expensiveUiStateUsesSharedCachesAndResumeRevalidatesAfterRendering()
            throws IOException {
        String source = source("MainActivity.java");
        String onResume = between(source, "protected void onResume()", "protected void onPause()");
        String runtimeScan = between(source, "static void requestRuntimeUiStateRefresh(",
                "static void requestPatchUiStateRefresh(");
        String patchScan = between(source, "static void requestPatchUiStateRefresh(",
                "private static Map<String, String> scanInstalledAppVersions");
        String assetScan = between(source, "static void requestNavigatorAssetUiStateRefresh(",
                "private static Map<String, String> scanInstalledAppVersions");
        String composeSnapshot = between(source, "public ComposeSnapshot composeSnapshot()",
                "public void composeStartNavigatorAssetDownload(");

        assertTrue(source.contains("private static volatile StorageCacheState storageCacheState"));
        assertTrue(source.contains("private static final Map<String, String> APP_LABEL_CACHE"));
        assertTrue(source.contains("private static final Map<String, Boolean> INSTALLED_PACKAGE_CACHE"));
        assertTrue(source.contains("private static volatile NavRuntimePermissionStatus"));
        assertFalse(onResume.contains("invalidatePackageMetadataCache();"));
        assertFalse(onResume.contains("invalidateNavRuntimePermissionStatus();"));
        assertTrue(onResume.indexOf("invalidateComposeSnapshot();")
                < onResume.indexOf("requestRuntimeUiStateRefresh("));
        assertTrue(onResume.contains("invalidateComposeSnapshot();"));
        assertTrue(onResume.contains(
                "requestRuntimeUiStateRefresh(this, true, \"activity-resume\")"));
        assertTrue(runtimeScan.contains("NavRuntimePermissionStatus.check(appContext)"));
        assertFalse(runtimeScan.contains("requestStorageRefresh("));
        assertFalse(runtimeScan.contains("NavigatorPackageInstaller.reconcile(appContext)"));
        assertFalse(runtimeScan.contains("scanNavigatorPatchRows(appContext)"));
        assertTrue(patchScan.contains("NavigatorPackageInstaller.reconcile(appContext)"));
        assertTrue(patchScan.contains(
                "navigatorAssetSnapshots = NavigatorAssetManager.snapshots(appContext, false)"));
        assertTrue(patchScan.contains("scanNavigatorPatchRows(appContext)"));
        assertTrue(assetScan.contains(
                "NavigatorAssetManager.snapshots(appContext, false)"));
        assertFalse(assetScan.contains("NavigatorAssetManager.reconcile(appContext)"));
        assertFalse(assetScan.contains("refreshInstalledMatches(appContext)"));
        assertFalse(assetScan.contains("scanNavigatorPatchRows(appContext)"));
        assertTrue(assetScan.contains("publishSharedUiStateChange()"));
        assertTrue(composeSnapshot.contains("localizedNavigatorAssetSnapshots(uaLanguage)"));
        assertFalse(composeSnapshot.contains("NavigatorAssetManager.snapshots("));
    }

    @Test
    public void forcedRuntimeScanCollisionQueuesOneFollowUpAfterRelease() throws IOException {
        String source = source("MainActivity.java");
        String runtimeScan = between(source, "static void requestRuntimeUiStateRefresh(",
                "static void requestPatchUiStateRefresh(");
        String collision = between(runtimeScan,
                "if (!APP_SCAN_IN_PROGRESS.compareAndSet(false, true)) {",
                "appScanStatus = \"scanning\";");
        String completion = between(runtimeScan, "} finally {",
                "}, \"bydhud-app-scan\").start();");

        assertTrue(source.contains(
                "private static final AtomicBoolean APP_FORCE_REFRESH_PENDING"));
        assertTrue(collision.contains("if (force) {"));
        assertTrue(collision.contains("APP_FORCE_REFRESH_PENDING.set(true);"));
        assertTrue(occurrences(collision, "APP_FORCE_REFRESH_PENDING.set(true);") == 1);
        int release = completion.indexOf("APP_SCAN_IN_PROGRESS.set(false);");
        int followUp = completion.indexOf(
                "APP_FORCE_REFRESH_PENDING.getAndSet(false)", release);
        int followUpRequest = completion.indexOf(
                "requestRuntimeUiStateRefresh(appContext, true, \"pending-force\")",
                followUp);
        assertTrue(release >= 0 && followUp > release && followUpRequest > followUp);
        assertTrue(occurrences(runtimeScan,
                "requestRuntimeUiStateRefresh(appContext, true, \"pending-force\")") == 1);
    }

    @Test
    public void bootstrapRefreshesAllDomainsWhileHeartbeatRefreshesRuntimeOnly()
            throws IOException {
        String activity = source("MainActivity.java");
        String bootstrap = between(activity, "static void requestInitialUiStateRefresh(",
                "static void requestStorageRefreshAfterMutation(");
        String service = source("HudRuntimeService.java");
        String onCreate = between(service, "public void onCreate()", "public int onStartCommand(");
        String heartbeat = between(service, "private final Runnable heartbeatRunnable",
                "static void startPersistent(");
        String compose = source("BydHudRuntimeCompose.kt");
        String polling = between(compose,
                "LaunchedEffect(appInForeground)",
                "LaunchedEffect(storageDeleteBusy, storageDeleteQueue)");

        assertTrue(bootstrap.contains("requestStorageRefresh(context, false"));
        assertTrue(bootstrap.contains("requestRuntimeUiStateRefresh(context, false"));
        assertTrue(bootstrap.contains("requestPatchUiStateRefresh(context, false"));
        assertTrue(onCreate.contains("requestInitialUiRefresh(\"runtime-create\")"));
        assertTrue(heartbeat.contains("requestRuntimeUiRefresh(false, \"runtime-heartbeat\")"));
        assertFalse(heartbeat.contains("requestInitialUiRefresh"));
        assertFalse(polling.contains("refresh()"));
        assertTrue(polling.contains("FOREGROUND_UI_REFRESH_REQUEST_MS"));
        assertTrue(polling.contains(
                "composeRequestRuntimeUiStateRefresh(false, \"foreground-periodic\")"));
        assertTrue(polling.contains("composeRequestNavigatorAssetUiStateRefresh("));
        assertTrue(polling.contains("foreground-asset-periodic"));
        assertTrue(polling.contains("assetCacheMissing"));
        assertTrue(polling.contains("foreground-asset-bootstrap"));
        assertFalse(polling.contains("composeRequestStorageRefresh"));
    }

    @Test
    public void allTabsAreCacheFirstWhileAppRowsStayVirtualized() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String apps = between(source, "private fun AppsTab(", "private fun AppRow(");
        String tabEffect = between(source, "LaunchedEffect(selectedTab)",
                "DisposableEffect(activity)");
        String refresh = between(source, "fun refresh()", "fun runAction(");
        String tabRefresh = between(source, "fun requestTabStateRefresh(", "fun runAction(");
        String lifecycle = between(source, "DisposableEffect(activity)",
                "LaunchedEffect(appInForeground)");
        String lifecycleObserver = between(lifecycle, "val observer =", "activity.lifecycle.addObserver");

        assertTrue(source.contains("LaunchedEffect(appInForeground)"));
        assertFalse(source.contains("selectedTab != RuntimeTab.Apps || !appInForeground"));
        assertFalse(tabEffect.contains("refresh()"));
        assertTrue(tabEffect.contains("requestTabStateRefresh(selectedTab, reason)"));
        assertFalse(tabEffect.contains("composeRequestUiStateRefresh("));
        assertTrue(tabRefresh.contains("composeRequestRuntimeUiStateRefresh(false, reason)"));
        assertFalse(tabRefresh.contains("RuntimeTab.Logs"));
        assertTrue(tabRefresh.contains("RuntimeTab.Options,"));
        assertTrue(tabRefresh.contains("RuntimeTab.Manual -> Unit"));
        assertFalse(tabRefresh.contains("RuntimeTab.Logs -> activity.composeRequestRuntimeUiStateRefresh"));
        assertTrue(tabRefresh.contains("composeRequestStorageRefresh(false)"));
        assertTrue(tabRefresh.contains("composeRequestPatchUiStateRefresh(reason)"));
        assertFalse(lifecycleObserver.contains("refresh()"));
        assertTrue(lifecycleObserver.contains("RuntimeTab.Storage"));
        assertTrue(lifecycleObserver.contains("composeRequestStorageRefresh(false)"));
        assertTrue(lifecycleObserver.contains("composeRequestPatchUiStateRefresh("));
        assertTrue(lifecycleObserver.contains("false,"));
        assertTrue(lifecycleObserver.contains("activity-resume-apps"));
        assertFalse(lifecycleObserver.contains("requestTabStateRefresh("));
        assertTrue(lifecycleObserver.contains("latestSelectedTab"));
        assertTrue(refresh.contains("if (snapshot != refreshed) snapshot = refreshed"));
        assertTrue(apps.contains("count = snapshot.supportedApps.size"));
        assertTrue(apps.contains("snapshot.supportedApps[index].packageName"));
        assertTrue(apps.contains("count = snapshot.allApps.size"));
        assertTrue(apps.contains("snapshot.allApps[index].packageName"));
        assertFalse(apps.contains("supportedApps.forEachIndexed"));
        assertFalse(apps.contains("allApps.forEachIndexed"));
    }

    @Test
    public void storageMutationsRefreshSharedCacheWithoutAnActivityTabDependency()
            throws IOException {
        String activity = source("MainActivity.java");
        String share = between(activity, "public String composeShareStorageDays(",
                "public ComposeStorageShareSummary composeDescribeStorageShareDays");
        String sentryShare = between(activity,
                "public ComposeSentryUploadResult composeUploadStorageDaysToSentry(",
                "public String composeShareVehicleConfiguration()");
        String storage = source("NavigationLogStorage.java");
        String retention = between(storage, "private static void runScheduledRetention(",
                "private static void runNavCaptureRetention(");

        assertTrue(activity.contains("requestStorageRefreshAfterMutation(this, \"delete\")"));
        assertTrue(share.contains("requestStorageRefreshAfterMutation(this, \"share\")"));
        assertTrue(sentryShare.contains(
                "requestStorageRefreshAfterMutation(this, \"sentry-share\")"));
        assertTrue(storage.contains("app, \"retired-day-cleanup\")"));
        assertTrue(retention.contains("request.onComplete == null"));
        assertTrue(retention.contains("requestStorageRefreshAfterMutation("));
    }

    @Test
    public void storageSnapshotKeepsWriteProbeExclusiveButScansUnderReadLock()
            throws IOException {
        String storage = source("NavigationLogStorage.java");
        String snapshot = between(storage, "static StorageSnapshot snapshotAccessibleStorage(",
                "//Caller stops active Waze/logcat first;");

        assertTrue(snapshot.contains(
                "List<StorageRoot> roots = withWriteLock(() -> accessibleRootsLocked(app));"));
        assertTrue(snapshot.contains("return withReadLock(() -> snapshotAccessibleStorageLocked("));
        assertTrue(snapshot.contains(
                "roots, activeDay, activeLogcatDay, activeCropDay"));
        assertFalse(snapshot.contains(
                "withWriteLock(() -> snapshotAccessibleStorageLocked("));
    }

    @Test
    public void immutableUiCopyModelsAreRememberedWithoutChangingFeedbackTiming()
            throws IOException {
        String source = source("BydHudRuntimeCompose.kt");

        assertTrue(source.contains("val palette = remember(snapshot.darkTheme)"));
        assertTrue(source.contains("val copy = remember(snapshot.uaLanguage)"));
        assertTrue(source.contains("val shareCopy = remember(copy.language)"));
        assertTrue(source.contains("VISUAL_PRESS_BEFORE_ACTION_MS = 90L"));
        assertTrue(source.contains("SWITCH_CENTER_BEFORE_ACTION_MS = 120L"));
    }

    @Test
    public void activityDestructionDetachesUiWithoutStoppingGlobalRuntime()
            throws IOException {
        String source = source("MainActivity.java");
        String destroy = between(source, "protected void onDestroy()", "public void onBackPressed()");

        assertTrue(destroy.contains("NavAppDisplayController.get(this).setListener(null)"));
        assertTrue(destroy.contains("destroy without explicit exit: keep sender state"));
        assertFalse(destroy.contains("stopImmediately("));
    }

    @Test
    public void patchStatusKeepsAllComponentPillsAfterSuccessfulPatch() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");

        assertTrue(source.contains("componentStates.chunked(2).forEach"));
        assertFalse(source.contains("val allPatched = componentStates"));
        assertFalse(source.contains("StatusChip(copy.patchPatched, ChipKind.Green"));
    }

    @Test
    public void assetProgressAndRecoveryRetainDurableMaterialUntilVerified() throws IOException {
        String source = source("NavigatorAssetManager.java");
        String restore = between(source, "static void restore(Context context", "static void reconcile(Context context)");
        String reconcileInstall = between(source, "private static void reconcileInstall(", "private static void reconcileDownload(");
        String verifyBackup = between(source, "private static void verifyBackupInstalledAsync(", "private static void reconcileCatalogRevision(");
        String reconcileDownload = between(source, "private static void reconcileDownload(", "private static void validateDownloadedAsync(");
        String cleanup = between(source, "private static void clearTransaction(", "private static boolean isMonolithicApk(");

        assertTrue(restore.contains("NavigatorPatchStore.claimAssetRecoveryTransaction"));
        assertFalse(restore.contains("NavigatorPatchStore.claimRecovery"));
        assertFalse(restore.contains("NavigatorPatchStore.claim(context"));
        assertTrue(restore.indexOf(".putString(key(asset, \"phase\"), PHASE_RECOVERY)")
                < restore.indexOf("NavigatorPackageInstaller.beginRestore"));
        assertTrue(reconcileInstall.contains("Navigator replacement verification failed"));
        assertTrue(reconcileInstall.contains("verifyBackupInstalledAsync"));
        assertFalse(reconcileInstall.contains("currentArtifactFingerprint"));
        assertTrue(verifyBackup.contains("ACTIVE_INSTALL_VERIFICATIONS"));
        assertTrue(verifyBackup.contains("new Thread("));
        assertTrue(verifyBackup.contains("currentArtifactFingerprint"));
        assertTrue(verifyBackup.contains("NavigatorPatchStore.installedIdentity"));
        assertTrue(source.contains("backup_fingerprint"));
        assertTrue(source.contains("currentArtifactFingerprint(context, asset.profile)"));
        assertTrue(reconcileDownload.contains("putString(key(asset, \"progress\"), progress).apply()"));
        assertTrue(occurrences(reconcileInstall, "clearTransaction(context, asset);") == 1);
        assertFalse(between(reconcileInstall, "if (PHASE_UNINSTALL.equals(phase))",
                "if (PHASE_INSTALL.equals(phase))").contains("clearTransaction(context, asset)"));
        assertTrue(cleanup.contains("asset.fileName"));
    }

    @Test
    public void recoveryReuseRequiresExactAssetTransactionAndFingerprint() throws IOException {
        String manager = source("NavigatorAssetManager.java");
        String store = source("NavigatorPatchStore.java");
        String restore = between(manager, "static void restore(Context context",
                "static void reconcile(Context context)");
        String exactClaim = between(store,
                "static synchronized void claimAssetRecoveryTransaction(",
                "static boolean matchesRecoveryTransaction(");

        assertTrue(restore.contains("context, asset.profile, asset.id, transaction, expected"));
        assertFalse(restore.contains(".setRecoveryTransaction("));
        assertTrue(exactClaim.contains("KEY_RECOVERY_OWNER"));
        assertTrue(exactClaim.contains("KEY_TRANSACTION_DIR"));
        assertTrue(exactClaim.contains("KEY_EXPECTED_SHA"));
        assertTrue(exactClaim.contains("KEY_OPERATION_PHASE, RECOVERY_REQUIRED"));
        assertTrue(occurrences(exactClaim, ".commit();") == 1);
        assertTrue(exactClaim.contains("Another recovery transaction is active"));
        assertTrue(manager.contains("phase = PHASE_RECOVERY;"));
        assertTrue(manager.contains("&& globalRecoveryOwns("));
    }

    @Test
    public void replacementSwitchesBackupOnlyAfterNewBackupVerification() throws IOException {
        String source = source("NavigatorAssetManager.java");
        String install = between(source, "static void install(Context context",
                "static void restore(Context context");

        assertTrue(install.contains("previousTransactionName"));
        assertTrue(install.contains("backup.fingerprint"));
        assertTrue(occurrences(install, "NavigatorPatchStore.installedIdentity") >= 2);
        assertTrue(install.contains("if (!switched)"));
        assertTrue(install.contains("if (!retained) deleteTree(transaction);"));
        assertTrue(install.indexOf("backup.fingerprint")
                < install.indexOf("deleteTree(previousTransaction)"));
    }

    @Test
    public void installerOwnsRecoveryVerificationAndPhysicalCleanup() throws IOException {
        String manager = source("NavigatorAssetManager.java");
        String installer = source("NavigatorPackageInstaller.java");
        String store = source("NavigatorPatchStore.java");
        String recovery = between(manager, "if (PHASE_RECOVERY.equals(phase))",
                "private static void reconcileDownload(");
        String receipt = between(manager, "static boolean recordAuthoritativeRestoreVerified(",
                "static boolean finishAuthoritativeRestoreVerified(");
        String finish = between(manager, "static boolean finishAuthoritativeRestoreVerified(",
                "static void reconcile(Context context)");
        String complete = between(installer, "private static void completeRestore(",
                "private static boolean initialInstalledTargetUnchanged(");
        String globalFinish = between(store,
                "static synchronized void completeRestoreTransaction(",
                "static void recordInstalledVerification(");

        assertFalse(recovery.contains("verifyBackupInstalledAsync"));
        assertFalse(recovery.contains("currentArtifactFingerprint"));
        assertFalse(recovery.contains("clearTransaction"));
        assertTrue(manager.contains("if (!PHASE_RECOVERY.equals(phase)"));
        assertTrue(complete.contains("recordAuthoritativeRestoreVerified"));
        assertTrue(complete.indexOf("recordAuthoritativeRestoreVerified")
                < complete.indexOf("NavigatorPatchStore.clearExternal"));
        assertTrue(complete.indexOf("recordAuthoritativeRestoreVerified")
                < complete.indexOf("NavigatorPatchStore.completeRestoreTransaction"));
        assertTrue(globalFinish.contains("KEY_OPERATION_PHASE, IDLE"));
        assertTrue(globalFinish.contains(".remove(KEY_TRANSACTION_DIR)"));
        assertTrue(occurrences(globalFinish, ".commit();") == 1);
        assertTrue(complete.contains("NavigatorPatchPipeline.deleteTree(transaction);"));
        assertTrue(complete.contains("finishAuthoritativeRestoreVerified"));
        assertTrue(complete.indexOf("NavigatorPatchStore.completeRestoreTransaction")
                < complete.indexOf("finishAuthoritativeRestoreVerified"));
        assertTrue(complete.indexOf("finishAuthoritativeRestoreVerified")
                < complete.indexOf("NavigatorPatchPipeline.deleteTree(transaction)"));
        assertTrue(between(complete, "finishAuthoritativeRestoreVerified",
                "NavigatorPatchPipeline.deleteTree(transaction)").contains("return;"));
        assertTrue(receipt.contains("PHASE_RECOVERY.equals"));
        assertTrue(receipt.contains("restore_verified_transaction"));
        assertTrue(receipt.contains("restore_verified_fingerprint"));
        assertTrue(receipt.contains(".commit();"));
        assertTrue(finish.contains("transactionName.equals"));
        assertTrue(finish.contains("expectedFingerprint.equals"));
        assertTrue(finish.contains(".putString(key(matched, \"phase\"), PHASE_NONE)"));
        assertTrue(recovery.contains("hasAuthoritativeRestoreReceipt"));
        assertTrue(recovery.contains("finishAuthoritativeRestoreVerified"));
    }

    @Test
    public void exactBackgroundVerificationRechecksInstalledIdentityBeforeCleanup()
            throws IOException {
        String manager = source("NavigatorAssetManager.java");
        String installer = source("NavigatorPackageInstaller.java");
        String store = source("NavigatorPatchStore.java");
        String target = between(manager, "private static void verifyTargetInstalledAsync(",
                "private static void verifyBackupInstalledAsync(");
        String backup = between(manager, "private static void verifyBackupInstalledAsync(",
                "private static void scheduleOrphanCleanup(");

        assertTrue(store.contains("info.lastUpdateTime"));
        assertTrue(store.contains("info.getLongVersionCode()"));
        assertTrue(store.contains("applicationInfo.sourceDir"));
        assertTrue(store.contains("applicationInfo.splitSourceDirs"));
        assertTrue(store.contains("installedCertificateSha256"));
        assertTrue(occurrences(target, "installedIdentity") >= 3);
        assertTrue(target.indexOf("installedIdentity.equals")
                < target.indexOf("clearTransaction(appContext, asset)"));
        assertTrue(occurrences(backup, "NavigatorPatchStore.installedIdentity") >= 2);
        assertTrue(backup.indexOf("installedIdentity.equals")
                < backup.indexOf("clearTransaction(appContext, asset)"));
        assertTrue(occurrences(installer, "NavigatorPatchStore.installedIdentity") >= 4);
        String installVerify = between(installer, "static void verifyInstalledAsync(",
                "static void verifyRestoredAsync(");
        String restoreComplete = between(installer, "private static void completeRestore(",
                "private static boolean initialInstalledTargetUnchanged(");
        assertTrue(installVerify.indexOf("installedIdentity.equals")
                < installVerify.indexOf("NavigatorPatchStore.clearExternal"));
        assertTrue(restoreComplete.indexOf("installedIdentity.equals")
                < restoreComplete.indexOf("NavigatorPatchStore.clearExternal"));
        assertTrue(occurrences(restoreComplete, "installedIdentity.equals") >= 2);
    }

    @Test
    public void targetHashAndOrphanCleanupStayOffReconcileThread() throws IOException {
        String source = source("NavigatorAssetManager.java");
        String reconcile = between(source, "private static void reconcileInstall(",
                "private static void reconcileDownload(");
        String target = between(source, "private static void verifyTargetInstalledAsync(",
                "private static void verifyBackupInstalledAsync(");
        String sweep = between(source, "private static void scheduleOrphanCleanup(",
                "private static void reconcileCatalogRevision(");

        assertTrue(reconcile.contains("cachedInstalledMatch"));
        assertTrue(reconcile.contains("verifyTargetInstalledAsync"));
        assertFalse(reconcile.contains("matchesInstalled(context, asset)"));
        assertFalse(reconcile.contains("sha256(new File"));
        assertTrue(target.contains("new Thread("));
        assertTrue(target.contains("sha256(new File(sourceDir))"));
        assertTrue(target.contains("expectedPhase.equals"));
        assertTrue(source.contains("scheduleOrphanCleanup(context);"));
        assertTrue(sweep.contains("ORPHAN_SWEEP_RUNNING.compareAndSet"));
        assertTrue(sweep.contains("MAX_ORPHAN_DELETIONS_PER_SWEEP"));
        assertTrue(sweep.contains("child.getName().startsWith(\"asset-\")"));
        assertTrue(sweep.contains("child.getCanonicalFile().getParentFile()"));
        assertTrue(sweep.contains("protectedTransactionNames"));
        assertTrue(sweep.contains("NavigatorPatchStore.transactionDirectory"));
        assertTrue(sweep.contains("new Thread("));
    }

    @Test
    public void targetShaMismatchRemainsExplicitFailure() throws IOException {
        String source = source("NavigatorAssetManager.java");
        String reconcile = between(source, "private static void reconcileInstall(",
                "private static void reconcileDownload(");
        String target = between(source, "private static void verifyTargetInstalledAsync(",
                "private static void verifyBackupInstalledAsync(");

        assertTrue(target.contains("Installed navigator does not match catalog asset"));
        assertTrue(target.contains("destructive ? RECOVERY_REQUIRED : ERROR"));
        assertFalse(between(target, "Installed navigator does not match catalog asset",
                "} catch (Exception error)")
                .contains("reconcileStale = true"));
        assertTrue(reconcile.contains("ERROR.equals(currentState)"));
        assertTrue(reconcile.contains("RECOVERY_REQUIRED.equals(currentState)"));
    }

    @Test
    public void wazeMetricsHintsDescribeWholeRouteFallback() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");

        assertFalse(source.contains("Waze shows only values for the next stop"));
        assertFalse(source.contains("Waze показує тільки значення до наступної зупинки"));
        assertTrue(source.contains("Waze supports the whole route"));
        assertTrue(source.contains("Waze falls back to an available next-stop value"));
    }

    @Test
    public void hudPillUsesDeliveryEvidenceInsteadOfCapturePermissions() throws IOException {
        String activity = source("MainActivity.java");
        String hudStatus = between(activity, "private String hudStatus(",
                "private String composeApplicationState(");
        String composeStatus = between(activity, "public String composeHudDeliveryStatus()",
                "private void invalidateComposeSnapshot()");
        String composePolling = between(source("BydHudRuntimeCompose.kt"),
                "LaunchedEffect(appInForeground)",
                "LaunchedEffect(storageDeleteBusy, storageDeleteQueue)");

        assertTrue(hudStatus.contains("HudDeliveryStatus.uiStatus()"));
        assertFalse(hudStatus.contains("readyForCapture()"));
        assertTrue(composeStatus.contains("HudDeliveryStatus.uiStatus()"));
        assertFalse(composePolling.contains("captureReady"));
        assertTrue(source("HudDeliveryStatus.java").contains("static String uiStatus()"));
    }

    @Test
    public void switchRowsExposeOneMergedSwitchSemanticsNode() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String switchRow = between(source, "private fun SwitchRow(",
                "private fun UpdateCheckLine(");
        String compact = between(source, "private fun CompactSwitchBox(",
                "private fun HudSwitch(");
        String hudSwitch = between(source, "private fun HudSwitch(",
                "private fun Segmented(");
        String blocker = between(source, "private fun ModalInputBlocker()", "private fun RuntimeApp(");

        assertTrue(switchRow.contains("toggleable("));
        assertTrue(switchRow.contains("role = Role.Switch"));
        assertTrue(switchRow.contains("semantics(mergeDescendants = true)"));
        assertTrue(compact.contains("toggleable("));
        assertTrue(compact.contains("role = Role.Switch"));
        assertTrue(compact.contains("semantics(mergeDescendants = true)"));
        assertTrue(hudSwitch.contains("toggleable("));
        assertTrue(hudSwitch.contains("role = Role.Switch"));
        assertTrue(hudSwitch.contains("clearAndSetSemantics {}"));
        assertTrue(blocker.contains("clearAndSetSemantics {}"));
    }

    @Test
    public void patchWarningDescribesStructuralCompatibilityAndInstallRisk() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");

        assertTrue(source.contains("exact DEX structure"));
        assertTrue(source.contains("a repository signer is not required"));
        assertTrue(source.contains("A signer mismatch may require removing the installed app"));
        assertTrue(source.contains("точною DEX-структурою"));
        assertFalse(source.contains("Only known original, project, or confirmed device-local patch signers are accepted"));
        assertFalse(source.contains("Приймаються лише відомі оригінальні, проєктні або підтверджені локальні ключі патчу цього пристрою"));
    }

    private static String source(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + fileName);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(value, from)) >= 0) {
            count++;
            from += value.length();
        }
        return count;
    }
}
