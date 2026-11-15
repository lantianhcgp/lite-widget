package com.litewidget.app

import com.litewidget.app.core.data.KanbanData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KanbanDataTest {
    private fun task(status: String = "running", progress: Any = 37) = JSONObject()
        .put("name", "模板渲染").put("line", "widget")
        .put("status", status).put("progress", progress).put("note", "已过预检")

    private fun board(tasks: JSONArray = JSONArray().put(task())) = JSONObject()
        .put("updated_at", "2026-11-15T08:00:00Z").put("tasks", tasks)

    @Test fun validContractBecomesSlotsWithoutInventingTasks() {
        val data = KanbanData.fields(board())
        assertEquals(1, data["parcel.count"])
        assertEquals("模板渲染", data["parcel.0.name"])
        assertEquals("running", data["parcel.0.status"])
        assertEquals(37, data["parcel.0.progress"])
        assertEquals("37%", data["parcel.0.percent"])
        assertFalse(data.containsKey("parcel.1.name"))
        assertTrue(KanbanData.validate(board()).isEmpty())
    }

    @Test fun invalidTasksDoNotBreakGoodSlotsButStrictWritesRejectThem() {
        val array = JSONArray().put(task("blocked", 64)).put(task("bogus")).put(task(progress = "50")).put(task(progress = -1)).put(task(progress = 101))
        val contract = board(array)
        assertFalse(KanbanData.validate(contract).isEmpty())
        val fields = KanbanData.fields(contract)
        assertEquals(1, fields["parcel.count"])
        assertEquals("受阻", fields["parcel.0.statusLabel"])
    }

    @Test fun eightTaskLimitAndCompactSummaryAreExplicit() {
        val array = JSONArray()
        repeat(9) { array.put(task(progress = it)) }
        assertFalse(KanbanData.validate(board(array)).isEmpty())
        val fields = KanbanData.fields(board(array))
        assertEquals(8, fields["parcel.count"])
        assertFalse(fields.containsKey("parcel.8.name"))
        assertEquals("另 6 项 · 切换 4x4 查看", fields["parcel.more2"])
        assertEquals("另 4 项 · 切换 4x4 查看", fields["parcel.more4"])
    }

    @Test fun unknownProgressIsNotInventedAndZeroCanStillBeKnown() {
        val unknown = task(progress = 0).put("note", "[进度未量化] 正在预检")
        val fields = KanbanData.fields(board(JSONArray().put(unknown).put(task(progress = 0))))
        assertEquals("—", fields["parcel.0.percent"])
        assertEquals("0%", fields["parcel.1.percent"])
    }

    @Test fun kanbanPinSizesAreSafeAndPreferFourByFourWhileLegacyIsUnchanged() {
        val legacy = listOf("4x2", "2x2", "4x4", "2x4", "4x1")
        assertEquals(listOf("4x4", "4x2", "2x2", "2x4"), KanbanData.pinSizes("task-kanban", legacy))
        assertSame(legacy, KanbanData.pinSizes("legacy-template", legacy))
    }

    @Test fun unknownNonzeroProgressIsRejected() {
        val unknown = task(progress = 37).put("note", "[进度未量化] 正在预检")
        assertFalse("unknown progress must be zero", KanbanData.validate(board(JSONArray().put(unknown))).isEmpty())
    }

    @Test fun fieldParsingSkipsUnknownNonzeroButAcceptsUnknownZero() {
        val invalid = task(progress = 37).put("note", "[进度未量化] 不可量化")
        val unknown = task(progress = 0).put("note", "[进度未量化] 正在预检")
        assertTrue(KanbanData.validate(board(JSONArray().put(unknown))).isEmpty())
        val fields = KanbanData.fields(board(JSONArray().put(invalid).put(unknown).put(task(progress = 0))))
        assertEquals(2, fields["parcel.count"])
        assertEquals("—", fields["parcel.0.percent"])
        assertEquals("0%", fields["parcel.1.percent"])
    }

    @Test fun oversizedWhitespaceJsonWritesRejectAndKeepLastGoodFile() {
        val directory = java.nio.file.Files.createTempDirectory("kanban-limit-test").toFile()
        try {
            val file = java.io.File(directory, "board.json")
            val reader = com.litewidget.app.core.data.KanbanFile(file)
            val original = board().toString()
            KanbanData.write(file, original)
            assertEquals(37, reader.values()["parcel.0.progress"])
            val changed = board(JSONArray().put(task(progress = 81))).toString()
            for (content in listOf(" ".repeat(256 * 1024) + changed, changed + "\n\t".repeat(256 * 1024))) {
                assertTrue(content.toByteArray(Charsets.UTF_8).size > 256 * 1024)
                assertTrue(KanbanData.validate(JSONObject(content)).isEmpty())
                try {
                    KanbanData.write(file, content)
                    fail("oversized valid JSON accepted")
                } catch (_: IllegalArgumentException) {
                    assertEquals(original, file.readText(Charsets.UTF_8))
                    assertEquals(37, reader.values()["parcel.0.progress"])
                }
                file.writeText(content, Charsets.UTF_8)
                assertEquals(37, reader.values()["parcel.0.progress"])
                file.writeText(original, Charsets.UTF_8)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun byteLimitIncludesUtf8AndAcceptsExactly256KiB() {
        val directory = java.nio.file.Files.createTempDirectory("kanban-byte-test").toFile()
        try {
            val file = java.io.File(directory, "board.json")
            val reader = com.litewidget.app.core.data.KanbanFile(file)
            val json = board().toString()
            val exact = json + " ".repeat(256 * 1024 - json.toByteArray(Charsets.UTF_8).size)
            assertEquals(256 * 1024, exact.toByteArray(Charsets.UTF_8).size)
            assertTrue(exact.length < 256 * 1024)
            KanbanData.write(file, exact)
            assertEquals(37, reader.values()["parcel.0.progress"])
            try {
                KanbanData.write(file, exact + " ")
                fail("one byte over UTF-8 limit accepted")
            } catch (_: IllegalArgumentException) {
                assertEquals(exact, file.readText(Charsets.UTF_8))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun headerDatesUseTheSameRfc3339CalendarSubset() {
        val invalid = listOf("2026-11-15 08:00:00Z", "2026-W46-7T08:00:00Z", "2026-11-15T08:00:00+01:00:30",
            "0000-11-15T08:00:00Z", "2026-02-30T08:00:00Z", "2026-11-15T08:00:00+18:01",
            "2026-11-15T08:00:00.1234567Z", "2026-11-15T24:00:00Z", "2026-11-15T08:00:60Z")
        for (value in invalid) assertFalse(value, KanbanData.validate(board().put("updated_at", value)).isEmpty())
        val valid = listOf("2026-11-15T08:00:00Z", "2026-11-15T08:00:00.123456Z", "2026-11-15T08:00:00+08:00",
            "2024-02-29T08:00:00.1-18:00", "2026-11-15T08:00:00+18:00")
        for (value in valid) assertTrue(value, KanbanData.validate(board().put("updated_at", value)).isEmpty())
    }

    @Test fun malformedHeadersAndUnexpectedFieldsAreRejected() {
        assertFalse(KanbanData.validate(board().put("updated_at", "not a date")).isEmpty())
        assertFalse(KanbanData.validate(board().put("secret", "not allowed")).isEmpty())
        assertFalse(KanbanData.validate(board(JSONArray().put(task().put("extra", 1)))).isEmpty())
        assertFalse(KanbanData.validate(board(JSONArray().put(task().put("name", " ")))).isEmpty())
    }

    @Test fun fileChangesAreVisibleWithoutRefreshAndBadWriteKeepsPrevious() {
        val directory = java.nio.file.Files.createTempDirectory("kanban-test").toFile()
        try {
            val file = java.io.File(directory, "board.json")
            val reader = com.litewidget.app.core.data.KanbanFile(file)
            assertTrue(reader.values().isEmpty())
            KanbanData.write(file, board().toString())
            assertEquals(37, reader.values()["parcel.0.progress"])
            KanbanData.write(file, board(JSONArray().put(task(progress = 81))).toString())
            assertEquals(81, reader.values()["parcel.0.progress"])
            val bytes = file.readText()
            try {
                KanbanData.write(file, board(JSONArray().put(task(progress = 101))).toString())
                fail("invalid contract accepted")
            } catch (_: IllegalArgumentException) {
                assertEquals(bytes, file.readText())
            }
            file.writeText("{incomplete")
            assertEquals(81, reader.values()["parcel.0.progress"])
            KanbanData.write(file, board(JSONArray()).toString())
            assertEquals(0, reader.values()["parcel.count"])
            assertFalse(reader.values().containsKey("parcel.0.name"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
