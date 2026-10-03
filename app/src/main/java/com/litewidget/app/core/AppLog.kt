package com.litewidget.app.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志：logcat + 落盘文件。
 * 落盘规则（按天滚动）：
 *  - 固定目录 filesDir/logs/，一天一个文件 logyyyyMMdd.txt
 *  - 跨天时自动删除昨天及更早的日志（只保留当天）
 *  - 单文件 8MB 护栏（超限截半保尾，正常量级远达不到）
 * MCP 的 log_tail / 首页「日志」读取当天全量。
 */
object AppLog {

    private const val TAG = "LiteWidget"
    private const val MAX_BYTES = 8L * 1024 * 1024

    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val dayFile = Regex("""^log(\d{8})""")

    @Volatile
    private var dir: File? = null
    @Volatile
    private var curDay: String = ""
    @Volatile
    private var logFile: File? = null

    fun init(filesDir: File) {
        val d = File(filesDir, "logs")
        d.mkdirs()
        dir = d
        roll()
    }

    /** 切到今天的文件；顺手删掉昨天及更早的按天日志与旧版遗留（app.log） */
    @Synchronized
    private fun roll() {
        val d = dir ?: return
        val todayName = day.format(Date())
        if (todayName == curDay && logFile != null) return
        try {
            for (f in (d.listFiles() ?: emptyArray())) {
                val n = f.name
                val legacy = n == "app.log" || n == "app.prev.log"
                val m = dayFile.find(n)
                val stale = m != null && m.groupValues[1] < todayName
                if (legacy || stale) f.delete()
            }
        } catch (_: Throwable) {
        }
        curDay = todayName
        logFile = File(d, "log$todayName.txt")
    }

    /** 当天日志文件（首页查看器显示路径用） */
    fun todayFile(): File? {
        roll()
        return logFile
    }

    fun d(msg: String) = write("D", msg)
    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, tr: Throwable? = null) =
        write("E", if (tr == null) msg else msg + "\n" + Log.getStackTraceString(tr))

    fun now(): String = ts.format(Date())
    fun today(): String = day.format(Date())

    @Synchronized
    private fun write(level: String, msg: String) {
        Log.println(priority(level), TAG, msg)
        roll()
        val f = logFile ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val txt = f.readText()
                f.writeText(txt.substring(txt.length / 2))
            }
            f.appendText("${now()} $level $msg\n")
        } catch (_: Throwable) {
            // 落盘失败不能影响主流程
        }
    }

    private fun priority(level: String) = when (level) {
        "E" -> Log.ERROR
        "W" -> Log.WARN
        "I" -> Log.INFO
        else -> Log.DEBUG
    }

    /** 读取当天日志的最近 N 行（上限 20000），可按关键字过滤 */
    fun tail(lines: Int = 500, filter: String? = null): String {
        roll()
        val f = logFile ?: return ""
        return try {
            val src = if (f.exists()) f.readText().lines() else emptyList()
            val sel = if (filter.isNullOrBlank()) src
            else src.filter { it.contains(filter, ignoreCase = true) }
            sel.takeLast(lines.coerceIn(1, 20000)).joinToString("\n") + "\n"
        } catch (t: Throwable) {
            "log read error: ${t.message}\n"
        }
    }

    fun clear() {
        roll()
        try {
            logFile?.writeBytes(ByteArray(0))
        } catch (_: Throwable) {
        }
    }
}
