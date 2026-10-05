package com.litewidget.app.core.data

import android.content.Context
import com.litewidget.app.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 物流查询：快递100 免费网页口（免 Key）。
 *
 * GET https://www.kuaidi100.com/query?type=<com>&postid=<tn>
 * → {"status":"200","state":"0","com":"zhongtong","data":[{"time","context","location"}...]}
 * state: 0运输中 1揽件 2疑难 3已签收 4退签 5派件中 6退回 7转投
 *
 * 约束：免费口会限流 → 每单 30 分钟缓存，每次刷新最多补查 [MAX_FETCH] 个过期单。
 * 失败一律回落缓存，绝不让渲染失败。
 */
object ParcelClient {

    private const val TAG = "ParcelClient"
    private const val TIMEOUT = 6000
    private const val TTL_MS = 30L * 60 * 1000
    private const val MAX_FETCH = 6
    const val MAX_SLOTS = 8

    data class Item(
        val no: String,          // 单号
        val com: String,         // 编码 zhongtong
        val comName: String,     // 中通快递
        val state: String,       // transit|delivering|signed|issue|other
        val stateCn: String,     // 运输中…
        val line: String,        // 最近一条轨迹
        val time: Long,          // 轨迹时间 epoch 秒
        val loc: String,
        val score: Int           // 排序：小=紧急
    )

    private val COM_NAME = mapOf(
        "shunfeng" to "顺丰速运", "zhongtong" to "中通快递", "yuantong" to "圆通速递",
        "yunda" to "韵达快递", "shentong" to "申通快递", "jtexpress" to "极兔速递",
        "jd" to "京东物流", "ems" to "邮政EMS", "huitongkuaidi" to "百世快递",
        "auto" to "快递"
    )

    /** 单号前缀 → 快递编码（能识别就免得让接口猜） */
    private val PREFIX = linkedMapOf(
        "shunfeng" to listOf("SF"),
        "yuantong" to listOf("YT"),
        "yunda" to listOf("YD"),
        "shentong" to listOf("ST"),
        "jtexpress" to listOf("JT"),
        "jd" to listOf("JD"),
        "ems" to listOf("EM"),
        "zhongtong" to listOf("ZT"),
        "huitongkuaidi" to listOf("55"),
        // 数字前缀（按长度优先匹配）
        "yunda" to listOf("YD", "46", "47", "48", "49"),
        "shentong" to listOf("ST", "268", "368", "468", "568", "668", "768", "868", "968"),
        "zhongtong" to listOf("ZT", "731", "732", "733", "734", "735", "736", "757", "758", "759",
            "761", "762", "763", "764", "765", "766", "767", "768", "769", "771", "772", "773",
            "774", "775", "776", "777", "778", "779", "780"),
        "huitongkuaidi" to listOf("550", "551", "552", "553", "554", "555", "556", "557", "558",
            "559", "560", "561", "562", "563", "564", "565", "566", "567", "568", "569", "570")
    )

    /** 解析单号清单：`单号` / `单号:快递编码` / `单号:快递编码:手机后四位`，逗号/空格/换行/顿号分隔 */
    fun parseList(raw: String): List<Array<String>> =
        raw.split(Regex("[,，;；\\s、\\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { item ->
                val p = item.split(":", "：").map { it.trim() }
                arrayOf(
                    p.getOrElse(0) { "" },
                    p.getOrElse(1) { "" },
                    p.getOrElse(2) { "" }
                )
            }
            .filter { it[0].isNotEmpty() }

    private fun detect(tn: String): String {
        val u = tn.uppercase(Locale.US)
        // 长前缀优先，避免 "768" 同时命中申通/中通
        val cands = mutableListOf<Pair<Int, String>>()
        for ((com, ps) in PREFIX) for (p in ps) if (u.startsWith(p)) cands.add(p.length to com)
        return cands.maxByOrNull { it.first }?.second ?: "auto"
    }

    /** 主入口：把 parcel.* 全部写进 out（与随身WiFi字段并存在同一份快照） */
    fun refreshInto(out: JSONObject, listRaw: String, ctx: Context) {
        val items = parseList(listRaw)
        if (items.isEmpty()) {
            // 不写 count → 组件用 `visibleIf missing` 显示空状态引导
            out.put("parcel.error", "单号清单为空")
            out.put("parcel.updated", now10())
            return
        }
        val cacheFile = File(File(ctx.filesDir, "data"), "parcel_cache.json")
        val cache = readCache(cacheFile)
        val now = System.currentTimeMillis()
        var budget = MAX_FETCH
        var lastErr = ""

        val results = mutableListOf<Item>()
        for (arr in items) {
            val tn = arr[0]
            val com = arr[1].ifEmpty { detect(tn) }
            val phone = arr[2]
            val hit = cache.optJSONObject(tn)
            val fresh = hit != null && now - hit.optLong("ts", 0) < TTL_MS
            var obj = if (fresh) hit else null
            if (!fresh && budget > 0) {
                budget--
                val r = query(com, tn, phone)
                if (r != null) {
                    r.put("ts", now)
                    r.put("com", r.optString("com").ifEmpty { com })
                    cache.put(tn, r)
                    obj = r
                } else {
                    lastErr = "$tn 查询失败"
                    if (hit != null) obj = hit   // 回落旧缓存
                }
            } else if (!fresh && hit != null) {
                obj = hit // 预算用完：拿过期缓存顶着
            }
            if (obj == null) continue
            results.add(toItem(tn, com, obj))
        }

        writeCache(cacheFile, cache)
        results.sortBy { it.score }

        out.put("parcel.count", items.size)
        out.put("parcel.active", results.count { it.state != "signed" })
        out.put("parcel.signed", results.count { it.state == "signed" })
        out.put("parcel.error", lastErr)
        out.put("parcel.updated", now / 1000)

        for (i in 0 until minOf(MAX_SLOTS, results.size)) {
            val p = results[i]
            val k = "parcel.${i + 1}"
            out.put("$k.no", p.no)
            out.put("$k.com", p.com)
            out.put("$k.comName", p.comName)
            out.put("$k.state", p.state)
            out.put("$k.stateCn", p.stateCn)
            out.put("$k.title", "${p.comName.removeSuffix("快递").removeSuffix("速运")} · ${p.no.takeLast(4)}")
            out.put("$k.line", p.line)
            out.put("$k.time", p.time)
            out.put("$k.loc", p.loc)
        }
        AppLog.i("$TAG refreshed ${results.size}/${items.size} slot=${minOf(MAX_SLOTS, results.size)}" +
                " active=${out.optInt("parcel.active")}" + if (lastErr.isEmpty()) "" else " err=$lastErr")
    }

    private fun toItem(tn: String, com: String, o: JSONObject): Item {
        val rawState = o.optString("state", "0")
        val data = o.optJSONArray("data")
        val first = data?.optJSONObject(0) ?: JSONObject()
        val line = first.optString("context", "")
        val loc = first.optString("location", "")
        val tStr = first.optString("time", first.optString("ftime", ""))
        val time = parseTime(tStr)
        val now = System.currentTimeMillis() / 1000
        val freshNode = time > 0 && now - time < 24 * 3600

        val (state, stateCn, score) = when (rawState) {
            "5" -> Triple("delivering", "派送中", 0)
            "2" -> Triple("issue", "疑难件", 1)
            "3" -> Triple("signed", "已签收", 4)
            "1" -> Triple("transit", "揽收", if (freshNode) 2 else 3)
            "4" -> Triple("other", "已退回", 3)
            "6" -> Triple("other", "退回中", 3)
            "7" -> Triple("other", "转投中", 3)
            else -> Triple("transit", "运输中", if (freshNode) 2 else 3)
        }
        return Item(
            no = tn, com = com, comName = COM_NAME[com] ?: o.optString("com").ifEmpty { "快递" },
            state = state, stateCn = stateCn,
            line = line, time = time, loc = loc, score = score
        )
    }

    private fun now10(): Long = System.currentTimeMillis() / 1000

    private fun parseTime(s: String): Long = try {
        for (f in arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            try {
                SimpleDateFormat(f, Locale.US).parse(s)?.let { return it.time / 1000 }
            } catch (_: Throwable) {
            }
        }
        0L
    } catch (t: Throwable) {
        0L
    }

    /** 单发查询；任何异常返回 null（不抛，避免一次失败拖垮整次刷新） */
    private fun query(com: String, tn: String, phone: String): JSONObject? {
        val urlStr = "https://www.kuaidi100.com/query?type=" + com +
                "&postid=" + java.net.URLEncoder.encode(tn, "UTF-8") +
                if (phone.isNotEmpty()) "&phone=" + phone else ""
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty("Referer", "https://www.kuaidi100.com/")
            }
            var text: String? = null
            try {
                text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
            if (text.isNullOrEmpty()) return null
            val j = JSONObject(text)
            if (j.optString("status") != "200" && j.optString("status") != "1") {
                AppLog.w("$TAG query $tn status=${j.optString("status")} ${j.optString("message")}")
                return null
            }
            j
        } catch (t: Throwable) {
            AppLog.w("$TAG query fail $tn: ${t.message}")
            null
        }
    }

    private fun readCache(f: File): JSONObject = try {
        if (f.isFile) JSONObject(f.readText()) else JSONObject()
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun writeCache(f: File, o: JSONObject) {
        try {
            // 只留 50 条，防止无限膨胀
            val keys = o.keys().asSequence().toList()
            if (keys.size > 50) {
                val sorted = keys.sortedBy { o.optJSONObject(it)?.optLong("ts", 0L) ?: 0L }
                for (k in sorted.take(keys.size - 50)) o.remove(k)
            }
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
        } catch (t: Throwable) {
            AppLog.w("$TAG cache write fail: ${t.message}")
        }
    }
}
