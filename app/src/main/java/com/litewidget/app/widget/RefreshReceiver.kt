package com.litewidget.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.litewidget.app.core.AppLog
import kotlin.concurrent.thread

/**
 * AlarmManager 唤醒入口：进程被杀后由 AlarmScheduler 排的精确闹钟拉起。
 * 具体“到没到点”由 WidgetUpdater.tickNow 判断；跑完必须 reschedule 续上下一次（闹钟链自续）。
 */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "widget-alarm", isDaemon = true) {
            try {
                WidgetUpdater.tickNow(ctx)
            } catch (t: Throwable) {
                AppLog.w("alarm tick fail: ${t.message}")
            } finally {
                AlarmScheduler.reschedule(ctx)
                pending.finish()
            }
        }
    }
}
