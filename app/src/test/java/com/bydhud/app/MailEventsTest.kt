package com.bydhud.app

import org.junit.Assert.*
import org.junit.Test

class MailEventsTest {
    private val id="33333333-3333-4333-8333-333333333333"
    private fun ready(p: MailEventParser) {
        p.line("event: ready")
        p.line("data: {\"streamId\":\"$id\",\"revision\":3,\"heartbeatSeconds\":20}")
        p.line("")
    }
    @Test fun heartbeatAndUnknownEventsDoNotRequestMailButReadyAndSignalsDo() {
        val signals=mutableListOf<MailEvent>()
        val p=MailEventParser(3,signals::add)
        ready(p)
        p.line(": heartbeat"); p.line("")
        p.line("event: future"); p.line("data: not interpreted"); p.line("")
        p.line("event: mail_available")
        p.line("data: {\"streamId\":\"$id\",")
        p.line("data: \"revision\":3,\"messageId\":\"42\"}")
        p.line("")
        assertEquals(listOf(MailEvent("ready",id),MailEvent("mail_available",id,"42")),signals)
    }
    @Test fun malformedKnownFramesAndStaleRevisionsCannotTriggerSync() {
        val signals=mutableListOf<MailEvent>()
        val p=MailEventParser(3,signals::add)
        ready(p)
        p.line("event: mail_available")
        p.line("data: {\"streamId\":\"$id\",\"revision\":2,\"messageId\":\"42\"}")
        assertThrows(IllegalArgumentException::class.java) { p.line("") }
        assertEquals(1,signals.size)
        val broken=MailEventParser(3,signals::add)
        broken.line("event: ready"); broken.line("data: private-mail-body")
        val error=assertThrows(org.json.JSONException::class.java) { broken.line("") }
        val diagnostic=mailFailureDetails(error)
        assertTrue(diagnostic.contains("org.json.JSONException"))
        assertTrue(diagnostic.contains("response value redacted"))
        assertFalse(diagnostic.contains("private-mail-body"))
        assertEquals(1,signals.size)
    }
    @Test fun closurePreservesReasonAndRetryFloorWithoutCreatingMail() {
        val signals=mutableListOf<MailEvent>()
        val p=MailEventParser(3,signals::add)
        ready(p)
        p.line("event: stream_closed")
        p.line("data: {\"streamId\":\"$id\",\"reason\":\"exchange_paused\",\"retryAfterSeconds\":60}")
        val failure=assertThrows(MailFailure::class.java) { p.line("") }
        assertEquals("exchange_paused",failure.reason)
        val policy=MailBackoff()
        assertEquals(60_000,policy.next(failure.waitSeconds,jitter=0.0))
        assertEquals(4_000,policy.next(jitter=0.0))
        assertEquals(8_000,policy.next(stableMillis=59_999,jitter=0.0))
        assertEquals(2_000,policy.next(stableMillis=60_000,jitter=0.0))
        assertEquals(1,signals.size)
    }
}
