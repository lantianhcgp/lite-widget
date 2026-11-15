package com.litewidget.app

import com.litewidget.app.core.data.KanbanData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** New display seams leave every legacy contract regression intact. */
class KanbanDisplayTest {
    private fun task(status: String = "running") = JSONObject()
        .put("name", "状态可见性").put("line", "组件线").put("status", status)
        .put("progress", 0).put("note", "[进度未量化] 等待自检")
    private fun board(vararg tasks: JSONObject) = JSONObject()
        .put("updated_at", "2026-11-15T08:00:00Z").put("tasks", JSONArray(tasks.toList()))

    @Test fun optionalElapsedFormatsMinutesWithoutInventingMissingValues() {
        val values = KanbanData.fields(board(task().put("elapsed", 83), task()))
        assertEquals(2, values["parcel.count"])
        assertEquals(83, values["parcel.0.elapsed"])
        assertEquals("1h23m", values["parcel.0.elapsedLabel"])
        assertEquals("", values["parcel.1.elapsedLabel"])
        assertFalse(values.containsKey("parcel.1.elapsed"))
        assertTrue(KanbanData.validate(board(task().put("elapsed", 83))).isEmpty())
    }

    @Test fun elapsedAllowsZeroAndHoursButRejectsMalformedValues() {
        for ((minutes, label) in listOf(0 to "0m", 59 to "59m", 60 to "1h", 120 to "2h", 1441 to "24h1m")) {
            val item = task().put("elapsed", minutes)
            assertTrue(KanbanData.validate(board(item)).isEmpty())
            assertEquals(label, KanbanData.fields(board(item))["parcel.0.elapsedLabel"])
        }
        for (bad in listOf(-1, 1.5, true, "83", JSONObject.NULL, 2147483648L)) {
            assertFalse("invalid elapsed=$bad", KanbanData.validate(board(task().put("elapsed", bad))).isEmpty())
        }
    }

    @Test fun unknownDisplayIsOngoingWhileLegacyPercentRemainsCompatible() {
        val values = KanbanData.fields(board(task(), task().put("note", "明确零")))
        assertEquals("—", values["parcel.0.percent"])
        assertEquals("进行中", values["parcel.0.progressLabel"])
        assertEquals(true, values["parcel.0.progressUnknown"])
        assertEquals("等待自检", values["parcel.0.displayNote"])
        assertEquals("0%", values["parcel.1.progressLabel"])
        assertEquals(false, values["parcel.1.progressUnknown"])
    }

    @Test fun knownProgressDisplayDoesNotRoundIncompleteWorkToOneHundred() {
        for ((progress, label) in listOf(0.01 to "0.01%", 99.99 to "99.99%", 37.5 to "37.5%", 100.0 to "100%")) {
            val values = KanbanData.fields(board(task().put("progress", progress).put("note", "已量化")))
            assertEquals(label, values["parcel.0.progressLabel"])
            assertEquals(progress, (values["parcel.0.progress"] as Number).toDouble(), 0.0)
        }
        val legacy = KanbanData.fields(board(task().put("progress", 99.99).put("note", "已量化")))
        assertEquals("100.0%", legacy["parcel.0.percent"])
    }

    @Test fun textualStatusAndCountsDescribeAllValidTasksOnly() {
        val values = KanbanData.fields(board(task(), task(), task("blocked"), task("incident"), task("waiting")))
        assertEquals("运行中", values["parcel.0.statusLabel"])
        assertEquals(2, values["parcel.runningCount"])
        assertEquals(1, values["parcel.blockedCount"])
        assertEquals(1, values["parcel.incidentCount"])
        assertEquals(1, values["parcel.waitingCount"])
        assertEquals("运行 2 · 受阻 1 · 事故 1 · 等待 1", values["parcel.statusSummary"])
    }
}
