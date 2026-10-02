package com.litewidget.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.litewidget.app.App
import com.litewidget.app.R
import com.litewidget.app.core.AppLog
import com.litewidget.app.core.render.Renderer
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * 渲染组件 → 推到桌面。
 * - 每个桌面实例可绑定不同模板（prefs.binding）
 * - 每个模板可设置自动刷新频率（prefs.refreshInterval），tickNow 只处理到点的
 * 数据刷新放后台线程，推桌面用 AppWidgetManager（任意线程可调）。
 */
object WidgetUpdater {

    private const val TAG = "WidgetUpdater"
    private const val MAX_PX = 1600

    /** 全部尺寸入口（改动规格时同步 Manifest） */
    private fun providers(ctx: Context) = listOf(
        WidgetProvider2x2::class.java,
        WidgetProvider4x1::class.java,
        WidgetProvider4x2::class.java,
        WidgetProvider2x4::class.java,
        WidgetProvider4x4::class.java
    ).map { ComponentName(ctx, it) }

    /** 手动推送：刷新数据（可选）后把所有桌面实例重绘一遍 */
    fun pushAll(ctx: Context, id: String? = null, refreshData: Boolean = true) {
        val widgetId = id ?: App.instance.prefs.activeWidget
        thread(name = "widget-push", isDaemon = true) {
            try {
                if (refreshData && App.instance.prefs.hasSource()) {
                    try {
                        App.instance.data.refresh()
                    } catch (t: Throwable) {
                        AppLog.w("$TAG data refresh failed: ${t.message}（用缓存继续渲染）")
                    }
                }
                doPush(ctx, widgetId, only = null)
            } catch (t: Throwable) {
                AppLog.e("$TAG push fail", t)
            }
        }
    }

    /**
     * 定时调度：只处理「到点」的模板（每个模板单独设置频率，0 = 不自动刷新）。
     * 同步执行——调用方负责放后台线程（Handler 轮询 / AlarmManager receiver）。
     */
    fun tickNow(ctx: Context) {
        try {
            val prefs = App.instance.prefs
            val store = App.instance.store
            val now = System.currentTimeMillis()
            val due = store.list().map { it.id }.filter { wid ->
                val iv = prefs.refreshInterval(wid)
                iv > 0 && now - prefs.lastRender(wid) >= iv * 60_000L
            }.toSet()
            if (due.isEmpty()) return
            AppLog.i("$TAG tick due=$due")
            if (prefs.hasSource()) {
                try {
                    App.instance.data.refresh()
                } catch (t: Throwable) {
                    AppLog.w("$TAG tick refresh fail: ${t.message}（用缓存渲染）")
                }
            }
            doPush(ctx, prefs.activeWidget, only = due)
            val ts = System.currentTimeMillis()
            due.forEach { prefs.setLastRender(it, ts) }
        } catch (t: Throwable) {
            AppLog.e("$TAG tick fail", t)
        }
    }

    /** only = null 全量推；否则只推绑定到这些模板的实例 */
    private fun doPush(ctx: Context, defaultId: String, only: Set<String>?) {
        val store = App.instance.store
        val prefs = App.instance.prefs
        var id = defaultId
        if (id.isEmpty() || !store.exists(id)) {
            val first = store.list().firstOrNull()
            if (first == null) {
                AppLog.w("$TAG no widget to render")
                return
            }
            id = first.id
        }
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = providers(ctx).flatMap { mgr.getAppWidgetIds(it).toList() }.distinct()
        if (ids.isEmpty()) {
            AppLog.i("$TAG no home widget instance yet（桌面还没添加组件）")
            return
        }
        val values = App.instance.data.forRender()
        val density = ctx.resources.displayMetrics.density
        val specCache = HashMap<String, JSONObject?>()
        var pushed = 0
        for (appWidgetId in ids) {
            // 实例绑定优先，其次当前激活组件
            var design = prefs.binding(appWidgetId)
            if (design.isEmpty() || !store.exists(design)) design = id
            if (only != null && design !in only) continue
            if (!specCache.containsKey(design)) specCache[design] = store.readSpec(design)
            val spec = specCache[design]
            if (spec == null) {
                AppLog.e("$TAG widget.json unreadable: $design")
                continue
            }
            val (wPx, hPx) = sizeOf(ctx, mgr, appWidgetId)
            try {
                val bmp = Renderer(App.instance.assetLoader.forWidget(design))
                    .render(spec, values, wPx, hPx, density)
                val rv = RemoteViews(ctx.packageName, R.layout.widget_host)
                rv.setImageViewBitmap(R.id.widget_image, bmp)
                mgr.updateAppWidget(appWidgetId, rv)
                pushed++
            } catch (t: Throwable) {
                AppLog.e("$TAG render fail id=$appWidgetId size=${wPx}x${hPx}", t)
            }
        }
        AppLog.i("$TAG pushed $id -> $pushed widget(s)" + if (only != null) " due=$only" else "")
    }

    private fun sizeOf(ctx: Context, mgr: AppWidgetManager, appWidgetId: Int): Pair<Int, Int> {
        val opts = mgr.getAppWidgetOptions(appWidgetId)
        val density = ctx.resources.displayMetrics.density
        val minWdp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val minHdp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        var w = if (minWdp > 0) (minWdp * density).toInt() else (400 * density).toInt()
        var h = if (minHdp > 0) (minHdp * density).toInt() else (200 * density).toInt()
        w = min(max(w, 160), MAX_PX)
        h = min(max(h, 120), MAX_PX)
        return w to h
    }

    /** 供列表预览：按组件声明的规范尺寸出图（有变体时展示对应变体），ImageView fitCenter 自适应 */
    fun previewBitmap(ctx: Context, id: String, wDp: Int, hDp: Int): Bitmap? {
        val store = App.instance.store
        val spec = store.readSpec(id) ?: return null
        return try {
            val size = store.readManifest(id)?.optString("size", "")?.takeIf { it.isNotEmpty() }
            val canonical = size?.let { Renderer.CANONICAL[it] }
            val w: Int; val h: Int; val density: Float
            if (canonical != null) {
                density = 3f
                w = (canonical.first * density).toInt()
                h = (canonical.second * density).toInt()
            } else {
                density = ctx.resources.displayMetrics.density
                w = (wDp * density).toInt()
                h = (hDp * density).toInt()
            }
            Renderer(App.instance.assetLoader.forWidget(id))
                .render(spec, App.instance.data.forRender(), w, h, density)
        } catch (t: Throwable) {
            AppLog.e("$TAG preview fail $id", t)
            null
        }
    }

    /** 按规范尺寸出预览（画廊用）：设计空间 = 该尺寸规范 dp，渲染器自动取对应变体 */
    fun previewBitmap(ctx: Context, id: String, size: String): Bitmap? {
        val store = App.instance.store
        val spec = store.readSpec(id) ?: return null
        val c = Renderer.CANONICAL[size] ?: return previewBitmap(ctx, id, 360, 180)
        return try {
            val d = 3f
            Renderer(App.instance.assetLoader.forWidget(id))
                .render(spec, App.instance.data.forRender(), (c.first * d).toInt(), (c.second * d).toInt(), d)
        } catch (t: Throwable) {
            AppLog.e("$TAG gallery preview fail $id/$size", t)
            null
        }
    }
}
