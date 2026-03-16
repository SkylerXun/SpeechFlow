package com.skyler.myai

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 智能语音系统控制器（按钮开/关）
 *
 * 规则（按你确认的策略）：
 * - 常驻监听（VAD）优先级最低=1，默认 8kHz + VOICE_RECOGNITION
 * - VAD 检测到人声：升级为“识别”优先级=10，切 16kHz + VOICE_RECOGNITION，收集音频给 Whisper
 * - 识别结束：若持续静音，降级回 8kHz 监听
 * - 通话/语音消息等业务未来用更高优先级（100/80）抢占麦克风；释放后自动恢复识别链路
 */
class SmartSpeechController(
    context: Context,
    private val whisperEngine: WhisperInferenceEngine,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val mic = MicrophoneManager.getInstance(appContext)

    private val vadProcessor = VADProcessor(
        onSpeechStart = {
            // 任意新语音开始：进入/保持 16k 识别态，并取消降级计划
            cancelDowngradeTimer()
            upgradeToRecognition()
        },
        onSpeechEnd = { speechPcm ->
            // 停顿 2000ms 触发结束（由 VADProcessor 负责）
            handleSpeechSegment(speechPcm)
            scheduleDowngradeTo8k()
        },
        onVadError = { /* TODO: report */ }
    )

    @Volatile private var enabled = false
    private var downgradeJob: Job? = null

    fun setEnabled(enable: Boolean) {
        if (enable == enabled) return
        enabled = enable
        if (enable) {
            startVadListen()
        } else {
            cancelDowngradeTimer()
            mic.releaseMic(TAG_VAD)
            mic.releaseMic(TAG_RECOG)
        }
    }

    private fun startVadListen() {
        mic.requestMic(
            priority = 1,
            requiredSampleRate = 8000,
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            callback = object : MicCallback {
                override fun onMicGranted() {}

                override fun onAudioData(data: ByteArray, sampleRate: Int) {
                    // VAD 统一用 8k 判断；若因为抢占恢复导致暂时是 16k，这里内部会降采样处理
                    vadProcessor.processAudioFrame(data, sampleRate)
                }

                override fun onMicReleased(reason: String) {}
                override fun onError(error: Exception) {}
            },
            tag = TAG_VAD
        )
    }

    private fun upgradeToRecognition() {
        if (!enabled) return

        // 识别阶段：更高优先级抢占；用于给 Whisper 收集 16k PCM
        mic.requestMic(
            priority = 10,
            requiredSampleRate = 16000,
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            callback = object : MicCallback {
                private val chunks = ArrayList<ByteArray>()

                override fun onMicGranted() {}

                override fun onAudioData(data: ByteArray, sampleRate: Int) {
                    // 这里收集的是 16k PCM16LE mono（AudioRecord）
                    chunks.add(data)

                    // 同时继续跑 VAD（用于检测停顿/结束）
                    vadProcessor.processAudioFrame(data, sampleRate)
                }

                override fun onMicReleased(reason: String) {
                    // 释放时把本段拼起来（占位；后续我们做“段结束”触发点更精确）
                    val pcm = concatenateByteArrays(chunks)
                    chunks.clear()
                    handleSpeechSegment(pcm)
                }

                override fun onError(error: Exception) {}
            },
            tag = TAG_RECOG
        )
    }

    private fun handleSpeechSegment(pcm16kOrUnknown: ByteArray) {
        if (pcm16kOrUnknown.isEmpty()) return

        // 你要求“既要 ByteArray 也要落盘文件”
        val pcmFile = createPcmFile()
        pcmFile.writeBytes(pcm16kOrUnknown)

        // Whisper 推理（占位实现可以先返回空字符串；你后续替换 Whisper 即可）
        scope.launch(Dispatchers.Default) {
            whisperEngine.transcribe(pcm16kOrUnknown)
        }

        // 是否立刻释放识别占用交由 scheduleDowngradeTo8k 控制：
        // 先保持 16k 一段时间，如果 1000ms 内持续静音则自动降回 8k。
    }

    private fun scheduleDowngradeTo8k() {
        cancelDowngradeTimer()
        downgradeJob = scope.launch(Dispatchers.Default) {
            delay(1_000) // 1000ms 静音窗口
            if (!enabled) return@launch
            // 降级：释放识别 MicRequest，让 VAD(8k) 重新成为最高可用请求
            mic.releaseMic(TAG_RECOG)
        }
    }

    private fun cancelDowngradeTimer() {
        downgradeJob?.cancel()
        downgradeJob = null
    }

    private fun createPcmFile(): File {
        val timeStamp =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ASR_$timeStamp.pcm"
        val dir = appContext.getExternalFilesDir("Music") ?: appContext.filesDir
        dir.mkdirs()
        return File(dir, fileName)
    }

    companion object {
        private const val TAG_VAD = "smart_speech_vad"
        private const val TAG_RECOG = "smart_speech_recognition"
    }
}

