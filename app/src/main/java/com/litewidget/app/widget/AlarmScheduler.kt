package com.litewidget.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.litewidget.app.core.AppLog
import com.litewidget.app.core.AutoStart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 自动唤起（建立在「自启动权限」基础上）：
 * - 唤醒路径 = AlarmManager 精确闹钟 → RefreshReceiver 冷启动进程 → tickNow → 重排下一次。
 *   进程被划掉/被杀后能不能被拉起，取决于 MIUI 自启动权限（AutoStart 检测 + App 内引导）。
 * - 触发时刻 = 所有桌面实例里最早到点的那个（nextDueAt），不是固定每分钟空转。
 * - 权限链：SCHEDULE_EXACT_ALARM（manifest，Android 13 默认授予）→ 无权限时降级
 *   setAndAllowWhileIdle（Doze 下仍能唤醒，精度略降）→ 再不行记日志放弃，不崩溃。
 * - Handler 45s 轮询仍是进程存活时的主力，本调度器只管「进程死后谁来拉一把」。
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private const val REQ_CODE = 701
    private const val MIN_LEAD_MS = 60_000L // 最近触发不早于 1 分钟后，防止坏数据导致空转自旋

    /** 重排下一次唤醒闹钟。任何入口（App 启动 / 闹钟触发 / 开机）都调它，幂等覆盖。 */
    fun reschedule(ctx: Context) {
        try {
            val now = System.currentTimeMillis()
            val due = WidgetUpdater.nextDueAt(ctx, now)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, REQ_CODE,
                Intent(ctx, RefreshReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            if (due == null) {
                am.cancel(pi)
                AppLog.i("$TAG no auto-refresh instance, alarm off")
                return
            }
            val at = max(due, now + MIN_LEAD_MS)
            val autoStart = AutoStart.status(ctx)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                // Android 14+ / 用户关了精确闹钟权限 → 降级：仍可唤醒，精度由系统批处理
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                AppLog.w("$TAG exact alarm denied, fallback setAndAllowWhileIdle")
            }
            AppLog.i(
                "$TAG next wake ${fmt(at)} (+${(at - now) / 1000}s)" +
                    ", autoStart=${AutoStart.label(autoStart)}"
            )
        } catch (t: Throwable) {
            AppLog.w("$TAG reschedule fail: ${t.message}")
        }
    }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
