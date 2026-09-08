package com.bydhud.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportElapsedTimeTest {
    @Test
    fun configurationElapsedUsesMonotonicNowAndFreezesAtEnd() {
        val running = ConfigurationExportSnapshot(
            operationId = "config-1",
            phase = ConfigurationExportPhase.COPYING,
            startedAtEpochMs = 99_000L,
            startedAtElapsedMs = 1_000L
        )
        assertEquals(4L, configurationExportElapsedSeconds(running, 5_999L))

        val finished = running.copy(endedAtElapsedMs = 7_250L)
        assertEquals(6L, configurationExportElapsedSeconds(finished, 100_000L))
    }

    @Test
    fun logShareElapsedUsesMonotonicNowAndNeverGoesNegative() {
        val running = StorageLogShareSnapshot(
            operationId = "logs-1",
            phase = StorageLogSharePhase.ARCHIVING,
            startedAtEpochMs = 500_000L,
            startedAtElapsedMs = 9_000L
        )
        assertEquals(0L, storageLogShareElapsedSeconds(running, 8_000L))
        assertEquals(3L, storageLogShareElapsedSeconds(running, 12_999L))

        val finished = running.copy(endedAtElapsedMs = 14_001L)
        assertEquals(5L, storageLogShareElapsedSeconds(finished, 99_000L))
    }
}
