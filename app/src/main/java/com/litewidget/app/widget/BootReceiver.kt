package com.litewidget.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.litewidget.app.core.AppLog

/**
 * 开机/解锁后重排唤醒闹钟——AlarmManager 的闹钟重启即失效，
 * 没有这个 receiver，重启一次手机组件就不自动刷新了。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED -> {
                AppLog.i("boot completed, reschedule alarm")
                AlarmScheduler.reschedule(ctx)
            }
        }
    }
}
