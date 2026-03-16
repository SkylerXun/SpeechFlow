package com.skyler.myai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.PriorityBlockingQueue
import kotlin.concurrent.thread

// 麦克风管理器 - 应用全局单例
class MicrophoneManager private constructor(
    private val context: Context
) {
    // 当前麦克风使用者优先级队列
    // priority 越大越优先；同优先级按 seq 先来先服务（避免饿死）
    private val requestQueue = PriorityBlockingQueue<MicRequest>(
        11,
        compareByDescending<MicRequest> { it.priority }.thenBy { it.seq }
    )
    private var nextSeq: Long = 0L

    // 当前活跃的请求
    private var activeRequest: MicRequest? = null

    // 音频录制实例
    private var audioRecord: AudioRecord? = null

    // 音频处理线程
    private var audioThread: Thread? = null
    private val running = AtomicBoolean(false)

    // 环形缓冲区 - 用于临时存储音频数据
    private val circularBuffer = AudioCircularBuffer(BUFFER_CAPACITY)

    // 当前采样率
    private var currentSampleRate = 8000 // 默认8kHz省电模式

    // 请求麦克风
    @Synchronized
    fun requestMic(
        priority: Int,           // 优先级：数字越大优先级越高
        requiredSampleRate: Int,  // 需要的采样率
        audioSource: Int,         // MediaRecorder.AudioSource.*
        callback: MicCallback,    // 回调接口
        tag: String               // 业务标识
    ): Boolean {
        // 同 tag 的旧请求先移除，避免队列里残留重复任务
        requestQueue.removeAll { it.tag == tag }
        val request = MicRequest(
            priority = priority,
            requiredSampleRate = requiredSampleRate,
            audioSource = audioSource,
            callback = callback,
            tag = tag,
            seq = nextSeq++
        )
        requestQueue.offer(request)

        // 重新调度
        scheduleNext()
        return true
    }

    // 释放麦克风
    @Synchronized
    fun releaseMic(tag: String) {
        requestQueue.removeAll { it.tag == tag }

        // 如果是当前活跃的请求，停止并调度下一个
        if (activeRequest?.tag == tag) {
            stopCurrentRecording(reason = "released_by_$tag")
            scheduleNext()
        }
    }

    // 调度下一个最高优先级的请求
    @Synchronized
    private fun scheduleNext() {
        val nextRequest = requestQueue.poll()
            ?: return // 队列为空，保持当前 active 不变

        // 如果优先级低于当前活跃的，不处理
        if (activeRequest != null &&
            nextRequest.priority <= activeRequest!!.priority
        ) {
            // 把取出来的放回去
            requestQueue.offer(nextRequest)
            return
        }

        // 停止当前录制
        stopCurrentRecording(reason = "preempted_or_switched")

        // 启动新录制
        startRecording(nextRequest)
    }

    // 启动录制
    private fun startRecording(request: MicRequest) {
        activeRequest = request

        // 如果需要的采样率/音源与当前不同，重新初始化AudioRecord
        val needReinit = (currentSampleRate != request.requiredSampleRate) ||
            (audioSourceInUse != request.audioSource)
        if (needReinit) {
            initAudioRecord(request.requiredSampleRate, request.audioSource)
        }

        // 启动音频采集线程
        running.set(true)
        audioThread = thread {
            collectAudioData(request.callback)
        }

        request.callback.onMicGranted()
    }

    // 采集音频数据
    private fun collectAudioData(callback: MicCallback) {
        val buffer = ByteArray(MIN_BUFFER_SIZE)

        while (running.get() && activeRequest?.callback == callback) {
            val ar = audioRecord ?: break
            val bytesRead = ar.read(buffer, 0, buffer.size)

            if (bytesRead > 0) {
                // 写入环形缓冲区
                circularBuffer.put(buffer.copyOf(bytesRead))

                // 回调原始数据（8kHz/16kHz按需）
                callback.onAudioData(buffer.copyOf(bytesRead), currentSampleRate)
            } else if (bytesRead < 0) {
                callback.onError(IllegalStateException("AudioRecord read error=$bytesRead"))
                break
            }
        }
    }

    // 初始化AudioRecord - 根据采样率动态调整
    private var audioSourceInUse: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION

    private fun initAudioRecord(sampleRate: Int, audioSource: Int) {
        releaseAudioRecord()

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2  // 双缓冲避免溢出

        audioRecord = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        currentSampleRate = sampleRate
        audioSourceInUse = audioSource
        audioRecord?.startRecording()
    }

    private fun stopCurrentRecording(reason: String) {
        val old = activeRequest
        activeRequest = null

        running.set(false)
        try {
            audioThread?.interrupt()
            audioThread?.join(500)
        } catch (_: InterruptedException) {
        } finally {
            audioThread = null
        }

        releaseAudioRecord()
        old?.callback?.onMicReleased(reason)
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.apply {
                try {
                    stop()
                } catch (_: Exception) {
                }
                release()
            }
        } catch (_: Exception) {
        } finally {
            audioRecord = null
        }
    }

    companion object {
        private const val BUFFER_CAPACITY = 1024 * 1024 // 1MB环形缓冲区
        private const val MIN_BUFFER_SIZE = 4096

        @Volatile
        private var instance: MicrophoneManager? = null

        fun getInstance(context: Context): MicrophoneManager {
            return instance ?: synchronized(this) {
                instance ?: MicrophoneManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

// 请求数据结构
data class MicRequest(
    val priority: Int,
    val requiredSampleRate: Int,
    val audioSource: Int,
    val callback: MicCallback,
    val tag: String,
    val seq: Long
)

// 回调接口
interface MicCallback {
    fun onMicGranted()
    fun onAudioData(data: ByteArray, sampleRate: Int)
    fun onMicReleased(reason: String)
    fun onError(error: Exception)
}

/**
 * 极简环形缓冲区（线程安全），用于缓存“最近一段音频”做 pre-roll / 补帧等。
 * 注意：这里实现是按 ByteArray chunk 缓存，不做 sample 对齐；够用但不做 DSP 级别保证。
 */
private class AudioCircularBuffer(private val capacityBytes: Int) {
    private val chunks = ArrayDeque<ByteArray>()
    private var sizeBytes: Int = 0

    @Synchronized
    fun put(chunk: ByteArray) {
        chunks.addLast(chunk)
        sizeBytes += chunk.size
        while (sizeBytes > capacityBytes && chunks.isNotEmpty()) {
            val removed = chunks.removeFirst()
            sizeBytes -= removed.size
        }
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(sizeBytes)
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, offset, c.size)
            offset += c.size
        }
        return out
    }
}