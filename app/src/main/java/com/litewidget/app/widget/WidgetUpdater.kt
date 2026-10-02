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
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * 渲染组件 → 推到桌面。
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

    /** 后台刷新数据 + 推送所有桌面小组件 */
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
                doPush(ctx, widgetId)
            } catch (t: Throwable) {
                AppLog.e("$TAG push fail", t)
            }
        }
    }

    private fun doPush(ctx: Context, id: String) {
        val store = App.instance.store
        if (id.isEmpty() || !store.exists(id)) {
            val first = store.list().firstOrNull()
            if (first == null) {
                AppLog.w("$TAG no widget to render")
                return
            }
            return doPush(ctx, first.id)
        }
        val spec = store.readSpec(id) ?: run {
            AppLog.e("$TAG widget.json unreadable: $id")
            return
        }
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = providers(ctx).flatMap { mgr.getAppWidgetIds(it).toList() }.distinct()
        if (ids.isEmpty()) {
            AppLog.i("$TAG no home widget instance yet（桌面还没添加组件）")
            return
        }
        val renderer = Renderer(App.instance.assetLoader.forWidget(id))
        val values = App.instance.data.forRender()

        for (appWidgetId in ids) {
            val (wPx, hPx) = sizeOf(ctx, mgr, appWidgetId)
            val density = ctx.resources.displayMetrics.density
            try {
                val bmp = renderer.render(spec, values, wPx, hPx, density)
                val rv = RemoteViews(ctx.packageName, R.layout.widget_host)
                rv.setImageViewBitmap(R.id.widget_image, bmp)
                mgr.updateAppWidget(appWidgetId, rv)
            } catch (t: Throwable) {
                AppLog.e("$TAG render fail id=$appWidgetId size=${wPx}x${hPx}", t)
            }
        }
        AppLog.i("$TAG pushed $id -> ${ids.size} widget(s)")
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

    /** 供预览用：按 dp 尺寸渲染 */
    fun previewBitmap(ctx: Context, id: String, wDp: Int, hDp: Int): Bitmap? {
        val store = App.instance.store
        val spec = store.readSpec(id) ?: return null
        val density = ctx.resources.displayMetrics.density
        val w = (wDp * density).toInt().coerceIn(1, MAX_PX)
        val h = (hDp * density).toInt().coerceIn(1, MAX_PX)
        return try {
            val density = Renderer.previewDensity(spec, w)
            Renderer(App.instance.assetLoader.forWidget(id))
                .render(spec, App.instance.data.forRender(), w, h, density)
        } catch (t: Throwable) {
            AppLog.e("$TAG preview fail $id", t)
            null
        }
    }
}
