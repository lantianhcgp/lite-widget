package com.litewidget.app.mcp

import android.util.Base64
import com.litewidget.app.App
import com.litewidget.app.core.AppLog
import com.litewidget.app.core.render.Renderer
import com.litewidget.app.core.spec.SpecValidator
import com.litewidget.app.widget.WidgetUpdater
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网 MCP 服务端：JSON-RPC 2.0 over HTTP（Streamable HTTP 的最小可用子集）。
 *
 * 定位：暴露 App 沙箱的「文件系统 + 日志 + 组件生效/渲染」，让 AI 直接开发小组件。
 * 凭据（后台地址/充值号）不在此暴露，只在 App 内配置。
 *
 * POST /mcp     methods: initialize / tools/list / tools/call / ping
 * GET  /health  匿名探活
 */
class McpServer(private val app: App) {

    @Volatile
    var running = false
        private set

    @Volatile
    var boundPort = -1
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var server: ServerSocket? = null
    private var loop: Thread? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "mcp-conn").apply { isDaemon = true }
    }
    private val inflight = AtomicInteger(0)

    fun start(port: Int, token: String): Boolean {
        if (running) stop()
        return try {
            val ss = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            ss.soTimeout = 0
            server = ss
            boundPort = ss.localPort
            running = true
            lastError = null
            loop = Thread({
                AppLog.i("MCP listening on 0.0.0.0:$boundPort")
                while (running) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handle(sock, token) }
                    } catch (t: Throwable) {
                        if (running) {
                            lastError = t.message
                            AppLog.w("MCP accept error: ${t.message}")
                        }
                    }
                }
            }, "mcp-loop").apply { isDaemon = true; start() }
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            AppLog.e("MCP start fail on $port", t)
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        boundPort = -1
        AppLog.i("MCP stopped")
    }

    fun stats(): String = "connections=$inflight"

    // ------------------------------------------------------------ 连接处理

    private fun handle(sock: Socket, token: String) {
        inflight.incrementAndGet()
        try {
            sock.soTimeout = 20000
            val input = BufferedInputStream(sock.getInputStream())
            val output = BufferedOutputStream(sock.getOutputStream())

            val (method, path, headers) = readRequestLine(input) ?: run {
                output.write(response(400, """{"error":"bad request"}""")); output.flush(); return
            }
            val body = readBody(input, headers)

            when {
                method == "GET" && path.startsWith("/health") -> {
                    output.write(
                        response(
                            200,
                            JSONObject().put("ok", true).put("app", "lite-widget")
                                .put("version", VERSION).toString()
                        )
                    )
                }
                method == "POST" && path.startsWith("/mcp") -> {
                    if (!authorized(path, headers, token)) {
                        output.write(response(401, """{"error":"unauthorized","hint":"Authorization: Bearer <token>"}"""))
                    } else if (body.isEmpty()) {
                        output.write(response(400, """{"error":"empty body"}"""))
                    } else {
                        output.write(response(200, routeRpc(body)))
                    }
                }
                else -> output.write(response(404, """{"error":"not found"}"""))
            }
            output.flush()
        } catch (t: Throwable) {
            AppLog.w("MCP conn error: ${t.message}")
        } finally {
            inflight.decrementAndGet()
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun authorized(path: String, headers: Map<String, String>, token: String): Boolean {
        if (token.isEmpty()) return true
        val h = headers["authorization"] ?: ""
        if (h.equals("Bearer $token", ignoreCase = true)) return true
        if (h == "Bearer $token") return true
        val q = path.substringAfter('?', "")
        if (q.isNotEmpty()) {
            for (kv in q.split('&')) {
                val p = kv.split('=', limit = 2)
                if (p.size == 2 && p[0] == "token" && p[1] == token) return true
            }
        }
        return false
    }

    private fun readRequestLine(input: BufferedInputStream): Triple<String, String, Map<String, String>>? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        return Triple(parts[0], parts[1], headers)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): String {
        val len = headers["content-length"]?.toIntOrNull() ?: return ""
        if (len <= 0) return ""
        if (len > MAX_BODY) throw IllegalStateException("body too large ($len)")
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun response(code: Int, json: String): ByteArray {
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            else -> "404 Not Found"
        }
        val head = "HTTP/1.1 $status\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${json.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n"
        return (head + json).toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------ JSON-RPC

    private fun routeRpc(body: String): String {
        val req = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return rpcError(null, -32700, "parse error")
        }
        val id = if (req.has("id")) req.opt("id") else null
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "initialize" -> rpcResult(
                    id, JSONObject()
                        .put("protocolVersion", "2024-11-05")
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put("serverInfo", JSONObject().put("name", "lite-widget").put("version", VERSION))
                        .put("instructions", "编辑 widgets/ 下的 widget.json 即可改小组件；改完调用 widget_validate 校验、widget_render 看效果、widget_reload 应用到桌面。日志用 log_tail。")
                )
                "notifications/initialized", "notifications/cancelled" -> ""
                "ping" -> rpcResult(id, JSONObject())
                "tools/list" -> rpcResult(id, JSONObject().put("tools", toolsArray()))
                "tools/call" -> {
                    val name = params.optString("name", "")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val r = callTool(name, args)
                    rpcResult(
                        id, JSONObject()
                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", r.first)))
                            .put("isError", r.second)
                    )
                }
                else -> rpcError(id, -32601, "method not found: $method")
            }
        } catch (t: Throwable) {
            AppLog.e("mcp tool error in $method", t)
            rpcError(id, -32603, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun rpcResult(id: Any?, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()

    private fun rpcError(id: Any?, code: Int, msg: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", msg))
            .toString()

    // ------------------------------------------------------------ 工具定义

    private fun toolsArray(): JSONArray {
        val a = JSONArray()
        a.put(tool("fs_tree", "列出 App 沙箱目录结构", obj {
            prop("path", "string", "相对路径，默认 widgets")
        }))
        a.put(tool("fs_read", "读取组件文件内容", obj {
            prop("path", "string", "如 widgets/wifi-flow/widget.json", true)
        }))
        a.put(tool("fs_write", "写入组件文件（AI 的主要编辑手段）", obj {
            prop("path", "string", "如 widgets/wifi-flow/widget.json", true)
            prop("content", "string", "完整文件内容，整体覆盖", true)
        }))
        a.put(tool("fs_delete", "删除组件内文件", obj {
            prop("path", "string", "如 widgets/wifi-flow/assets/old.png", true)
        }))
        a.put(tool("widget_list", "列出所有组件", obj {}))
        a.put(tool("widget_validate", "校验组件是否符合 schema（写完必调）", obj {
            prop("id", "string", "组件 id", true)
        }))
        a.put(tool("widget_render", "渲染组件预览图（PNG base64），用于自检样式", obj {
            prop("id", "string", "组件 id", true)
            prop("size", "string", "规范尺寸 2x2/4x1/4x2/2x4/4x4：渲染该尺寸的变体（推荐）")
            prop("width", "integer", "输出宽 px（给了 size 时默认按规范比例）")
            prop("height", "integer", "输出高 px")
        }))
        a.put(tool("widget_reload", "把组件应用到桌面小组件", obj {
            prop("id", "string", "组件 id，缺省用当前激活组件")
        }))
        a.put(tool("log_tail", "读取 App 日志（改完看渲染报错）", obj {
            prop("lines", "integer", "行数，默认 200")
            prop("filter", "string", "关键字过滤")
        }))
        a.put(tool("log_clear", "清空日志", obj {}))
        a.put(tool("data_snapshot", "读取当前数据快照（流量/设备/账户字段）", obj {}))
        a.put(tool("data_refresh", "重新拉取后台数据（后台线程执行）", obj {}))
        a.put(tool("schema_get", "获取 widget.json / manifest.json 的 schema", obj {}))
        return a
    }

    private fun obj(f: JSONObject.() -> Unit): JSONObject = JSONObject().apply(f)

    /** 定义一个属性（在 obj{} 里作为扩展调用；required 用描述表达，避免污染 properties 结构） */
    private fun JSONObject.prop(name: String, type: String, desc: String, required: Boolean = false) {
        val text = if (required) "【必填】$desc" else desc
        put(name, JSONObject().put("type", type).put("description", text))
    }

    private fun tool(name: String, desc: String, schema: JSONObject): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", schema))

    // ------------------------------------------------------------ 工具实现

    private fun callTool(name: String, args: JSONObject): Pair<String, Boolean> {
        val store = app.store
        return when (name) {
            "fs_tree" -> {
                val rel = args.optString("path", "widgets")
                val root = sandbox(rel) ?: return "路径越权: $rel" to true
                if (!root.exists()) return "不存在: $rel" to true
                tree(root, StringBuilder()).toString() to false
            }
            "fs_read" -> {
                val rel = args.optString("path", "")
                if (rel.isEmpty()) return "缺少 path" to true
                val f = resolveRead(rel) ?: return "路径越权: $rel" to true
                if (!f.isFile) return "不存在或不是文件: $rel" to true
                if (f.length() > 256 * 1024) return "文件过大 (${f.length()} bytes)" to true
                f.readText(Charsets.UTF_8) to false
            }
            "fs_write" -> {
                val rel = args.optString("path", "")
                val content = args.optString("content", "")
                if (rel.isEmpty()) return "缺少 path" to true
                if (!args.has("content")) return "缺少 content" to true
                val f = resolveWrite(rel) ?: return "路径越权: $rel" to true
                if (f.isDirectory) return "路径是目录: $rel" to true
                f.parentFile?.mkdirs()
                f.writeText(content, Charsets.UTF_8)
                AppLog.i("mcp write $rel (${content.length} bytes)")
                // 顺手跑一遍 schema 校验，AI 立刻知道格式对不对
                val warn = when {
                    rel.endsWith("widget.json") -> checkJson(content) { SpecValidator.widget(it) }
                    rel.endsWith("manifest.json") -> checkJson(content) { SpecValidator.manifest(it) }
                    else -> ""
                }
                "已写入 $rel (${content.length} bytes)\n$warn".trim() to false
            }
            "fs_delete" -> {
                val rel = args.optString("path", "")
                val f = resolveRead(rel) ?: return "路径越权: $rel" to true
                if (!f.exists()) return "不存在: $rel" to true
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                (if (ok) "已删除 $rel" else "删除失败: $rel") to !ok
            }
            "widget_list" -> {
                val items = store.list()
                if (items.isEmpty()) "（还没有组件）" to false
                else items.joinToString("\n") { "${it.id}  ${it.name}  ${it.size}  v${it.version}" } to false
            }
            "widget_validate" -> {
                val id = args.optString("id", "")
                if (!store.exists(id)) return "组件不存在: $id" to true
                val errs = store.validate(id)
                if (errs.isEmpty()) "OK：$id 通过 schema 校验" to false
                else "发现 ${errs.size} 个问题:\n" + errs.joinToString("\n") { " - $it" } to true
            }
            "widget_render" -> {
                val id = args.optString("id", "")
                if (!store.exists(id)) return "组件不存在: $id" to true
                val spec = store.readSpec(id) ?: return "widget.json 缺失或解析失败" to true
                val errs = SpecValidator.widget(spec)
                val size = args.optString("size", "").takeIf { it in Renderer.CANONICAL }
                var w = args.optInt("width", 0)
                var h = args.optInt("height", 0)
                val density: Float
                if (size != null) {
                    val c = Renderer.CANONICAL[size]!!
                    if (w <= 0) w = (c.first * 2f).toInt()
                    density = w / c.first
                    if (h <= 0) h = (c.second * density).toInt()
                } else {
                    if (w <= 0) w = 720
                    if (h <= 0) h = 360
                    density = Renderer.previewDensity(spec, w)
                }
                val bmp = Renderer(app.assetLoader.forWidget(id)).render(spec, app.data.forRender(), w, h, density)
                val b64 = Base64.encodeToString(bitmapToPng(bmp), Base64.NO_WRAP)
                bmp.recycle()
                val header = "渲染成功 ${w}x${h}" + if (errs.isEmpty()) "，schema OK" else
                    "\n⚠ schema 有 ${errs.size} 个问题（可能渲染的是旧内容）:\n" + errs.joinToString("\n") { " - $it" }
                "$header\nimage/png;base64:\n$b64" to false
            }
            "widget_reload" -> {
                var id = args.optString("id", "")
                if (id.isEmpty()) id = app.prefs.activeWidget
                if (id.isEmpty()) {
                    id = store.list().firstOrNull()?.id ?: return "没有可用组件" to true
                }
                if (!store.exists(id)) return "组件不存在: $id" to true
                app.prefs.activeWidget = id
                WidgetUpdater.pushAll(app, id)
                "已应用 $id 到桌面（home widget 已更新）" to false
            }
            "log_tail" -> {
                val lines = args.optInt("lines", 200)
                val filter = if (args.has("filter")) args.optString("filter") else null
                val t = AppLog.tail(lines, filter)
                if (t.isEmpty()) "（日志为空）" to false else t to false
            }
            "log_clear" -> {
                AppLog.clear()
                "日志已清空" to false
            }
            "data_snapshot" -> {
                val v = app.data.values()
                if (v.isEmpty()) "（还没有数据，调 data_refresh 或在 App 里填数据源）" to false
                else {
                    val o = JSONObject()
                    for ((k, value) in v) o.put(k, value ?: JSONObject.NULL)
                    o.toString(2) to false
                }
            }
            "data_refresh" -> {
                val v = try {
                    app.data.refresh()
                } catch (t: Throwable) {
                    return "拉取失败: ${t.message}" to true
                }
                "刷新成功，${v.size} 个字段" to false
            }
            "schema_get" -> {
                val f = File(app.filesDir, "schema.json")
                if (f.isFile) f.readText(Charsets.UTF_8) to false
                else DEFAULT_SCHEMA to false
            }
            else -> "未知工具: $name" to true
        }
    }

    private fun checkJson(content: String, v: (JSONObject) -> List<String>): String = try {
        val errs = v(JSONObject(content))
        if (errs.isEmpty()) "schema: OK"
        else "schema 问题:\n" + errs.joinToString("\n") { " - $it" }
    } catch (t: Throwable) {
        "JSON 解析失败: ${t.message}"
    }

    /** 沙箱根目录，只允许 widgets/ logs/ data/ exports/ */
    private fun sandbox(rel: String): File? {
        val r = rel.trim().trimStart('/')
        if (r.contains("..")) return null
        val top = r.substringBefore('/')
        if (top !in ALLOWED) return null
        val f = File(app.filesDir, r)
        return if (f.canonicalFile.path.startsWith(app.filesDir.canonicalFile.path)) f.canonicalFile else null
    }

    private fun resolveRead(rel: String): File? {
        val r = rel.trim().trimStart('/')
        if (r in ALLOWED) return File(app.filesDir, r)
        return sandbox(r)
    }

    private fun resolveWrite(rel: String): File? {
        val r = rel.trim().trimStart('/')
        if (r.contains("..")) return null
        val top = r.substringBefore('/')
        if (top !in WRITABLE) return null
        val f = File(app.filesDir, r)
        return if (f.canonicalFile.path.startsWith(app.filesDir.canonicalFile.path)) f.canonicalFile else null
    }

    private fun tree(f: File, sb: StringBuilder, depth: Int = 0, prefix: String = "") {
        if (depth > 6) return
        val kids = f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
        for ((i, k) in kids.withIndex()) {
            val last = i == kids.size - 1
            sb.append(prefix).append(if (last) "└─ " else "├─ ")
            sb.append(k.name).append(if (k.isDirectory) "/" else " (${k.length()}B)").append('\n')
            if (k.isDirectory) tree(k, sb, depth + 1, prefix + if (last) "   " else "│  ")
        }
    }

    private fun bitmapToPng(bmp: android.graphics.Bitmap): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    companion object {
        const val VERSION = "0.1.8"
        private const val MAX_BODY = 2 * 1024 * 1024
        private val ALLOWED = setOf("widgets", "logs", "data", "exports")
        private val WRITABLE = setOf("widgets", "logs")

        private val DEFAULT_SCHEMA =
            """{"note":"schema.json 未落地到 App，参考 lite-widget-schema.json"}"""
    }
}
