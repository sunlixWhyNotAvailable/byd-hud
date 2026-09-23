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
    public void compositeControlsKeepInputBoundsAndModeGates() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String options = between(source, "private fun OptionsTab(", "private fun SetupReminderOverlay(");

        assertTrue(options.contains("maxValue = 103"));
        assertTrue(options.contains("fallbackValue = 64"));
        assertTrue(options.contains("maxValue = 36"));
        assertTrue(options.contains("fallbackValue = 36"));

        assertTrue(options.contains("HudPrefs.effectiveSpeedLimitBitmapMode("));
        assertTrue(options.contains("val freeFallbackEnabled = effectiveSpeedLimitBitmapMode == HudPrefs.SPEED_LIMIT_FREE"));
        assertTrue(options.contains("val compositeEnabled = effectiveSpeedLimitBitmapMode == HudPrefs.SPEED_LIMIT_COMPOSITE"));
        assertTrue(options.contains("val overlaySecondsEnabled = effectiveSpeedLimitBitmapMode == HudPrefs.SPEED_LIMIT_MANEUVER"));
        assertTrue(options.contains("|| effectiveSpeedLimitBitmapMode == HudPrefs.SPEED_LIMIT_LANES"));
        assertTrue(options.contains("(freeFallbackEnabled && snapshot.speedLimitFreeFallback != HudPrefs.SPEED_LIMIT_FALLBACK_OFF)"));

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
    public void dropdownPositionRemainsInsideWindowBounds() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String dropdown = between(source,
                "private fun HudDropdown(",
                "private fun HudIntegerStepper(");

        String position = between(source,
                "private object HudDropdownPositionProvider",
                "@Composable\nprivate fun HudIntegerStepper(");
        assertTrue(position.contains("below + popupContentSize.height <= windowSize.height"));
        assertTrue(position.contains("above >= 0"));
        assertTrue(position.contains("below.coerceIn(0, maxY)"));
    }

    @Test
    public void dashboardScreenModeUsesRuntimeCallback() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String options = between(source, "private fun OptionsTab(", "private fun SetupReminderOverlay(");

        assertTrue(options.contains("selectedIndex = snapshot.dashboardScreenMode"));
        assertTrue(options.contains("activity.composeSetDashboardScreenMode(mode)"));
        assertFalse(options.contains("fullscreenDashboard"));
    }

    @Test
    public void dashboardProfilesExposeIndependentGeometryControlsAndHideForNone() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String options = between(source, "private fun OptionsTab(", "private fun SetupReminderOverlay(");

        assertTrue(options.contains("snapshot.dashboardScreenMode != HudPrefs.DASHBOARD_MODE_NONE"));
        assertTrue(options.contains("snapshot.dashboardWidthPercent"));
        assertTrue(options.contains("snapshot.dashboardHeightPercent"));
        assertTrue(options.contains("snapshot.dashboardOffsetPercent"));
        assertTrue(options.contains("snapshot.dashboardScalePercent"));
        assertTrue(options.contains("activity.composeSetDashboardWidthPercent("));
        assertTrue(options.contains("activity.composeSetDashboardHeightPercent("));
        assertTrue(options.contains("activity.composeSetDashboardOffsetPercent("));
        assertTrue(options.contains("activity.composeSetDashboardScalePercent("));
        assertTrue(options.contains("snapshot.dashboardScreenMode, it"));
        assertTrue(options.contains("DashboardProjectionPolicy.MIN_WIDTH_PERCENT"));
        assertTrue(options.contains("DashboardProjectionPolicy.MAX_WIDTH_PERCENT"));
        assertTrue(options.contains("DashboardProjectionPolicy.MIN_HEIGHT_PERCENT"));
        assertTrue(options.contains("DashboardProjectionPolicy.MAX_HEIGHT_PERCENT"));
        assertTrue(options.contains("DashboardProjectionPolicy.MIN_OFFSET_PERCENT"));
        assertTrue(options.contains("DashboardProjectionPolicy.MAX_OFFSET_PERCENT"));
        assertTrue(options.contains("DashboardProjectionPolicy.MIN_SCALE_PERCENT"));
        assertTrue(options.contains("DashboardProjectionPolicy.MAX_SCALE_PERCENT"));

        String prefs = sourcePath("app/src/main/java/com/bydhud/app/HudPrefs.java");
        assertTrue(prefs.contains("dashboardProjectionProfile(Context context, int mode)"));
        assertTrue(prefs.contains("DashboardProjectionPolicy.defaultProfile()"));
        assertTrue(prefs.contains("KEY_DASHBOARD_PARTIAL_WIDTH_PERCENT"));
        assertTrue(prefs.contains("KEY_DASHBOARD_FULL_WIDTH_PERCENT"));
        assertTrue(prefs.contains("KEY_DASHBOARD_HEIGHT_PERCENT"));
        assertTrue(prefs.contains("int heightDefault = full && preferences.contains(KEY_DASHBOARD_HEIGHT_PERCENT)"));
        assertTrue(prefs.contains("DashboardProjectionPolicy.clampScalePercent(percent)"));

        String activity = sourcePath("app/src/main/java/com/bydhud/app/MainActivity.java");
        assertTrue(activity.contains(
                "composeSetDashboardWidthPercent(int editedMode, int percent)"));
        assertTrue(activity.contains(
                "int mode = HudPrefs.normalizeDashboardScreenMode(editedMode);"));
    }

    @Test
    public void dashboardFormatMethodMatchesAcceptedSettingsAndDeferredApplyContract() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String options = between(source, "private fun OptionsTab(", "private fun SetupReminderOverlay(");
        String activeProfile = between(options,
                "if (snapshot.dashboardScreenMode != HudPrefs.DASHBOARD_MODE_NONE)",
                "row(\"dashboard-height\")");

        assertTrue(activeProfile.contains("snapshot.dashboardFormatMethod == HudPrefs.DASHBOARD_FORMAT_NATIVE"));
        assertTrue(activeProfile.contains("activity.composeSetDashboardFormatMethod("));

        String prefs = sourcePath("app/src/main/java/com/bydhud/app/HudPrefs.java");
        assertTrue(prefs.contains("KEY_DASHBOARD_PARTIAL_FORMAT_METHOD"));
        assertTrue(prefs.contains("KEY_DASHBOARD_FULL_FORMAT_METHOD"));
        assertTrue(prefs.contains("static int dashboardFormatMethod(Context context, int dashboardMode)"));
        assertTrue(prefs.contains("static void setDashboardFormatMethod(Context context, int dashboardMode, int method)"));
        String prefsSetter = between(prefs,
                "static void setDashboardFormatMethod(",
                "static int normalizeDashboardFormatMethod(");
        assertTrue(prefsSetter.contains("if (mode == DASHBOARD_MODE_NONE)"));
        assertTrue(prefsSetter.contains("mode == DASHBOARD_MODE_FULL\n"
                + "                ? KEY_DASHBOARD_FULL_FORMAT_METHOD : KEY_DASHBOARD_PARTIAL_FORMAT_METHOD"));
        assertTrue(prefsSetter.contains("normalizeDashboardFormatMethod(mode, method)"));

        String activity = sourcePath("app/src/main/java/com/bydhud/app/MainActivity.java");
        String setter = between(activity,
                "public void composeSetDashboardFormatMethod(",
                "public void composeSetDashboardWidthPercent(");
        assertTrue(setter.contains("AppEventLogger.event(this, \"ui dashboard_format_method mode=\""));
        assertTrue(setter.contains("invalidateComposeSnapshot();"));
        assertFalse(setter.contains("refreshControls"));
        assertFalse(setter.contains("finishDashboardProfileChange"));
        assertFalse(setter.contains("applyDashboardProfile"));
        assertTrue(activity.contains("dashboardFormatMethod == other.dashboardFormatMethod"));
        assertTrue(activity.contains("dashboardFormatMethod, dashboardWidthPercent"));

        String diagnostics = sourcePath(
                "app/src/main/java/com/bydhud/app/VehicleConfigurationDiagnostics.java");
        assertTrue(diagnostics.contains(".put(\"miniFormatMethod\", HudPrefs.dashboardFormatMethod("));
        assertTrue(diagnostics.contains(".put(\"fullFormatMethod\", HudPrefs.dashboardFormatMethod("));
    }

    @Test
    public void snapshotCallbacksCarryCompositeSettings() throws IOException {
        String activity = sourcePath("app/src/main/java/com/bydhud/app/MainActivity.java");

        assertTrue(activity.contains("HudPrefs.speedLimitCompositePlacement(this)"));
        assertTrue(activity.contains("HudPrefs.speedLimitManeuverOverlaySize(this)"));
        assertTrue(activity.contains("HudPrefs.speedLimitLaneOverlaySize(this)"));
        assertTrue(activity.contains("HudPrefs.setSpeedLimitCompositePlacement(this, placement)"));
        assertTrue(activity.contains("HudPrefs.setSpeedLimitManeuverOverlaySize(this, size)"));
        assertTrue(activity.contains("HudPrefs.setSpeedLimitLaneOverlaySize(this, size)"));
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

}
