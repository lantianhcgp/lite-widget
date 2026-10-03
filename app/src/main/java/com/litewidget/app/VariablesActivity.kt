package com.litewidget.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.litewidget.app.widget.WidgetUpdater
import java.util.concurrent.Executors

/**
 * 变量管理：
 * 扫描所有组件 widget.json 的 data.vars 声明 → 自动生成表单 → 用户填值保存。
 * 值只存本地 SharedPreferences（var_<name>），不进组件包、不通过 MCP 暴露。
 */
class VariablesActivity : Activity() {

    private val ui = Executors.newSingleThreadExecutor { r -> Thread(r, "vars-work") }
    private val fields = ArrayList<Triple<String, Boolean, EditText>>() // name, required, input
    private lateinit var box: LinearLayout
    private lateinit var status: TextView

    private data class Decl(
        val name: String, val label: String, val type: String,
        val required: Boolean, val secret: Boolean, val hint: String, val from: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0B0C10.toInt()
        window.navigationBarColor = 0xFF0B0C10.toInt()
        setContentView(R.layout.activity_vars)
        box = findViewById(R.id.vars_box)
        status = findViewById(R.id.vars_status)
        findViewById<Button>(R.id.btn_vars_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_vars_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_vars_refresh).setOnClickListener {
            save()
            refreshData()
        }
        build()
    }

    private fun build() {
        fields.clear()
        box.removeAllViews()
        val prefs = App.instance.prefs
        val seen = LinkedHashMap<String, Decl>()
        val fromMap = LinkedHashMap<String, MutableList<String>>() // 变量名 -> 所有声明它的组件
        for (item in App.instance.store.list()) {
            val spec = App.instance.store.readSpec(item.id) ?: continue
            val vars = spec.optJSONObject("data")?.optJSONObject("vars") ?: continue
            for (k in vars.keys()) {
                fromMap.getOrPut(k) { ArrayList() }.add(item.name)
                if (seen.containsKey(k)) continue
                val v = vars.optJSONObject(k) ?: continue
                seen[k] = Decl(
                    k,
                    v.optString("label", k),
                    v.optString("type", "string"),
                    v.optBoolean("required", false),
                    v.optBoolean("secret", false),
                    v.optString("hint", ""),
                    item.name
                )
            }
        }
        if (seen.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "当前没有组件声明变量。\n组件在 widget.json 的 data.vars 里声明需要的输入" +
                        "（例如后台地址、充值号），声明后这里会自动出现对应表单。"
                setTextColor(0xFF9E9EA6.toInt())
                textSize = 13f
            })
            return
        }
        val dm = resources.displayMetrics.density
        fun space(dp: Int) {
            box.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(1, (dp * dm).toInt())
            })
        }
        for (d in seen.values) {
            val label = TextView(this).apply {
                text = d.label + if (d.required) "（必填）" else ""
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
            }
            space(5)
            val input = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                hint = d.hint.ifEmpty { d.name }
                setText(prefs.varValue(d.name))
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF6A6A72.toInt())
                background = getDrawable(R.drawable.bg_input)
                textSize = 14f
                val pad = (10 * dm).toInt()
                setPadding(pad, pad, pad, pad)
                when {
                    d.secret || d.type == "password" ->
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    d.type == "number" -> inputType = InputType.TYPE_CLASS_NUMBER
                    d.type == "url" ->
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
            }
            space(3)
            val froms = (fromMap[d.name] ?: arrayListOf(d.from)).distinct()
            val declText = if (froms.size > 1)
                froms.joinToString("、") { "「$it」" } + " 共同声明"
                else "「${froms.first()}」声明"
            val meta = TextView(this).apply {
                text = "由 $declText · var_${d.name}" + if (d.secret) " · 仅存本机" else ""
                setTextColor(0xFF6A6A72.toInt())
                textSize = 11f
            }
            box.addView(label)
            box.addView(input)
            box.addView(meta)
            space(14)
            fields.add(Triple(d.name, d.required, input))
        }
    }

    private fun save() {
        val prefs = App.instance.prefs
        var missing = 0
        for ((name, required, input) in fields) {
            val v = input.text.toString()
            prefs.setVarValue(name, v)
            if (required && v.trim().isEmpty()) missing++
        }
        if (fields.isEmpty()) toast("没有可保存的变量")
        else if (missing == 0) toast("变量已保存")
        else toast("已保存，还有 $missing 个必填项为空")
    }

    private fun refreshData() {
        status.text = "正在拉取后台数据…"
        ui.execute {
            val msg = try {
                val v = App.instance.data.refresh()
                "已刷新：${v["package.name"]} · 已用 ${v["flow.used"]} MB · 电量 ${v["device.battery"]}%"
            } catch (t: Throwable) {
                "拉取失败：${t.message}"
            }
            runOnUiThread {
                status.text = msg
                WidgetUpdater.pushAll(this, null, refreshData = false)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        ui.shutdownNow()
        super.onDestroy()
    }
}
