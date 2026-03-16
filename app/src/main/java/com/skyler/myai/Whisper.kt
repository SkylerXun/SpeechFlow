package com.skyler.myai

import android.content.Context

/**
 * 占位接口：你集成的 Whisper 推理实现需要在这里提供真实实现或替换为对应库的类。
 *
 * 现在项目里 `WhisperInferenceEngine` 直接引用 `Whisper`，但仓库中没有该类定义导致无法编译。
 * 你把实际用的 Whisper 库/源码接入后，我会把这里删掉或改为适配层。
 */
class Whisper(private val context: Context) : AutoCloseable {
    fun loadModel(
        modelFileName: String,
        vocabFileName: String,
        isQuantized: Boolean
    ) {
        // TODO: connect to real Whisper implementation
    }

    fun transcribe(pcm16k16bitMono: ByteArray): String {
        // TODO: connect to real Whisper implementation
        return ""
    }

    override fun close() {
        // TODO: connect to real Whisper implementation
    }
}

