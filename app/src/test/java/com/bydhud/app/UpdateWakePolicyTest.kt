package com.bydhud.app

import org.junit.Assert.*
import org.junit.Test

class UpdateWakePolicyTest {
    private val quick = "android.intent.action.QUICKBOOT_POWERON"
    @Test fun coldEntryBootAndUnlockAreOneCycleButNextSleepAlwaysRearms() {
        val policy = UpdateWakePolicy()
        policy.onEntry(0L)
        assertFalse(policy.onWake(quick, 1L))
        assertFalse(policy.onWake("android.intent.action.USER_PRESENT", 60_000L))
        policy.onSleep()
        assertTrue(policy.onWake("android.intent.action.SCREEN_ON", 61_000L))
        assertFalse(policy.onWake(quick, 61_001L))
        assertFalse(policy.onWake("android.intent.action.USER_PRESENT", 62_000L))
        policy.onSleep()
        assertTrue(policy.onWake(quick, 63_000L))
    }
    @Test fun explicitQuickbootCanRecoverWithoutScreenOffAndShutdownClearsAdmission() {
        val policy = UpdateWakePolicy()
        assertTrue(policy.onWake(quick, 0L))
        assertFalse(policy.onWake(quick, 1_000L))
        assertTrue(policy.onWake(quick, 120_000L))
        assertFalse(policy.onWake("android.intent.action.USER_PRESENT", 121_000L))
        policy.reset()
        assertTrue(policy.onWake("android.intent.action.BOOT_COMPLETED", 122_000L))
    }
    @Test fun interactiveEntryRecoversSleepingSessionWithoutBackgroundBootAdmission() {
        val policy = UpdateWakePolicy()
        assertFalse(policy.onEntry(0L))
        policy.onSleep()
        assertFalse(policy.onEntry(1_000L, interactive = false))
        assertTrue(policy.onEntry(2_000L))
        assertFalse(policy.onWake("android.intent.action.SCREEN_ON", 2_001L))
    }
}
