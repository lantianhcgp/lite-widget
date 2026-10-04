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
    private lateinit var frostSwitch: Switch
    private lateinit var frostStatus: TextView
    private lateinit var sourceStatus: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var emptyTip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0B0C10.toInt()
        window.navigationBarColor = 0xFF0B0C10.toInt()
        com.litewidget.app.core.Trace.mark(this, "7 MainActivity.onCreate start")
        try {
            boot()
        } catch (t: Throwable) {
            onBootFailure(t)
        }
    }

    /**
     * 自启动权限引导：组件「关后台后到点自动唤起」建立在自启动权限上。
     * 只在明确检测为未授权时弹一次（非 MIUI 检测不了就不打扰），点了不再提醒。
     */
    private fun maybePromptAutoStart() {
        try {
            val prefs = App.instance.prefs
            if (prefs.autostartPrompted) return
            if (!com.litewidget.app.core.AutoStart.needPrompt(this)) return
            prefs.autostartPrompted = true
            serverHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                AlertDialog.Builder(this)
                    .setTitle("开启自启动，组件才会准时刷新")
                    .setMessage(
                        "划掉后台后，组件要到点刷新需要系统重新拉起本 App——" +
                            "这依赖「自启动」权限。\n\n" +
                            "未开启时：关后台后组件数据将不再自动更新。\n" +
                            "建议现在去安全中心开启本应用的自启动。"
                    )
                    .setPositiveButton("去开启") { _, _ ->
                        com.litewidget.app.core.AutoStart.openSettings(this)
                    }
                    .setNegativeButton("以后再说", null)
                    .show()
            }, 1200)
        } catch (t: Throwable) {
            AppLog.w("autostart prompt fail: ${t.message}")
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
        frostSwitch = findViewById(R.id.frost_switch)
        frostStatus = findViewById(R.id.frost_status)
        sourceStatus = findViewById(R.id.source_status)
        listBox = findViewById(R.id.widget_list)
        emptyTip = findViewById(R.id.empty_tip)

        val prefs = App.instance.prefs
        prefs.ensureToken()
        maybePromptAutoStart()

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

        frostSwitch.isChecked = prefs.frostWanted
        updateFrostUi()
        frostSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.frostWanted = checked
            if (checked && android.os.Build.VERSION.SDK_INT >= 29 &&
                !android.os.Environment.isExternalStorageManager()
            ) {
                toast("需要授予「所有文件访问权限」")
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ))
                } catch (t: Throwable) {
                    try {
                        startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (t2: Throwable) {
                        AppLog.w("open all-files settings failed: ${t2.message}")
                    }
                }
            }
            updateFrostUi()
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refreshData() }
        findViewById<Button>(R.id.btn_manager).setOnClickListener {
            startActivity(Intent(this, ManagerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_vars).setOnClickListener {
            startActivity(Intent(this, VariablesActivity::class.java))
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener { promptNewWidget() }

        findViewById<Button>(R.id.btn_pin).setOnClickListener { promptPinToDesktop() }

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
            row.findViewById<TextView>(R.id.item_meta).text =
                "${item.id} · ${item.size} · v${item.version}$active"
            val sizes = supportedSizes(item.id)
            row.findViewById<TextView>(R.id.item_sizes).text =
                "尺寸 " + sizes.joinToString(" · ") + if (sizes.size > 1) "　（点卡片看全尺寸预览）" else ""
            row.setOnClickListener { showGallery(item.id, item.name) }

            val preview = row.findViewById<ImageView>(R.id.item_preview)
            loadPreview(item.id, preview)

            row.findViewById<Button>(R.id.item_apply).setOnClickListener {
                val prefs = App.instance.prefs
                prefs.activeWidget = item.id
                // 一键应用：把桌面全部实例的绑定都换成这套模板（覆盖逐实例设置）
                var n = 0
                try {
                    val mgr = android.appwidget.AppWidgetManager.getInstance(this)
                    for (cls in listOf(
                            com.litewidget.app.widget.WidgetProvider2x2::class.java,
                            com.litewidget.app.widget.WidgetProvider4x1::class.java,
                            com.litewidget.app.widget.WidgetProvider4x2::class.java,
                            com.litewidget.app.widget.WidgetProvider2x4::class.java,
                            com.litewidget.app.widget.WidgetProvider4x4::class.java
                        )) {
                        val arr = mgr.getAppWidgetIds(
                            android.content.ComponentName(this, cls)) ?: IntArray(0)
                        for (wid in arr) { prefs.setBinding(wid, item.id); n++ }
                    }
                } catch (t: Throwable) {
                    AppLog.e("apply: bind all fail", t)
                }
                toast(if (n > 0) "已应用「${item.name}」到 $n 个桌面组件" else "已应用「${item.name}」")
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
            setPadding((16 * dm).toInt(), (10 * dm).toInt(), (16 * dm).toInt(), (6 * dm).toInt())
        }
        // 暗底自定义头部（透明位图组件在暗底上才看得清，不依赖系统 title 配色）
        box.addView(TextView(this).apply {
            text = "$name · 全尺寸预览"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((4 * dm).toInt(), (4 * dm).toInt(), (4 * dm).toInt(), (12 * dm).toInt())
        })
        val loading = TextView(this).apply {
            text = "正在渲染各尺寸预览…"
            setTextColor(0xFF9E9EA6.toInt())
            textSize = 13f
        }
        box.addView(loading)
        val scroll = ScrollView(this).apply { addView(box) }
        val dlg = AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        dlg.setOnDismissListener { box.removeAllViews() }
        dlg.show()
        // 暗色弹窗底 + 按钮文字反白
        dlg.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(0xFF17171C.toInt()))
        dlg.window?.setLayout(-1, -2) // MATCH_PARENT：消除主题左右边距，预览按容器实测宽排版
        for (bid in intArrayOf(android.R.id.button1, android.R.id.button2, android.R.id.button3)) {
            try { dlg.findViewById<Button>(bid)?.setTextColor(0xFFFFFFFF.toInt()) } catch (_: Throwable) {}
        }

        val sizes = supportedSizes(id)
        // 预览宽度 = 该尺寸规范宽 / 360dp × 可用宽（优先取滚动容器实测宽，避免估宽溢出被裁）
        val fallbackW = resources.displayMetrics.widthPixels - 64 * dm
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
                    val c = com.litewidget.app.core.render.Renderer.CANONICAL[s]
                    val wDp = c?.first ?: 360f
                    val hDp = c?.second ?: 180f
                    val avail = (if (scroll.width > 0)
                        (scroll.width - box.paddingLeft - box.paddingRight).toFloat()
                        else fallbackW)
                    val pw = avail * (wDp / 360f)
                    val ph = pw * (hDp / wDp)
                    val label = TextView(this).apply {
                        text = (SIZE_LABEL[s] ?: s) + "  " + wDp.toInt() + "×" + hDp.toInt() + "dp"
                        setTextColor(0xFF9E9EA6.toInt())
                        textSize = 12f
                        setPadding((4 * dm).toInt(), (8 * dm).toInt(), 0, (4 * dm).toInt())
                    }
                    val img = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_XY
                        layoutParams = LinearLayout.LayoutParams(pw.toInt(), ph.toInt()).apply {
                            gravity = android.view.Gravity.CENTER_HORIZONTAL
                            bottomMargin = (10 * dm).toInt()
                        }
                        if (bmp != null) setImageBitmap(bmp)
                        else setBackgroundColor(0xFF2A2A32.toInt())
                    }
                    box.addView(label)
                    box.addView(img)
                }
            }
        }
    }

    private fun intervalText(m: Int) = if (m <= 0) "关" else "${m}分"

    private fun showLogs() {
        val f = AppLog.todayFile()
        val content = AppLog.tail(20000)
        val count = content.count { it == '\n' }
        val tv = TextView(this).apply {
            setPadding(32, 24, 32, 24)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "文件: ${f?.name ?: "-"} · $count 行\n" +
                "目录: ${f?.parent ?: "-"}（一天一张，次日自动删昨天）\n" +
                "————————————————\n" + content
        }
        AlertDialog.Builder(this)
            .setTitle("今日日志（完整）")
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

    /**
     * 实验（负一屏）：给 provider 加了 miuiWidget=true 后，桌面会把组件从「安卓小部件」
     * 列表里剔除（BaseWidgetsVerticalAdapter 过滤 isMIUIWidget），所以选择器里找不到它。
     * 这里改走 MIUI 桌面的导出广播 com.miui.home.launcher.action.INSTALL_WIDGET 直装，
     * 该 receiver 是 exported + protectionLevel=normal 权限；没生效再退回系统 requestPinWidget。
     */
    private fun promptPinToDesktop() {
        val labels = arrayOf(
            "4x2 标准",
            "2x2 方形",
            "4x4 大方",
            "2x4 竖长",
            "4x1 横条（对照：预期弹「负一屏暂不支持该尺寸」）"
        )
        val classes = listOf(
            com.litewidget.app.widget.WidgetProvider4x2::class.java,
            com.litewidget.app.widget.WidgetProvider2x2::class.java,
            com.litewidget.app.widget.WidgetProvider4x4::class.java,
            com.litewidget.app.widget.WidgetProvider2x4::class.java,
            com.litewidget.app.widget.WidgetProvider4x1::class.java
        )
        AlertDialog.Builder(this)
            .setTitle("把哪个组件装到桌面")
            .setItems(labels) { _, i -> pinToDesktop(classes[i], labels[i]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pinToDesktop(cls: Class<*>, label: String) {
        val cn = android.content.ComponentName(this, cls)
        val mgr = android.appwidget.AppWidgetManager.getInstance(this)
        fun count(): Int = try {
            (mgr.getAppWidgetIds(cn) ?: IntArray(0)).size
        } catch (t: Throwable) {
            AppLog.e("pin[$label]: count fail", t)
            0
        }
        val before = count()
        try {
            sendBroadcast(Intent("com.miui.home.launcher.action.INSTALL_WIDGET").apply {
                setPackage("com.miui.home")
                putExtra("miui.intent.extra.provider_component_name", cn)
            })
            AppLog.i("pin[$label]: MIUI INSTALL_WIDGET broadcast sent, before=$before")
        } catch (t: Throwable) {
            AppLog.e("pin[$label]: broadcast fail", t)
        }
        toast("已请求安装 $label，1.5 秒后检查…")
        serverHandler.postDelayed({
            val now = count()
            when {
                now > before -> {
                    AppLog.i("pin[$label]: ok $before -> $now")
                    toast("$label 已在桌面（$before→$now），长按它拖向负一屏")
                }
                else -> {
                    AppLog.i("pin[$label]: broadcast no effect (count=$now)，退回 requestPin")
                    tryPin(cn, label)
                }
            }
        }, 1500)
    }

    private fun tryPin(cn: android.content.ComponentName, label: String) {
        try {
            val mgr = android.appwidget.AppWidgetManager.getInstance(this)
            if (mgr.isRequestPinAppWidgetSupported) {
                val accepted = mgr.requestPinAppWidget(cn, null, null)
                AppLog.i("pin[$label]: requestPinAppWidget accepted=$accepted")
                toast(
                    if (accepted) "广播没生效，已发起系统「固定到桌面」，确认弹窗即可"
                    else "广播没生效，系统 pin 也被拒绝（accepted=false）"
                )
            } else {
                AppLog.i("pin[$label]: pin not supported by launcher")
                toast("$label：广播和系统固定都不支持，需要换路子")
            }
        } catch (t: Throwable) {
            AppLog.e("pin[$label]: requestPin fail", t)
            toast("固定失败：${t.message}")
        }
    }

    /** 磨砂开关状态行：开关意愿 × 权限状态 */
    private fun updateFrostUi() {
        if (!::frostSwitch.isInitialized) return
        val on = App.instance.prefs.frostWanted
        frostSwitch.isChecked = on
        val granted = android.os.Build.VERSION.SDK_INT < 29 ||
            android.os.Environment.isExternalStorageManager()
        frostStatus.text = when {
            !on -> "已关闭 · 卡底为纯光洗"
            granted -> "已开启 · 壁纸模糊层生效中"
            else -> "已开启 · 缺「所有文件访问权限」，切一下开关跳转设置授权"
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (::serverSwitch.isInitialized && ::listBox.isInitialized) {
                updateServerUi()
                updateFrostUi()
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
    const val VERSION = "0.3.3"
}
