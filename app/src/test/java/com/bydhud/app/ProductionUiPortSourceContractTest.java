package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the approved Preview UI port without coupling tests to screenshots. */
public final class ProductionUiPortSourceContractTest {
    @Test
    public void helpCatalogAndFixedAssetsCoverAllApprovedControls() throws Exception {
        String catalog = source("HudHelpCatalog.kt");
        String ids = between(catalog, "internal enum class HudHelpTopicId {", "}");
        assertEquals(21, ids.substring(ids.indexOf('{') + 1).lines()
                .filter(line -> !line.trim().isEmpty()).count());

        Path assets = projectRoot().resolve("app/src/main/res/drawable-nodpi");
        try (java.util.stream.Stream<Path> files = Files.list(assets)) {
            assertEquals(68, files.filter(path -> path.getFileName().toString()
                    .startsWith("hud_help_") && path.toString().endsWith(".png")).count());
        }
        assertTrue(catalog.contains("fun localizedImage(imageRes: Int, ua: Boolean)"));
        assertTrue(catalog.contains("hud_help_warning_maneuver_en"));
        assertTrue(catalog.contains("hud_help_eta_street_7_en"));
    }

    @Test
    public void helpDialogIsCompactLocalOnlyAndButtonsHaveTapFeedback() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String overlay = between(compose, "private fun HudHelpOverlay(",
                "private fun SettingRow(");
        assertTrue(overlay.contains(".width(820.dp)"));
        assertTrue(overlay.contains(".aspectRatio(3f)"));
        assertTrue(overlay.contains("mutableStateOf(request.checked)"));
        assertTrue(overlay.contains("mutableIntStateOf(request.selectedIndex)"));
        assertTrue(overlay.contains("modifier = Modifier.fillMaxWidth()"));
        assertFalse(overlay.contains("activity.compose"));

        String helpButton = between(compose, "private fun HudHelpButton(",
                "private fun ShareIconLabelButton(");
        assertTrue(helpButton.contains("rememberPressFeedback"));
        assertTrue(helpButton.contains("onClick = onClick"));
    }

    @Test
    public void controlsKeepApprovedGatingAndPersistentExperimentalSelectors() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String basic = between(compose, "optionsSection(\"basic-navigation\"",
                "optionsSection(\"route-eta\"");
        assertTrue(basic.indexOf("row(\"distance-output\")")
                < basic.indexOf("row(\"small-distance-clamp\")"));
        assertTrue(basic.contains("enabled = snapshot.distanceOutputEnabled"));
        assertTrue(compose.contains("row(\"eta-output-field\")"));
        assertTrue(compose.contains("row(\"waze-alert-field\")"));
        assertTrue(source("HudPrefs.java").contains("setEtaOutputField(Context context, int field)"));
        assertTrue(source("HudPrefs.java").contains("setWazeAlertField(Context context, int field)"));
        assertTrue(source("MainActivity.java").contains("composeSetEtaOutputField(int field)"));
        assertTrue(source("MainActivity.java").contains("composeSetWazeAlertField(int field)"));
    }

    @Test
    public void hudCheckSelectorSlidesAndSwitchActionHasNoArtificialDelay() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String choice = between(compose, "private fun OutputImageChoice(",
                "private fun OutputImageChoiceItem(");
        assertTrue(choice.contains("animateDpAsState"));
        assertTrue(choice.contains("outputImageChoiceOffset"));
        assertTrue(choice.contains(".offset(x = selectedOffset"));
        String choiceItem = between(compose, "private fun OutputImageChoiceItem(",
                "private fun LazyPageSurface(");
        assertFalse(choiceItem.contains("rememberVisualFirstClick"));
        assertTrue(choiceItem.contains("onClick = onClick"));
        String compact = between(compose, "private fun CompactSwitchBox(",
                "private fun HudSwitch(");
        assertFalse(compact.contains("rememberVisualFirstClick"));
        assertTrue(compact.contains("onValueChange = { switchControl.value?.trigger?.invoke() }"));
        String hudSwitch = between(compose, "private fun HudSwitch(",
                "private fun Segmented(");
        assertFalse(hudSwitch.substring(hudSwitch.indexOf("scope.launch"),
                hudSwitch.indexOf("latestOnChecked(target)")).contains("delay("));
    }

    @Test
    public void allFormerlyDelayedControlsDispatchDirectlyAndKeepPressFeedback() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        assertFalse(compose.contains("rememberVisualFirstClick"));
        assertFalse(compose.contains("VISUAL_PRESS_BEFORE_ACTION_MS"));
        for (String control : new String[] {"StorageDayRow", "HudChevronButton",
                "HudCheckModeTile", "TransferProfileIconButton", "SwitchRow", "HudButton",
                "HudIconButton", "HudHelpButton", "ShareIconLabelButton"}) {
            String body = between(compose, "private fun " + control + "(", "@Composable");
            assertTrue(control, body.contains("rememberPressFeedback("));
            assertTrue(control, body.contains(".then(press.modifier)"));
            assertTrue(control, body.contains("interactionSource = press.interactionSource"));
            assertFalse(control, body.contains("delay("));
            assertFalse(control, body.contains("scope.launch"));
            String callback = control.equals("SwitchRow")
                    ? "onValueChange = { switchControl.value?.trigger?.invoke() }"
                    : control.equals("StorageDayRow") ? "onClick = onToggle" : "onClick = onClick";
            assertTrue(control, body.contains(callback));
        }
        String feedback = between(compose, "private fun rememberPressFeedback(",
                "private fun pressBackground(");
        assertTrue(feedback.contains("animateFloatAsState("));
        assertTrue(feedback.contains("val visiblePressed = visualPressed && (releaseHoldMillis > 0L || enabled)"));
        assertTrue(feedback.contains("if (visiblePressed) 0.97f else 1.0f"));
        assertTrue(feedback.contains("PressInteraction.Press"));
        assertTrue(feedback.contains("PressInteraction.Release"));
        assertTrue(feedback.contains("PressInteraction.Cancel"));
        assertTrue(feedback.contains("clearIfCurrent(generation)"));
        assertTrue(feedback.contains("LaunchedEffect(interactionSource, tracker)"));
        assertTrue(compose.contains("private const val VISUAL_PRESS_HOLD_MS = 90L"));
        String hudSwitch = between(compose, "private fun HudSwitch(", "private fun Segmented(");
        assertTrue(hudSwitch.contains("if (enabled && pendingHolder.value == null)"));
        assertTrue(hudSwitch.contains("SWITCH_PENDING_TIMEOUT_MS"));
        assertTrue(hudSwitch.contains("delay(50L)"));
        assertTrue(hudSwitch.contains("animationSpec = tween(durationMillis = 140)"));
        assertTrue(compose.contains("delay(viewConfiguration.longPressTimeoutMillis)"));
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
        assertTrue(localSwitch.contains("releaseHoldMillis = VISUAL_PRESS_HOLD_MS"));

        String settingRow = between(compose, "private fun SettingRow(",
                "private fun steeringButtonLabel(");
        assertFalse(settingRow.contains("toggleable("));
        assertFalse(settingRow.contains("clickable("));
    }

    @Test
    public void holdIsOptInAndExcludedInteractionPathsKeepNoHoldFeedback() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        for (String control : new String[] {"SwitchRow", "UpdateCheckLine", "CompactSwitchBox",
                "HudSwitch", "HudButton", "HudIconButton", "HudHelpButton",
                "ShareIconLabelButton", "HudChevronButton", "TransferProfileIconButton",
                "OutputImageChoiceItem", "NavigatorAssetAction"}) {
            String body = between(compose, "private fun " + control + "(", "@Composable");
            assertTrue(control, body.contains("releaseHoldMillis = VISUAL_PRESS_HOLD_MS"));
        }
        for (String control : new String[] {"StorageDayRow", "HudCheckModeTile", "SegmentedItem", "TabButton"}) {
            String body = between(compose, "private fun " + control + "(", "@Composable");
            if (control.equals("StorageDayRow")) {
                assertTrue(control, body.contains("rememberPressFeedback(enabled)"));
            } else {
                assertTrue(control, body.contains("rememberPressFeedback()"));
            }
            assertFalse(control, body.contains("VISUAL_PRESS_HOLD_MS"));
        }
    }

    @Test
    public void navigatorAssetActionHasScopedFeedbackAndKeepsParentPassive() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String action = between(compose, "private fun NavigatorAssetAction(", "@Composable");
        assertTrue(action.contains(
                "rememberPressFeedback(enabled, releaseHoldMillis = VISUAL_PRESS_HOLD_MS)"));
        assertTrue(action.contains("text = label"));
        assertTrue(action.contains("asset.state == NavigatorAssetManager.RECOVERY_REQUIRED -> palette.red"));
        assertTrue(action.contains("palette.red.copy(alpha = if (palette.dark) 0.30f else 0.18f)"));
        assertTrue(action.contains(".clip(RoundedCornerShape(4.dp))"));
        assertTrue(action.contains(".background(renderedBackground)"));
        assertTrue(action.contains(".then(press.modifier)"));
        assertTrue(action.contains("enabled = enabled"));
        assertTrue(action.contains("interactionSource = press.interactionSource"));
        assertTrue(action.contains("indication = null"));
        assertTrue(action.contains(".padding(horizontal = 8.dp, vertical = 6.dp)"));
        assertTrue(action.contains("onInstall(asset.id)"));
        assertTrue(action.contains("onRestore(asset.id)"));
        assertTrue(action.contains("onDownload(asset.id)"));
        assertFalse(action.contains("delay("));
        assertFalse(action.contains("scope.launch"));

        String parent = between(compose, "private fun NavigatorAssetColumn(",
                "private fun NavigatorAssetAction(");
        assertFalse(parent.contains("rememberPressFeedback"));
        assertFalse(parent.contains(".clickable("));
        assertFalse(parent.contains("VISUAL_PRESS_HOLD_MS"));
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
