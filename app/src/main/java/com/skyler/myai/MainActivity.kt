package com.skyler.myai

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AudioRecordApp()
                }
            }
        }
    }

    /**
     * 主应用组件 - 连接 UI 和业务逻辑
     */
    @Composable
    fun AudioRecordApp() {
        // 权限管理
        val recordAudioPermissionState = rememberPermissionState(
            permission = Manifest.permission.RECORD_AUDIO
        )
        var enabled by remember { mutableStateOf(SmartSpeechService.isEnabled) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = if (enabled) "智能语音识别：已开启（后台会自动监听并识别语音）"
                else "智能语音识别：未开启\n点击下方按钮开启，允许录音权限后会在后台持续监听。",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    val hasRecordPermission = recordAudioPermissionState.status is PermissionStatus.Granted
                    if (!hasRecordPermission) {
                        recordAudioPermissionState.launchPermissionRequest()
                        return@Button
                    }

                    enabled = !enabled
                    val action = if (enabled) {
                        SmartSpeechService.ACTION_START
                    } else {
                        SmartSpeechService.ACTION_STOP
                    }
                    startService(Intent(this@MainActivity, SmartSpeechService::class.java).apply {
                        this.action = action
                    })
                },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(if (enabled) "关闭智能语音识别" else "开启智能语音识别")
            }
        }
    }
}