package com.litewidget.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.litewidget.app.core.AppLog

/**
 * 桌面小组件：系统定时/添加时回调，统一交给 WidgetUpdater 渲染推送。
 *
 * MIUI 小部件（provider 声明 miuiWidget=true）的两条特殊通路（真机 ROM 源码实锤）：
 * - 系统侧 AppWidgetServiceImpl 分支 `isForMiui()==true` 时**不排原生 updatePeriodMillis
 *   周期更新** → 声明小米标识后 30 分钟兜底回调消失，必须有自己的刷新来源；
 * - 曝光刷新：宿主（桌面 LauncherWidgetView / 负一屏 PA WidgetHostView）在组件露出时发
 *   显式广播 `miui.appwidget.action.APPWIDGET_UPDATE`（extra "appWidgetIds"），前置条件 =
 *   meta miuiWidgetRefresh=exposure 且 miuiWidgetRefreshMinInterval>0（单位 ms，下限 10000）。
 *   该 action 不是系统标准 action，AppWidgetProvider.onReceive 不认 → 必须在 onReceive 自接。
 */
open class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        AppLog.i("WidgetProvider.onUpdate n=${appWidgetIds.size}")
        // 新实例落桌后立刻把刷新闹钟排上（否则要等下次开 App 才会调度）
        AlarmScheduler.reschedule(context)
        WidgetUpdater.pushAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                AppLog.i("WidgetProvider refresh requested")
                WidgetUpdater.pushAll(context)
            }
            ACTION_MIUI_UPDATE -> {
                val ids = intent.getIntArrayExtra(EXTRA_APP_WIDGET_IDS)?.joinToString(",")
                AppLog.i("WidgetProvider miui exposure update ids=$ids")
                AlarmScheduler.reschedule(context)
                WidgetUpdater.pushAll(context)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.litewidget.app.widget.REFRESH"
        const val ACTION_MIUI_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"
        const val EXTRA_APP_WIDGET_IDS = "appWidgetIds"
    }
}
