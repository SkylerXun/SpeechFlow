package com.skyler.myai

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.Timer
import java.util.TimerTask

/**
 * 监听前后台：后台超过 5 秒才把智能语音切到前台服务（保活 + 合规）。
 */
class SmartSpeechApp : Application() {
    private var backgroundTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // 进入后台：延迟 5 秒再升前台（如果功能仍开启）
                backgroundTimer?.cancel()
                backgroundTimer = Timer("smart-speech-bg", true).apply {
                    schedule(object : TimerTask() {
                        override fun run() {
                            if (SmartSpeechService.isEnabled) {
                                startService(Intent(this@SmartSpeechApp, SmartSpeechService::class.java).apply {
                                    action = SmartSpeechService.ACTION_ENSURE_FOREGROUND
                                })
                            }
                        }
                    }, 5_000)
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                // 回到前台：取消升前台的延迟；如果服务在前台，可降级（仍可继续采集）
                backgroundTimer?.cancel()
                backgroundTimer = null

                startService(Intent(this@SmartSpeechApp, SmartSpeechService::class.java).apply {
                    action = SmartSpeechService.ACTION_MAYBE_STOP_FOREGROUND
                })
            }
        })
    }
}

