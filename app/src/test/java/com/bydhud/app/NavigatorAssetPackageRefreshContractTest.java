package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class NavigatorAssetPackageRefreshContractTest {
    @Test
    public void mainProcessRegistersPackageChangesAndReceiverOnlyQueuesAsyncRefresh()
            throws IOException {
        String application = source("BydHudApplication.java");
        String receiver = source("NavigatorAssetPackageReceiver.java");
        String activity = source("MainActivity.java");

        assertTrue(application.contains(
                "getPackageName().equals(Application.getProcessName())"));
        assertTrue(application.contains("Intent.ACTION_PACKAGE_ADDED"));
        assertTrue(application.contains("Intent.ACTION_PACKAGE_REMOVED"));
        assertTrue(application.contains("Intent.ACTION_PACKAGE_REPLACED"));
        assertTrue(application.contains("ContextCompat.RECEIVER_EXPORTED"));
        assertTrue(receiver.contains(
                "NavigatorAssetManager.onCatalogPackageChanged(context, packageName)"));
        assertTrue(source("NavigatorAssetManager.java").contains("requestPatchUiStateRefresh("));
        assertFalse(receiver.contains("getPackageManager()"));
        assertFalse(receiver.contains("downloadFile("));

        String resume = between(activity, "protected void onResume()", "protected void onPause()");
        String refresh = between(activity, "static void requestPatchUiStateRefresh(",
                "static void requestNavigatorAssetUiStateRefresh(");
        assertTrue(resume.contains(
                "requestRuntimeUiStateRefresh(this, true, \"activity-resume\")"));
        assertTrue(resume.contains(
                "requestActivityLocalStatusRefresh(\"activity-resume\")"));
        assertTrue(refresh.contains("new Thread("));
        assertTrue(refresh.contains("NavigatorAssetManager.refreshInstalledMatches(appContext)"));
    }

    @Test
    public void packageReceiverIsNotManifestRegisteredAsAnImplicitBroadcast()
            throws IOException {
        String manifest = new String(
                Files.readAllBytes(root().resolve("app/src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);
        assertFalse(manifest.contains(".NavigatorAssetPackageReceiver"));
    }

    private static String source(String name) throws IOException {
        return new String(
                Files.readAllBytes(root().resolve("app/src/main/java/com/bydhud/app/" + name)),
                StandardCharsets.UTF_8);
    }

    private static Path root() {
        Path root = Paths.get(System.getProperty("user.dir"));
        return Files.isDirectory(root.resolve("app")) ? root : root.getParent();
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Missing source anchors");
        return source.substring(from, to);
    }
}
