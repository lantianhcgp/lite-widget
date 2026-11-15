package com.litewidget.app

import com.litewidget.app.core.spec.SpecValidator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class KanbanSpecTest {
    private fun asset(name: String): JSONObject {
        val root = if (File("src/main/assets").isDirectory) File("src/main/assets") else File("app/src/main/assets")
        return JSONObject(File(root, "templates/task-kanban/$name").readText())
    }

    @Test fun builtInSpecHasOnlySafeSizesAndPassesRuntimeSchema() {
        val spec = asset("widget.json")
        assertEquals(setOf("2x2", "4x2", "2x4", "4x4"), spec.getJSONObject("variants").keys().asSequence().toSet())
        assertTrue(SpecValidator.widget(spec).toString(), SpecValidator.widget(spec).isEmpty())
        assertTrue(SpecValidator.manifest(asset("manifest.json")).isEmpty())
    }

    @Test fun slotsAreHiddenAsAWholeForEmptyDataInEverySize() {
        val spec = asset("widget.json")
        val variants = spec.getJSONObject("variants")
        val roots = listOf("default" to spec.getJSONObject("root")) +
            variants.keys().asSequence().map { it to variants.getJSONObject(it).getJSONObject("root") }.toList()
        for ((size, root) in roots) {
            val children = root.getJSONArray("children")
            val slots = (0 until children.length()).map { children.getJSONObject(it) }.single { it.optString("id") == "slots" }
            val predicate = slots.optJSONObject("visibleIf")
            assertNotNull("$size slots must collapse when count is missing or zero", predicate)
            assertEquals("parcel.count", predicate!!.getString("field"))
            assertEquals("gt", predicate.getString("op"))
            assertEquals(0, predicate.getInt("value"))
            assertEquals(setOf("field", "op", "value"), predicate.keys().asSequence().toSet())
        }
    }

    @Test fun pinDialogUsesKanbanSizeHelperWithoutChangingLegacyOrder() {
        val source = if (File("src/main/java").isDirectory) File("src/main/java/com/litewidget/app/MainActivity.kt")
            else File("app/src/main/java/com/litewidget/app/MainActivity.kt")
        assertTrue("pin dialog must use safe helper", source.readText().contains(
            "val order = com.litewidget.app.core.data.KanbanData.pinSizes(templateId, listOf(\"4x2\", \"2x2\", \"4x4\", \"2x4\", \"4x1\"))"))
    }

    @Test fun schemaRequiresZeroOnlyWhenNoteStartsWithUnknownPrefix() {
        val root = if (File("src/main/assets").isDirectory) File("src/main/assets") else File("app/src/main/assets")
        val items = JSONObject(File(root, "kanban-schema.json").readText()).getJSONObject("properties")
            .getJSONObject("tasks").getJSONObject("items")
        val condition = items.optJSONObject("if")
        assertNotNull("schema must enforce the unknown-progress convention", condition)
        assertEquals("note", condition!!.getJSONArray("required").getString(0))
        assertEquals("^\\[进度未量化\\]", condition.getJSONObject("properties").getJSONObject("note").getString("pattern"))
        assertEquals(0, items.getJSONObject("then").getJSONObject("properties").getJSONObject("progress").getInt("const"))
    }

    @Test fun manifestAcceptsTwoByFourButBindingsStillRejectTypos() {
        assertTrue(SpecValidator.manifest(asset("manifest.json").put("size", "2x4")).isEmpty())
        val spec = asset("widget.json")
        val title = spec.getJSONObject("root").getJSONArray("children").getJSONObject(0).getJSONArray("children").getJSONObject(1)
        title.put("bind", JSONObject().put("field", "parcle.count"))
        assertFalse(SpecValidator.widget(spec).isEmpty())
    }
}
