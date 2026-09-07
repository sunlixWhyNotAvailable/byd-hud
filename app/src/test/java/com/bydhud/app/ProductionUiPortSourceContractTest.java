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
        assertEquals(19, ids.substring(ids.indexOf('{') + 1).lines()
                .filter(line -> !line.trim().isEmpty()).count());

        Path assets = projectRoot().resolve("app/src/main/res/drawable-nodpi");
        try (java.util.stream.Stream<Path> files = Files.list(assets)) {
            assertEquals(50, files.filter(path -> path.getFileName().toString()
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
    public void controlsKeepApprovedGatingAndNonFunctionalExperimentalSelectors() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String basic = between(compose, "optionsSection(\"basic-navigation\"",
                "optionsSection(\"route-eta\"");
        assertTrue(basic.indexOf("row(\"distance-output\")")
                < basic.indexOf("row(\"small-distance-clamp\")"));
        assertTrue(basic.contains("enabled = snapshot.distanceOutputEnabled"));
        assertTrue(compose.contains("row(\"eta-output-field\")"));
        assertTrue(compose.contains("row(\"waze-alert-field\")"));

        for (String runtime : new String[] {"NavHudLiveSender.java",
                "SomeIpHudClient.java", "WazeDirectChannel.java"}) {
            String text = source(runtime);
            assertFalse(runtime, text.contains("wazeAlertField("));
            assertFalse(runtime, text.contains("etaOutputField("));
        }
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
        assertTrue(feedback.contains("if (enabled && pressed) 0.97f else 1.0f"));
        String hudSwitch = between(compose, "private fun HudSwitch(", "private fun Segmented(");
        assertTrue(hudSwitch.contains("if (enabled && pendingHolder.value == null)"));
        assertTrue(hudSwitch.contains("SWITCH_PENDING_TIMEOUT_MS"));
        assertTrue(hudSwitch.contains("delay(50L)"));
        assertTrue(hudSwitch.contains("animationSpec = tween(durationMillis = 140)"));
        assertTrue(compose.contains("delay(viewConfiguration.longPressTimeoutMillis)"));
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
