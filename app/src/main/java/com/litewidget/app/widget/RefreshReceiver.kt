package com.litewidget.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.litewidget.app.core.AppLog
import kotlin.concurrent.thread

/**
 * AlarmManager 兜底唤醒：进程被杀后仍能按各组件的刷新频率到点重绘。
 * 具体“到没到点”由 WidgetUpdater.tickNow 判断，没到点直接返回（零开销）。
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
                pending.finish()
            }
        }
    }
}
