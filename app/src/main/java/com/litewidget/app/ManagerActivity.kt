package com.litewidget.app

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.litewidget.app.core.AppLog
import com.litewidget.app.widget.WidgetProvider2x2
import com.litewidget.app.widget.WidgetProvider2x4
import com.litewidget.app.widget.WidgetProvider4x1
import com.litewidget.app.widget.WidgetProvider4x2
import com.litewidget.app.widget.WidgetProvider4x4
import com.litewidget.app.widget.WidgetUpdater

/**
 * 桌面管理（KWGT 式管理器）：
 * 列出桌面上已添加的小组件实例 → 逐个指定显示哪个模板（binding）/ 恢复默认。
 */
class ManagerActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyTip: TextView

    private data class Entry(val appWidgetId: Int, val label: String, val wDp: Int, val hDp: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manager)
        container = findViewById(R.id.instance_list)
        emptyTip = findViewById(R.id.instance_empty)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        try {
            val mgr = AppWidgetManager.getInstance(this)
            val providers = listOf(
                WidgetProvider2x2::class.java to "2x2 方形",
                WidgetProvider4x1::class.java to "4x1 横条",
                WidgetProvider4x2::class.java to "4x2 标准",
                WidgetProvider2x4::class.java to "2x4 竖长",
                WidgetProvider4x4::class.java to "4x4 大方"
            )
            val entries = ArrayList<Entry>()
            for ((cls, label) in providers) {
                val ids = mgr.getAppWidgetIds(ComponentName(this, cls)) ?: IntArray(0)
                for (id in ids) {
                    val opts = mgr.getAppWidgetOptions(id)
                    val w = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
                    val h = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
                    entries.add(Entry(id, label, w, h))
                }
            }
            container.removeAllViews()
            emptyTip.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

            val store = App.instance.store
            val prefs = App.instance.prefs
            val inflater = LayoutInflater.from(this)
            for (e in entries) {
                val row = inflater.inflate(R.layout.item_instance, container, false)
                row.findViewById<TextView>(R.id.inst_title).text = "${e.label} · 实例 #${e.appWidgetId}"

                val bound = prefs.binding(e.appWidgetId).ifEmpty { prefs.activeWidget }
                val design = store.list().firstOrNull { it.id == bound }
                    ?.let { "${it.name}（${it.id}）" }
                    ?: bound.ifEmpty { "（未设置）" }
                val boundIv = prefs.instanceInterval(e.appWidgetId)
                    ?: if (store.exists(bound)) prefs.refreshInterval(bound) else 0
                row.findViewById<TextView>(R.id.inst_meta).text =
                    "${e.wDp}×${e.hDp}dp · 模板: $design · 刷新: ${fmtInterval(boundIv)}"

                row.findViewById<Button>(R.id.inst_pick).setOnClickListener { pickTemplate(e) }
                row.findViewById<Button>(R.id.inst_freq).setOnClickListener { promptInstanceInterval(e) }
                row.findViewById<Button>(R.id.inst_reset).setOnClickListener {
                    prefs.clearBinding(e.appWidgetId)
                    WidgetUpdater.pushAll(this, null, refreshData = false)
                    toast("实例 #${e.appWidgetId} 已恢复默认模板")
                    refresh()
                }
                container.addView(row)
            }
        } catch (t: Throwable) {
            AppLog.e("manager refresh fail", t)
            toast("读取桌面组件失败: ${t.message}")
        }
    }

    private fun pickTemplate(e: Entry) {
        val prefs = App.instance.prefs
        val items = App.instance.store.list()
        if (items.isEmpty()) {
            toast("还没有组件：先在首页新建或导入")
            return
        }
        val current = prefs.binding(e.appWidgetId).ifEmpty { prefs.activeWidget }
        // 显示与实例对应的尺寸（实例是 4x1 就标 4x1），并注明模板是否有该尺寸专属变体
        val instSize = com.litewidget.app.core.render.Renderer.classify(
            e.wDp.toFloat().coerceAtLeast(1f), e.hDp.toFloat().coerceAtLeast(1f)
        )
        val names = items.map {
            val mark = if (it.id == current) "（当前）" else ""
            val has = try {
                App.instance.store.readSpec(it.id)?.optJSONObject("variants")?.has(instSize) == true
            } catch (_: Throwable) { false }
            val sizePart = if (has) instSize else "$instSize · 无专属变体按默认适配"
            "${it.name} · $sizePart$mark"
        }.toTypedArray()
        val checked = items.indexOfFirst { it.id == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("为 ${e.label} 选择模板")
            .setSingleChoiceItems(names, checked) { d, which ->
                prefs.setBinding(e.appWidgetId, items[which].id)
                prefs.setLastRender(items[which].id, 0L)
                WidgetUpdater.pushAll(this, null, refreshData = false)
                toast("实例 #${e.appWidgetId} → ${items[which].name}")
                d.dismiss()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun intervalText(m: Int) = if (m <= 0) "关" else "${m}分"

    private fun fmtInterval(m: Int) = when {
        m <= 0 -> "关"
        m % 1440 == 0 -> "${m / 1440} 天"
        m % 60 == 0 -> "${m / 60} 小时"
        else -> "${m} 分钟"
    }

    /** 桌面实例的自动刷新：数值 + 单位（分钟/小时/天）自选，像闹钟设置那样 */
    private fun promptInstanceInterval(e: Entry) {
        val prefs = App.instance.prefs
        val bound = prefs.binding(e.appWidgetId).ifEmpty { prefs.activeWidget }
        val cur = prefs.instanceInterval(e.appWidgetId) ?: prefs.refreshInterval(bound)
        val dm = resources.displayMetrics.density
        val pad = (24 * dm).toInt()
        val num = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "输入数值"
            setText(when {
                cur <= 0 -> ""
                cur % 1440 == 0 -> (cur / 1440).toString()
                cur % 60 == 0 -> (cur / 60).toString()
                else -> cur.toString()
            })
        }
        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            setPadding(0, (12 * dm).toInt(), 0, 0)
        }
        val rMin = android.widget.RadioButton(this).apply { text = "分钟"; id = View.generateViewId() }
        val rHour = android.widget.RadioButton(this).apply { text = "小时"; id = View.generateViewId() }
        val rDay = android.widget.RadioButton(this).apply { text = "天"; id = View.generateViewId() }
        group.addView(rMin); group.addView(rHour); group.addView(rDay)
        when {
            cur > 0 && cur % 1440 == 0 -> group.check(rDay.id)
            cur > 0 && cur % 60 == 0 -> group.check(rHour.id)
            else -> group.check(rMin.id)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dm).toInt(), pad, 0)
            addView(num, LinearLayout.LayoutParams(-1, -2))
            addView(group)
        }
        AlertDialog.Builder(this)
            .setTitle("实例 #${e.appWidgetId} · 自动刷新")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val n = num.text.toString().toLongOrNull() ?: 0L
                val mult = when (group.checkedRadioButtonId) { rHour.id -> 60L; rDay.id -> 1440L; else -> 1L }
                val minutes = (n * mult).coerceIn(0L, 525600L).toInt()
                prefs.setInstanceInterval(e.appWidgetId, minutes)
                prefs.setInstLastRender(e.appWidgetId, 0L)
                toast(if (minutes <= 0) "已关闭自动刷新" else "实例 #${e.appWidgetId}：每 ${fmtInterval(minutes)} 刷新")
                refresh()
            }
            .setNeutralButton("不自动刷新") { _, _ ->
                prefs.setInstanceInterval(e.appWidgetId, 0)
                prefs.setInstLastRender(e.appWidgetId, 0L)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
