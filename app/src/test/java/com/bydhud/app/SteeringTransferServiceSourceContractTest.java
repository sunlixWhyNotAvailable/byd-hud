package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Keeps the Android service contract visible to JVM tests without instantiating the service. */
public final class SteeringTransferServiceSourceContractTest {
    @Test
    public void accessibilityServiceRequestsKeyFilteringAndConsumesLearning() throws Exception {
        String service = source("NavAccessibilityService.java");
        String keyHandler = between(
                service,
                "public boolean onKeyEvent(KeyEvent event)",
                "private void beginKeyLearningInternal()");
        assertTrue(keyHandler.contains("return true;"));
        assertTrue(keyHandler.contains("isCachedTargetEligible(packageName)"));
        assertTrue(keyHandler.contains("hasFreshTaskEvidence"));
        assertTrue(keyHandler.contains("scheduleSteeringTaskCacheRefresh(\"mapped-key-stale\")"));
        assertTrue(!keyHandler.contains("forceScanIfIdle"));
        assertTrue(!keyHandler.contains("LocalAdbBridge"));
        assertTrue(!keyHandler.contains("checkDisplay("));
        String xml = xmlSource("nav_accessibility_service.xml");
        assertTrue(xml.contains("flagRequestFilterKeyEvents"));
        assertTrue(xml.contains("canRequestFilterKeyEvents=\"true\""));
        assertTrue(service.contains("scanner.forceFreshScanIfIdle()"));
        assertTrue(service.contains("expireSteeringKeyTail"));
        assertTrue(service.contains("service.invalidateSteeringTaskEvidence()"));
        assertTrue(service.contains("scheduleSteeringTaskCacheRefresh(\"service-interrupt\")"));
        assertTrue(service.contains("steeringTaskInvalidationEpoch"));
        assertTrue(service.contains("steeringTaskCacheRefreshPending"));
        assertTrue(service.contains("canPublishTaskEvidence"));
        assertTrue(service.contains("scheduleSteeringTaskCacheRefresh(\"pending-invalidation\")"));
        String activity = source("MainActivity.java");
        assertTrue(activity.contains("resumeSteeringRuntime(this, \"activity-resume\")"));
    }

    @Test
    public void controllerRechecksTaskAndUsesExistingMovePipeline() throws Exception {
        String controller = source("NavAppDisplayController.java");
        assertTrue(controller.contains("void requestSteeringToggle("));
        assertTrue(controller.contains("checkDisplay(normalized, \"steering-precheck\")"));
        assertTrue(controller.contains("moveIndependentDashboardApp("));
        assertTrue(controller.contains("steering_transfer_failed"));
        assertTrue(!controller.contains("startActivity("));
        assertTrue(controller.contains("catch (IOException | SecurityException e)"));
        assertTrue(controller.contains("catch (SecurityException e)"));
    }

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Path.of("src/main/java/com/bydhud/app", name)),
                StandardCharsets.UTF_8);
    }

    private static String xmlSource(String name) throws IOException {
        return new String(Files.readAllBytes(Path.of("src/main/res/xml", name)),
                StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from) throw new AssertionError("missing source section");
        return source.substring(from, to);
    }
}
