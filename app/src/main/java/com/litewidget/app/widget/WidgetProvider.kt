package com.litewidget.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.litewidget.app.core.AppLog

/** 桌面小组件：系统定时/添加时回调，统一交给 WidgetUpdater 渲染推送。 */
open class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        AppLog.i("WidgetProvider.onUpdate n=${appWidgetIds.size}")
        WidgetUpdater.pushAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            AppLog.i("WidgetProvider refresh requested")
            WidgetUpdater.pushAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.litewidget.app.widget.REFRESH"
    }
}
