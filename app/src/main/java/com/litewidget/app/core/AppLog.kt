package com.litewidget.app.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志：logcat + 落盘文件（按大小滚动），MCP 通过 log.tail 读取。
 * 规则：日志完整不截断，UI 上只显示摘要。
 */
object AppLog {

    private const val TAG = "LiteWidget"
    private const val MAX_BYTES = 512L * 1024
    private const val ROTATED = "app.prev.log"

    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile
    private var dir: File? = null
    @Volatile
    private var logFile: File? = null

    fun init(filesDir: File) {
        val d = File(filesDir, "logs")
        d.mkdirs()
        dir = d
        logFile = File(d, "app.log")
    }

    fun d(msg: String) = write("D", msg)
    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, tr: Throwable? = null) =
        write("E", if (tr == null) msg else msg + "\n" + Log.getStackTraceString(tr))

    fun now(): String = ts.format(Date())
    fun today(): String = day.format(Date())

    private fun write(level: String, msg: String) {
        Log.println(priority(level), TAG, msg)
        val f = logFile ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val prev = File(f.parentFile, ROTATED)
                if (prev.exists()) prev.delete()
                f.renameTo(prev)
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

    /** 读取最近 N 行，可按关键字过滤（MCP 工具 log.tail） */
    fun tail(lines: Int = 200, filter: String? = null): String {
        val f = logFile ?: return ""
        val out = StringBuilder()
        try {
            val src = ArrayList<String>()
            val prev = File(f.parentFile, ROTATED)
            if (prev.exists()) src.addAll(prev.readText().lines())
            if (f.exists()) src.addAll(f.readText().lines())
            val sel = if (filter.isNullOrBlank()) src
            else src.filter { it.contains(filter, ignoreCase = true) }
            val cut = sel.takeLast(lines.coerceIn(1, 5000))
            cut.forEach { out.append(it).append('\n') }
        } catch (t: Throwable) {
            out.append("log read error: ").append(t.message)
        }
        return out.toString()
    }

    fun clear() {
        try {
            logFile?.writeBytes(ByteArray(0))
            File(logFile?.parentFile, ROTATED).delete()
        } catch (_: Throwable) {
        }
    }
}
