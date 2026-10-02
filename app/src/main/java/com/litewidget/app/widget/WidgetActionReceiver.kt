package com.litewidget.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.litewidget.app.core.AppLog

/** 手动刷新入口（App 内按钮 / 通知栏 / 后续快捷方式） */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.i("WidgetActionReceiver ${intent.action}")
        if (intent.action == WidgetProvider.ACTION_REFRESH) {
            WidgetUpdater.pushAll(context)
        }
    }
}
