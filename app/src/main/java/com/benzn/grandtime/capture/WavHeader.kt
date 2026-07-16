package com.benzn.grandtime.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure 44-byte RIFF/WAVE (PCM) header builder — the pipeline ingests .wav. */
object WavHeader {
    fun riffWav(pcmLen: Int, sampleRate: Int = 16000, channels: Int = 1, bits: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + pcmLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)                       // PCM fmt chunk size
        bb.putShort(1)                      // audio format = PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bits.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(pcmLen)
        return bb.array()
    }
}
