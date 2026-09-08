package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the approved ETA/warning UI port and its UI-only interaction rules. */
public final class EtaUiSourceContractTest {
    @Test
    public void preferencesDefaultPersistAndInvalidateOnlyRuntimeRelevantChoices() throws Exception {
        String prefs = source("HudPrefs.java");

        assertTrue(prefs.contains("ETA_STREET_FORMAT_PREPEND = 0"));
        assertTrue(prefs.contains("KEY_ETA_WAIT_FOR_FULL_TEXT, true"));
        assertTrue(prefs.contains("KEY_ETA_ARRIVAL_COLOR, 0xFFFFFFFF"));
        assertTrue(prefs.contains("KEY_ETA_DURATION_COLOR, 0xFFFFFFFF"));
        assertTrue(prefs.contains("KEY_ETA_REMAINING_DISTANCE_COLOR, 0xFFFFFFFF"));
        assertTrue(prefs.contains("KEY_WAZE_WARNING_DISTANCE_COLOR,\n                0xFFFFFF00"));

        assertTrue(between(prefs, "static void setEtaStreetFormat(",
                "static boolean isEtaWaitForFullTextEnabled(")
                .contains("markOutputOptionChanged(KEY_ETA_STREET_FORMAT)"));
        String waitSetter = between(prefs, "static void setEtaWaitForFullTextEnabled(",
                "static int getEtaArrivalColor(");
        assertTrue(waitSetter.contains("putBoolean(KEY_ETA_WAIT_FOR_FULL_TEXT, enabled)"));
        assertTrue(waitSetter.contains("markOutputOptionChanged(KEY_ETA_WAIT_FOR_FULL_TEXT)"));
        for (String key : new String[] {"KEY_ETA_ARRIVAL_COLOR", "KEY_ETA_DURATION_COLOR",
                "KEY_ETA_REMAINING_DISTANCE_COLOR", "KEY_WAZE_WARNING_DISTANCE_COLOR"}) {
            assertTrue(key, prefs.contains("markOutputOptionChanged(" + key + ")"));
        }
        assertTrue(between(prefs, "static void setUaLanguage(", "static int storageLimitGb(")
                .contains("markOutputOptionChanged(KEY_UA_LANGUAGE)"));
    }

    @Test
    public void activitySnapshotAndCallbacksCarryEveryUiPreference() throws Exception {
        String activity = source("MainActivity.java");

        for (String getter : new String[] {"getEtaStreetFormat(this)",
                "isEtaWaitForFullTextEnabled(this)", "getEtaArrivalColor(this)",
                "getEtaDurationColor(this)", "getEtaRemainingDistanceColor(this)",
                "getWazeWarningDistanceColor(this)"}) {
            assertTrue(getter, activity.contains(getter));
        }
        for (String callback : new String[] {"composeSetEtaStreetFormat(int format)",
                "composeSetEtaWaitForFullTextEnabled(boolean enabled)",
                "composeSetEtaArrivalColor(int color)", "composeSetEtaDurationColor(int color)",
                "composeSetEtaRemainingDistanceColor(int color)",
                "composeSetWazeWarningDistanceColor(int color)"}) {
            assertTrue(callback, activity.contains(callback));
        }
        assertTrue(activity.contains("etaWaitForFullTextEnabled == other.etaWaitForFullTextEnabled"));
        assertTrue(activity.contains("etaRemainingDistanceColor == other.etaRemainingDistanceColor"));

        String payload = source("DirectTbtPayload.java");
        assertTrue(payload.contains("final boolean waitForFullText"));
        assertTrue(payload.contains("HudPrefs.isEtaWaitForFullTextEnabled(safeContext)"));
        assertTrue(payload.contains("etaWaitForFullText="));
    }

    @Test
    public void etaRowsStayVisibleWithApprovedConditionalEnablementAndCopy() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String eta = between(compose, "optionsSection(\"route-eta\"",
                "optionsSection(\"speed-limit\"");

        for (String row : new String[] {"eta-street-format", "eta-wait-full-text",
                "eta-arrival-color", "eta-duration-color", "eta-remaining-color"}) {
            assertTrue(row, eta.contains("row(\"" + row + "\")"));
        }
        assertTrue(eta.contains("etaStreetEnabled = snapshot.routeMetricsMode != HudPrefs.ROUTE_METRICS_OFF"));
        assertTrue(eta.contains("snapshot.etaOutputField == HudPrefs.ETA_OUTPUT_FIELD_STREET"));
        assertTrue(eta.contains("snapshot.etaOutputField == HudPrefs.ETA_OUTPUT_FIELD_EXPERIMENTAL"));
        assertTrue(eta.contains("hudPresentation.waitApplies(etaStreetEnabled)"));
        assertTrue(eta.contains("Дочікуватись повного показу тексту вулиці"));
        assertTrue(eta.contains("Оновлювати ЕТА після повного проходження тексту або зміни вулиці"));
        assertTrue(eta.contains("Wait for the full street text to display"));
        assertTrue(eta.contains("Update ETA after the text finishes scrolling or the street changes"));
        assertFalse(between(eta, "row(\"eta-wait-full-text\")", "row(\"eta-output\")")
                .contains("onHelp"));

        String waze = between(compose, "optionsSection(\"waze-features\"",
                "optionsSection(\"extra-navigation\"");
        assertTrue(waze.contains("row(\"waze-warning-distance-color\")"));
        assertTrue(waze.contains("snapshot.wazeAlertField == HudPrefs.WAZE_ALERT_FIELD_EXPERIMENTAL"));
        assertTrue(compose.contains("Show the estimated arrival time in the selected ETA output field"));
        assertTrue(compose.contains("Показувати очікуваний час прибуття у вибраному полі виводу ЕТА"));
        assertFalse(compose.contains("Prepend the estimated arrival time to the street text"));
        assertFalse(compose.contains("Experimental output will be activated in the next patch"));
        assertFalse(compose.contains("Експериментальний режим буде активовано в наступному патчі"));
    }

    @Test
    public void helpUsesBothFormatsLocalizedAssetsAndLocalOnlyColorEditing() throws Exception {
        String catalog = source("HudHelpCatalog.kt");
        String presentation = source("HudPresentationPreview.kt");
        String tint = source("HudHelpTint.kt");
        String compose = source("BydHudRuntimeCompose.kt");

        assertTrue(catalog.contains("hud_help_eta_replace_7_en"));
        assertTrue(catalog.contains("hud_help_eta_both_replace_4_en"));
        assertFalse(catalog.contains("saved for the next patch"));
        assertFalse(catalog.contains("зберігається для наступного патчу"));
        assertTrue(presentation.contains("val streetFormat: EtaStreetFormat = EtaStreetFormat.Prefix"));
        assertTrue(presentation.contains("val waitForFullText: Boolean = true"));
        assertTrue(presentation.contains("fun waitApplies(etaStreetEnabled: Boolean) = etaStreetEnabled"));
        assertFalse(presentation.contains("waitApplies(etaStreetEnabled: Boolean) = etaStreetEnabled &&"));
        assertTrue(presentation.contains("if (ua) \"25 хв\" else \"25 min\""));
        assertTrue(presentation.contains("else \"[$fields] \" + if (short) \"Dn\" else \"Dniprovske\""));
        assertTrue(tint.contains("Fixed text-only masks"));
        assertTrue(tint.contains("intArrayOf(1996, 350, 127, 53) to colors.arrivalColor"));
        assertTrue(tint.contains("intArrayOf(174, 451, 140, 68) to colors.warningColor"));
        String overlay = between(compose, "private fun HudHelpOverlay(",
                "private fun SettingRow(");
        assertTrue(overlay.contains("request.topic == HudHelpTopicId.EtaStreetFormat"));
        assertTrue(overlay.contains("etaImage above already follows the modal's local dropdown choice"));
        assertTrue(overlay.contains("localPresentation = localPresentation.withColor(slot, color)"));
        assertFalse(overlay.contains("composeSetEtaArrivalColor"));
        assertFalse(overlay.contains("composeSetWazeWarningDistanceColor"));
    }

    @Test
    public void automaticUpdateRowAndSwitchShareOneToggleWhileManualButtonStaysIndependent()
            throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String update = between(compose, "private fun UpdateCheckLine(",
                "private fun ActionRow(");

        assertTrue(update.contains(".toggleable("));
        assertTrue(update.contains("role = Role.Switch"));
        assertTrue(update.contains("onValueChange = { switchControl.value?.trigger?.invoke() }"));
        assertTrue(update.contains("HudButton(buttonText, palette, width = 190.dp, onClick = onCheckClick)"));
        assertTrue(update.contains("externalControl = switchControl"));
        assertTrue(update.contains("semantics(mergeDescendants = true)"));
    }

    private static String source(String fileName) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java/com/bydhud/app/" + fileName)), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
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
