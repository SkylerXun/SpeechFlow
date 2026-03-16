package com.skyler.myai

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

// VAD处理器 - 运行在后台线程
class VADProcessor(
    private val onSpeechStart: () -> Unit,
    private val onSpeechEnd: (ByteArray) -> Unit,  // 返回语音段数据
    private val onVadError: (Exception) -> Unit
) {
    // WebRTC VAD实例（android-vad:webrtc）
    private var vad: VadWebRTC? = null

    // 语音数据累积缓冲区
    private val speechBuffer = mutableListOf<ByteArray>()

    // 状态机
    private var isSpeaking = false
    private var silenceFrames = 0

    // 配置参数（WebRTC VAD 支持 10/20/30ms）
    private val frameMs = 20
    private val preSpeechPadFrames = 2  // 语音开始前保留的帧数（后续可接 circular buffer）
    // 你要求：停顿 2000ms 结束识别；20ms/帧 => 100 帧
    private val postSpeechPadFrames = 100

    init {
        initVad()
    }

    private fun initVad() {
        // 初始化 WebRTC VAD：8kHz + 20ms 帧（160 samples） + VERY_AGGRESSIVE
        vad = VadWebRTC(
            SampleRate.SAMPLE_RATE_8K,
            FrameSize.FRAME_SIZE_160,
            Mode.VERY_AGGRESSIVE,
            0, // speechDurationMs: 我们用自己的状态机控制
            0  // silenceDurationMs: 我们用自己的状态机控制
        )
    }

    // 处理音频帧（来自麦克风管理器）
    fun processAudioFrame(data: ByteArray, sampleRate: Int) {
        try {
            // VAD 按固定帧长工作：这里把输入 chunk 切成多帧处理
            val bytesPerSample = 2 // PCM16
            val frameSamples = (sampleRate * frameMs) / 1000
            val frameBytes = frameSamples * bytesPerSample

            var offset = 0
            while (offset + frameBytes <= data.size) {
                val frame = data.copyOfRange(offset, offset + frameBytes)
                offset += frameBytes

                // VAD 统一用 8kHz + 20ms(160) shorts
                val vadShorts: ShortArray = when (sampleRate) {
                    8000 -> pcm16leToShortArray(frame) // 20ms@8k => 160 shorts
                    16000 -> downsample16kPcm16leFrameTo8kShorts(frame) // 20ms@16k => 320 shorts -> 160 shorts
                    else -> {
                        // 不支持的采样率：先跳过（后续可加重采样）
                        return
                    }
                }

                val isSpeech = vad?.isSpeech(vadShorts) ?: false
                handleVadResult(isSpeech, frame)
            }
        } catch (e: Exception) {
            onVadError(e)
        }
    }

    private fun handleVadResult(isSpeech: Boolean, originalData: ByteArray) {
        if (isSpeech) {
            // 检测到语音
            silenceFrames = 0

            if (!isSpeaking) {
                // 语音开始，保留前几帧作为上下文
                isSpeaking = true
                onSpeechStart()
            }

            // 累积语音数据
            speechBuffer.add(originalData)
        } else {
            if (isSpeaking) {
                // 语音中遇到静音
                silenceFrames++

                if (silenceFrames >= postSpeechPadFrames) {
                    // 语音结束，返回完整语音段
                    val completeSpeech = concatenateByteArrays(speechBuffer)
                    onSpeechEnd(completeSpeech)

                    // 重置状态
                    isSpeaking = false
                    speechBuffer.clear()
                    silenceFrames = 0
                } else {
                    // 短时停顿，继续累积
                    speechBuffer.add(originalData)
                }
            }
        }
    }

    private fun pcm16leToShortArray(pcm16le: ByteArray): ShortArray {
        val samples = pcm16le.size / 2
        val out = ShortArray(samples)
        var j = 0
        var i = 0
        while (i + 1 < pcm16le.size) {
            val lo = pcm16le[i].toInt() and 0xFF
            val hi = pcm16le[i + 1].toInt()
            out[j] = ((hi shl 8) or lo).toShort()
            j++
            i += 2
        }
        return out
    }

    /**
     * 16kHz 20ms frame (320 samples) -> 8kHz 20ms shorts (160 samples)
     * 直接抽取偶数样本，不做低通滤波。
     */
    private fun downsample16kPcm16leFrameTo8kShorts(pcm16le: ByteArray): ShortArray {
        val inShorts = pcm16leToShortArray(pcm16le) // 320
        val out = ShortArray(inShorts.size / 2) // 160
        var oi = 0
        var ii = 0
        while (ii < inShorts.size && oi < out.size) {
            out[oi++] = inShorts[ii]
            ii += 2
        }
        return out
    }

    fun release() {
        vad?.close()
    }
}