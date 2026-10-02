package com.litewidget.app.core

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/**
 * 本地配置：数据源（后台地址 + 充值号）、MCP 端口/令牌、当前组件。
 * 凭据只存本地，不进组件包、不进日志、不通过 MCP 暴露。
 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("litewidget", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "") ?: ""
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    var devNo: String
        get() = sp.getString("dev_no", "") ?: ""
        set(v) = sp.edit().putString("dev_no", v.trim()).apply()

    var port: Int
        get() = sp.getInt("port", 8765)
        set(v) = sp.edit().putInt("port", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var activeWidget: String
        get() = sp.getString("active_widget", "") ?: ""
        set(v) = sp.edit().putString("active_widget", v).apply()

    var serverRunning: Boolean
        get() = sp.getBoolean("server_running", false)
        set(v) = sp.edit().putBoolean("server_running", v).apply()

    /** 用户对 MCP 开关的意愿：App 启动时按它自动恢复服务（重装/被杀后不丢） */
    var serverWanted: Boolean
        get() = sp.getBoolean("server_wanted", false)
        set(v) = sp.edit().putBoolean("server_wanted", v).apply()

    /** 首次生成 32 位访问令牌 */
    fun ensureToken(): String {
        var t = token
        if (t.isEmpty()) {
            t = UUID.randomUUID().toString().replace("-", "")
            token = t
            AppLog.i("MCP token generated")
        }
        return t
    }

    /** 变量值：组件 data 声明的输入（存 var_<name>，凭据永不进组件包/MCP） */
    fun varValue(name: String): String = sp.getString("var_$name", "") ?: ""
    fun setVarValue(name: String, v: String) = sp.edit().putString("var_$name", v.trim()).apply()

    /** 数据源优先读变量（新机制），回退老字段（兼容已填过的用户） */
    val effectiveBaseUrl: String get() = varValue("baseUrl").ifEmpty { baseUrl }
    val effectiveDevNo: String get() = varValue("devNo").ifEmpty { devNo }

    fun hasSource(): Boolean = effectiveBaseUrl.isNotEmpty() && effectiveDevNo.isNotEmpty()

    /** 老字段一次性迁移进变量存储 */
    fun migrateLegacyVars() {
        if (varValue("baseUrl").isEmpty() && baseUrl.isNotEmpty()) setVarValue("baseUrl", baseUrl)
        if (varValue("devNo").isEmpty() && devNo.isNotEmpty()) setVarValue("devNo", devNo)
    }

    /** 每个组件的自动刷新频率（分钟，0 = 不自动刷新） */
    fun refreshInterval(id: String): Int = sp.getInt("refresh_$id", 0)
    fun setRefreshInterval(id: String, minutes: Int) = sp.edit().putInt("refresh_$id", minutes).apply()
    fun lastRender(id: String): Long = sp.getLong("last_render_$id", 0L)
    fun setLastRender(id: String, ts: Long) = sp.edit().putLong("last_render_$id", ts).apply()

    /** 桌面实例绑定：appWidgetId -> 组件 id（管理器里逐实例指定模板） */
    fun binding(appWidgetId: Int): String = sp.getString("bind_$appWidgetId", "") ?: ""
    fun setBinding(appWidgetId: Int, id: String) = sp.edit().putString("bind_$appWidgetId", id).apply()
    fun clearBinding(appWidgetId: Int) = sp.edit().remove("bind_$appWidgetId").apply()

    companion object {
        /** sign = MD5(dev_no + time + nonce + SALT).toUpperCase() —— 已逆向，服务端暂不校验 */
        const val SIGN_SALT = "ttu260915sslpJSk"

        fun md5(s: String): String {
            val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(d.size * 2)
            for (b in d) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            return sb.toString().uppercase()
        }
    }
}
