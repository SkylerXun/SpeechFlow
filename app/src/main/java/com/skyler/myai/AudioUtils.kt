package com.skyler.myai

/**
 * 拼接多段音频 ByteArray（按顺序拼接）。
 * 适用于 VAD 切段后合并输出。
 */
fun concatenateByteArrays(chunks: List<ByteArray>): ByteArray {
    val total = chunks.sumOf { it.size }
    val out = ByteArray(total)
    var offset = 0
    for (c in chunks) {
        System.arraycopy(c, 0, out, offset, c.size)
        offset += c.size
    }
    return out
}

