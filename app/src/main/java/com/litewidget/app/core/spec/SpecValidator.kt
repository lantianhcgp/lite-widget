package com.litewidget.app.core.spec

import org.json.JSONArray
import org.json.JSONObject

/**
 * widget.json / manifest.json 校验器。
 * 规则与 /storage/emulated/0/Documents/lite-widget-schema.json 保持一致（子集实现）。
 * 全部 additionalProperties=false：写错字段名直接报错，这是 AI 自检的关键。
 */
object SpecValidator {

    private val COLOR = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val BIND = Regex("^(vars|flow|package|device|account)\\.[a-zA-Z0-9_.]+$")
    private val ID = Regex("^[a-z0-9][a-z0-9_-]{1,63}$")
    private val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+$")

    private val SIZES = setOf("2x1", "2x2", "3x2", "4x1", "4x2", "4x4", "5x2", "6x2")

    private val COMMON = setOf("type", "id", "x", "y", "width", "height", "style", "visibleIf")
    private val SPECIFIC = mapOf(
        "frame" to setOf("direction", "justify", "align", "gap", "padding", "children"),
        "text" to setOf(
            "text", "bind", "size", "weight", "family", "color", "gradient",
            "letterSpacing", "lineHeight", "align", "maxLines", "case", "shadow"
        ),
        "image" to setOf("src", "scale", "tint", "alpha"),
        "progress" to setOf(
            "value", "bind", "min", "max", "shape", "thickness", "roundCap",
            "track", "bar", "label", "startAngle", "sweep"
        ),
        "spacer" to setOf("size")
    )
    private val STYLE_KEYS = setOf("width", "height", "radius", "background", "border", "shadow", "opacity", "transform")

    private val errs = ArrayList<String>()

    // ---------- manifest ----------

    fun manifest(json: JSONObject): List<String> {
        errs.clear()
        val allowed = setOf(
            "id", "name", "author", "description", "version", "size",
            "minAppVersion", "preview", "tags", "license", "createdAt", "schemaVersion"
        )
        unknown(json, allowed, "$")
        need(json, listOf("id", "name", "version", "size"), "$")
        val id = json.optString("id", "")
        if (id.isNotEmpty() && !ID.matches(id)) bad("$", "id", "需小写字母/数字/-_，1-64 位")
        val ver = json.optString("version", "")
        if (ver.isNotEmpty() && !SEMVER.matches(ver)) bad("$", "version", "需 x.y.z 格式")
        val size = json.optString("size", "")
        if (size.isNotEmpty() && size !in SIZES) bad("$", "size", "允许值 $SIZES")
        return errs
    }

    // ---------- widget ----------

    fun widget(json: JSONObject): List<String> {
        errs.clear()
        unknown(json, setOf("version", "canvas", "root", "vars"), "$")
        need(json, listOf("version", "canvas", "root"), "$")
        if (json.opt("version") != null && json.opt("version") != 1) bad("$", "version", "当前只支持 1")
        val canvas = json.optJSONObject("canvas")
        if (canvas == null) errs.add("$.canvas: 缺少")
        else {
            unknown(canvas, setOf("width", "height", "fit"), "$.canvas")
            need(canvas, listOf("width", "height"), "$.canvas")
            num(canvas, "width", "$.canvas"); num(canvas, "height", "$.canvas")
            enum(canvas, "fit", setOf("auto", "contain", "cover", "stretch", "fill"), "$.canvas")
        }
        val root = json.optJSONObject("root")
        if (root == null) errs.add("$.root: 缺少")
        else node(root, "$.root")
        return errs
    }

    private fun node(o: JSONObject, path: String) {
        val type = o.optString("type", "")
        if (type.isEmpty()) {
            errs.add("$path: 缺少必填字段 'type'")
            return
        }
        val spec = SPECIFIC[type]
        if (spec == null) {
            errs.add("$path.type: 必须是 ${SPECIFIC.keys.joinToString("/")}, 实际 '$type'")
            return
        }
        unknown(o, COMMON + spec, path)
        when (type) {
            "frame" -> {
                need(o, listOf("children"), path)
                enum(o, "direction", setOf("vertical", "horizontal", "absolute"), path)
                enum(o, "justify", setOf("start", "center", "end", "space-between", "space-around"), path)
                enum(o, "align", setOf("start", "center", "end", "stretch"), path)
                num(o, "gap", path)
                val kids = o.optJSONArray("children")
                if (kids != null) {
                    if (kids.length() > 64) errs.add("$path.children: 最多 64 项")
                    for (i in 0 until kids.length()) {
                        val k = kids.optJSONObject(i)
                        if (k == null) errs.add("$path.children[$i]: 必须是对象")
                        else node(k, "$path.children[$i]")
                    }
                }
            }
            "text" -> {
                val hasText = o.has("text"); val hasBind = o.has("bind")
                if (hasText == hasBind) errs.add("$path: text 与 bind 必须二选一")
                if (hasBind) bind(o.optJSONObject("bind"), "$path.bind")
                if (hasText && o.opt("text") !is String) bad(path, "text", "必须是字符串")
                num(o, "size", path); num(o, "letterSpacing", path); num(o, "lineHeight", path)
                intIn(o, "weight", 100..900, path)
                enum(o, "align", setOf("left", "center", "right"), path)
                enum(o, "case", setOf("none", "upper", "lower"), path)
                if (o.has("maxLines")) intIn(o, "maxLines", 1..20, path)
                color(o, "color", path)
                fill(o.optJSONObject("gradient"), "$path.gradient")
                shadow(o.optJSONObject("shadow"), "$path.shadow")
                family(o, path)
            }
            "image" -> {
                need(o, listOf("src"), path)
                if (o.optString("src", "").isNotEmpty() && !o.optString("src").startsWith("assets/"))
                    errs.add("$path.src: 必须以 assets/ 开头")
                enum(o, "scale", setOf("cover", "contain", "fill", "none"), path)
                color(o, "tint", path)
                if (o.has("alpha")) range(o.opt("alpha"), 0.0, 1.0, "$path.alpha")
            }
            "progress" -> {
                val hasValue = o.has("value"); val hasBind = o.has("bind")
                if (hasValue == hasBind) errs.add("$path: value 与 bind 必须二选一")
                if (hasBind) bind(o.optJSONObject("bind"), "$path.bind")
                enum(o, "shape", setOf("bar", "arc"), path)
                num(o, "thickness", path); num(o, "startAngle", path); num(o, "sweep", path)
                fill(o.optJSONObject("track"), "$path.track")
                fill(o.optJSONObject("bar"), "$path.bar")
                val label = o.optJSONObject("label")
                if (label != null) {
                    unknown(label, setOf("show", "size", "color", "format"), "$path.label")
                    color(label, "color", "$path.label")
                }
            }
            "spacer" -> if (o.has("size") && o.opt("size") !is Number && o.optString("size") != "fill")
                bad(path, "size", "必须是数字或 \"fill\"")
        }
        if (o.has("style")) style(o.optJSONObject("style"), "$path.style")
        if (o.has("visibleIf")) visIf(o.optJSONObject("visibleIf"), "$path.visibleIf")
    }

    private fun style(s: JSONObject?, path: String) {
        if (s == null) return
        unknown(s, STYLE_KEYS, path)
        enum(s, "width", setOf("fill", "hug"), path, numericAlso = true)
        enum(s, "height", setOf("fill", "hug"), path, numericAlso = true)
        fill(s.optJSONObject("background"), "$path.background")
        val b = s.optJSONObject("border")
        if (b != null) {
            unknown(b, setOf("color", "width", "style"), "$path.border")
            color(b, "color", "$path.border"); num(b, "width", "$path.border")
            enum(b, "style", setOf("solid", "dash"), "$path.border")
        }
        shadow(s.optJSONObject("shadow"), "$path.shadow")
        if (s.has("opacity")) range(s.opt("opacity"), 0.0, 1.0, "$path.opacity")
        val t = s.optJSONObject("transform")
        if (t != null) {
            unknown(t, setOf("rotate", "scaleX", "scaleY"), "$path.transform")
            num(t, "rotate", "$path.transform"); num(t, "scaleX", "$path.transform"); num(t, "scaleY", "$path.transform")
        }
    }

    private fun fill(f: JSONObject?, path: String) {
        if (f == null) return
        when (f.optString("type", "")) {
            "solid" -> {
                unknown(f, setOf("type", "color"), path)
                need(f, listOf("color"), path); color(f, "color", path)
            }
            "linear" -> {
                unknown(f, setOf("type", "colors", "angle"), path)
                colors(f, path); num(f, "angle", path)
            }
            "radial" -> {
                unknown(f, setOf("type", "colors", "center", "radius"), path)
                colors(f, path); num(f, "radius", path)
            }
            "image" -> {
                unknown(f, setOf("type", "src", "scale", "opacity"), path)
                need(f, listOf("src"), path)
                if (!f.optString("src").startsWith("assets/")) errs.add("$path.src: 必须以 assets/ 开头")
            }
            else -> errs.add("$path.type: 必须是 solid/linear/radial/image")
        }
    }

    private fun colors(f: JSONObject, path: String) {
        val a = f.optJSONArray("colors")
        if (a == null) { errs.add("$path.colors: 缺少"); return }
        if (a.length() < 2 || a.length() > 8) errs.add("$path.colors: 需要 2-8 个颜色")
        for (i in 0 until a.length()) {
            val c = a.optString(i, "")
            if (!COLOR.matches(c)) bad("$path.colors", "[$i]", "需 #RRGGBB/#RRGGBBAA，实际 '$c'")
        }
    }

    private fun shadow(s: JSONObject?, path: String) {
        if (s == null) return
        unknown(s, setOf("color", "blur", "x", "y"), path)
        need(s, listOf("color", "blur"), path); color(s, "color", path); num(s, "blur", path)
    }

    private fun bind(b: JSONObject?, path: String) {
        if (b == null) { errs.add("$path: 缺少"); return }
        unknown(b, setOf("field", "format", "fallback"), path)
        val f = b.optString("field", "")
        if (!BIND.matches(f)) errs.add("$path.field: 必须匹配 ^(vars|flow|package|device|account)\\.[a-zA-Z0-9_.]+$，实际 '$f'")
        val fm = b.optJSONObject("format")
        if (fm != null) {
            unknown(fm, setOf("decimals", "unit", "prefix", "suffix", "multiplier", "date"), "$path.format")
            if (fm.has("decimals")) intIn(fm, "decimals", 0..4, "$path.format")
            enum(fm, "unit", setOf("auto", "B", "KB", "MB", "GB", "TB", "%", "none"), "$path.format")
            enum(fm, "date", setOf("YYYY-MM-DD", "MM-DD", "HH:mm", "MM-DD HH:mm"), "$path.format")
        }
    }

    private fun visIf(v: JSONObject?, path: String) {
        if (v == null) return
        unknown(v, setOf("field", "op", "value"), path)
        need(v, listOf("field", "op"), path)
        enum(v, "op", setOf("eq", "ne", "gt", "gte", "lt", "lte", "exists", "missing", "contains"), path)
    }

    private fun family(o: JSONObject, path: String) {
        if (!o.has("family")) return
        val f = o.optString("family", "")
        if (f.isNotEmpty() && f != "default" && f != "sans" && f != "serif" && f != "mono" && !f.startsWith("assets/"))
            errs.add("$path.family: 允许 default/sans/serif/mono 或 assets/xxx.ttf")
    }

    // ---------- 小工具 ----------

    private fun unknown(o: JSONObject, allowed: Set<String>, path: String) {
        for (k in o.keys()) if (k !in allowed) errs.add("$path: 未知字段 '$k'（可能拼错了）")
    }

    private fun need(o: JSONObject, keys: List<String>, path: String) {
        keys.forEach { if (!o.has(it)) errs.add("$path: 缺少必填字段 '$it'") }
    }

    private fun bad(path: String, key: String, msg: String) = errs.add("$path.$key: $msg")

    private fun color(o: JSONObject, key: String, path: String) {
        if (!o.has(key)) return
        val v = o.optString(key, "")
        if (!COLOR.matches(v)) bad(path, key, "需 #RRGGBB/#RRGGBBAA，实际 '$v'")
    }

    private fun num(o: JSONObject, key: String, path: String) {
        if (!o.has(key)) return
        if (o.opt(key) !is Number) bad(path, key, "必须是数字")
    }

    private fun intIn(o: JSONObject, key: String, r: IntRange, path: String) {
        if (!o.has(key)) return
        val v = o.opt(key)
        if (v !is Number || v.toInt() !in r) bad(path, key, "需在 $r")
    }

    private fun range(v: Any?, lo: Double, hi: Double, path: String) {
        if (v !is Number || v.toDouble() < lo || v.toDouble() > hi) errs.add("$path: 需在 [$lo,$hi]")
    }

    private fun enum(o: JSONObject, key: String, allowed: Set<String>, path: String, numericAlso: Boolean = false) {
        if (!o.has(key)) return
        val v = o.opt(key)
        if (v is Number && numericAlso) return
        if (v !is String || v !in allowed) bad(path, key, "允许值 $allowed，实际 '$v'")
    }
}
