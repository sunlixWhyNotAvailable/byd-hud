package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Static UI-to-backend wiring/privacy sentinels; these do not execute Compose interactions.
 * Behavioral feedback and help-resource checks live in their executable test suites. */
public final class ProductionUiPortSourceContractTest {
    @Test
    public void controlsKeepApprovedGatingAndPersistentExperimentalSelectors() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String basic = between(compose, "optionsSection(\"basic-navigation\"",
                "optionsSection(\"route-eta\"");
        assertTrue(basic.contains("enabled = snapshot.distanceOutputEnabled"));
        assertTrue(compose.contains("row(\"eta-output-field\")"));
        assertTrue(compose.contains("row(\"waze-alert-field\")"));
        assertTrue(source("HudPrefs.java").contains("setEtaOutputField(Context context, int field)"));
        assertTrue(source("HudPrefs.java").contains("setWazeAlertField(Context context, int field)"));
        assertTrue(source("MainActivity.java").contains("composeSetEtaOutputField(int field)"));
        assertTrue(source("MainActivity.java").contains("composeSetWazeAlertField(int field)"));
    }

    @Test
    public void toggleOnlySettingRowsUseSharedRowAndSwitchRouting() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String eta = between(compose, "optionsSection(\"route-eta\"",
                "optionsSection(\"speed-limit\"");
        String wait = between(eta, "row(\"eta-wait-full-text\")", "row(\"eta-output\")");
        assertTrue(wait.contains("SwitchRow("));
        assertTrue(wait.contains("hudPresentation.waitForFullText"));
        assertTrue(wait.contains("composeSetEtaWaitForFullTextEnabled(enabled)"));
        assertFalse(wait.contains("SettingRow("));

        String widget = between(compose, "optionsSection(\n            \"dashboard-widget\"",
                "optionsSection(\"dashboard-move\"");
        for (String row : new String[] {"widget-auto-collapse", "widget-auto-collapse-inactivity",
                "widget-apply-window-profile"}) {
            assertTrue(row, between(widget, "row(\"" + row + "\")", "row(")
                    .contains("SwitchRow("));
        }
        assertTrue(widget.contains("dashboardWidget.copy(autoCollapse = it)"));
        assertTrue(widget.contains("dashboardWidget.copy(autoCollapseAfterInactivity = it)"));
        assertTrue(widget.contains("dashboardWidget.copy(applyWindowProfile = it)"));

        String localSwitch = between(compose,
                "if (request.kind == HudHelpControlKind.Switch)", "} else {");
        assertTrue(localSwitch.contains("localChecked"));
        assertTrue(localSwitch.contains("toggleable("));
        assertTrue(localSwitch.contains("role = Role.Switch"));
        assertTrue(localSwitch.contains("externalControl = switchControl"));

        String settingRow = between(compose, "private fun SettingRow(",
                "private fun steeringButtonLabel(");
        assertFalse(settingRow.contains("toggleable("));
        assertFalse(settingRow.contains("clickable("));
    }

    @Test
    public void navigatorAssetActionDispatchesWithoutAnArtificialDelay() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String action = between(compose, "private fun NavigatorAssetAction(", "@Composable");
        assertTrue(action.contains("onInstall(asset.id)"));
        assertTrue(action.contains("onRestore(asset.id)"));
        assertTrue(action.contains("onDownload(asset.id)"));
        assertFalse(action.contains("delay("));
        assertFalse(action.contains("scope.launch"));

        String parent = between(compose, "private fun NavigatorVersionSection(",
                "private fun NavigatorAssetAction(");
        assertFalse(parent.contains(".clickable("));
    }

    @Test
    public void navigatorErrorsOfferRetryAndDoNotExposeRawBackendDetails() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String action = between(compose, "private fun NavigatorAssetAction(",
                "private fun NavigatorAssetTextAction(");
        assertTrue(action.contains("text = copy.navigatorAssetError"));
        assertTrue(action.contains("onClick = { onShowError(asset.id) }"));
        assertTrue(action.contains("NavigatorAssetManager.ERROR -> copy.navigatorAssetRetry"));
        assertTrue(action.contains("else -> onDownload(asset.id)"));

        String reason = between(compose, "private fun navigatorAssetErrorReason(",
                "@Composable");
        for (String category : new String[] {"ERROR_NETWORK", "ERROR_STORAGE",
                "ERROR_MISSING_OPERATION", "ERROR_INVALID_APK", "ERROR_INTEGRITY"}) {
            assertTrue(category, reason.contains("NavigatorAssetManager." + category));
        }
        assertTrue(reason.contains("else -> copy.navigatorAssetErrorSystem"));

        String overlay = between(compose, "private fun NavigatorAssetErrorOverlay(",
                "@Composable");
        assertTrue(overlay.contains("asset.label"));
        assertTrue(overlay.contains("asset.versionName"));
        assertTrue(overlay.contains("navigatorAssetErrorReason(copy, asset.errorCategory)"));
        assertTrue(overlay.contains("copy.updateClose"));
        assertFalse(overlay.contains("asset.errorDetail"));
        assertFalse(overlay.contains("asset.error)"));

        for (String factory : new String[] {"enCopy", "uaCopy", "ruCopy"}) {
            String copy = between(compose, "private fun " + factory + "()", factory.equals("ruCopy")
                    ? "private fun shareCopy(" : factory.equals("enCopy") ? "private fun uaCopy()"
                    : "private fun ruCopy()");
            assertTrue(factory, copy.contains("navigatorAssetErrorTitle = "));
            assertTrue(factory, copy.contains("navigatorAssetErrorNetwork = "));
            assertTrue(factory, copy.contains("navigatorAssetErrorStorage = "));
            assertTrue(factory, copy.contains("navigatorAssetErrorMissingFile = "));
            assertTrue(factory, copy.contains("navigatorAssetErrorInvalidApk = "));
            assertTrue(factory, copy.contains("navigatorAssetErrorIntegrity = "));
            assertTrue(factory, copy.contains("navigatorAssetErrorSystem = "));
        }
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java/com/bydhud/app/" + name)), StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path root = Paths.get(System.getProperty("user.dir"));
        return Files.isDirectory(root.resolve("app")) ? root : root.getParent();
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from) throw new AssertionError("missing source section: " + start);
        return source.substring(from, to);
    }

}
