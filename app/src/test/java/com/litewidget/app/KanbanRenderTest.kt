package com.litewidget.app

import android.app.Application
import android.graphics.Bitmap
import com.litewidget.app.core.data.DataRepo
import com.litewidget.app.core.data.KanbanData
import com.litewidget.app.core.render.Renderer
import com.litewidget.app.core.render.WidgetAssets
import com.litewidget.app.core.store.WidgetStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KanbanRenderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun spec() = JSONObject(context.assets.open("templates/task-kanban/widget.json").bufferedReader().use { it.readText() })

    private fun sample(count: Int = 8): JSONObject {
        val tasks = JSONArray()
        val statuses = arrayOf("running", "blocked", "waiting", "incident")
        val names = arrayOf("模板渲染", "数据接入", "构建验证", "错误定位", "文档整理", "回归测试", "产物校验", "待审提交")
        val progress = intArrayOf(37, 64, 0, 18, 80, 92, 100, 0)
        repeat(count) {
            tasks.put(JSONObject().put("name", names[it]).put("line", "任务线 ${it + 1}")
                .put("status", statuses[it % 4]).put("progress", progress[it])
                .put("note", if (it == 7) "[进度未量化] 待确认" else "正在校验当前阶段"))
        }
        return JSONObject().put("updated_at", "2026-11-15T08:00:00Z").put("tasks", tasks)
    }

    private fun render(width: Int, height: Int, density: Float = 2f, count: Int = 8): Bitmap =
        Renderer(WidgetAssets(File(context.filesDir, "widgets/task-kanban")))
            .render(spec(), KanbanData.fields(sample(count)), width, height, density)

    private fun save(name: String, bitmap: Bitmap) {
        val folder = File("build/kanban-preview").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun actualCanvasRendersFourSafeSizesIncludingNarrowMiuiHosts() {
        val dimensions = linkedMapOf(
            "2x2" to (360 to 360), "4x2" to (720 to 360),
            "2x4" to (360 to 720), "4x4" to (720 to 720),
            "host-2x2" to (276 to 336), "host-4x2" to (688 to 320),
            "host-2x4" to (276 to 770), "host-4x4" to (570 to 770),
            "host-pa-4x2" to (570 to 336))
        val manifest = JSONObject()
        for ((name, wh) in dimensions) {
            val bitmap = render(wh.first, wh.second)
            assertEquals(wh.first, bitmap.width)
            assertEquals(wh.second, bitmap.height)
            val center = bitmap.getPixel(wh.first / 2, wh.second / 2)
            assertEquals("Canvas did not draw the card: $name", 255, android.graphics.Color.alpha(center))
            save(name, bitmap)
            manifest.put(name, JSONObject().put("width", bitmap.width).put("height", bitmap.height)
                .put("density", 2).put("classification", Renderer.classify(wh.first / 2f, wh.second / 2f)))
            bitmap.recycle()
        }
        File("build/kanban-preview/render-manifest.json").writeText(manifest.toString(2))
    }

    @Test fun emptyAndPartialBoardsAreRealNotDemoTasks() {
        for (count in intArrayOf(0, 1, 3)) {
            val bitmap = render(720, 720, count = count)
            save("4x4-count-$count", bitmap)
            bitmap.recycle()
        }
        for ((name, wh) in linkedMapOf("2x2" to (276 to 336), "4x2" to (688 to 320),
                                    "2x4" to (276 to 770), "4x4" to (570 to 770))) {
            val empty = render(wh.first, wh.second, count = 0)
            save("host-$name-empty", empty)
            empty.recycle()
        }
        val values = KanbanData.fields(sample(0))
        assertEquals(0, values["parcel.count"])
        assertFalse(values.containsKey("parcel.0.name"))
    }

    @Test fun localDataIsReadAtRenderWithoutNetworkOrCacheInvalidation() {
        val file = File(context.filesDir, KanbanData.RELATIVE_PATH)
        val wifi = File(context.filesDir, "data/snapshot.json")
        wifi.parentFile!!.mkdirs()
        wifi.writeText(JSONObject().put("packageName", "独立旧数据").put("remainAmount", 123).toString())
        val wifiBytes = wifi.readText()
        val repo = DataRepo(context)
        KanbanData.write(file, sample(1).toString())
        assertEquals(37, repo.values()["parcel.0.progress"])
        assertEquals("独立旧数据", repo.values()["package.name"])
        assertEquals(1, repo.refresh()["parcel.count"])
        KanbanData.write(file, sample(3).toString())
        assertEquals(3, repo.forRender()["parcel.count"])
        assertEquals(wifiBytes, wifi.readText())
        KanbanData.write(file, sample(0).toString())
        assertEquals(0, repo.values()["parcel.count"])
        assertFalse(repo.values().containsKey("parcel.1.name"))
    }

    @Test fun builtinSeedingPreservesUserSpecAndBoard() {
        val store = WidgetStore(context.filesDir)
        store.ensureBuiltins(context.assets)
        assertTrue(store.validate("task-kanban").toString(), store.validate("task-kanban").isEmpty())
        val raw = store.readText("task-kanban", "widget.json")!!
        val edited = JSONObject(raw).put("vars", JSONObject().put("userFlag", 123)).toString()
        assertTrue(store.writeText("task-kanban", "widget.json", edited))
        val file = File(context.filesDir, KanbanData.RELATIVE_PATH)
        KanbanData.write(file, sample(1).toString())
        val before = file.readText()
        store.ensureBuiltins(context.assets)
        assertEquals(edited, store.readText("task-kanban", "widget.json"))
        assertEquals(before, file.readText())
    }
}
