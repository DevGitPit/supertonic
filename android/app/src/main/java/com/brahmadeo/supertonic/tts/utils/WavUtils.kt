package com.brahmadeo.supertonic.tts.utils

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {
    fun saveWav(file: File, pcmData: ByteArray, sampleRate: Int = 44100, channels: Int = 1, bitsPerSample: Int = 16) {
        val dataSize = pcmData.size
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        
        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize) // ChunkSize
        buffer.put("WAVE".toByteArray())
        
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1.toShort()) // AudioFormat (PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        
        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(pcmData)
        }
    }
}
