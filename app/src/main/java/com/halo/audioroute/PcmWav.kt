package com.halo.audioroute

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM with a standard 44-byte RIFF/WAVE header. */
object PcmWav {
    fun encode(pcm: ByteArray, sampleRate: Int): ByteArray {
        require(sampleRate in 1..192000) { "无效采样率" }
        require(pcm.size % 2 == 0) { "PCM 必须包含完整的 16 位采样" }
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1) // format, channels: uint16
            putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16) // block align, bits per sample: uint16
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
        }.array()
    }
}
