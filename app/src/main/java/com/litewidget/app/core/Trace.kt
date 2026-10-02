package com.litewidget.app.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动进度 + 崩溃栈落盘。双写：app 私有目录 + 公共 Download（Termux 能直接读文件）。
 * 崩溃哪怕发生在我们代码之前（类加载/主题），只要走到 mark() 就有痕迹。
 */
object Trace {

    const val NAME = "lw-trace.txt"

    fun now(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    fun mark(ctx: Context?, stage: String) {
        val line = "${now()}  $stage\n"
        try {
            ctx?.filesDir?.let { File(it, "trace.txt").appendText(line) }
        } catch (_: Throwable) {
        }
        try {
            appendShared(ctx, line)
        } catch (_: Throwable) {
        }
    }

    /** 崩溃栈用它，能带长文本 */
    fun error(ctx: Context?, text: String) {
        val body = "==== ${now()} ====\n$text\n"
        try {
            ctx?.filesDir?.let { File(it, "trace.txt").appendText(body) }
        } catch (_: Throwable) {
        }
        try {
            appendShared(ctx, body)
        } catch (_: Throwable) {
        }
    }

    private fun appendShared(ctx: Context?, data: String) {
        if (ctx == null) return
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver ?: return
            var uri: Uri? = null
            cr.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(NAME), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                }
            }
            if (uri == null) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
            }
            cr.openOutputStream(uri!!, "wa")?.use { o -> o.write(data.toByteArray(Charsets.UTF_8)) }
        } else {
            File("/storage/emulated/0/Download", NAME).appendText(data)
        }
    }
}
