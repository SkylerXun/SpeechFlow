package com.skyler.myai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 智能语音后台服务：
 * - 功能开启时常驻（可不在前台）
 * - App 进入后台超过 5 秒后，升为前台服务（通知栏常驻），避免系统限制/杀死
 */
class SmartSpeechService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var controller: SmartSpeechController

    override fun onCreate() {
        super.onCreate()
        val whisperEngine = WhisperInferenceEngine(applicationContext) { /* TODO: deliver result */ }
        controller = SmartSpeechController(applicationContext, whisperEngine, scope)
        controller.setEnabled(isEnabled)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isEnabled = true
                controller.setEnabled(true)
            }
            ACTION_STOP -> {
                isEnabled = false
                controller.setEnabled(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_ENSURE_FOREGROUND -> {
                if (isEnabled) {
                    startForeground(NOTIF_ID, buildNotification())
                }
            }
            ACTION_MAYBE_STOP_FOREGROUND -> {
                // 回到前台后可取消常驻通知（仍保持服务/采集逻辑）
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller.setEnabled(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("智能语音识别运行中")
            .setContentText("后台监听：检测到语音将自动识别")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "智能语音识别",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val ACTION_START = "com.skyler.myai.action.SMART_SPEECH_START"
        const val ACTION_STOP = "com.skyler.myai.action.SMART_SPEECH_STOP"
        const val ACTION_ENSURE_FOREGROUND = "com.skyler.myai.action.SMART_SPEECH_ENSURE_FG"
        const val ACTION_MAYBE_STOP_FOREGROUND = "com.skyler.myai.action.SMART_SPEECH_STOP_FG"

        private const val CHANNEL_ID = "smart_speech"
        private const val NOTIF_ID = 10001

        @Volatile
        var isEnabled: Boolean = false
    }
}

