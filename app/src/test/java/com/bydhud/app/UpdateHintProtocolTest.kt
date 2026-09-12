package com.bydhud.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateHintProtocolTest {
    @Test
    fun `wire v1 names and message codes remain exact`() {
        assertEquals("com.byd.apps.updatehint.COORDINATION", UpdateHintProtocol.ACTION)
        assertEquals("com.byd.apps.updatehint.PROTOCOL_VERSION",
            UpdateHintProtocol.METADATA_PROTOCOL_VERSION)
        assertEquals(1, UpdateHintProtocol.VERSION)
        assertEquals(1, UpdateHintProtocol.SUBSCRIBE)
        assertEquals(2, UpdateHintProtocol.STATE)
        assertEquals(3, UpdateHintProtocol.UNSUBSCRIBE)
        assertEquals(listOf("NONE", "PENDING", "VISIBLE"),
            UpdateHintPhase.entries.map { it.name })
        assertEquals(listOf("com.bydhud.app", "com.byd.extend", "com.bydcollector.collector"),
            UpdateHintProtocol.OWNERS)
        assertEquals(listOf(
            "protocolVersion", "ownerPackage", "processSessionId", "revision", "eventId",
            "phase", "requestedAtElapsedNanos", "expiresAtElapsedMs", "displayId",
            "preferredSizePercent", "preferredWidthPx", "preferredHeightPx"
        ), listOf(
            UpdateHintProtocol.KEY_PROTOCOL_VERSION, UpdateHintProtocol.KEY_OWNER_PACKAGE,
            UpdateHintProtocol.KEY_PROCESS_SESSION_ID, UpdateHintProtocol.KEY_REVISION,
            UpdateHintProtocol.KEY_EVENT_ID, UpdateHintProtocol.KEY_PHASE,
            UpdateHintProtocol.KEY_REQUESTED_AT_NANOS, UpdateHintProtocol.KEY_EXPIRES_AT_MS,
            UpdateHintProtocol.KEY_DISPLAY_ID, UpdateHintProtocol.KEY_PREFERRED_SIZE_PERCENT,
            UpdateHintProtocol.KEY_PREFERRED_WIDTH_PX, UpdateHintProtocol.KEY_PREFERRED_HEIGHT_PX
        ))
    }

    @Test
    fun `initial exchange budget includes time already spent since request`() {
        val requested = 1_000_000_000L
        assertEquals(500L, UpdateHintProtocol.initialExchangeDelayMs(requested, requested))
        assertEquals(300L, UpdateHintProtocol.initialExchangeDelayMs(
            requested, requested + 200_000_000L))
        assertEquals(0L, UpdateHintProtocol.initialExchangeDelayMs(
            requested, requested + 500_000_001L))
    }

    @Test
    fun `uid packages are reduced to known owners before payload trust`() {
        assertEquals(
            linkedSetOf(UpdateHintProtocol.OWNER_HUD, UpdateHintProtocol.OWNER_COLLECTOR),
            UpdateHintProtocol.knownOwners(arrayOf(
                UpdateHintProtocol.OWNER_HUD, "forged.package", UpdateHintProtocol.OWNER_COLLECTOR))
        )
        assertEquals(emptySet<String>(), UpdateHintProtocol.knownOwners(null))
    }
}
