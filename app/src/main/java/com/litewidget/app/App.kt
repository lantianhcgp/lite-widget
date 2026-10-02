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
        AppLog.init(filesDir)
        prefs = Prefs(this)
        store = WidgetStore(filesDir)
        assetLoader = AssetLoader(filesDir)
        data = DataRepo(this)
        copySchema()
        AppLog.i("App started, widgetsDir=${store.dir.absolutePath}")
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
