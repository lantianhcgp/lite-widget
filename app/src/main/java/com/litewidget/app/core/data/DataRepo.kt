package com.litewidget.app.core.data

import android.content.Context
import com.litewidget.app.core.AppLog
import com.litewidget.app.core.Prefs
import com.litewidget.app.core.render.sampleValues
import org.json.JSONObject
import java.io.File

/**
 * 数据仓库：抓后台 → 落盘快照 → 暴露成绑定字段 map。
 * 小组件/预览/MCP 都只读这里，不直接碰网络。
 *
 * 字段命名空间：package.* / flow.* / device.* / account.*
 */
class DataRepo(private val ctx: Context) {

    @Volatile
    private var cache: Map<String, Any?>? = null

    private val file get() = File(File(ctx.filesDir, "data"), "snapshot.json")

    fun lastFetch(): Long = file.lastModified()

    /** 读缓存；无缓存时返回空 map（调用方自行用 sample 兜底） */
    fun values(): Map<String, Any?> {
        cache?.let { return it }
        val j = readJson() ?: return emptyMap()
        val m = fromJson(j)
        cache = m
        return m
    }

    fun hasData(): Boolean = file.exists()

    fun readJson(): JSONObject? = try {
        if (!file.isFile) null else JSONObject(file.readText())
    } catch (t: Throwable) {
        AppLog.w("snapshot read fail: ${t.message}")
        null
    }

    /** 必须在后台线程调用。并集刷新：随身WiFi 变量和 parcel.list 单号清单各自独立生效 */
    fun refresh(): Map<String, Any?> {
        val prefs = Prefs(ctx)
        val list = prefs.varValue("parcel.list")
        val hasWifi = prefs.hasSource()
        if (!hasWifi && list.isEmpty()) {
            throw IllegalStateException("变量未填写：填随身WiFi变量，或物流组件的 parcel.list 单号清单")
        }
        val out = JSONObject()
        if (hasWifi) {
            val wifi = WifiClient.loginCard(prefs.effectiveBaseUrl, prefs.effectiveDevNo)
            for (k in wifi.keys()) out.put(k, wifi.get(k))
        }
        if (list.isNotEmpty()) ParcelClient.refreshInto(out, list, ctx)

        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        val m = fromJson(out)
        cache = m
        AppLog.i(
            "data refreshed: pkg=${m["package.name"]} remain=${m["flow.remain"]}" +
                    " battery=${m["device.battery"]} parcels=${m["parcel.count"] ?: "-"}"
        )
        return m
    }

    /** loginCard data → 绑定字段。单位：流量 MB，百分比 % */
    private fun fromJson(d: JSONObject): Map<String, Any?> {
        val eq = d.optJSONObject("equipment") ?: JSONObject()
        val total = d.optDouble("totalAmount", 0.0)
        val remain = d.optDouble("remainAmount", 0.0)
        val used = total - remain
        val percent = if (total > 0) used / total * 100.0 else 0.0

        val cards = eq.optJSONArray("card_list")
        var activeSim = ""
        if (cards != null) {
            for (i in 0 until cards.length()) {
                val c = cards.optJSONObject(i) ?: continue
                if (c.optInt("currentUsage", 0) == 1) activeSim = c.optString("operator_text", "")
            }
        }

        return mapOf<String, Any?>(
            "package.name" to d.optString("packageName", ""),
            "package.expire" to d.optString("expiretime", ""),
            "package.spec" to d.optInt("packageSpec", 0),
            "package.describe" to d.optString("packageDescribe", ""),

            "flow.total" to total,
            "flow.remain" to remain,
            "flow.used" to used,
            "flow.percent" to percent,

            "device.battery" to eq.optInt("devicePower", -1),
            "device.ssid" to eq.optString("hotspotName", ""),
            "device.pass" to eq.optString("hotspotPassword", ""),
            "device.status" to eq.optInt("deviceStatus", 0),
            "device.running" to eq.optString("runningTime", ""),
            "device.updated" to eq.optString("reportTime", ""),
            "device.sim" to activeSim,
            "device.cards" to (cards?.length() ?: 0),

            "account.balance" to d.optString("balance", "0.00"),
            "account.operator" to d.optString("operator", ""),
            "account.realname" to d.optString("realname_status", ""),
            "account.status" to d.optString("status", ""),
            "account.invite" to d.optString("invite_code", "")
        ).toMutableMap().apply {
            // 物流字段与随身WiFi字段并存于同一份快照
            for (k in d.keys()) {
                if (!k.startsWith("parcel.")) continue
                val v = d.get(k)
                put(k, if (v === JSONObject.NULL) null else v)
            }
        }
    }

    /** 取当前用于渲染的值：真实数据优先，没有就用示例数据（新装机也能看到效果） */
    fun forRender(): Map<String, Any?> {
        val v = values()
        return if (v.isEmpty()) sampleValues() else v
    }

    fun invalidate() {
        cache = null
    }
}
