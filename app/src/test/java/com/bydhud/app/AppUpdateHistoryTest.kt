package com.bydhud.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppUpdateHistoryTest {
    private val offer = AppUpdateManager.UpdateInfo("3.2.4", "https://github.com/validated.apk", "target")
    private fun release(tag: String, body: String = tag, draft: Boolean = false, prerelease: Boolean = false) =
        JSONObject().put("tag_name", tag).put("body", body).put("draft", draft).put("prerelease", prerelease)
    private fun page(vararg rows: JSONObject) = JSONArray().apply { rows.forEach { put(it) } }

    @Test fun filtersRangeAndServiceTagsDeduplicatesAndKeepsValidatedTarget() = runBlocking {
        val result = AppUpdateManager.loadReleaseHistory("3.2.1", offer, false, fetchPage = {
            page(release("v3.2.2", "first"), release("v3.2.4", "stale"),
                release("v3.2.1"), release("v3.2.5"), release("navigator-assets-v2"),
                release("v3.2.3"), release("v3.2.2", "duplicate"), release("v3.2.4-beta.1"),
                release("v3.2.9", draft = true))
        })
        assertEquals(listOf("3.2.4", "3.2.3", "3.2.2"), result.releaseHistory.map { it.version })
        assertEquals(listOf("target", "v3.2.3", "first"), result.releaseHistory.map { it.body })
        assertEquals(offer.downloadUrl, result.downloadUrl)
        assertTrue(result.historyComplete)
    }

    @Test fun reusedFirstPageStillLoadsLaterPagesRegardlessOfVersionOrder() = runBlocking {
        val first = JSONArray().apply { repeat(100) { put(release("v3.0.0")) } }
        val pages = mutableListOf<Int>()
        val result = AppUpdateManager.loadReleaseHistory("3.2.1", offer, false, first, fetchPage = {
            pages += it
            page(release("v3.2.3")) // A missing 3.2.2 release must not be invented.
        })
        assertEquals(listOf(2), pages)
        assertEquals(listOf("3.2.4", "3.2.3"), result.releaseHistory.map { it.version })
    }

    @Test fun betaPolicyIncludesBetasButStableExcludesPrereleaseEntries() = runBlocking {
        val rows = page(release("v3.2.4-beta.2", prerelease = true),
            release("v3.2.3", prerelease = true), release("v3.2.2", draft = true))
        val beta = AppUpdateManager.loadReleaseHistory("3.2.1", offer, true, fetchPage = { rows })
        val stable = AppUpdateManager.loadReleaseHistory("3.2.1", offer, false, fetchPage = { rows })
        assertEquals(listOf("3.2.4", "3.2.4-beta.2", "3.2.3"), beta.releaseHistory.map { it.version })
        assertEquals(listOf("3.2.4"), stable.releaseHistory.map { it.version })
    }

    @Test fun networkFailureKeepsOfferAndCollectedNotesAndManualRetryCanComplete() = runBlocking {
        val first = JSONArray().apply { repeat(100) { put(release("v3.2.2")) } }
        var failedPage = 0
        val result = AppUpdateManager.loadReleaseHistory("3.2.1", offer, false, first,
            fetchPage = { throw IOException("HTTP 503") }, onFailure = { page, _ -> failedPage = page })
        assertFalse(result.historyComplete)
        assertEquals(2, failedPage)
        assertEquals(listOf("3.2.4", "3.2.2"), result.releaseHistory.map { it.version })
        assertEquals(offer.downloadUrl, result.downloadUrl)
        val retry = AppUpdateManager.loadReleaseHistory("3.2.1", offer, false, fetchPage = { page(release("v3.2.3"), release("v3.2.2")) })
        assertTrue(retry.historyComplete)
        assertEquals(3, retry.releaseHistory.size)
        val failedFirst = AppUpdateManager.loadReleaseHistory("3.2.1", offer, false, fetchPage = { throw IOException() })
        assertFalse(failedFirst.historyComplete)
        assertEquals(offer.releaseHistory, failedFirst.releaseHistory)
    }

    @Test fun cancellationNeverBecomesAnIncompleteSuccessfulHistory() = runBlocking {
        var failureReported = false
        try {
            AppUpdateManager.loadReleaseHistory("3.2.1", offer, false,
                fetchPage = { throw CancellationException("obsolete") },
                onFailure = { _, _ -> failureReported = true })
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertFalse(failureReported)
    }

    @Test fun eachReleaseSelectsItsOwnLanguageAndFallbackWithoutMixingMarkers() {
        fun block(language: String, text: String) = "<!-- bydhud:release-notes:$language -->\n$text\n<!-- /bydhud:release-notes:$language -->"
        val info = offer.copy(releaseHistory = listOf(
            AppUpdateManager.ReleaseNotesEntry("3.2.4", block("uk", "українська") + "\n" + block("en", "english")),
            AppUpdateManager.ReleaseNotesEntry("3.2.3", block("en", "fallback")),
            AppUpdateManager.ReleaseNotesEntry("3.2.2", "legacy full text")))
        val notes = AppUpdateManager.releaseNotesForLanguage(info, "uk")
        assertTrue(notes.contains("українська"))
        assertFalse(notes.contains("english"))
        assertTrue(notes.contains("fallback"))
        assertTrue(notes.contains("legacy full text"))
        assertFalse(notes.contains("<!--"))
        assertTrue(notes.indexOf("v3.2.4") < notes.indexOf("v3.2.3"))
        assertTrue(AppUpdateManager.releaseNotesForLanguage(info, "ru").contains("english"))
    }
}
