package com.litewidget.app.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.litewidget.app.App
import com.litewidget.app.MainActivity
import com.litewidget.app.R
import com.litewidget.app.core.AppLog

/**
 * 前台服务承载 MCP 端口，保证 App 退到后台时局域网 AI 仍能连上。
 */
class McpService : Service() {

    private var server: McpServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = App.instance.prefs
        val token = prefs.ensureToken()
        val port = prefs.port

        startForeground(NOTIF_ID, buildNotification(port))

        if (server == null) server = McpServer(App.instance)
        val ok = server!!.start(port, token)
        prefs.serverRunning = ok
        AppLog.i("McpService started ok=$ok port=$port")
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        App.instance.prefs.serverRunning = false
        instance = null
        AppLog.i("McpService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(port: Int): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "MCP 服务", NotificationManager.IMPORTANCE_LOW)
            ch.description = "局域网 MCP 端口常驻"
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MCP 服务运行中")
            .setContentText("端口 $port · 局域网可连接")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "mcp_server"
        private const val NOTIF_ID = 4211

        @Volatile
        private var instance: McpService? = null

        fun isRunning(): Boolean = instance?.server?.running == true
        fun port(): Int = instance?.server?.boundPort ?: -1
        fun error(): String? = instance?.server?.lastError

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, McpService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, McpService::class.java))
        }
    }
}
