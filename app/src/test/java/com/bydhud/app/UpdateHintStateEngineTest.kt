package com.bydhud.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHintStateEngineTest {
    @Test
    fun `three participants sort by request then exact tie owner priority`() {
        val engine = UpdateHintStateEngine()
        assertTrue(engine.accept(UpdateHintProtocol.OWNER_COLLECTOR,
            record(UpdateHintProtocol.OWNER_COLLECTOR, "c", 1, requested = 20)))
        assertTrue(engine.accept(UpdateHintProtocol.OWNER_EXTEND,
            record(UpdateHintProtocol.OWNER_EXTEND, "e", 1, requested = 10)))
        assertTrue(engine.accept(UpdateHintProtocol.OWNER_HUD,
            record(UpdateHintProtocol.OWNER_HUD, "h", 1, requested = 10)))

        assertEquals(
            listOf(UpdateHintProtocol.OWNER_HUD, UpdateHintProtocol.OWNER_EXTEND,
                UpdateHintProtocol.OWNER_COLLECTOR),
            engine.active(0, 20).map { it.ownerPackage }
        )
    }

    @Test
    fun `pending and visible deadlines expire without duplicate extension`() {
        val engine = UpdateHintStateEngine()
        val pending = record(UpdateHintProtocol.OWNER_HUD, "h", 1, requested = 1_000)
        assertTrue(engine.accept(UpdateHintProtocol.OWNER_HUD, pending))
        assertFalse(engine.accept(UpdateHintProtocol.OWNER_HUD, pending))
        assertEquals(1, engine.active(0, 500_001_000).size)
        assertTrue(engine.active(0, 500_001_001).isEmpty())

        val visible = pending.copy(revision = 2, phase = UpdateHintPhase.VISIBLE,
            expiresAtElapsedMs = 10_000)
        assertTrue(engine.accept(UpdateHintProtocol.OWNER_HUD, visible))
        assertFalse(engine.accept(UpdateHintProtocol.OWNER_HUD,
            pending.copy(revision = 3)))
        assertFalse(engine.accept(UpdateHintProtocol.OWNER_HUD,
            visible.copy(revision = 3, expiresAtElapsedMs = 11_000)))
        assertEquals(1, engine.active(9_999, Long.MAX_VALUE).size)
        assertTrue(engine.active(10_000, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `newer revisions none restart and confirmed death fence stale state`() {
        val engine = UpdateHintStateEngine()
        val old = record(UpdateHintProtocol.OWNER_EXTEND, "old", 4)
        assertTrue(engine.accept(UpdateHintProtocol.OWNER_EXTEND, old))
        assertFalse(engine.accept(UpdateHintProtocol.OWNER_EXTEND, old.copy(revision = 3)))
        assertTrue(engine.accept(UpdateHintProtocol.OWNER_EXTEND,
            old.copy(revision = 5, phase = UpdateHintPhase.NONE)))
        assertFalse(engine.accept(UpdateHintProtocol.OWNER_EXTEND, old.copy(revision = 4)))

        val restarted = record(UpdateHintProtocol.OWNER_EXTEND, "new", 0)
        assertTrue(engine.accept(UpdateHintProtocol.OWNER_EXTEND, restarted))
        assertFalse(engine.accept(UpdateHintProtocol.OWNER_EXTEND, old.copy(revision = 99)))
        assertTrue(engine.confirmedDeath(UpdateHintProtocol.OWNER_EXTEND, "new"))
        assertFalse(engine.accept(UpdateHintProtocol.OWNER_EXTEND, restarted.copy(revision = 1)))
        assertTrue(engine.active(0, 1).isEmpty())
    }

    @Test
    fun `authenticated owner must match payload and known owner`() {
        val engine = UpdateHintStateEngine()
        val hud = record(UpdateHintProtocol.OWNER_HUD, "h", 1)
        assertFalse(engine.accept(UpdateHintProtocol.OWNER_EXTEND, hud))
        assertFalse(engine.accept("forged.package", hud.copy(ownerPackage = "forged.package")))
        assertTrue(engine.active(0, 1).isEmpty())
    }

    private fun record(
        owner: String,
        session: String,
        revision: Long,
        requested: Long = 1
    ) = UpdateHintRecord(
        ownerPackage = owner,
        processSessionId = session,
        revision = revision,
        eventId = "$owner-event",
        phase = UpdateHintPhase.PENDING,
        requestedAtElapsedNanos = requested,
        expiresAtElapsedMs = 0,
        displayId = 0,
        preferredSizePercent = 100,
        preferredWidthPx = 100,
        preferredHeightPx = 100
    )
}
