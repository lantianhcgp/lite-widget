package com.litewidget.app.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.litewidget.app.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas 自绘渲染引擎：widget.json -> Bitmap。
 *
 * 设计坐标系 = spec.canvas（design unit），渲染时按 fit 缩放铺满输出位图。
 * 布局：measure 单趟完成「量尺寸 + 摆位置」（子节点坐标相对父节点）。
 * 好看的上限来自这里：渐变、圆角、阴影、描边、文字渐变、圆弧、变换，全部像素级可控。
 */
class Renderer(private val assets: WidgetAssets) {

    /** 数据上下文：绑定字段取值 */
    class Ctx(val values: Map<String, Any?>, val vars: JSONObject?)

    private class L(val o: JSONObject) {
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f
        val kids = ArrayList<L>()
        var lines: List<String> = emptyList()
        var visible = true
    }

    private sealed class Sz {
        data class Px(val v: Float) : Sz()
        object Fill : Sz()
        object Hug : Sz()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private lateinit var ctx: Ctx
    private var nodeAlpha = 255

    companion object {
        const val UNBOUNDED = Float.POSITIVE_INFINITY
        private var frostLogged = false
        private var frostFailed = false

        fun parseColor(s: String?): Int {
            if (s.isNullOrEmpty()) return Color.TRANSPARENT
            return try {
                Color.parseColor(s)
            } catch (t: Throwable) {
                Color.TRANSPARENT
            }
        }

        private fun fmtNum(d: Double, dec: Int): String =
            String.format(Locale.US, "%.${dec}f", d)

        /** 预览用密度：让 auto 模式按 spec 画布渲染（720px 宽 → density=2 → 设计宽 360） */
        fun previewDensity(spec: JSONObject, outW: Int, size: String? = null): Float {
            if (size != null) {
                val c = CANONICAL[size]
                if (c != null && c.first > 0f) return outW / c.first
            }
            val cw = spec.optJSONObject("canvas")?.optDouble("width", 360.0)?.toFloat() ?: 360f
            return if (cw > 0f) outW / cw else 1f
        }

        /** 各尺寸的规范设计空间（dp）：变体按此对号入座 */
        val CANONICAL: Map<String, Pair<Float, Float>> = linkedMapOf(
            "4x1" to Pair(360f, 90f),
            "4x2" to Pair(360f, 180f),
            "2x2" to Pair(180f, 180f),
            "4x4" to Pair(360f, 360f),
            "2x4" to Pair(180f, 360f)
        )

        /** 按设计空间 dp 归类到最接近的规范尺寸（对数距离，容忍启动器的尺寸偏差） */
        fun classify(dw: Float, dh: Float): String {
            var best = "4x2"
            var bestD = Double.MAX_VALUE
            for ((k, s) in CANONICAL) {
                val d = abs(ln((dw / s.first).toDouble())) + abs(ln((dh / s.second).toDouble()))
                if (d < bestD) { bestD = d; best = k }
            }
            return best
        }

        /** 选取尺寸变体：variants.<size>.root 优先，找不到回退顶层 root（老组件兼容） */
        fun selectVariant(spec: JSONObject, dw: Float, dh: Float): JSONObject {
            val vs = spec.optJSONObject("variants") ?: return spec
            val root = vs.optJSONObject(classify(dw, dh))?.optJSONObject("root") ?: return spec
            val out = JSONObject(spec.toString())
            out.put("root", root)
            return out
        }
    }

    // ------------------------------------------------------------------ 入口

    fun render(
        spec: JSONObject,
        values: Map<String, Any?>,
        outW: Int,
        outH: Int,
        density: Float = 1f
    ): Bitmap {
        val canvas = spec.optJSONObject("canvas")
        val cw = canvas?.optDouble("width", 360.0)?.toFloat() ?: 360f
        val ch = canvas?.optDouble("height", 180.0)?.toFloat() ?: 180f
        val fit = canvas?.optString("fit", "contain") ?: "contain"
        // auto：设计坐标 = 组件实际 dp 尺寸 → 字号物理大小处处一致，布局按 flex 重排（自适应）
        var dw = cw
        var dh = ch
        if (fit == "auto" && density > 0f && density.isFinite()) {
            dw = outW / density
            dh = outH / density
        }
        // 变体优先：按设计空间尺寸挑 variants.<size>；没有对应变体则用顶层 root
        val active = selectVariant(spec, dw, dh)
        val rootObj = active.optJSONObject("root")
            ?: throw IllegalArgumentException("widget.json 缺少 root")
        ctx = Ctx(values, active.optJSONObject("vars"))

        val root = L(rootObj)
        measure(root, dw, dh)
        if (!root.w.isFinite() || !root.h.isFinite() || root.w <= 0f || root.h <= 0f) {
            AppLog.e("root size 非法: ${root.w}x${root.h}（spec 尺寸可能写错）")
            root.w = dw
            root.h = dh
        }

        val w = outW.coerceAtLeast(1)
        val h = outH.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sx = w / dw
        val sy = h / dh
        when (fit) {
            "stretch", "fill", "auto" -> c.scale(sx, sy)
            "cover" -> {
                val s = max(sx, sy)
                c.translate((w - cw * s) / 2f, (h - ch * s) / 2f)
                c.scale(s, s)
            }
            else -> {
                val s = min(sx, sy)
                c.translate((w - cw * s) / 2f, (h - ch * s) / 2f)
                c.scale(s, s)
            }
        }
        draw(c, root, 0f, 0f, 255)
        return bmp
    }

    // ------------------------------------------------------------------ 测量 + 布局

    private fun measure(l: L, maxW: Float, maxH: Float) {
        l.visible = evalVisible(l.o)
        if (!l.visible) {
            l.w = 0f
            l.h = 0f
            return
        }
        when (l.o.optString("type", "")) {
            "frame" -> measureFrame(l, maxW, maxH)
            "text" -> measureText(l, maxW, maxH)
            "image" -> measureImage(l, maxW, maxH)
            "progress" -> measureProgress(l, maxW, maxH)
            "spacer" -> measureSpacer(l, maxW, maxH)
            else -> {
                l.w = 0f
                l.h = 0f
            }
        }
    }

    private fun sizeOf(o: JSONObject, key: String, bound: Float): Sz {
        var v: Any? = o.opt(key)
        if (v == null || v === JSONObject.NULL) {
            val st = o.optJSONObject("style") ?: return Sz.Hug
            v = st.opt(key)
        }
        return when {
            v == null || v === JSONObject.NULL -> Sz.Hug
            v is String && v == "fill" -> if (bound.isFinite()) Sz.Fill else Sz.Hug
            v is String && v == "hug" -> Sz.Hug
            v is Number -> Sz.Px(v.toFloat())
            else -> Sz.Hug
        }
    }

    private fun box(o: JSONObject, key: String): FloatArray {
        val v = o.opt(key)
        return when (v) {
            is Number -> {
                val f = v.toFloat()
                floatArrayOf(f, f, f, f)
            }
            is JSONArray -> {
                val a = FloatArray(4)
                for (i in 0..3) a[i] = if (i < v.length()) (v.opt(i) as? Number)?.toFloat() ?: 0f else 0f
                a
            }
            else -> FloatArray(4)
        }
    }

    private fun isMainFill(k: L, horiz: Boolean): Boolean {
        val type = k.o.optString("type")
        if (type == "spacer") {
            val v = k.o.opt("size")
            return v is String && v == "fill"
        }
        val key = if (horiz) "width" else "height"
        return sizeOf(k.o, key, 1f) is Sz.Fill
    }

    private fun measureFrame(l: L, maxW: Float, maxH: Float) {
        val o = l.o
        val pad = box(o, "padding")
        val dir = o.optString("direction", "vertical")
        val horiz = dir == "horizontal"
        val absolute = dir == "absolute"

        val wS = sizeOf(o, "width", maxW)
        val hS = sizeOf(o, "height", maxH)
        val myW = if (wS is Sz.Px) wS.v else if (wS is Sz.Fill) maxW else -1f
        val myH = if (hS is Sz.Px) hS.v else if (hS is Sz.Fill) maxH else -1f
        val availW = if (myW >= 0f) myW else maxW
        val availH = if (myH >= 0f) myH else maxH
        val innerW = (availW - pad[3] - pad[1]).coerceAtLeast(0f)
        val innerH = (availH - pad[0] - pad[2]).coerceAtLeast(0f)
        val gap = o.optDouble("gap", 0.0).toFloat()

        l.kids.clear()
        val arr = o.optJSONArray("children") ?: JSONArray()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { l.kids.add(L(it)) }
        }
        val n = l.kids.size

        if (absolute) {
            l.kids.forEach { measure(it, innerW, innerH) }
            val hw = l.kids.maxOfOrNull { it.x + it.w } ?: 0f
            val hh = l.kids.maxOfOrNull { it.y + it.h } ?: 0f
            l.w = if (myW >= 0f) myW else pad[3] + pad[1] + hw
            l.h = if (myH >= 0f) myH else pad[0] + pad[2] + hh
            return
        }

        // 第一趟：主轴不设限 → Fill 子节点按自身 hug 尺寸量
        l.kids.forEach {
            if (horiz) measure(it, UNBOUNDED, innerH)
            else measure(it, innerW, UNBOUNDED)
        }

        fun mainOf(k: L) = if (horiz) k.w else k.h
        fun crossOf(k: L) = if (horiz) k.h else k.w
        fun setMain(k: L, v: Float) {
            if (horiz) k.w = v else k.h = v
        }

        val fills = l.kids.filter { isMainFill(it, horiz) }
        val parentMainFixed = (horiz && myW >= 0f) || (!horiz && myH >= 0f)
        val innerMain = (if (horiz) availW - pad[3] - pad[1] else availH - pad[0] - pad[2])
            .coerceAtLeast(0f)
        val fixedSum = l.kids.filter { !isMainFill(it, horiz) }
            .sumOf { mainOf(it).toDouble() }.toFloat()
        val gaps = gap * max(n - 1, 0)
        val free = if (parentMainFixed) innerMain - fixedSum - gaps else 0f
        val fillSize = if (fills.isEmpty() || !parentMainFixed) 0f
        else (free / fills.size).coerceAtLeast(0f)

        // 第二趟：Fill 子节点用分配到的真实主轴尺寸重量一次
        l.kids.forEach {
            if (isMainFill(it, horiz)) {
                if (horiz) measure(it, fillSize, innerH)
                else measure(it, innerW, fillSize)
                setMain(it, fillSize)
            }
        }

        val rawCross = if (horiz) innerH else innerW
        val align = o.optString("align", "start")
        // 父级以 UNBOUNDED 量我们时（hug pass1），交叉轴是无穷大 ——
        // 直接拿去算 align 会得到 Infinity/NaN 坐标，进而让 LinearGradient 崩掉。
        // 兜底：交叉轴取子节点实测最大值，align 在真实内容盒内对齐。
        val innerCross = if (rawCross.isFinite()) rawCross
        else (l.kids.maxOfOrNull { crossOf(it) } ?: 0f)
        if (align == "stretch" && rawCross.isFinite()) {
            l.kids.forEach {
                if (!crossExplicit(it, horiz)) {
                    if (horiz) it.h = innerCross else it.w = innerCross
                }
            }
        }

        // 摆位
        val justify = o.optString("justify", "start")
        val totalMain = l.kids.sumOf { mainOf(it).toDouble() }.toFloat() + gaps
        val freeSpace = (if (parentMainFixed) innerMain else totalMain) - totalMain
        var cursor = if (horiz) pad[3] else pad[0]
        var extra = 0f
        when (justify) {
            "center" -> cursor += freeSpace / 2f
            "end" -> cursor += freeSpace
            "space-between" -> if (n > 1) extra = freeSpace / (n - 1)
            "space-around" -> if (n > 0) {
                extra = freeSpace / n
                cursor += extra / 2f
            }
        }
        for (k in l.kids) {
            val off = crossOffset(crossOf(k), innerCross, align)
            if (horiz) {
                k.x = cursor
                k.y = pad[0] + off
            } else {
                k.y = cursor
                k.x = pad[3] + off
            }
            cursor += mainOf(k) + gap + extra
        }

        if (horiz) {
            l.w = if (myW >= 0f) myW else pad[3] + pad[1] + totalMain
            l.h = if (myH >= 0f) myH else pad[0] + pad[2] + (l.kids.maxOfOrNull { crossOf(it) } ?: 0f)
        } else {
            l.h = if (myH >= 0f) myH else pad[0] + pad[2] + totalMain
            l.w = if (myW >= 0f) myW else pad[3] + pad[1] + (l.kids.maxOfOrNull { crossOf(it) } ?: 0f)
        }
    }

    private fun crossExplicit(k: L, horiz: Boolean): Boolean {
        val key = if (horiz) "height" else "width"
        return sizeOf(k.o, key, 1f) is Sz.Px
    }

    private fun crossOffset(size: Float, inner: Float, align: String): Float = when (align) {
        "center" -> (inner - size) / 2f
        "end" -> inner - size
        else -> 0f
    }

    private fun measureText(l: L, maxW: Float, maxH: Float) {
        val o = l.o
        val size = o.optDouble("size", 14.0).toFloat()
        val lh = o.optDouble("lineHeight", 1.25).toFloat()
        paint.typeface = assets.typeface(o.optString("family", "default"))
        paint.textSize = size
        paint.letterSpacing = o.optDouble("letterSpacing", 0.0).toFloat()
        paint.isFakeBoldText = o.optInt("weight", 400) >= 600
        paint.textAlign = Paint.Align.LEFT

        val raw = textContent(o)
        val text = when (o.optString("case", "none")) {
            "upper" -> raw.uppercase(Locale.ROOT)
            "lower" -> raw.lowercase(Locale.ROOT)
            else -> raw
        }
        val wS = sizeOf(o, "width", maxW)
        val hS = sizeOf(o, "height", maxH)
        val myW = if (wS is Sz.Px) wS.v else if (wS is Sz.Fill) maxW else -1f
        val myH = if (hS is Sz.Px) hS.v else if (hS is Sz.Fill) maxH else -1f
        val wrapW = if (myW >= 0f) myW else if (maxW.isFinite()) maxW else UNBOUNDED

        var lines = wrap(text, paint, wrapW)
        val maxLines = o.optInt("maxLines", Int.MAX_VALUE)
        if (lines.size > maxLines && maxLines > 0) {
            val kept = lines.subList(0, maxLines).toMutableList()
            kept[kept.size - 1] = ellipsize(kept[kept.size - 1], paint, wrapW)
            lines = kept
        }
        l.lines = lines

        var widest = 0f
        for (s in lines) widest = max(widest, paint.measureText(s))
        l.w = if (myW >= 0f) myW else widest
        l.h = if (myH >= 0f) myH else lines.size * size * lh
    }

    private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        if (!maxW.isFinite() || maxW <= 0f) return text.split('\n')
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isEmpty()) {
                out.add("")
                continue
            }
            val sb = StringBuilder()
            var start = 0
            var i = 0
            while (i < para.length) {
                val w = p.measureText(para, start, i + 1)
                if (w > maxW && i > start) {
                    out.add(para.substring(start, i))
                    start = i
                    sb.setLength(0)
                }
                i++
            }
            out.add(para.substring(start))
        }
        return out
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (!maxW.isFinite()) return s
        var cur = s
        while (cur.isNotEmpty() && p.measureText(cur) > maxW) {
            cur = cur.substring(0, cur.length - 1)
        }
        if (cur.isEmpty()) return ""
        return cur + "…"
    }

    private fun measureImage(l: L, maxW: Float, maxH: Float) {
        val o = l.o
        val bmp = assets.bitmap(o.optString("src", ""))
        val wS = sizeOf(o, "width", maxW)
        val hS = sizeOf(o, "height", maxH)
        val natW = bmp?.width?.toFloat() ?: 48f
        val natH = bmp?.height?.toFloat() ?: 48f
        var w = when {
            wS is Sz.Px -> wS.v
            wS is Sz.Fill -> maxW
            else -> if (maxW.isFinite()) min(natW, maxW) else natW
        }
        var h = when {
            hS is Sz.Px -> hS.v
            hS is Sz.Fill -> maxH
            else -> if (maxH.isFinite()) min(natH, maxH) else natH
        }
        if (!w.isFinite()) w = 0f
        if (!h.isFinite()) h = 0f
        l.w = w
        l.h = h
    }

    private fun measureProgress(l: L, maxW: Float, maxH: Float) {
        val o = l.o
        val shape = o.optString("shape", "bar")
        val th = o.optDouble("thickness", 6.0).toFloat().coerceAtLeast(1f)
        val wS = sizeOf(o, "width", maxW)
        val hS = sizeOf(o, "height", maxH)
        val myW = if (wS is Sz.Px) wS.v else if (wS is Sz.Fill) maxW else -1f
        val myH = if (hS is Sz.Px) hS.v else if (hS is Sz.Fill) maxH else -1f

        val label = o.optJSONObject("label")
        val labelH = if (label != null && label.optBoolean("show", false))
            label.optDouble("size", 12.0).toFloat() * 1.5f else 0f

        if (shape == "arc") {
            val side = min(if (maxW.isFinite()) maxW else 96f, if (maxH.isFinite()) maxH else 96f)
            l.w = if (myW >= 0f) myW else side
            l.h = if (myH >= 0f) myH else side
        } else {
            l.w = if (myW >= 0f) myW else if (maxW.isFinite()) maxW else 120f
            l.h = if (myH >= 0f) myH else th + labelH
        }
        if (!l.w.isFinite()) l.w = 0f
        if (!l.h.isFinite()) l.h = 0f
    }

    private fun measureSpacer(l: L, maxW: Float, maxH: Float) {
        val v = l.o.opt("size")
        val s = if (v is Number) v.toFloat() else 0f
        l.w = s
        l.h = s
    }

    // ------------------------------------------------------------------ 取值 / 格式化

    fun valueOf(field: String): Any? {
        if (field.startsWith("vars.")) {
            val v = ctx.vars?.opt(field.substring(5))
            return if (v === JSONObject.NULL) null else v
        }
        return ctx.values[field]
    }

    fun formatValue(v: Any?, f: JSONObject?): String {
        if (v == null) return f?.optString("fallback", "") ?: ""
        val prefix = f?.optString("prefix", "") ?: ""
        val suffix = f?.optString("suffix", "") ?: ""
        val dec = f?.optInt("decimals", 1) ?: 1
        val mult = f?.optDouble("multiplier", 1.0) ?: 1.0
        val unit = f?.optString("unit", "none") ?: "none"
        val dateFmt = f?.optString("date", "") ?: ""

        if (dateFmt.isNotEmpty() && v is Number) {
            val pat = when (dateFmt) {
                "YYYY-MM-DD" -> "yyyy-MM-dd"
                "MM-DD" -> "MM-dd"
                "HH:mm" -> "HH:mm"
                else -> "MM-dd HH:mm"
            }
            val ms = if (v.toLong() > 100000000000L) v.toLong() else v.toLong() * 1000
            return try {
                prefix + SimpleDateFormat(pat, Locale.US).format(Date(ms)) + suffix
            } catch (t: Throwable) {
                prefix + v.toString() + suffix
            }
        }

        val num = when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
        if (num != null) {
            var d = num * mult
            var u = ""
            when (unit) {
                "auto" -> {
                    // 输入按 MB，自动换算
                    when {
                        abs(d) >= 1024 * 1024 -> { d /= 1024 * 1024; u = "TB" }
                        abs(d) >= 1024 -> { d /= 1024; u = "GB" }
                        else -> u = "MB"
                    }
                }
                "B", "KB", "MB", "GB", "TB" -> {
                    val steps = mapOf("B" to 0, "KB" to 1, "MB" to 2, "GB" to 3, "TB" to 4)
                    val target = steps[unit] ?: 2
                    var scale = 1.0
                    repeat(target - 2) { scale *= 1024 }
                    repeat(2 - target) { scale /= 1024 }
                    d *= scale
                    u = unit
                }
                "%" -> u = "%"
            }
            return prefix + fmtNum(d, dec) + u + suffix
        }
        if (v is Boolean) return prefix + (if (v) "是" else "否") + suffix
        return prefix + v.toString() + suffix
    }

    private fun textContent(o: JSONObject): String {
        if (o.has("text")) return o.optString("text", "")
        val b = o.optJSONObject("bind") ?: return ""
        return formatValue(valueOf(b.optString("field", "")), b.optJSONObject("format"))
    }

    private fun evalVisible(o: JSONObject): Boolean {
        val v = o.optJSONObject("visibleIf") ?: return true
        val field = v.optString("field", "")
        val actual = valueOf(field)
        val want = v.opt("value")
        val op = v.optString("op", "exists")
        return when (op) {
            "exists" -> actual != null
            "missing" -> actual == null
            "contains" -> actual?.toString()?.contains(want?.toString() ?: "") == true
            "eq" -> strOf(actual) == strOf(want)
            "ne" -> strOf(actual) != strOf(want)
            else -> {
                val a = (actual as? Number)?.toDouble() ?: return false
                val b = (want as? Number)?.toDouble() ?: return false
                when (op) {
                    "gt" -> a > b
                    "gte" -> a >= b
                    "lt" -> a < b
                    "lte" -> a <= b
                    else -> true
                }
            }
        }
    }

    private fun strOf(v: Any?): String = when {
        v == null -> ""
        v === JSONObject.NULL -> ""
        v is Double && v == floor2(v) -> v.toLong().toString()
        else -> v.toString()
    }

    private fun floor2(d: Double): Double = kotlin.math.floor(d)

    private fun fraction(o: JSONObject): Float {
        val raw: Double = if (o.has("bind")) {
            val b = o.optJSONObject("bind")
            val v = valueOf(b?.optString("field", "") ?: "")
            val num = (v as? Number)?.toDouble() ?: 0.0
            val mult = b?.optJSONObject("format")?.optDouble("multiplier", 1.0) ?: 1.0
            num * mult
        } else o.optDouble("value", 0.0)
        val minV = o.optDouble("min", 0.0)
        val maxV = o.optDouble("max", 100.0)
        if (maxV <= minV) return 0f
        return ((raw - minV) / (maxV - minV)).coerceIn(0.0, 1.0).toFloat()
    }

    // ------------------------------------------------------------------ 绘制

    private fun draw(c: Canvas, l: L, ox: Float, oy: Float, alpha: Int) {
        if (!l.visible) return
        val o = l.o
        val x = ox + l.x
        val y = oy + l.y
        val style = o.optJSONObject("style")
        val ownAlpha = ((style?.optDouble("opacity", 1.0) ?: 1.0) * alpha).toInt().coerceIn(0, 255)
        nodeAlpha = ownAlpha

        c.save()
        val transform = style?.optJSONObject("transform")
        if (transform != null) {
            val cx = x + l.w / 2f
            val cy = y + l.h / 2f
            val rot = transform.optDouble("rotate", 0.0).toFloat()
            if (rot != 0f) c.rotate(rot, cx, cy)
            val sx = transform.optDouble("scaleX", 1.0).toFloat()
            val sy = transform.optDouble("scaleY", 1.0).toFloat()
            if (sx != 1f || sy != 1f) {
                c.translate(cx, cy)
                c.scale(sx, sy)
                c.translate(-cx, -cy)
            }
        }

        when (o.optString("type", "")) {
            "frame" -> drawFrame(c, l, x, y)
            "text" -> drawText(c, l, x, y)
            "image" -> drawImage(c, l, x, y)
            "progress" -> drawProgress(c, l, x, y)
        }
        c.restore()
        nodeAlpha = alpha
    }

    private fun radii(style: JSONObject?): FloatArray {
        val r = style?.opt("radius") ?: return floatArrayOf(0f, 0f, 0f, 0f)
        return when (r) {
            is Number -> {
                val f = r.toFloat()
                floatArrayOf(f, f, f, f)
            }
            is JSONArray -> {
                val a = FloatArray(4)
                for (i in 0..3) a[i] = if (i < r.length()) (r.opt(i) as? Number)?.toFloat() ?: 0f else 0f
                a
            }
            else -> floatArrayOf(0f, 0f, 0f, 0f)
        }
    }

    private fun shapePath(rect: RectF, rr: FloatArray, squircle: Boolean = false): Path {
        val p = Path()
        when {
            squircle && rect.width() > 0f && rect.height() > 0f -> superellipse(p, rect)
            rr.all { it <= 0f } -> p.addRect(rect, Path.Direction.CW)
            else -> p.addRoundRect(
                rect,
                floatArrayOf(rr[0], rr[0], rr[1], rr[1], rr[2], rr[2], rr[3], rr[3]),
                Path.Direction.CW
            )
        }
        return p
    }

    /** 超椭圆 |x/a|^n + |y/b|^n = 1，n=5 ≈ Apple 连续曲率 squircle */
    private fun superellipse(p: Path, rect: RectF, n: Float = 5f) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val a = rect.width() / 2f
        val b = rect.height() / 2f
        val e = 2.0 / n
        val steps = 128
        for (i in 0 until steps) {
            val t = (i.toDouble() / steps) * Math.PI * 2.0
            val ct = Math.cos(t)
            val st = Math.sin(t)
            val x = cx + (a * Math.signum(ct) * Math.pow(Math.abs(ct), e)).toFloat()
            val y = cy + (b * Math.signum(st) * Math.pow(Math.abs(st), e)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }

    private fun finiteRect(r: RectF): Boolean =
        r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite() &&
                r.width().isFinite() && r.height().isFinite()

    private fun shaderFor(f: JSONObject?, rect: RectF): Shader? {
        if (f == null || f.optString("type") == "solid" || f.optString("type") == "image") return null
        if (!finiteRect(rect)) {
            AppLog.w("shader skip: 坐标非法 rect=$rect type=${f.optString("type")}")
            return null
        }
        return try {
            when (f.optString("type", "")) {
                "solid" -> null
                "linear" -> {
                    val cols = colorList(f)
                    val ang = Math.toRadians(f.optDouble("angle", 135.0))
                    val cx = rect.centerX()
                    val cy = rect.centerY()
                    val r = (rect.width() + rect.height()) / 2f
                    val dx = (Math.cos(ang) * r).toFloat()
                    val dy = (Math.sin(ang) * r).toFloat()
                    LinearGradient(
                        cx - dx, cy - dy, cx + dx, cy + dy, cols,
                        positionsOf(cols.size, f), Shader.TileMode.CLAMP
                    )
                }
                "radial" -> {
                    val cols = colorList(f)
                    val ctr = f.optJSONArray("center")
                    val fx = (rect.left + rect.width() * ((ctr?.optDouble(0, 0.5) ?: 0.5))).toFloat()
                    val fy = (rect.top + rect.height() * ((ctr?.optDouble(1, 0.5) ?: 0.5))).toFloat()
                    val rad = max(rect.width(), rect.height()) * f.optDouble("radius", 1.0).toFloat()
                    RadialGradient(
                        fx, fy, rad, cols, positionsOf(cols.size, f), Shader.TileMode.CLAMP
                    )
                }
                else -> null
            }
        } catch (t: Throwable) {
            AppLog.w("shader fail type=${f.optString("type")} rect=$rect: ${t.message}")
            null
        }
    }

    private fun colorList(f: JSONObject): IntArray {
        val a = f.optJSONArray("colors") ?: return intArrayOf(Color.BLACK, Color.WHITE)
        val out = IntArray(a.length())
        for (i in 0 until a.length()) out[i] = parseColor(a.optString(i, "#000000"))
        return out
    }

    private fun positionsOf(n: Int, f: JSONObject? = null): FloatArray {
        if (n <= 1) return floatArrayOf(0f)
        val a = f?.optJSONArray("positions")
        if (a != null && a.length() == n) {
            val out = FloatArray(n)
            var ok = true
            for (i in 0 until n) {
                val v = a.optDouble(i, -1.0)
                if (v < 0.0 || v > 1.0) { ok = false; break }
                out[i] = v.toFloat()
            }
            if (ok) return out
        }
        val p = FloatArray(n)
        for (i in 0 until n) p[i] = i / (n - 1f)
        return p
    }

    private fun applyFill(paint: Paint, f: JSONObject?, rect: RectF, alpha: Int) {
        paint.shader = shaderFor(f, rect)
        val c = when {
            f == null -> Color.BLACK
            f.optString("type") == "solid" -> parseColor(f.optString("color"))
            else -> colorList(f).firstOrNull() ?: Color.BLACK
        }
        paint.color = withAlpha(c, alpha)
        paint.style = Paint.Style.FILL
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        if (alpha >= 255) color else Color.argb(
            (Color.alpha(color) * alpha) / 255,
            Color.red(color), Color.green(color), Color.blue(color)
        )

    private fun drawFrame(c: Canvas, l: L, x: Float, y: Float) {
        val o = l.o
        val style = o.optJSONObject("style")
        val rect = RectF(x, y, x + l.w, y + l.h)
        val rr = radii(style)
        val bg = style?.optJSONObject("background")
        val sq = style?.optString("shape") == "squircle"
        val hasVisual = bg != null || style?.has("border") == true || style?.has("shadow") == true ||
            style?.has("rim") == true || style?.has("innerGlow") == true
        val path = shapePath(rect, rr, sq)

        val sh = style?.optJSONObject("shadow")
        if (sh != null && l.w > 0 && l.h > 0) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(parseColor(sh.optString("color")), nodeAlpha)
            paint.setShadowLayer(
                sh.optDouble("blur", 0.0).toFloat(),
                sh.optDouble("x", 0.0).toFloat(),
                sh.optDouble("y", 0.0).toFloat(),
                withAlpha(parseColor(sh.optString("color")), nodeAlpha)
            )
            c.drawPath(path, paint)
            paint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }

        var clip = false
        if (bg != null && l.w > 0 && l.h > 0) {
            if (bg.optString("type") == "frost") {
                drawFrost(c, path, rect, bg)
                if (bg.has("tint")) {
                    paint.shader = null
                    paint.style = Paint.Style.FILL
                    paint.color = withAlpha(parseColor(bg.optString("tint")), nodeAlpha)
                    c.drawPath(path, paint)
                }
                clip = true
            } else if (bg.optString("type") == "image") {
                val bmp = assets.bitmap(bg.optString("src", ""))
                if (bmp != null) {
                    val save = c.save()
                    c.clipPath(path)
                    drawBitmapFit(c, bmp, rect, bg.optString("scale", "cover"), nodeAlpha)
                    c.restoreToCount(save)
                } else {
                    paint.shader = null
                    paint.color = withAlpha(0xFF141418.toInt(), nodeAlpha)
                    c.drawPath(path, paint)
                }
                clip = true
            } else {
                applyFill(paint, bg, rect, nodeAlpha)
                c.drawPath(path, paint)
                paint.shader = null
                clip = true
            }
        }

        val border = style?.optJSONObject("border")
        if (border != null && l.w > 0 && l.h > 0) {
            val bw = border.optDouble("width", 1.0).toFloat().coerceAtLeast(0.5f)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = bw
            paint.pathEffect = if (border.optString("style", "solid") == "dash")
                DashPathEffect(floatArrayOf(bw * 3, bw * 3), 0f) else null
            paint.color = withAlpha(parseColor(border.optString("color")), nodeAlpha)
            val inset = RectF(rect)
            inset.inset(bw / 2f, bw / 2f)
            c.drawPath(shapePath(inset, rr, sq), paint)
            paint.pathEffect = null
        }

        // 方向性镜面亮边：上/下发丝线（亮）+ 左右侧发丝线（暗）——实测 Control Center 分布
        val rim = style?.optJSONObject("rim")
        if (rim != null && l.w > 0 && l.h > 0) {
            val rw = rim.optDouble("width", 1.0).toFloat().coerceAtLeast(0.5f)
            val save = c.save()
            c.clipPath(path)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rw
            paint.pathEffect = null
            if (rim.has("top")) {
                paint.color = withAlpha(parseColor(rim.optString("top")), nodeAlpha)
                c.drawLine(rect.left + rw, rect.top + rw / 2f, rect.right - rw, rect.top + rw / 2f, paint)
            }
            if (rim.has("bottom")) {
                paint.color = withAlpha(parseColor(rim.optString("bottom")), nodeAlpha)
                c.drawLine(rect.left + rw, rect.bottom - rw / 2f, rect.right - rw, rect.bottom - rw / 2f, paint)
            }
            if (rim.has("sides")) {
                paint.color = withAlpha(parseColor(rim.optString("sides")), nodeAlpha)
                c.drawLine(rect.left + rw / 2f, rect.top + rw, rect.left + rw / 2f, rect.bottom - rw, paint)
                c.drawLine(rect.right - rw / 2f, rect.top + rw, rect.right - rw / 2f, rect.bottom - rw, paint)
            }
            c.restoreToCount(save)
            paint.style = Paint.Style.FILL
        }

        // 内透镜高光：玻璃厚度感（顶光带 + 可选底光带）
        val ig = style?.optJSONObject("innerGlow")
        if (ig != null && l.w > 0 && l.h > 0) {
            val gw = ig.optDouble("width", 24.0).toFloat().coerceAtLeast(1f)
            val col = parseColor(ig.optString("color", "#FFFFFF"))
            val save = c.save()
            c.clipPath(path)
            paint.shader = null
            paint.style = Paint.Style.FILL
            if (ig.optBoolean("top", true)) {
                val g = LinearGradient(
                    rect.left, rect.top, rect.left, rect.top + gw,
                    col, col and 0x00FFFFFF, Shader.TileMode.CLAMP
                )
                paint.shader = g
                c.drawRect(rect.left, rect.top, rect.right, rect.top + gw, paint)
                paint.shader = null
            }
            if (ig.optBoolean("bottom", false)) {
                val g = LinearGradient(
                    rect.left, rect.bottom, rect.left, rect.bottom - gw,
                    col, col and 0x00FFFFFF, Shader.TileMode.CLAMP
                )
                paint.shader = g
                c.drawRect(rect.left, rect.bottom - gw, rect.right, rect.bottom, paint)
                paint.shader = null
            }
            c.restoreToCount(save)
        }

        if (hasVisual && clip) {
            val save = c.save()
            c.clipPath(path)
            l.kids.forEach { draw(c, it, x, y, nodeAlpha) }
            c.restoreToCount(save)
        } else {
            l.kids.forEach { draw(c, it, x, y, nodeAlpha) }
        }
    }

    /** 壁纸磨砂层：取系统壁纸 cover 缩放到卡区域，降采样-回升实现模糊（真·磨砂玻璃）。
     *  拿不到壁纸（权限/机型）时静默跳过，仅记一次日志。 */
    private fun drawFrost(c: Canvas, path: Path, rect: RectF, bg: JSONObject) {
        try {
            val app = com.litewidget.app.App.instance
            if (frostFailed) return
            val wall = try {
                val wm = app.getSystemService(android.content.Context.WALLPAPER_SERVICE)
                        as android.app.WallpaperManager
                val d = wm.drawable
                (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
            } catch (t: Throwable) {
                if (!frostLogged) {
                    AppLog.w("frost: wallpaper unavailable: ${t.javaClass.simpleName}: ${t.message}")
                    frostLogged = true; frostFailed = true
                }
                return
            }
            if (wall == null || wall.isRecycled) return
            val bw = rect.width().toInt().coerceAtLeast(1)
            val bh = rect.height().toInt().coerceAtLeast(1)
            val scale = max(bw.toFloat() / wall.width, bh.toFloat() / wall.height)
            var scaled = Bitmap.createScaledBitmap(
                wall,
                max(1, (wall.width * scale).toInt()),
                max(1, (wall.height * scale).toInt()),
                true
            )
            val ox = ((scaled.width - bw) / 2).coerceAtLeast(0)
            val oy = ((scaled.height - bh) / 2).coerceAtLeast(0)
            var crop = Bitmap.createBitmap(
                scaled, ox, oy, min(bw, scaled.width), min(bh, scaled.height)
            )
            if (crop != scaled) scaled.recycle()
            // 模糊：降采样 blur 倍 → 双线性回升
            val f = bg.optDouble("blur", 14.0).toFloat().coerceAtLeast(2f)
            val sw = max(2, (crop.width / f).toInt())
            val sh = max(2, (crop.height / f).toInt())
            val small = Bitmap.createScaledBitmap(crop, sw, sh, true)
            val out = Bitmap.createScaledBitmap(small, crop.width, crop.height, true)
            if (small != out) small.recycle()
            crop.recycle()
            val save = c.save()
            c.clipPath(path)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.alpha = (bg.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f) * 255).toInt()
            c.drawBitmap(out, rect.left, rect.top, paint)
            paint.alpha = 255
            out.recycle()
            c.restoreToCount(save)
            if (!frostLogged) {
                AppLog.i("frost: wallpaper layer active (${bw}x${bh} blur=$f)")
                frostLogged = true
            }
        } catch (t: Throwable) {
            if (!frostLogged) {
                AppLog.w("frost draw fail: ${t.javaClass.simpleName}: ${t.message}")
                frostLogged = true; frostFailed = true
            }
        }
    }

    private fun drawText(c: Canvas, l: L, x: Float, y: Float) {
        if (l.lines.isEmpty()) return
        val o = l.o
        val size = o.optDouble("size", 14.0).toFloat()
        val lh = o.optDouble("lineHeight", 1.25).toFloat()
        paint.typeface = assets.typeface(o.optString("family", "default"))
        paint.textSize = size
        paint.letterSpacing = o.optDouble("letterSpacing", 0.0).toFloat()
        paint.isFakeBoldText = o.optInt("weight", 400) >= 600
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        paint.strokeWidth = 0f

        val align = o.optString("align", "left")
        paint.textAlign = when (align) {
            "center" -> Paint.Align.CENTER
            "right" -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }

        val rect = RectF(x, y, x + l.w, y + l.h)
        val grad = o.optJSONObject("gradient")
        val g = if (grad != null && grad.optString("type") == "image") null else shaderFor(grad, rect)
        paint.shader = g
        paint.color = if (g != null) paint.color
        else withAlpha(parseColor(o.optString("color", "#FFFFFF")), nodeAlpha)

        val sh = o.optJSONObject("shadow")
        if (sh != null) {
            paint.setShadowLayer(
                sh.optDouble("blur", 0.0).toFloat(),
                sh.optDouble("x", 0.0).toFloat(),
                sh.optDouble("y", 0.0).toFloat(),
                withAlpha(parseColor(sh.optString("color")), nodeAlpha)
            )
        }

        val fm = paint.fontMetrics
        val lineH = if (lh > 0f) size * lh else -fm.top + fm.bottom
        val totalH = l.lines.size * lineH
        var top = y + ((l.h - totalH) / 2f).coerceAtLeast(0f)

        for (s in l.lines) {
            val baseline = top - fm.ascent
            val tx = when (align) {
                "center" -> x + l.w / 2f
                "right" -> x + l.w
                else -> x
            }
            c.drawText(s, tx, baseline, paint)
            top += lineH
        }
        paint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        paint.shader = null
    }

    private fun drawImage(c: Canvas, l: L, x: Float, y: Float) {
        val o = l.o
        val bmp = assets.bitmap(o.optString("src", "")) ?: return
        if (l.w <= 0 || l.h <= 0) return
        val rect = RectF(x, y, x + l.w, y + l.h)
        val tint = o.optString("tint", "")
        if (tint.isNotEmpty() && tint.startsWith("#")) {
            paint.colorFilter = PorterDuffColorFilter(parseColor(tint), PorterDuff.Mode.SRC_IN)
        }
        paint.alpha = nodeAlpha
        drawBitmapFit(c, bmp, rect, o.optString("scale", "cover"), nodeAlpha)
        paint.colorFilter = null
        paint.alpha = 255
    }

    private fun drawBitmapFit(c: Canvas, bmp: Bitmap, dst: RectF, scale: String, alpha: Int) {
        paint.alpha = alpha
        when (scale) {
            "fill" -> c.drawBitmap(bmp, null, dst, paint)
            "none" -> {
                val r = RectF(dst.left, dst.top, dst.left + bmp.width, dst.top + bmp.height)
                c.drawBitmap(bmp, null, r, paint)
            }
            "contain" -> {
                val s = min(dst.width() / bmp.width, dst.height() / bmp.height)
                val w = bmp.width * s
                val h = bmp.height * s
                val r = RectF(
                    dst.left + (dst.width() - w) / 2f,
                    dst.top + (dst.height() - h) / 2f,
                    dst.left + (dst.width() + w) / 2f,
                    dst.top + (dst.height() + h) / 2f
                )
                c.drawBitmap(bmp, null, r, paint)
            }
            else -> { // cover
                val s = max(dst.width() / bmp.width, dst.height() / bmp.height)
                val w = bmp.width * s
                val h = bmp.height * s
                val src = RectF(
                    (bmp.width - dst.width() / s) / 2f,
                    (bmp.height - dst.height() / s) / 2f,
                    (bmp.width + dst.width() / s) / 2f,
                    (bmp.height + dst.height() / s) / 2f
                )
                val m = Matrix()
                m.setRectToRect(src, dst, Matrix.ScaleToFit.FILL)
                c.drawBitmap(bmp, m, paint)
            }
        }
        paint.alpha = 255
    }

    private fun drawProgress(c: Canvas, l: L, x: Float, y: Float) {
        val o = l.o
        if (l.w <= 0 || l.h <= 0) return
        val shape = o.optString("shape", "bar")
        val th = o.optDouble("thickness", 6.0).toFloat().coerceAtLeast(1f)
        val frac = fraction(o)
        val roundCap = o.optBoolean("roundCap", true)
        val track = o.optJSONObject("track")
        val bar = o.optJSONObject("bar")
        val rect = RectF(x, y, x + l.w, y + l.h)

        paint.pathEffect = null
        paint.strokeCap = if (roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = th

        if (shape == "arc") {
            val inset = th / 2f
            val r = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            if (r.width() <= 0 || r.height() <= 0) return
            val start = o.optDouble("startAngle", -90.0).toFloat()
            val sweep = o.optDouble("sweep", 360.0).toFloat()
            paint.shader = shaderFor(track, rect)
            if (paint.shader == null)
                paint.color = withAlpha(parseColor(track?.optString("color", "#26262C") ?: "#26262C"), nodeAlpha)
            c.drawArc(r, start, sweep, false, paint)
            paint.shader = shaderFor(bar, rect)
            if (paint.shader == null)
                paint.color = withAlpha(parseColor(bar?.optString("color", "#4CAF50") ?: "#4CAF50"), nodeAlpha)
            c.drawArc(r, start, sweep * frac, false, paint)
            paint.shader = null
        } else {
            val label = o.optJSONObject("label")
            val showLabel = label != null && label.optBoolean("show", false)
            val labelH = if (showLabel) label.optDouble("size", 12.0).toFloat() * 1.5f else 0f
            val cy = y + th / 2f
            paint.shader = shaderFor(track, rect)
            if (paint.shader == null)
                paint.color = withAlpha(parseColor(track?.optString("color", "#26262C") ?: "#26262C"), nodeAlpha)
            c.drawLine(x, cy, x + l.w, cy, paint)
            paint.shader = shaderFor(bar, rect)
            if (paint.shader == null)
                paint.color = withAlpha(parseColor(bar?.optString("color", "#4CAF50") ?: "#4CAF50"), nodeAlpha)
            val end = x + max(l.w * frac, if (roundCap) th else 0f)
            if (l.w * frac > 0f) c.drawLine(x, cy, min(end, x + l.w), cy, paint)
            paint.shader = null

            if (showLabel) {
                val b = o.optJSONObject("bind")
                val txt = if (b != null)
                    formatValue(valueOf(b.optString("field", "")), b.optJSONObject("format"))
                else fmtNum(fraction(o) * 100.0, 0) + "%"
                paint.style = Paint.Style.FILL
                paint.textSize = label.optDouble("size", 12.0).toFloat()
                paint.textAlign = Paint.Align.RIGHT
                paint.color = withAlpha(parseColor(label.optString("color", "#9E9EA6")), nodeAlpha)
                val lrect = RectF(x, cy + th / 2f, x + l.w, cy + th / 2f + labelH)
                val base = lrect.top - paint.fontMetrics.ascent + 2f
                c.drawText(txt, x + l.w, base, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
    }

}

/** 默认数据：没有数据源时也能看到渲染效果（DataRepo / MCP 预览用） */
fun sampleValues(): Map<String, Any?> = mapOf(
        "package.name" to "3.8元/1500G（好评赠送）",
        "package.expire" to "2026-10-04",
        "package.spec" to 1536000,
        "flow.total" to 1536000.0,
        "flow.remain" to 1098000.0,
        "flow.used" to 438000.0,
        "flow.percent" to 28.5,
        "device.battery" to 80,
        "device.ssid" to "HCLink-ouben",
        "device.status" to 1,
        "device.running" to "03:50:25",
        "device.updated" to "2026-10-02 20:04:36",
        "account.balance" to "0.00",
        "account.operator" to "联通",
        "account.realname" to "已实名"
    )
