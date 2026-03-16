package com.skyler.myai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Whisper推理引擎
class WhisperInferenceEngine(
    private val context: Context,
    private val onResult: (String) -> Unit
) {
    private var whisper: Whisper? = null
    private var isModelLoaded = false

    // 预加载模型（在应用启动时做）
    suspend fun loadModel() {
        withContext(Dispatchers.IO) {
            try {
                whisper = Whisper(context)
                // 加载TFLite模型 - 建议用tiny模型，平衡速度和准确率[citation:3]
                whisper?.loadModel(
                    "whisper-tiny.tflite",
                    "filters_vocab_multilingual.bin",
                    true  // 启用量化
                )
                isModelLoaded = true
            } catch (e: Exception) {
                Log.e("WhisperEngine", "模型加载失败", e)
            }
        }
    }

    // 识别语音段（来自VAD的完整语音）
    suspend fun transcribe(audioData: ByteArray): String {
        if (!isModelLoaded) {
            return "模型未加载"
        }

        return withContext(Dispatchers.Default) {
            try {
                // 确保是16kHz 16bit PCM
                val processedAudio = ensure16kHz16bitPCM(audioData)

                // 执行推理
                val result = whisper?.transcribe(processedAudio) ?: ""

                withContext(Dispatchers.Main) {
                    onResult(result)
                }

                result
            } catch (e: Exception) {
                Log.e("WhisperEngine", "识别失败", e)
                ""
            }
        }
    }

    // 确保音频格式正确
    private fun ensure16kHz16bitPCM(data: ByteArray): ByteArray {
        // 这里需要根据实际情况判断是否需要重采样
        // 简单场景：假设VAD传过来的已经是16kHz
        return data
    }

    fun release() {
        whisper?.close()
    }
}