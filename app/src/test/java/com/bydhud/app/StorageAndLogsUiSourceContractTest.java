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
    public void storageTabUsesAcceptedThreeColumnLogControls() throws IOException {
        String source = source();
        String storage = between(source, "private fun StorageTab(", "private fun PatchTab(");
        String logControls = between(
                storage, "item(key = \"navigation-log-controls\")", "items(");

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
        assertEquals(1, occurrences(storage,
                "if (snapshot.logcatRecording) copy.stopLogcat else copy.startLogcat"));
        assertTrue(storage.contains("copy.shareConfiguration"));
        assertTrue(storage.contains("copy.shareSelected"));
        assertTrue(storage.contains("copy.sortByName"));
        assertTrue(storage.contains(".width(430.dp)"));
        assertTrue(storage.contains("width = 300.dp"));
        assertTrue(storage.contains("ShareIconLabelButton"));
        assertTrue(storage.contains("width = 180.dp"));
        assertTrue(logControls.contains("verticalAlignment = Alignment.CenterVertically"));
        assertFalse(logControls.contains("verticalAlignment = Alignment.Top"));
        assertTrue(logControls.contains("Spacer(Modifier.weight(1f))"));
        String share = between(logControls, "ShareIconLabelButton(", "onClick = { onShareSelected");
        assertTrue(share.contains("width = 172.dp"));
        assertTrue(logControls.contains("Arrangement.spacedBy(10.dp)"));
        String logsSection = storage.substring(storage.indexOf("item(key = \"storage-logs\")"));
        assertEquals(1, occurrences(logsSection, "item(key = \"storage-logs\")"));
        assertFalse(storage.contains("storage-logs-header"));
        assertFalse(storage.contains("storage-logs-footer"));
        assertTrue(logsSection.contains("LazyColumn("));
        assertTrue(logsSection.contains(".heightIn(max = 260.dp)"));
        assertTrue(logsSection.contains("contentType = { \"storage-day\" }"));
        assertTrue(logsSection.contains("bottom = false"));
        assertTrue(logsSection.contains("closeSection = false"));
        int header = logsSection.indexOf("Text(copy.logs.uppercase(Locale.ROOT)");
        int dayList = logsSection.indexOf("LazyColumn(", header);
        int emptyMessage = logsSection.indexOf("AppSectionMessage(", header);
        int footer = logsSection.indexOf("copy.shareConfiguration", dayList);
        assertTrue(header >= 0);
        assertTrue(emptyMessage > header && emptyMessage < footer);
        assertTrue(dayList > header && dayList < footer);
        String footerBox = logsSection.substring(logsSection.lastIndexOf("Box(", footer),
                logsSection.indexOf("onDeleteSelected(selectedDayNames)", footer));
        assertTrue(footerBox.contains("Modifier.fillMaxWidth()"));
        assertTrue(footerBox.contains("contentAlignment = Alignment.Center"));
        assertTrue(footerBox.contains("modifier = Modifier.align(Alignment.CenterEnd)"));
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
