package com.bydhud.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateWakePolicyTest {
    @Test fun eitherOrderStartsOnceWhenBothConditionsAreConfirmed() {
        val screenFirst = UpdateWakePolicy()
        assertFalse(screenFirst.update(true, 0, true, true).startCheck)
        assertFalse(screenFirst.update(true, -1, true, true).startCheck)
        assertTrue(screenFirst.update(true, 2, true, true).startCheck)
        assertFalse(screenFirst.update(true, 2, true, true).startCheck)

        val vehicleFirst = UpdateWakePolicy()
        assertFalse(vehicleFirst.update(false, 2, true, true).startCheck)
        assertTrue(vehicleFirst.update(true, 2, true, true).startCheck)
        assertFalse(vehicleFirst.update(true, 3, true, true).startCheck)
    }

    @Test fun UnknownBlocksPresentationButDoesNotStartAnotherCycle() {
        val policy = UpdateWakePolicy()
        for (raw in listOf(-1, 5, 255, Int.MIN_VALUE)) {
            val blocked = policy.update(true, raw, true, true)
            assertFalse(blocked.allowed)
            assertFalse(blocked.startCheck)
        }
        assertTrue(policy.update(true, 1, true, true).startCheck)
        assertFalse(policy.update(true, -1, true, true).allowed)
        val recovered = policy.update(true, 1, true, true)
        assertTrue(recovered.allowed)
        assertFalse(recovered.startCheck)
    }

    @Test fun confirmedOffAndScreenOffEachPermitANewCycle() {
        val policy = UpdateWakePolicy()
        assertTrue(policy.update(true, 4, true, true).startCheck)
        assertTrue(policy.update(true, 0, true, true).endCycle)
        assertTrue(policy.update(true, 4, true, true).startCheck)
        assertTrue(policy.update(false, 4, true, true).endCycle)
        assertTrue(policy.update(true, 4, true, true).startCheck)
    }

    @Test fun automaticPreferenceAndRuntimeAdmissionKeepTheirAuthority() {
        val policy = UpdateWakePolicy()
        val manualOnly = policy.update(true, 2, false, true)
        assertTrue(manualOnly.allowed)
        assertFalse(manualOnly.startCheck)
        assertTrue(policy.update(true, 2, true, true).startCheck)
        val shutdown = policy.update(true, 2, true, false)
        assertFalse(shutdown.allowed)
        assertFalse(shutdown.startCheck)
        assertTrue(shutdown.endCycle)
        assertTrue(policy.update(true, 2, true, true).startCheck)
    }
}
