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

    fun hasSource(): Boolean = baseUrl.isNotEmpty() && devNo.isNotEmpty()

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
