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
        String runtimeState = between(source, "private fun RuntimeApp(", "fun refresh()");
        String runtimeStorage = between(source, "RuntimeTab.Storage -> StorageTab(",
                "RuntimeTab.Patch -> PatchTab(");
        String storage = between(source, "private fun StorageTab(", "private fun PatchTab(");
        String slider = between(source, "private fun StorageLimitSlider(",
                "private fun storageLimitFromSliderValue(");
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
        assertTrue(runtimeState.contains(
                "val storageDayScrollState = viewportStates.getValue(\"storage-days\").listState"));
        assertFalse(runtimeState.contains("storageDayScrollState = rememberSaveable"));
        assertTrue(runtimeStorage.contains("dayScrollState = storageDayScrollState"));
        assertTrue(storage.contains("scrollState: LazyListState"));
        assertTrue(storage.contains("dayScrollState: LazyListState"));
        assertTrue(storage.contains(
                "LazyPageSurface(copy.storage, copy.storageHint, palette, scrollState)"));
        assertTrue(logsSection.contains("state = dayScrollState"));

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

        assertTrue(settingsSection.contains("value = \"$storageLimitDraft ${gbUnit(copy)}\""));
        assertTrue(settingsSection.contains(
                "enabled = storageLimitDraft != snapshot.storageLimitGb"));
        assertTrue(settingsSection.contains("onStorageLimitGb(storageLimitDraft)"));
        assertEquals(1, occurrences(settingsSection, "onStorageLimitGb("));
        assertTrue(settingsSection.contains(
                "StorageLimitSlider(storageLimitDraft, palette, onStorageLimitDraftChange)"));
        assertTrue(runtimeState.contains(
                "var storageLimitDraft by remember(snapshot.storageLimitGb)"));
        assertFalse(runtimeState.contains("storageLimitDraft by rememberSaveable"));
        assertTrue(runtimeStorage.contains(
                "onStorageLimitDraftChange = { storageLimitDraft = it },"));
        assertTrue(runtimeStorage.contains(
                "onStorageLimitGb = { value -> runAction { activity.composeSetStorageLimitGb(value) } },"));
        assertEquals(1, occurrences(runtimeStorage, "composeSetStorageLimitGb("));
        assertTrue(slider.contains(
                "onValueChange = { onLimit(storageLimitFromSliderValue(it)) }"));
        assertFalse(slider.contains("onValueChangeFinished"));
        assertFalse(slider.contains("mutableStateOf"));

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
        assertTrue(dayListSection.contains("key = { index -> \"storage-day-${days[index].name}\" }"));
        assertTrue(dayListSection.contains(".padding(start = 14.dp, end = 14.dp, top = 10.dp)"));
        assertTrue(dayListSection.contains(
                ".appSectionSegmentFrame(palette, palette.panel, top = false, bottom = false)"));
        assertFalse(dayListSection.contains("AppSectionRow("));
        assertFalse(dayListSection.contains("segmented ="));
        String dayRow = between(source, "private fun StorageDayRow(", "private fun storageLocationLabel(");
        assertTrue(dayRow.contains(".clip(RoundedCornerShape(8.dp))"));
        assertTrue(dayRow.contains(
                ".border(1.dp, if (selected) palette.accent else palette.border, RoundedCornerShape(8.dp))"));
        assertFalse(dayRow.contains("segmented"));
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
        assertTrue(storage.contains("snapshot.navCaptureFolderPaths"));
        assertTrue(storage.contains(".distinct()"));
        assertTrue(foldersSection.contains("storageFolderBlocks.size == 1"));
        assertTrue(foldersSection.contains("storageFolderBlocks.single()"));
        assertTrue(foldersSection.contains("else -> Row("));
        assertTrue(foldersSection.contains("Arrangement.spacedBy(12.dp)"));
        assertTrue(foldersSection.contains("storageFolderBlocks.forEach"));
        assertTrue(foldersSection.contains("\"$label:\\n$path\""));
        assertTrue(foldersSection.contains("modifier = Modifier.weight(1f)"));
        assertFalse(foldersSection.contains("joinToString"));
        assertTrue(source.contains(
                "activity.composeTryStartBlockingUiFlow(\"configuration-share\")"));
        assertTrue(source.contains("configurationShareVisible = true"));
    }

    @Test
    public void configurationExportCopyDisclosesNewReadOnlyGroupsWithoutChangingDestinations() throws IOException {
        String source = source();
        assertTrue(source.contains("shareConfiguration = \"Export configuration\""));
        assertTrue(source.contains("shareConfiguration = \"Експортувати конфігурацію\""));
        for (String copy : new String[]{
                "available live HUD/cluster values", "permissions, components, runtime and BYD HUD options",
                "device/firmware, package/process, display, audio and system metadata",
                "Network addresses are masked", "Nothing is uploaded automatically",
                "доступні поточні значення HUD/приборки", "дозволи, компоненти, стан виконання й налаштування BYD HUD",
                "Мережеві адреси маскуються", "Автоматичного надсилання немає"}) {
            assertTrue(copy, source.contains(copy));
        }
        String modal = between(source, "private fun ConfigurationShareDestinationOverlay(",
                "private fun StorageDeleteConfirmOverlay(");
        assertTrue(modal.contains("copy.shareLogsSentryNotice"));
        assertTrue(modal.contains("onClick = onSentry"));
        assertTrue(modal.contains("onClick = onAnotherApp"));
        assertTrue(modal.contains("onClick = onCancel"));
        assertTrue(source.contains("shareSelected = \"Share logs\""));
        assertTrue(source.contains("shareSelected = \"Поділитись логами\""));
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
