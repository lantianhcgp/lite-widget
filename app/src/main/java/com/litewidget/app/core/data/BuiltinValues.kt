package com.litewidget.app.core.data

import android.content.res.Configuration
import android.os.SystemClock
import android.os.StatFs
import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import java.util.Locale
import java.util.TimeZone

/**
 * 内置状态量（App 状态层，不依赖数据源、不申请任何权限）：
 * 组件用 bind.field = "sys.xxx" 直接调用；布局/展示方式完全由组件 spec 决定。
 *
 * 不做设置界面——对外声明只有两处（Agent 靠这两处自发现）：
 *   1. assets/schema.json 的 bind.field $comment —— MCP schema_get 原样暴露
 *   2. skill lite-widget-dev「内置状态变量」节
 *
 * 字段（全部免授权）：
 *   sys.refreshTime   本次刷新（渲染）时刻 epoch 毫秒 —— 配 format.date 展示
 *   sys.timezone      时区 id，如 Asia/Shanghai
 *   sys.uptimeMin     开机时长（分钟）
 *   sys.battery       电量 0-100（配 format.suffix "%"）
 *   sys.charging      是否在充电 true/false
 *   sys.network       网络状态 wifi / mobile / vpn / none / other
 *   sys.memFreeMB     可用内存（MB）
 *   sys.memTotalMB    总内存（MB）
 *   sys.storageFreeMB 沙箱所在磁盘可用（MB）
 *   sys.nightMode     系统深色模式 true/false
 *   sys.sdk           Android API 级别（如 33）
 *   sys.locale        系统语言区域，如 zh-CN
 *
 * 权限类状态量（定位/通讯录/短信等）明确不做，需要时另行单独设计。
 */
object BuiltinValues {

    fun resolve(field: String): Any? = when (field) {
        "sys.refreshTime" -> System.currentTimeMillis()
        "sys.timezone" -> TimeZone.getDefault().id
        "sys.uptimeMin" -> SystemClock.elapsedRealtime() / 60_000L
        "sys.battery" -> batteryLevel()
        "sys.charging" -> isCharging()
        "sys.network" -> network()
        "sys.memFreeMB" -> memInfo()?.first
        "sys.memTotalMB" -> memInfo()?.second
        "sys.storageFreeMB" -> storageFreeMB()
        "sys.nightMode" -> nightMode()
        "sys.sdk" -> android.os.Build.VERSION.SDK_INT
        "sys.locale" -> Locale.getDefault().toLanguageTag()
        else -> null
    }

    private fun batteryManager(): BatteryManager? = try {
        com.litewidget.app.App.instance
            .getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
    } catch (t: Throwable) {
        null
    }

    private fun batteryLevel(): Int? {
        val am = batteryManager() ?: return null
        val level = am.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 1..100) level else null
    }

    private fun isCharging(): Boolean? = try {
        batteryManager()?.isCharging
    } catch (t: Throwable) {
        null
    }

    /** VPN 优先判定（VPN 激活时底层 wifi/cellular 也会置位，报 vpn 更有信息量） */
    private fun network(): String = try {
        val cm = com.litewidget.app.App.instance
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) {
            "none"
        } else when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "other"
            else -> "other"
        }
    } catch (t: Throwable) {
        "none"
    }

    /** Pair(可用MB, 总MB) */
    private fun memInfo(): Pair<Long, Long>? = try {
        val am = com.litewidget.app.App.instance
            .getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        (info.availMem / 1_048_576L) to (info.totalMem / 1_048_576L)
    } catch (t: Throwable) {
        null
    }

    private fun storageFreeMB(): Long? = try {
        val s = StatFs(com.litewidget.app.App.instance.filesDir.path)
        (s.availableBlocksLong * s.blockSizeLong) / 1_048_576L
    } catch (t: Throwable) {
        null
    }

    private fun nightMode(): Boolean = try {
        val ui = com.litewidget.app.App.instance.resources.configuration.uiMode
        (ui and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    } catch (t: Throwable) {
        false
    }
}
