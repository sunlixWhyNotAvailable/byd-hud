package com.bydhud.app;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class NavigatorPatchDailyReportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void appendsSnapshotsWithoutMovingTheirDayOrOverwritingWorker() throws Exception {
        JSONObject app = new JSONObject().put("operationId", "same-operation")
                .put("kind", "CHECK").put("day", "20261016").put("detail", "first snapshot");
        File logs = new File(temporary.getRoot(), NavigatorPatchReportStore.reportDay(app) + "/logs");
        File first = NavigatorPatchReportStore.writeDailyReport(logs, app);
        JSONObject worker = new JSONObject(app.toString()).put("kind", "SHELL");
        File shell = NavigatorPatchReportStore.writeDailyReport(logs, worker);
        app.put("detail", "second snapshot").put("updatedAt", 1792195200000L);
        File last = NavigatorPatchReportStore.writeDailyReport(logs, app);
        assertEquals(first, last);
        assertNotEquals(last, shell);
        assertEquals("20261016", NavigatorPatchReportStore.reportDay(app));
        java.util.List<String> snapshots = Files.readAllLines(last.toPath(), StandardCharsets.UTF_8);
        assertEquals(2, snapshots.size());
        assertEquals("first snapshot", new JSONObject(snapshots.get(0)).getString("detail"));
        assertEquals("second snapshot", new JSONObject(snapshots.get(1)).getString("detail"));
        assertEquals("SHELL", new JSONObject(new String(Files.readAllBytes(shell.toPath()),
                StandardCharsets.UTF_8)).getString("kind"));
        assertEquals(2, last.getParentFile().listFiles().length);
    }

    @Test public void rejectsMissingIdentityAndInvalidDayBeforeWriting() throws Exception {
        JSONObject report = new JSONObject().put("day", "../outside").put("operationId", "op");
        assertThrows(IOException.class, () -> NavigatorPatchReportStore.writeDailyReport(temporary.getRoot(), report));
        report.put("day", "20261016").remove("operationId");
        assertThrows(IOException.class, () -> NavigatorPatchReportStore.writeDailyReport(temporary.getRoot(), report));
        assertEquals(0, temporary.getRoot().listFiles().length);
    }

    @Test public void repairsOnlyAnUnterminatedTailBeforeAppending() throws Exception {
        JSONObject report = new JSONObject().put("day", "20261016").put("operationId", "op")
                .put("detail", "first");
        File file = NavigatorPatchReportStore.writeDailyReport(temporary.getRoot(), report);
        byte[] partial = "{\"detail\":\"unfinished".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), partial, java.nio.file.StandardOpenOption.APPEND);
        report.put("detail", "second");
        NavigatorPatchReportStore.writeDailyReport(temporary.getRoot(), report);
        java.util.List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("first", new JSONObject(lines.get(0)).getString("detail"));
        assertEquals("second", new JSONObject(lines.get(1)).getString("detail"));
        Files.write(file.toPath(), partial);
        NavigatorPatchReportStore.writeDailyReport(temporary.getRoot(), report);
        lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals("second", new JSONObject(lines.get(0)).getString("detail"));
    }
}
