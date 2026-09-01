package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class StorageAndLogsUiSourceContractTest {
    @Test
    public void storageTabUsesAcceptedBoundedLogsLayout() throws IOException {
        String source = source();
        String storage = between(source, "private fun StorageTab(", "private fun PatchTab(");
        int settingsIndex = storage.indexOf("item(key = \"storage-settings\")");
        int logsIndex = storage.indexOf("item(key = \"storage-logs\")");
        int foldersIndex = storage.indexOf("item(key = \"navigation-log-folders\")");

        assertTrue(settingsIndex >= 0 && settingsIndex < logsIndex && logsIndex < foldersIndex);
        String settingsSection = storage.substring(settingsIndex, logsIndex);
        String logsSection = storage.substring(logsIndex, foldersIndex);
        String foldersSection = storage.substring(foldersIndex);

        assertFalse(source.contains("private fun LogsTab("));
        assertFalse(source.contains("RuntimeTab.Logs"));
        assertTrue(source.contains("storage = \"Storage and logs\""));
        assertTrue(source.contains("storage = \"Сховище та логи\""));
        assertTrue(source.contains(
                "storageHint = \"Navigation log recording, sharing, retention, and cleanup controls.\""));
        assertTrue(source.contains(
                "storageHint = \"Запис, поширення, зберігання й очищення навігаційних логів.\""));
        assertTrue(source.contains("\"/Documents/BYD-HUD\", ignoreCase = true"));
        assertTrue(source.contains("privateStorageLocation = \"Private folder\""));
        assertTrue(source.contains("publicStorageLocation = \"Public folder\""));
        assertTrue(source.contains("privateStorageLocation = \"Приватна тека\""));
        assertTrue(source.contains("publicStorageLocation = \"Публічна тека\""));
        assertTrue(source.contains("shareSelected = \"Share logs\""));
        assertTrue(source.contains("shareSelected = \"Поділитись логами\""));
        assertEquals(1, occurrences(storage,
                "if (snapshot.logcatRecording) copy.stopLogcat else copy.startLogcat"));

        int currentSizeIndex = settingsSection.indexOf("title = copy.currentNavLogsSize");
        assertTrue(currentSizeIndex >= 0);
        String currentSizeRow = settingsSection.substring(currentSizeIndex);
        assertTrue(currentSizeRow.contains("if (!snapshot.storageCacheAvailable)"));
        assertTrue(currentSizeRow.contains("coldStorageText"));
        assertTrue(currentSizeRow.contains("snapshot.storageScanError.isNotBlank()"));
        assertTrue(currentSizeRow.contains("storageScanFailureText"));
        assertTrue(currentSizeRow.contains("snapshot.storageCalculating"));
        assertTrue(currentSizeRow.contains("copy.storageCalculating"));
        assertTrue(currentSizeRow.contains("storageUsageColors("));
        assertTrue(currentSizeRow.contains("formatStorageUsage("));
        assertTrue(currentSizeRow.contains("snapshot.navCaptureFolderBytes"));
        assertTrue(currentSizeRow.contains("snapshot.storageLimitGb"));
        assertTrue(currentSizeRow.contains("snapshot.storageSessionCount"));
        assertTrue(currentSizeRow.contains("copy.storageSessionsShort"));

        assertEquals(1, occurrences(logsSection, "ShareIconLabelButton("));
        assertFalse(storage.contains("storage-logs-header"));
        assertFalse(storage.contains("storage-logs-footer"));
        int header = logsSection.indexOf("Text(copy.logs.uppercase(Locale.ROOT)");
        int share = logsSection.indexOf("ShareIconLabelButton(", header);
        int sort = logsSection.indexOf(
                "if (sortOldestFirst) copy.sortByName else copy.sortByDate", share);
        int dayList = logsSection.indexOf("LazyColumn(", header);
        int emptyMessage = logsSection.indexOf("AppSectionMessage(", header);
        int shareConfiguration = logsSection.indexOf("copy.shareConfiguration", dayList);
        int footer = logsSection.lastIndexOf("Box(", shareConfiguration);
        assertTrue(header >= 0);
        assertTrue(share > header && sort > share && emptyMessage > sort);
        assertTrue(dayList > emptyMessage && shareConfiguration > dayList && footer > dayList);

        String shareButton = logsSection.substring(share, sort);
        String sortButton = logsSection.substring(sort, emptyMessage);
        assertTrue(shareButton.contains("label = copy.shareSelected"));
        assertTrue(shareButton.contains(
                "enabled = selectedDayNames.isNotEmpty() && !storageActionBusy"));
        assertTrue(shareButton.contains("onClick = { onShareSelected(selectedDayNames) }"));
        assertTrue(sortButton.contains(
                "enabled = snapshot.storageCacheAvailable && !storageSortBusy"));
        assertTrue(sortButton.contains(
                "onClick = { onSortOldestFirst(!sortOldestFirst) }"));

        String dayListSection = logsSection.substring(dayList, footer);
        assertTrue(dayListSection.contains(".heightIn(max = 260.dp)"));
        assertTrue(dayListSection.contains("contentType = { \"storage-day\" }"));
        assertTrue(dayListSection.contains("closeSection = false"));
        assertTrue(dayListSection.contains("selected = selectedDays.contains(day.name)"));
        assertTrue(dayListSection.contains("enabled = !storageSortBusy"));
        assertTrue(dayListSection.contains("onToggle = { onToggleDay(day.name) }"));

        String deleteCallback = "onClick = { onDeleteSelected(selectedDayNames) }";
        int footerEnd = logsSection.indexOf(deleteCallback, footer);
        assertTrue(footerEnd > footer);
        String footerBox = logsSection.substring(footer, footerEnd + deleteCallback.length());
        assertEquals(3, occurrences(footerBox, "HudButton("));
        assertTrue(footerBox.contains("Modifier.fillMaxWidth()"));
        int startControl = footerBox.indexOf(
                "if (snapshot.logcatRecording) copy.stopLogcat else copy.startLogcat");
        int configurationControl = footerBox.indexOf("copy.shareConfiguration", startControl);
        int deleteControl = footerBox.indexOf("copy.deleteSelected", configurationControl);
        assertTrue(startControl >= 0
                && startControl < configurationControl
                && configurationControl < deleteControl);
        String startButton = footerBox.substring(startControl, configurationControl);
        String configurationButton = footerBox.substring(configurationControl, deleteControl);
        String deleteButton = footerBox.substring(deleteControl);
        assertTrue(startButton.contains("modifier = Modifier.align(Alignment.CenterStart)"));
        assertTrue(startButton.contains(
                "enabled = !storageActionBusy && !logcatBusy"));
        assertTrue(startButton.contains(
                "if (snapshot.logcatRecording) onStopLogcat() else onStartLogcat()"));
        assertTrue(configurationButton.contains(
                "enabled = !storageActionBusy && !configurationShareBusy"));
        assertTrue(configurationButton.contains("modifier = Modifier.align(Alignment.Center)"));
        assertTrue(configurationButton.contains("onClick = onShareConfiguration"));
        assertTrue(deleteButton.contains(
                "enabled = selectedDayNames.isNotEmpty() && !storageActionBusy"));
        assertTrue(deleteButton.contains("modifier = Modifier.align(Alignment.CenterEnd)"));
        assertTrue(deleteButton.contains(deleteCallback));

        assertFalse(settingsSection.contains("CodeBlock("));
        assertFalse(logsSection.contains("CodeBlock("));
        assertEquals(1, occurrences(foldersSection, "CodeBlock("));
        assertTrue(foldersSection.contains("snapshot.navCaptureFolderPaths.joinToString"));
        assertTrue(foldersSection.contains("modifier = Modifier.fillMaxWidth()"));
        assertTrue(source.contains(
                "activity.composeTryStartBlockingUiFlow(\"configuration-share\")"));
        assertTrue(source.contains("configurationShareVisible = true"));
    }

    private static String source() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
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
