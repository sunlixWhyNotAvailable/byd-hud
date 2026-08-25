package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SpeedLimitCompositeUiSourceContractTest {
    @Test
    public void compositeControlsMatchAcceptedLabelsRangesAndOrder() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String options = between(source, "private fun OptionsTab(", "private fun SetupReminderOverlay(");

        assertTrue(options.contains("\"У вільному полі\", \"Композитний\""));
        assertTrue(options.contains("\"In a free field\", \"Composite\""));
        assertTrue(options.contains("listOf(\"Тільки маневру\", \"Тільки смуг\", "
                + "\"Вільне або маневру\", \"Вільне або смуг\")"));
        assertTrue(options.contains("listOf(\"Maneuver only\", \"Lanes only\", "
                + "\"Free or maneuver\", \"Free or lanes\")"));
        assertTrue(options.contains("\"Поле для виводу у композитному режимі\""));
        assertTrue(options.contains("\"Composite output field\""));
        assertTrue(options.contains("\"Розмір знаку у полі маневру\""));
        assertTrue(options.contains("\"Sign size in maneuver field\""));
        assertTrue(options.contains("\"Розмір знаку у полі для смуг\""));
        assertTrue(options.contains("\"Sign size in lane field\""));
        assertTrue(options.contains("maxValue = 103"));
        assertTrue(options.contains("fallbackValue = 64"));
        assertTrue(options.contains("maxValue = 36"));
        assertTrue(options.contains("fallbackValue = 36"));

        assertTrue(options.contains("val freeFallbackEnabled = snapshot.speedLimitMode == 3"));
        assertTrue(options.contains("val compositeEnabled = snapshot.speedLimitMode == HudPrefs.SPEED_LIMIT_COMPOSITE"));
        assertTrue(options.contains("val overlaySecondsEnabled = snapshot.speedLimitMode in 1..2"));
        assertTrue(options.contains("(freeFallbackEnabled && snapshot.speedLimitFreeFallback != 0)"));

        assertOrdered(options,
                "optionsSection(\"runtime-permissions\"",
                "optionsSection(\"basic-navigation\"",
                "optionsSection(\"route-eta\"",
                "optionsSection(\"speed-limit\"",
                "optionsSection(\"waze-features\"",
                "optionsSection(\"extra-navigation\"",
                "optionsSection(\"dashboard-control\"");
    }

    @Test
    public void integerStepperKeepsAcceptedInputGuardsAndDefaults() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String stepper = between(source,
                "private fun HudIntegerStepper(",
                "private fun isValidHudInteger(");

        assertTrue(stepper.contains("minValue: Int = 1"));
        assertTrue(stepper.contains("maxValue: Int? = 10"));
        assertTrue(stepper.contains("fallbackValue: Int = 5"));
        assertTrue(stepper.contains("rawValue.filter(Char::isDigit)"));
        assertTrue(stepper.contains("candidate.isEmpty() || isValidHudInteger("));
        assertTrue(stepper.contains("textValue = fallbackValue.toString()"));
        assertTrue(stepper.contains("onValueChange(fallbackValue)"));
        assertTrue(stepper.contains("current < (maxValue ?: Int.MAX_VALUE)"));
        assertFalse(stepper.contains(".take("));
    }

    @Test
    public void dropdownUsesFocusablePopupWithEqualRowsAndBoundedPosition() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String dropdown = between(source,
                "private fun HudDropdown(",
                "private fun HudIntegerStepper(");

        assertTrue(dropdown.contains("val rowHeight = 40.dp"));
        assertTrue(dropdown.contains("Popup("));
        assertTrue(dropdown.contains("PopupProperties(focusable = true)"));
        assertTrue(dropdown.contains(".height(rowHeight)"));
        assertTrue(dropdown.contains(".background(if (index == safeIndex) selectedBackground else Color.Transparent)"));
        assertFalse(dropdown.contains("DropdownMenu("));
        assertFalse(dropdown.contains("32.dp"));

        String position = between(source,
                "private object HudDropdownPositionProvider",
                "@Composable\nprivate fun HudIntegerStepper(");
        assertTrue(position.contains("below + popupContentSize.height <= windowSize.height"));
        assertTrue(position.contains("above >= 0"));
        assertTrue(position.contains("below.coerceIn(0, maxY)"));
    }

    @Test
    public void dashboardScreenModeUsesAcceptedLabelsAndRuntimeCallback() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String options = between(source, "private fun OptionsTab(", "private fun SetupReminderOverlay(");

        assertTrue(options.contains("row(\"dashboard-screen-mode\")"));
        assertTrue(options.contains("listOf(\"Немає\", \"Частковий\", \"Повний\")"));
        assertTrue(options.contains("listOf(\"None\", \"Partial\", \"Full\")"));
        assertTrue(options.contains("selectedIndex = snapshot.dashboardScreenMode"));
        assertTrue(options.contains("activity.composeSetDashboardScreenMode(mode)"));
        assertFalse(options.contains("fullscreenDashboard"));
    }

    @Test
    public void rowExplanationsStripOnlyFinalFullStopThroughSharedHelper() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        assertTrue(source.contains("private fun rowExplanation(text: String): String = text.trimEnd().removeSuffix(\".\")"));
        assertTrue(between(source, "private fun DashboardHeightRow(",
                "private fun StorageDayRow(").contains("rowExplanation(hint)"));
        assertTrue(between(source, "private fun SettingRow(",
                "private fun HudDropdown(").contains("rowExplanation(hint)"));
        assertTrue(between(source, "private fun SwitchRow(",
                "private fun UpdateCheckLine(").contains("rowExplanation(hint)"));
        assertTrue(between(source, "private fun ActionRow(",
                "private fun ManualModeTile(").contains("rowExplanation(hint)"));
        assertTrue(between(source, "private fun ManualModeTile(",
                "private fun LabeledInput(").contains("rowExplanation(hint)"));
    }

    @Test
    public void snapshotCallbacksAndVersionUseCurrentContract() throws IOException {
        String activity = sourcePath("app/src/main/java/com/bydhud/app/MainActivity.java");
        String gradle = sourcePath("app/build.gradle.kts");

        assertTrue(activity.contains("HudPrefs.speedLimitCompositePlacement(this)"));
        assertTrue(activity.contains("HudPrefs.speedLimitManeuverOverlaySize(this)"));
        assertTrue(activity.contains("HudPrefs.speedLimitLaneOverlaySize(this)"));
        assertTrue(activity.contains("HudPrefs.setSpeedLimitCompositePlacement(this, placement)"));
        assertTrue(activity.contains("HudPrefs.setSpeedLimitManeuverOverlaySize(this, size)"));
        assertTrue(activity.contains("HudPrefs.setSpeedLimitLaneOverlaySize(this, size)"));
        assertTrue(gradle.contains("versionCode = 91"));
        assertTrue(gradle.contains("versionName = \"2.4.0-beta.9\""));
    }

    private static String sourcePath(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }

    private static void assertOrdered(String source, String... markers) {
        int prior = -1;
        for (String marker : markers) {
            int current = source.indexOf(marker);
            assertTrue("missing or out-of-order marker " + marker, current > prior);
            prior = current;
        }
    }
}
