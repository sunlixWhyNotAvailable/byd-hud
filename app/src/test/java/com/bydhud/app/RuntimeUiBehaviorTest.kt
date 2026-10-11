package com.bydhud.app

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, qualifiers = "w1280dp-h800dp-mdpi", shadows = [UiBoundary::class])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RuntimeUiBehaviorTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var host: MainActivity
    private lateinit var life: LifecycleRegistry
    private lateinit var session: RuntimeUiSession.Session
    private lateinit var display: org.robolectric.android.controller.ActivityController<ComponentActivity>

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("byd_hud_prefs", 0).edit().clear().commit()
        HudPrefs.setUiLanguage(context, "en")
        AppUpdateManager.setAutoCheckEnabled(context, false)
        AppUpdateManager.initialize(context)
        RuntimeUiSession.PROCESS.clear()
        host = Robolectric.buildActivity(MainActivity::class.java).get()
        java.lang.invoke.MethodHandles.privateLookupIn(MainActivity::class.java, java.lang.invoke.MethodHandles.lookup())
            .findSpecial(ComponentActivity::class.java, "onCreate",
                java.lang.invoke.MethodType.methodType(Void.TYPE, android.os.Bundle::class.java), MainActivity::class.java)
            .bindTo(host).invokeWithArguments(listOf<Any?>(null))
        life = host.lifecycle as LifecycleRegistry
        life.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        life.handleLifecycleEvent(Lifecycle.Event.ON_START)
        life.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        UiBoundary.scans = 0
        session = RuntimeUiSession.PROCESS.getOrCreate { "Options" }
        session.select("Options", "basic-navigation")
        MainActivity.updateRuntimeStatusCache(NavRuntimePermissionStatus.check(host), false)
    }

    @After fun cleanup() {
        display.pause().stop().destroy()
        life.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        RuntimeUiSession.PROCESS.clear()
    }

    @Test fun switchesKeepTheirValueAndRowAfterTabRoundTrip() {
        render()
        val controls = listOf("PNG output", "Native output", "Lane output")
        val before = controls.associateWith { switch(it).fetchSemanticsNode().boundsInRoot }
        val thumbPositions = controls.associateWith { thumb(it).fetchSemanticsNode().boundsInRoot }
        val wasOn = HudPrefs.isPngOutputEnabled(host)
        switch("PNG output").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        settle()
        assertEquals(!wasOn, HudPrefs.isPngOutputEnabled(host))
        for (tab in listOf("Apps", "Storage and logs", "Patch", "HUD check")) {
            ui.onNode(hasClickAction() and hasText(tab)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            ui.onNode(hasClickAction() and hasText("Options")).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            switch("PNG output").assertIsDisplayed()
            if (wasOn) switch("PNG output").assertIsOff() else switch("PNG output").assertIsOn()
            for (label in controls) {
                val bounds = switch(label).fetchSemanticsNode().boundsInRoot
                assertEquals("$label must remain in its row", before.getValue(label), bounds)
                assertEquals("$label switch must stay aligned", thumbPositions.getValue(label),
                    thumb(label).fetchSemanticsNode().boundsInRoot)
            }
        }
    }

    @Test fun returnBackgroundDefaultsOffAndPersistsAcrossDashboardModes() {
        session.select("Options", "dashboard-window-profile")
        HudPrefs.setDashboardScreenMode(host, HudPrefs.DASHBOARD_MODE_NONE)
        render()
        val label = "Keep black background when returning to the tablet"
        switch(label).assertIsDisplayed().assertIsOff().assertIsEnabled()
        switch(label).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        settle()
        assertTrue(HudPrefs.isDashboardKeepBlackBackgroundEnabled(host))
        ui.runOnIdle {
            host.composeSetDashboardScreenMode(HudPrefs.DASHBOARD_MODE_FULL)
            invalidateSnapshot()
        }
        settle()
        switch(label).assertIsOn()
        assertTrue(UserSettingsDiagnostics.snapshot(host).getJSONObject("output")
            .getBoolean("dashboardKeepBlackBackground"))
    }

    @Test fun hiddenUiShowsBackgroundChangesWithoutRestartingWork() {
        render()
        switch("PNG output").assertIsOn()
        ui.runOnIdle {
            life.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            life.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        // Production preferences/cache change while the view is not foregrounded.
        val worker = Thread {
            HudPrefs.setPngOutputEnabled(host, false)
            MainActivity.updateRuntimeStatusCache(NavRuntimePermissionStatus.check(host), true)
        }
        worker.start(); worker.join(3000)
        assertFalse(worker.isAlive)
        ui.runOnIdle {
            life.handleLifecycleEvent(Lifecycle.Event.ON_START)
            life.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            invalidateSnapshot()
        }
        switch("PNG output").assertIsOff()
        ui.onNodeWithText("ADB: OK").assertIsDisplayed()
        assertEquals("Options must not restart app scanning", 0, UiBoundary.scans)
    }

    @Test fun permissionReadbackUpdatesVisiblePillsAndKeepsAdbIndependent() {
        render()
        ui.onNodeWithText("Permissions: missing").assertIsDisplayed()
        ui.onNodeWithText("ADB: not granted").assertIsDisplayed()
        ui.runOnIdle {
            grantSettings(host)
            MainActivity.updateRuntimeStatusCache(NavRuntimePermissionStatus.check(host), true)
            invalidateSnapshot()
        }
        settle()
        ui.onNodeWithText("Permissions: OK").assertIsDisplayed()
        ui.onNodeWithText("ADB: OK").assertIsDisplayed()
        ui.runOnIdle {
            Settings.Secure.putInt(host.contentResolver, "accessibility_enabled", 0)
            MainActivity.updateRuntimeStatusCache(NavRuntimePermissionStatus.check(host), true)
            invalidateSnapshot()
        }
        ui.onNodeWithText("Permissions: missing").assertIsDisplayed()
        ui.onNodeWithText("ADB: OK").assertIsDisplayed()

        val unreadableSettings = object : android.content.ContextWrapper(host) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): android.content.ContentResolver =
                throw SecurityException("settings provider denied the read")
        }
        val previous: NavRuntimePermissionStatus = ReflectionHelpers.getStaticField(
            MainActivity::class.java, "cachedNavRuntimePermissionStatus")
        MainActivity.requestRuntimeStatusRefresh(unreadableSettings, true, "test-read-failure")
        val reading: java.util.concurrent.atomic.AtomicBoolean = ReflectionHelpers.getStaticField(
            MainActivity::class.java, "RUNTIME_STATUS_REFRESH_IN_PROGRESS")
        ui.waitUntil(3000) { !reading.get() }
        assertSame("failed read must not manufacture a new permission state", previous,
            ReflectionHelpers.getStaticField(MainActivity::class.java, "cachedNavRuntimePermissionStatus"))
        ui.onNodeWithText("Permissions: missing").assertIsDisplayed()
        ui.onNodeWithText("ADB: OK").assertIsDisplayed()
    }

    private fun switch(label: String) = ui.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
                and hasAnyDescendant(hasText(label)), useUnmergedTree = true)

    private fun thumb(label: String) = ui.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
                and hasParent(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
                    and hasAnyDescendant(hasText(label))), useUnmergedTree = true)

    private fun render() {
        display = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        display.get().setContent { ProductionUi(host, session) }
        ui.waitForIdle()
    }

    // Render the unchanged production composable; no parallel test UI or source-text assertions.
    @OptIn(InternalComposeApi::class)
    @Composable private fun ProductionUi(activity: MainActivity, state: RuntimeUiSession.Session) {
        val method = Class.forName("com.bydhud.app.BydHudRuntimeComposeKt").declaredMethods
            .single { it.name == "RuntimeApp" }
        method.isAccessible = true
        method.invoke(null, activity, state, currentComposer, 0)
    }

    private fun invalidateSnapshot() {
        ReflectionHelpers.callInstanceMethod<Unit>(host, "invalidateComposeSnapshot")
    }

    private fun settle() {
        ui.mainClock.advanceTimeBy(400)
        ui.waitForIdle()
    }

    companion object {
        fun grantSettings(context: Context) {
            val plan = NavPermissionGrantPlan.fromCurrentSettings(context, context.packageName,
                "", "", true, true, true)
            assertTrue(plan.error, plan.isValid)
            Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", plan.notificationService)
            Settings.Secure.putString(context.contentResolver, "enabled_accessibility_services", plan.accessibilityService)
            Settings.Secure.putInt(context.contentResolver, "accessibility_enabled", 1)
            ShadowSettings.setCanDrawOverlays(true)
            org.robolectric.shadows.ShadowEnvironment.setIsExternalStorageLegacy(true)
            org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

// Only suppress external scan/authorization scheduling. UI, preference callbacks and snapshots stay real.
@Implements(MainActivity::class)
class UiBoundary : org.robolectric.shadows.ShadowActivity() {
    @Implementation fun composeRequestRuntimeUiStateRefresh(force: Boolean, reason: String) { scans++ }
    @Implementation fun composeRequestPatchUiStateRefresh(reason: String) {}
    @Implementation fun composeRequestPatchUiStateRefresh(force: Boolean, reason: String) {}
    @Implementation fun composeRequestNavigatorAssetUiStateRefresh(reason: String) {}
    @Implementation fun composeRequestStorageRefresh(force: Boolean) {}
    @Implementation fun composeReportMainUiState(flow: String) {}
    companion object { var scans = 0 }
}

