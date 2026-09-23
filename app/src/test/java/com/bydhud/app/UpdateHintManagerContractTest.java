package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class UpdateHintManagerContractTest {
    @Test
    public void observerUsesActualMainActivityVisibilityAndNeverStartsUpdateWork() throws Exception {
        String source = source("UpdateHintManager.kt");
        assertTrue(source.contains("Application.ActivityLifecycleCallbacks"));
        assertTrue(source.contains("override fun onActivityStarted(activity: Activity)"));
        assertTrue(source.contains("override fun onActivityStopped(activity: Activity)"));
        assertTrue(source.contains("activity is MainActivity"));
        assertTrue(source.contains("override fun onActivityResumed(activity: Activity)"));
        assertTrue(source.contains("override fun onActivityPaused(activity: Activity) = Unit"));
        assertFalse(source.contains("AppUpdateManager.onSessionEntry"));
        assertFalse(source.contains("requestManualCheck"));
    }

    @Test
    public void attachmentStartsImmutableDeadlineAndTapUsesRetainedIdentity() throws Exception {
        String source = source("UpdateHintManager.kt");
        int add = source.indexOf("hint.windows.addView(hint.container, params)");
        int entryStart = source.indexOf("hint.container.translationX = -placement.widthPx.toFloat()", add);
        int entryEnd = source.indexOf(".translationX(0f)", entryStart);
        int deadline = source.indexOf("hint.expiresAtElapsedMs = SystemClock.elapsedRealtime() + DISPLAY_DURATION_MS");
        int visible = source.indexOf("UpdateHintCoordinator.markVisible(hint.eventId, hint.expiresAtElapsedMs)");
        assertTrue(add >= 0 && deadline > add && visible > deadline && entryStart > add && entryEnd > entryStart);
        assertTrue(source.indexOf(".setDuration(MOVE_DURATION_MS)", entryEnd) > entryEnd);
        assertTrue(source.indexOf(".setInterpolator(DecelerateInterpolator())", entryEnd) > entryEnd);
        int retained = source.indexOf("AppUpdateManager.showRetainedOffer(resultId)");
        int launch = source.indexOf("app.startActivity(Intent(app, MainActivity::class.java)");
        assertTrue(retained >= 0 && launch > retained);
        assertTrue(source.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"));
        assertTrue(source.contains("private const val DISPLAY_DURATION_MS = 10_000L"));
        assertTrue(source.contains("private const val MOVE_DURATION_MS = 220L"));
        assertTrue(source.contains("Intent.ACTION_SCREEN_ON"));
        assertTrue(source.contains("Intent.ACTION_SCREEN_OFF"));
        assertTrue(source.contains("SystemClock.elapsedRealtime() >= hint.expiresAtElapsedMs"));
        assertTrue(source.contains("registerScreenReceiver()"));
        assertTrue(source.contains("unregisterScreenReceiver()"));
        assertTrue(source.contains("hint.container.animate().cancel()"));
    }

    @Test
    public void liveTransparencyUsesWindowAlphaWithoutDoubleAttenuation() throws Exception {
        String source = source("UpdateHintManager.kt");
        int container = source.indexOf("val container = FrameLayout(windowContext).apply");
        int containerAlpha = source.indexOf("alpha = 1f", container);
        int child = source.indexOf("addView(card)", container);
        assertTrue(container >= 0 && containerAlpha > container && child > containerAlpha);
        assertTrue(source.contains("alpha = appearance.alpha"));
        assertTrue(source.contains("params.alpha = appearance.alpha"));
        assertTrue(source.contains("hint.container.alpha = 1f"));
        assertTrue(source.contains("if (appearance.alpha == 0f) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"));

        String compose = source("BydHudRuntimeCompose.kt");
        assertTrue(compose.contains("card.alpha = appearance.alpha"));
    }

    private static String source(String file) throws Exception {
        Path root = Paths.get("src/main/java/com/bydhud/app");
        if (!Files.exists(root)) root = Paths.get("app").resolve(root);
        return new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
    }
}
