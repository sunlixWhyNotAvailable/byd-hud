package com.bydhud.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryLogReportTest {
    @Test
    fun blankCommentsUseLocalizedFallbackAndNoExtraComment() {
        val empty = SentryLogReport.create("", "3.3.0", "10.09.2026 12:00", "Логи")
        val unicodeWhitespace = SentryLogReport.create(
            "\u00A0\u2003\u202F",
            "3.3.0",
            "10.09.2026 12:00",
            "Логи"
        )

        assertNull(empty.comment)
        assertEquals("BYD HUD 3.3.0 — Логи — 10.09.2026 12:00", empty.title)
        assertEquals(empty, unicodeWhitespace)
        val english = SentryLogReport.create(" \n\t ", "3.3.0", "10.09.2026 12:00", "Logs")
        assertNull(english.comment)
        assertEquals("BYD HUD 3.3.0 — Logs — 10.09.2026 12:00", english.title)
    }

    @Test
    fun normalizesTitleWhitespaceButPreservesFullTrimmedComment() {
        val raw = "  First\nline\t with\u00A0spacing\nSecond line  "
        val report = SentryLogReport.create(raw, "3.3.0", "unused", "unused")

        assertEquals("First\nline\t with\u00A0spacing\nSecond line", report.comment)
        assertEquals("BYD HUD 3.3.0 — First line with spacing Second line", report.title)
    }

    @Test
    fun titleSubjectCapsAtOneHundredUnicodeCodePointsPlusEllipsis() {
        val comment = "🙂".repeat(100) + "tail"
        val report = SentryLogReport.create(comment, "3.3.0", "unused", "unused")
        val subject = report.title.removePrefix("BYD HUD 3.3.0 — ")

        assertEquals(101, subject.codePointCount(0, subject.length))
        assertTrue(subject.endsWith("…"))
        assertEquals(comment, report.comment)
    }

    @Test
    fun exactlyOneHundredUnicodeCodePointsHasNoEllipsis() {
        val comment = "🙂".repeat(100)
        val report = SentryLogReport.create(comment, "3.3.0", "unused", "unused")
        val subject = report.title.removePrefix("BYD HUD 3.3.0 — ")

        assertEquals(100, subject.codePointCount(0, subject.length))
        assertEquals(comment, subject)
    }
}
