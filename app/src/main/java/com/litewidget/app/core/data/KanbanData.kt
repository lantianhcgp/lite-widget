package com.litewidget.app.core.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.util.Locale

/** 看板契约 → 显式 parcel.N.* 槽位。与网络源独立，无定时器。 */
object KanbanData {
    const val RELATIVE_PATH = "widgets/task-kanban/看板.json"
    const val MAX_TASKS = 8
    const val MAX_BYTES = 256 * 1024
    const val UNKNOWN_PREFIX = "[进度未量化]"
    private val statuses = mapOf("running" to "进行", "blocked" to "受阻", "waiting" to "等待", "incident" to "事故")
    private val taskKeys = setOf("name", "line", "status", "progress", "note")
    private val timestamp = Regex("""^(?!0000)[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,6})?(?:Z|[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00))$(?![\s\S])""")

    fun pinSizes(templateId: String, legacyOrder: List<String>): List<String> =
        if (templateId == "task-kanban") listOf("4x4", "4x2", "2x2", "2x4") else legacyOrder

    private fun taskErrors(task: JSONObject, path: String): List<String> {
        val errors = ArrayList<String>()
        if (task.keys().asSequence().toSet() != taskKeys) errors.add("$path: 必须且只能含 name/line/status/progress/note")
        for ((key, limit) in listOf("name" to 64, "line" to 64, "note" to 240)) {
            val value = task.opt(key)
            if (value !is String || value.length > limit || (key != "note" && value.isBlank())) errors.add("$path.$key: 无效字符串")
        }
        if (task.opt("status") !in statuses.keys) errors.add("$path.status: 仅 running/blocked/waiting/incident")
        val n = task.opt("progress") as? Number
        if (n == null || !n.toDouble().isFinite() || n.toDouble() !in 0.0..100.0) errors.add("$path.progress: 必须为 0..100 的有限数值")
        if ((task.opt("note") as? String)?.startsWith(UNKNOWN_PREFIX) == true && n?.toDouble() != 0.0) {
            errors.add("$path.progress: 进度未量化时必须为 0")
        }
        return errors
    }

    private fun headerErrors(board: JSONObject): List<String> {
        val errors = ArrayList<String>()
        if (board.keys().asSequence().toSet() != setOf("updated_at", "tasks")) errors.add("$: 必须且只能含 updated_at/tasks")
        try {
            val value = board.opt("updated_at") as? String ?: throw IllegalArgumentException()
            require(timestamp.matches(value))
            OffsetDateTime.parse(value)
        } catch (_: Exception) {
            errors.add("$.updated_at: 必须为带时区的 ISO8601 时间")
        }
        if (board.opt("tasks") !is JSONArray) errors.add("$.tasks: 必须为数组")
        return errors
    }

    fun validate(board: JSONObject): List<String> {
        val errors = ArrayList(headerErrors(board))
        val tasks = board.optJSONArray("tasks") ?: return errors
        if (tasks.length() > MAX_TASKS) errors.add("$.tasks: 最多 $MAX_TASKS 项")
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i)
            if (task == null) errors.add("$.tasks[$i]: 必须为对象")
            else errors.addAll(taskErrors(task, "$.tasks[$i]"))
        }
        return errors
    }

    /** 文件读取容错：坏任务逐项跳过，不填充假任务。 */
    fun fields(board: JSONObject): Map<String, Any?> {
        require(headerErrors(board).isEmpty()) { "看板顶层契约无效" }
        val fields = linkedMapOf<String, Any?>()
        val tasks = board.getJSONArray("tasks")
        var slot = 0
        for (i in 0 until tasks.length()) {
            if (slot == MAX_TASKS) break
            val task = tasks.optJSONObject(i) ?: continue
            if (taskErrors(task, "task").isNotEmpty()) continue
            val prefix = "parcel.$slot."
            for (key in taskKeys) fields[prefix + key] = task.get(key)
            val progress = task.getDouble("progress")
            val note = task.getString("note")
            fields[prefix + "percent"] = if (note.startsWith(UNKNOWN_PREFIX)) "—"
                else if (progress == progress.toInt().toDouble()) "${progress.toInt()}%"
                else String.format(Locale.ROOT, "%.1f%%", progress)
            fields[prefix + "statusLabel"] = statuses[task.getString("status")]
            fields[prefix + "detail"] = (task.getString("line") + " · " + note.removePrefix(UNKNOWN_PREFIX).trim()).trimEnd(' ', '·')
            slot++
        }
        val updated = OffsetDateTime.parse(board.getString("updated_at"))
        fields["parcel.count"] = slot
        fields["parcel.updated_at"] = board.getString("updated_at")
        fields["parcel.updated_ms"] = updated.toInstant().toEpochMilli()
        fields["parcel.summary"] = "$slot 个任务"
        fields["parcel.more2"] = if (slot > 2) "另 ${slot - 2} 项 · 切换 4x4 查看" else ""
        fields["parcel.more4"] = if (slot > 4) "另 ${slot - 4} 项 · 切换 4x4 查看" else ""
        return fields
    }

    /** 校验通过才替换目标；并发渲染不会读到半份 JSON。 */
    @Synchronized
    fun write(file: File, content: String) {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "看板文件超过 $MAX_BYTES 字节" }
        val errors = validate(JSONObject(content))
        require(errors.isEmpty()) { errors.joinToString("; ") }
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(".kanban-", ".tmp", file.parentFile)
        try {
            temporary.writeText(content, Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}

/** 每次渲染重新读本地文件；损坏时保留进程内最后有效快照。 */
class KanbanFile(private val file: File) {
    private var previous: Map<String, Any?> = emptyMap()

    @Synchronized
    fun values(): Map<String, Any?> {
        try {
            if (file.isFile && file.length() <= KanbanData.MAX_BYTES) {
                val content = file.readText(Charsets.UTF_8)
                require(content.toByteArray(Charsets.UTF_8).size <= KanbanData.MAX_BYTES)
                previous = KanbanData.fields(JSONObject(content))
            }
        } catch (_: Exception) {
            // 静默降级，既不回显源内容，也不清掉已渲染的有效任务。
        }
        return previous
    }
}
