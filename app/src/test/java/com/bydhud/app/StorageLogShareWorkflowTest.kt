package com.bydhud.app

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StorageLogShareWorkflowTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        StorageLogShareWorkflow.shutdown()
        ShellRuntimeSession.prefs(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        StorageLogShareWorkflow.shutdown()
    }

    @Test
    fun restoredAndRedismissedResultKeepsPartialExportFlags() {
        val operationId = "restored-partial"
        val prefs = ShellRuntimeSession.prefs(context)
        prefs.edit().putString("share_work_$operationId", JSONObject()
            .put("id", operationId)
            .put("phase", StorageLogSharePhase.FAILED.name)
            .put("started", 1_000L)
            .put("elapsed", 500L)
            .put("ended", 900L)
            .put("days", JSONArray().put("20261012"))
            .put("developer", false)
            .put("collectionOutcome", "PARTIAL")
            .put("patchReportsIncomplete", true)
            .toString()).commit()

        StorageLogShareWorkflow.restore(context)
        val restored = StorageLogShareWorkflow.snapshots.value.single()
        assertEquals("PARTIAL", restored.collectionOutcome)
        assertTrue(restored.patchReportsIncomplete)

        assertTrue(StorageLogShareWorkflow.dismiss(operationId))
        val persisted = JSONObject(prefs.getString("share_work_$operationId", "{}")!!)
        assertEquals("PARTIAL", persisted.getString("collectionOutcome"))
        assertTrue(persisted.getBoolean("patchReportsIncomplete"))
    }
}
