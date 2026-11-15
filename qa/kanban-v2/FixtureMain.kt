package qa

import com.litewidget.app.core.data.KanbanData
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic test fixture only. Values come from the production public fields seam. */
fun main() {
    val names = arrayOf("模板渲染", "数据接入", "构建验证", "错误定位", "文档整理", "回归测试", "产物校验", "待审提交")
    val statuses = arrayOf("running", "blocked", "waiting", "incident")
    val progress = intArrayOf(37, 64, 0, 18, 80, 92, 100, 0)
    val minutes = intArrayOf(83, 42, 0, 16, 140, 58, 61, 5)
    val tasks = JSONArray()
    repeat(8) {
        tasks.put(JSONObject().put("name", names[it]).put("line", "任务线 ${it + 1}")
            .put("status", statuses[it % 4]).put("progress", progress[it])
            .put("elapsed", minutes[it])
            .put("note", if (it == 7) "[进度未量化] 待确认" else "正在校验当前阶段"))
    }
    val board = JSONObject().put("updated_at", "2026-11-15T08:00:00Z").put("tasks", tasks)
    check(KanbanData.validate(board).isEmpty())
    println(JSONObject(KanbanData.fields(board)).toString(2))
}
