package com.litewidget.app

import android.app.Application
import com.litewidget.app.core.AppLog
import com.litewidget.app.core.Prefs
import com.litewidget.app.core.data.DataRepo
import com.litewidget.app.core.store.WidgetStore
import com.litewidget.app.core.render.AssetLoader

class App : Application() {

    lateinit var store: WidgetStore
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var data: DataRepo
        private set
    lateinit var assetLoader: AssetLoader
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashCapture()
        AppLog.init(filesDir)
        prefs = Prefs(this)
        store = WidgetStore(filesDir)
        assetLoader = AssetLoader(filesDir)
        data = DataRepo(this)
        copySchema()
        AppLog.i("App started, widgetsDir=${store.dir.absolutePath}")
    }

    val crashFile: java.io.File
        get() = java.io.File(filesDir, "crash.txt")

    /**
     * 启动崩溃自捕获：把未捕获异常完整落盘，下次启动在首页弹出来。
     * 没有 adb / logcat 的环境下，这是拿到堆栈的唯一途径。
     */
    private fun installCrashCapture() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sb = StringBuilder()
                sb.append("==== crash @ ").append(System.currentTimeMillis()).append(" ====\n")
                sb.append("thread: ").append(thread.name).append('\n')
                sb.append(android.util.Log.getStackTraceString(throwable))
                sb.append("\n----\n")
                val f = crashFile
                f.parentFile?.mkdirs()
                f.appendText(sb.toString())
            } catch (_: Throwable) {
            }
            if (prev != null) prev.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** 把内置 schema 落到沙箱，MCP 的 schema_get 直接读它 */
    private fun copySchema() {
        try {
            val dst = java.io.File(filesDir, "schema.json")
            if (!dst.exists()) {
                getAssets().open("schema.json").use { ins ->
                    dst.outputStream().use { outs -> ins.copyTo(outs) }
                }
                AppLog.i("schema.json installed")
            }
        } catch (t: Throwable) {
            AppLog.w("schema copy fail: ${t.message}")
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
