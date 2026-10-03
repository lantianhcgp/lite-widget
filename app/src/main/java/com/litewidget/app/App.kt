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

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase)
        com.litewidget.app.core.Trace.mark(newBase, "1 App.attachBaseContext OK")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.litewidget.app.core.Trace.mark(this, "2 App.onCreate start")
        installCrashCapture()
        try {
            AppLog.init(filesDir)
            com.litewidget.app.core.Trace.mark(this, "3 AppLog.init OK")
            prefs = Prefs(this)
            store = WidgetStore(filesDir)
            store.ensureBuiltins()
            com.litewidget.app.core.Trace.mark(this, "4 WidgetStore OK")
            assetLoader = AssetLoader(filesDir)
            data = DataRepo(this)
            com.litewidget.app.core.Trace.mark(this, "5 DataRepo/AssetLoader OK")
            copySchema()
            com.litewidget.app.core.Trace.mark(this, "6 copySchema OK")
            prefs.migrateLegacyVars()
            startRefreshTicker()
            AppLog.i("App started, widgetsDir=${store.dir.absolutePath}")
        } catch (t: Throwable) {
            com.litewidget.app.core.Trace.error(this, "App.onCreate FAILED\n" + android.util.Log.getStackTraceString(t))
            // Application 初始化失败：落盘后继续，让首页有机会把堆栈弹出来
            try {
                crashFile.appendText(
                    "==== App.onCreate failed ====\n" +
                        android.util.Log.getStackTraceString(t) + "\n----\n"
                )
            } catch (_: Throwable) {
            }
        }
    }

    val crashFile: java.io.File
        get() = java.io.File(filesDir, "crash.txt")

    /**
     * 自动刷新：进程内 Handler 45 秒轮询（主力）+ AlarmManager 每分钟兜底（进程被杀时唤醒）。
     * 到不到点由 WidgetUpdater.tickNow 按每个组件各自设置的频率判断。
     */
    private fun startRefreshTicker() {
        try {
            val h = android.os.Handler(mainLooper)
            val task = object : Runnable {
                override fun run() {
                    try {
                        com.litewidget.app.widget.WidgetUpdater.tickNow(this@App)
                    } catch (t: Throwable) {
                        AppLog.w("ticker tick fail: ${t.message}")
                    }
                    h.postDelayed(this, 45_000L)
                }
            }
            h.postDelayed(task, 45_000L)
            val am = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                this, 701,
                android.content.Intent(this, com.litewidget.app.widget.RefreshReceiver::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000L,
                60_000L, pi
            )
            AppLog.i("refresh ticker started (45s handler + 60s alarm)")
        } catch (t: Throwable) {
            AppLog.w("ticker start fail: ${t.message}")
        }
    }

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
            try {
                com.litewidget.app.core.Trace.error(
                    this@App,
                    "UNCAUGHT on ${thread.name}\n" + android.util.Log.getStackTraceString(throwable)
                )
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
