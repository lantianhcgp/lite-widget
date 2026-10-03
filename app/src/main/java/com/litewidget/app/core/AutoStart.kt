package com.litewidget.app.core

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.litewidget.app.core.AppLog

/**
 * MIUI/HyperOS「自启动」权限：组件自动唤起机制的前提——
 * 进程被划掉后，alarm 广播能否拉起 App 全看它。
 *
 * 检测走 AppOps 字符串 "android:auto_start"（MIUI 私有 op，非 MIUI 一律返回 null = 无法检测）。
 * 引导跳转用 MIUI 安全中心 intent，失败回退系统应用详情页。
 */
object AutoStart {

    private const val TAG = "AutoStart"

    /** true = 已授权；false = 明确未授权；null = 检测不了（非 MIUI / 低版本 / 接口变了） */
    fun status(ctx: Context): Boolean? {
        if (!isMiui()) return null
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            when (aom.checkOpNoThrow("android:auto_start", Process.myUid(), ctx.packageName)) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_IGNORED -> false
                else -> null
            }
        } catch (t: Throwable) {
            AppLog.w("$TAG status fail: ${t.message}")
            null
        }
    }

    /** 需要弹引导？= 明确未授权（检测不了就不打扰用户） */
    fun needPrompt(ctx: Context): Boolean = status(ctx) == false

    fun label(s: Boolean?): String = when (s) {
        true -> "granted"
        false -> "NOT-granted"
        null -> "unknown"
    }

    /** 打开自启动管理页；MIUI 专用 intent 失败则回退系统应用详情 */
    fun openSettings(ctx: Context) {
        val pkg = ctx.packageName
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", pkg)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.app.AppPermissionsEditorActivity"
                )
                putExtra("extra_pkgname", pkg)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        )
        for (i in candidates.indices) {
            try {
                ctx.startActivity(candidates[i].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                AppLog.i("$TAG settings opened (candidate $i)")
                return
            } catch (t: Throwable) {
                AppLog.w("$TAG candidate $i fail: ${t.message}")
            }
        }
    }

    private fun isMiui(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", true) ||
            Build.MANUFACTURER.equals("Redmi", true) ||
            Build.DISPLAY.contains("miui", true)
}
