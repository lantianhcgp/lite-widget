package com.litewidget.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.RemoteViews
import com.litewidget.app.App
import com.litewidget.app.R
import com.litewidget.app.core.AppLog
import com.litewidget.app.core.render.Renderer
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    /** 桌面上全部组件实例 id（AlarmScheduler 排闹钟也用） */
    fun instanceIds(ctx: Context): List<Int> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers(ctx).flatMap { mgr.getAppWidgetIds(it).toList() }.distinct()
    }

    /** 实例绑定的模板；无绑定回退当前激活组件；都没有则返回空串 */
    fun designOf(appWidgetId: Int): String {
        val prefs = App.instance.prefs
        val store = App.instance.store
        var d = prefs.binding(appWidgetId)
        if (d.isEmpty() || !store.exists(d)) d = prefs.activeWidget
        if (d.isEmpty() || !store.exists(d)) return ""
        return d
    }

    /** 实例生效的刷新频率（分钟）：实例显式设置优先，否则继承模板级；0 = 不自动刷新 */
    fun intervalOf(appWidgetId: Int, design: String): Int {
        val prefs = App.instance.prefs
        return prefs.instanceInterval(appWidgetId) ?: prefs.refreshInterval(design)
    }

    /**
     * 最近一次「到点重绘」的时刻（ms epoch）——AlarmScheduler 据此设置下一次精确闹钟。
     * 返回 null = 没有任何设置了自动刷新的实例（无需闹钟）。
     */
    fun nextDueAt(ctx: Context, now: Long): Long? {
        var min = Long.MAX_VALUE
        for (wid in instanceIds(ctx)) {
            val design = designOf(wid)
            if (design.isEmpty()) continue
            val iv = intervalOf(wid, design)
            if (iv <= 0) continue
            val last = App.instance.prefs.instLastRender(wid)
            // 从未渲染过（last=0）视为已到点，交给 AlarmScheduler 的「最近 1 分钟后」兜底
            val due = if (last <= 0) now - iv * 60_000L else last + iv * 60_000L
            if (due < min) min = due
        }
        return if (min == Long.MAX_VALUE) null else min
    }

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
            val ids = instanceIds(ctx)
            if (ids.isEmpty()) return
            // 到点判定按桌面实例：实例显式频率优先，未设置继承模板级
            val dueIds = HashSet<Int>()
            val dueDesigns = HashSet<String>()
            for (wid in ids) {
                val design = designOf(wid)
                if (design.isEmpty()) continue
                val iv = intervalOf(wid, design)
                if (iv > 0 && now - prefs.instLastRender(wid) >= iv * 60_000L) {
                    dueIds.add(wid); dueDesigns.add(design)
                }
            }
            if (dueIds.isEmpty()) return
            AppLog.i("$TAG tick due=$dueIds designs=$dueDesigns")
            if (prefs.hasSource()) {
                try {
                    App.instance.data.refresh()
                } catch (t: Throwable) {
                    AppLog.w("$TAG tick refresh fail: ${t.message}（用缓存渲染）")
                }
            }
            doPush(ctx, prefs.activeWidget, only = dueDesigns, onlyInst = dueIds)
            val ts = System.currentTimeMillis()
            dueIds.forEach { prefs.setInstLastRender(it, ts) }
            dueDesigns.forEach { prefs.setLastRender(it, ts) }
        } catch (t: Throwable) {
            AppLog.e("$TAG tick fail", t)
        }
    }

    /** only = null 全量推；否则只推绑定到这些模板的实例。onlyInst 非空时再按实例白名单过滤 */
    private fun doPush(ctx: Context, defaultId: String, only: Set<String>?, onlyInst: Set<Int>? = null) {
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
            if (onlyInst != null && appWidgetId !in onlyInst) continue
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
                val stamped = stamp(bmp, System.currentTimeMillis(), density)
                val rv = RemoteViews(ctx.packageName, R.layout.widget_host)
                rv.setImageViewBitmap(R.id.widget_image, stamped)
                mgr.updateAppWidget(appWidgetId, rv)
                pushed++
            } catch (t: Throwable) {
                AppLog.e("$TAG render fail id=$appWidgetId size=${wPx}x${hPx}", t)
            }
        }
        AppLog.i("$TAG pushed $id -> $pushed widget(s)" + if (only != null) " due=$only" else "")
    }

    /**
     * 组件右下角盖「上次刷新时间」角标：半透明圆角底衬 + 浅色小字，深浅底都可读。
     * 只在真正推桌面的路径调（列表预览/画廊不打戳）。
     */
    private fun stamp(bmp: Bitmap, ts: Long, density: Float): Bitmap {
        val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val textSize = 9.5f * density
        val padH = 5f * density
        val padV = 3f * density
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = textSize
        val text = formatStamp(ts)
        val w = p.measureText(text) + padH * 2
        val h = textSize + padV * 2
        val right = out.width - 4f * density
        val bottom = out.height - 4f * density
        p.color = 0xE6000000.toInt() // 黑 90% 底衬
        c.drawRoundRect(RectF(right - w, bottom - h, right, bottom), 4f * density, 4f * density, p)
        p.color = 0xCCFFFFFF.toInt() // 白 80% 文字
        c.drawText(text, right - padH - p.measureText(text), bottom - padV - p.descent(), p)
        return out
    }

    /** 今天只显 HH:mm，跨天补 MM-dd 前缀 */
    private fun formatStamp(ts: Long): String {
        val now = System.currentTimeMillis()
        val pat = if (sameDay(ts, now)) "刷新 HH:mm" else "刷新 MM-dd HH:mm"
        return SimpleDateFormat(pat, Locale.US).format(Date(ts))
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US)
        return f.format(Date(a)) == f.format(Date(b))
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
