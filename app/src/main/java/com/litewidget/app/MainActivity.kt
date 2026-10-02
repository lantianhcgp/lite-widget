package com.litewidget.app

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import com.litewidget.app.core.AppLog
import com.litewidget.app.core.Prefs
import com.litewidget.app.mcp.McpService
import com.litewidget.app.widget.WidgetUpdater
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val ui = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-work") }
    private var pendingImport: Boolean = false
    private var pendingExportId: String? = null

    private lateinit var serverStatus: TextView
    private lateinit var serverToken: TextView
    private lateinit var serverSwitch: Switch
    private lateinit var sourceStatus: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var emptyTip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.litewidget.app.core.Trace.mark(this, "7 MainActivity.onCreate start")
        try {
            boot()
        } catch (t: Throwable) {
            onBootFailure(t)
        }
    }

    private fun boot() {
        showCrashIfAny() // 最先弹，保证即使后面还有崩溃点也能拿到堆栈
        com.litewidget.app.core.Trace.mark(this, "8 showCrashIfAny done")
        setContentView(R.layout.activity_main)
        com.litewidget.app.core.Trace.mark(this, "9 setContentView OK")

        findViewById<TextView>(R.id.subtitle).text =
            "v${BuildConfigCompat.VERSION} · 文件驱动 + MCP 开发"

        serverStatus = findViewById(R.id.server_status)
        serverToken = findViewById(R.id.server_token)
        serverSwitch = findViewById(R.id.server_switch)
        sourceStatus = findViewById(R.id.source_status)
        listBox = findViewById(R.id.widget_list)
        emptyTip = findViewById(R.id.empty_tip)

        val prefs = App.instance.prefs
        prefs.ensureToken()

        // 首次启动：给个示例组件，界面不空
        if (App.instance.store.list().isEmpty()) {
            App.instance.store.create("wifi-flow", "流量卡")
            prefs.activeWidget = "wifi-flow"
            AppLog.i("bootstrap sample widget created")
        }

        if (prefs.serverWanted && !McpService.isRunning()) {
            McpService.start(this)
            AppLog.i("MCP auto-restore (serverWanted=true)")
        }
        serverSwitch.isChecked = McpService.isRunning()
        updateServerUi()

        serverSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.serverWanted = checked
            if (checked) {
                McpService.start(this)
                serverStatus.text = "正在启动…"
                serverHandler.postDelayed({ runOnUiThread { updateServerUi() } }, 600)
            } else {
                McpService.stop(this)
                updateServerUi()
            }
        }

        serverToken.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("mcp token", prefs.token))
            toast("令牌已复制")
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refreshData() }
        findViewById<Button>(R.id.btn_manager).setOnClickListener {
            startActivity(Intent(this, ManagerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_vars).setOnClickListener {
            startActivity(Intent(this, VariablesActivity::class.java))
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener { promptNewWidget() }

        findViewById<Button>(R.id.btn_import).setOnClickListener {
            pendingImport = true
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
            }
            startActivityForResult(i, REQ_IMPORT)
        }

        findViewById<Button>(R.id.btn_logs).setOnClickListener { showLogs() }

        com.litewidget.app.core.Trace.mark(this, "10 findViewById/listeners OK")
        renderList()
        com.litewidget.app.core.Trace.mark(this, "11 renderList OK")
        askNotificationPermission()
        com.litewidget.app.core.Trace.mark(this, "12 boot COMPLETE (界面出来了)")
    }

    /** 启动失败不上报系统，直接在首页把堆栈亮出来（没有 logcat 就靠这个） */
    private fun onBootFailure(t: Throwable) {
        com.litewidget.app.core.Trace.error(
            this,
            "boot FAILED\n" + android.util.Log.getStackTraceString(t)
        )
        AppLog.e("onCreate failed", t)
        try {
            App.instance.crashFile.appendText(
                "==== boot failure ====\n" + android.util.Log.getStackTraceString(t) + "\n----\n"
            )
        } catch (_: Throwable) {
        }
        try {
            val tv = TextView(this).apply {
                setPadding(48, 48, 48, 48)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                text = android.util.Log.getStackTraceString(t)
            }
            AlertDialog.Builder(this)
                .setTitle("启动异常（截给我看）")
                .setView(tv)
                .setPositiveButton("关闭", null)
                .show()
        } catch (_: Throwable) {
        }
    }

    /** 上次崩溃自报家门：没有 logcat 时靠这个拿堆栈 */
    private fun showCrashIfAny() {
        val f = App.instance.crashFile
        if (!f.isFile || f.length() == 0L) return
        val crashText = try {
            f.readText()
        } catch (t: Throwable) {
            return
        }
        val tv = TextView(this).apply {
            setPadding(32, 24, 32, 24)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = crashText
        }
        AlertDialog.Builder(this)
            .setTitle("上次启动崩溃（截给我看）")
            .setView(tv)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                f.writeText("")
            }
            .show()
        AppLog.e("previous crash shown:\n$crashText")
    }

    // 不能在构造期取 mainLooper（此时 Context 尚未 attach → NPE，正是闪退根因），首次使用时才初始化
    private val serverHandler by lazy { android.os.Handler(mainLooper) }

    // ------------------------------------------------------------ 列表

    private fun renderList() {
        val items = App.instance.store.list()
        emptyTip.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        listBox.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.item_widget, listBox, false)
            row.findViewById<TextView>(R.id.item_title).text = "${item.name}"
            val active = if (item.id == App.instance.prefs.activeWidget) " · 已应用" else ""
            val iv = App.instance.prefs.refreshInterval(item.id)
            row.findViewById<TextView>(R.id.item_meta).text =
                "${item.id} · ${item.size} · v${item.version}$active · 刷新:${intervalText(iv)}"
            val sizes = supportedSizes(item.id)
            row.findViewById<TextView>(R.id.item_sizes).text =
                "尺寸 " + sizes.joinToString(" · ") + if (sizes.size > 1) "　（点卡片看全尺寸预览）" else ""
            row.setOnClickListener { showGallery(item.id, item.name) }

            val preview = row.findViewById<ImageView>(R.id.item_preview)
            loadPreview(item.id, preview)

            row.findViewById<Button>(R.id.item_apply).setOnClickListener {
                App.instance.prefs.activeWidget = item.id
                toast("已应用「${item.name}」")
                WidgetUpdater.pushAll(this, item.id, refreshData = false)
                renderList()
            }
            row.findViewById<Button>(R.id.item_export).setOnClickListener {
                pendingExportId = item.id
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, "${item.id}.lwgt")
                }
                startActivityForResult(i, REQ_EXPORT)
            }
            row.findViewById<Button>(R.id.item_delete).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("删除「${item.name}」？")
                    .setMessage("将删除组件目录及其资源，不可恢复。")
                    .setPositiveButton("删除") { _, _ ->
                        App.instance.store.deleteWidget(item.id)
                        App.instance.assetLoader.invalidate(item.id)
                        renderList()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            row.findViewById<Button>(R.id.item_freq).setOnClickListener {
                promptRefreshInterval(item.id)
            }
            listBox.addView(row)
        }
    }

    private fun loadPreview(id: String, target: ImageView) {
        ui.execute {
            val bmp = try {
                WidgetUpdater.previewBitmap(this, id, 360, 180)
            } catch (t: Throwable) {
                AppLog.e("preview $id fail", t)
                null
            }
            runOnUiThread {
                if (bmp != null) target.setImageBitmap(bmp) else target.setImageDrawable(null)
            }
        }
    }

    // ------------------------------------------------------------ 数据

    private fun refreshData() {
        sourceStatus.text = "正在拉取后台数据…"
        ui.execute {
            val msg = try {
                val v = App.instance.data.refresh()
                "已刷新：${v["package.name"]} · 已用 ${v["flow.used"]} MB · 电量 ${v["device.battery"]}%"
            } catch (t: Throwable) {
                "拉取失败：${t.message}"
            }
            runOnUiThread {
                sourceStatus.text = msg
                renderList()
                WidgetUpdater.pushAll(this, null, refreshData = false)
            }
        }
    }

    private fun promptNewWidget() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val idInput = EditText(this).apply { hint = "id（小写字母数字）"; setText("widget-${System.currentTimeMillis() % 10000}") }
        val nameInput = EditText(this).apply { hint = "组件名"; setText("新组件") }
        val pad = (16 * resources.displayMetrics.density).toInt()
        box.addView(idInput, LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, pad, pad, 0) })
        box.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, 0, pad, pad) })
        AlertDialog.Builder(this)
            .setTitle("新建组件")
            .setView(box)
            .setPositiveButton("创建") { _, _ ->
                val id = idInput.text.toString().trim().ifEmpty { "widget-${System.currentTimeMillis() % 10000}" }
                val name = nameInput.text.toString().trim().ifEmpty { "新组件" }
                if (App.instance.store.create(id, name)) {
                    App.instance.prefs.activeWidget = id
                    toast("已创建 $id")
                    renderList()
                } else toast("id 已存在")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 模板支持的尺寸（variants 键 + manifest 尺寸，按固定顺序） */
    private fun supportedSizes(id: String): List<String> {
        val spec = App.instance.store.readSpec(id) ?: return listOf("4x2")
        val order = listOf("4x1", "2x2", "4x2", "2x4", "4x4")
        val set = HashSet<String>()
        spec.optJSONObject("variants")?.let { vs -> for (k in vs.keys()) set.add(k) }
        val ms = App.instance.store.readManifest(id)?.optString("size", "") ?: ""
        if (ms.isNotEmpty()) set.add(ms)
        val list = order.filter { it in set }
        return if (list.isEmpty()) listOf(ms.ifEmpty { "4x2" }) else list
    }

    private val SIZE_LABEL = mapOf(
        "4x1" to "4x1 横条", "2x2" to "2x2 方形", "4x2" to "4x2 标准",
        "2x4" to "2x4 竖长", "4x4" to "4x4 大方"
    )

    /** 点开模板 → 逐尺寸渲染预览画廊（边渲染边追加） */
    private fun showGallery(id: String, name: String) {
        val dm = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dm).toInt(), 0, (16 * dm).toInt(), 0)
        }
        val loading = TextView(this).apply {
            text = "正在渲染各尺寸预览…"
            setTextColor(0xFF9E9EA6.toInt())
            textSize = 13f
        }
        box.addView(loading)
        val scroll = ScrollView(this).apply { addView(box) }
        val dlg = AlertDialog.Builder(this)
            .setTitle("$name · 全尺寸预览")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        dlg.setOnDismissListener { box.removeAllViews() }
        dlg.show()

        val sizes = supportedSizes(id)
        ui.execute {
            var first = true
            for (s in sizes) {
                val bmp = try {
                    WidgetUpdater.previewBitmap(this, id, s)
                } catch (t: Throwable) {
                    AppLog.e("gallery render $id/$s fail", t)
                    null
                }
                runOnUiThread {
                    if (first) { box.removeView(loading); first = false }
                    val label = TextView(this).apply {
                        text = SIZE_LABEL[s] ?: s
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 13f
                        setPadding(0, (6 * dm).toInt(), 0, 0)
                    }
                    val img = ImageView(this).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(0, (4 * dm).toInt(), 0, (10 * dm).toInt())
                        if (bmp != null) setImageBitmap(bmp)
                        else {
                            setBackgroundColor(0xFF14141A.toInt())
                            setPadding((12 * dm).toInt(), (40 * dm).toInt(), (12 * dm).toInt(), (40 * dm).toInt())
                        }
                    }
                    box.addView(label)
                    box.addView(img)
                }
            }
        }
    }

    /** 自动刷新频率选择（每个组件独立设置） */
    private fun promptRefreshInterval(id: String) {
        val items = arrayOf("关闭（不自动刷新）", "每 1 分钟", "每 5 分钟", "每 15 分钟", "每 30 分钟", "每 60 分钟")
        val values = intArrayOf(0, 1, 5, 15, 30, 60)
        val cur = App.instance.prefs.refreshInterval(id)
        val checked = values.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("「${id}」自动刷新频率")
            .setSingleChoiceItems(items, checked) { d, which ->
                App.instance.prefs.setRefreshInterval(id, values[which])
                App.instance.prefs.setLastRender(id, 0L)
                toast(if (values[which] == 0) "已关闭自动刷新" else "每 ${values[which]} 分钟自动刷新")
                d.dismiss()
                renderList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun intervalText(m: Int) = if (m <= 0) "关" else "${m}分"

    private fun showLogs() {
        val tv = TextView(this).apply {
            setPadding(32, 24, 32, 24)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = AppLog.tail(300)
        }
        AlertDialog.Builder(this)
            .setTitle("日志（最近 300 行）")
            .setView(tv)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                AppLog.clear()
                toast("日志已清空")
            }
            .show()
    }

    // ------------------------------------------------------------ 导入导出

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQ_IMPORT -> ui.execute {
                val msg = try {
                    val inp = contentResolver.openInputStream(uri)
                    if (inp == null) "无法打开文件"
                    else {
                        val id = App.instance.store.importZip(inp)
                        val errs = App.instance.store.validate(id)
                        if (errs.isEmpty()) "已导入 $id"
                        else "已导入 $id，但 schema 有 ${errs.size} 个问题：\n${errs.take(5).joinToString("\n")}"
                    }
                } catch (t: Throwable) {
                    "导入失败：${t.message}"
                }
                runOnUiThread {
                    toast(msg)
                    renderList()
                }
            }
            REQ_EXPORT -> {
                val id = pendingExportId ?: return
                pendingExportId = null
                ui.execute {
                    val msg = try {
                        val out = contentResolver.openOutputStream(uri)
                        if (out == null) "无法写入目标文件"
                        else {
                            App.instance.store.exportZip(id, out)
                            "已导出 $id.lwgt"
                        }
                    } catch (t: Throwable) {
                        "导出失败：${t.message}"
                    }
                    runOnUiThread { toast(msg) }
                }
            }
        }
    }

    // ------------------------------------------------------------ 其它

    private fun updateServerUi() {
        val prefs = App.instance.prefs
        val running = McpService.isRunning()
        serverSwitch.isChecked = running
        serverStatus.text = if (running) {
            val ip = localIp()
            "运行中 · 0.0.0.0:${McpService.port()}\n" +
                    "本机: http://127.0.0.1:${McpService.port()}/mcp\n" +
                    "局域网: http://$ip:${McpService.port()}/mcp"
        } else {
            val err = McpService.error()
            if (err != null) "启动失败：$err" else "已停止（打开开关以监听端口）"
        }
        serverToken.text = "Token: ${prefs.token}  （点按复制）"
    }

    private fun localIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "0.0.0.0"
        } catch (t: Throwable) {
            "0.0.0.0"
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        try {
            if (::serverSwitch.isInitialized && ::listBox.isInitialized) {
                updateServerUi()
                renderList()
            }
        } catch (t: Throwable) {
            AppLog.e("onResume failed", t)
        }
    }

    override fun onDestroy() {
        ui.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQ_IMPORT = 1001
        private const val REQ_EXPORT = 1002
    }
}

/** 版本号占位（避免依赖 BuildConfig 生成时机） */
object BuildConfigCompat {
    const val VERSION = "0.1.7"
}
