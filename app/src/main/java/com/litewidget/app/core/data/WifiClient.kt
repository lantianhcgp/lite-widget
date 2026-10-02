package com.litewidget.app.core.data

import com.litewidget.app.core.AppLog
import com.litewidget.app.core.Prefs
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * 随身 WiFi 后台接口（白牌多租户 SaaS）。
 * 逆向自 pddwifi.gzkpiot.com / wifi.tianzhicheniot.com，详见接口扫描报告。
 *
 * 登录即数据：POST /api/Card/loginCard  {"dev_no": 充值号, "type": 2, time, nonce, sign}
 * sign = MD5(dev_no+time+nonce+SALT).toUpperCase()（服务端暂不校验，仍按算法发，兼容未来收紧）
 */
object WifiClient {

    private const val TAG = "WifiClient"
    private const val TIMEOUT = 15000

    fun loginCard(baseUrl: String, devNo: String): JSONObject {
        require(baseUrl.isNotEmpty()) { "后台地址为空" }
        require(devNo.isNotEmpty()) { "充值号为空" }

        val body = JSONObject()
        body.put("dev_no", devNo)
        body.put("type", 2)
        val time = System.currentTimeMillis()
        val nonce = randomNonce()
        body.put("time", time)
        body.put("nonce", nonce)
        body.put("sign", Prefs.md5(devNo + time + nonce + Prefs.SIGN_SALT))

        val url = URL(baseUrl.trimEnd('/') + "/api/Card/loginCard")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LiteWidget/0.1")
        }

        var code = -1
        var text: String? = null
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            code = conn.responseCode
            text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } finally {
            conn.disconnect()
        }

        AppLog.d("$TAG loginCard $baseUrl -> HTTP $code")
        if (text == null) throw IllegalStateException("空响应 (HTTP $code)")
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw IllegalStateException("响应不是 JSON (HTTP $code): ${text.take(200)}")
        }
        val c = json.optInt("code", 0)
        if (c != 1) throw IllegalStateException(json.optString("msg", "接口返回 code=$c"))
        return json.optJSONObject("data") ?: JSONObject()
    }

    private fun randomNonce(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        val sb = StringBuilder(32)
        for (x in b) sb.append(Character.forDigit((x.toInt() shr 4) and 0xF, 16))
            .append(Character.forDigit(x.toInt() and 0xF, 16))
        return sb.toString()
    }
}
