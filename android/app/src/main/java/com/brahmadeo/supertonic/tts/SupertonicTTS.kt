package com.brahmadeo.supertonic.tts

import android.util.Log

object SupertonicTTS {
    private var nativePtr: Long = 0
    private val progressListener = ThreadLocal<ProgressListener>()

    interface ProgressListener {
        fun onProgress(current: Int, total: Int)
        fun onAudioChunk(data: ByteArray)
    }

    init {
        try {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("supertonic_tts")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("SupertonicTTS", "Failed to load native library: ${e.message}")
        }
    }

    private external fun init(modelPath: String, libPath: String): Long
    private external fun synthesize(ptr: Long, text: String, stylePath: String, speed: Float, bufferSeconds: Float, steps: Int): ByteArray
    private external fun getSocClass(ptr: Long): Int
    private external fun getSampleRate(ptr: Long): Int
    private external fun close(ptr: Long)

    @Synchronized
    fun initialize(modelPath: String, libPath: String): Boolean {
        if (nativePtr != 0L) return true // Already initialized
        nativePtr = init(modelPath, libPath)
        return nativePtr != 0L
    }

    fun setProgressListener(listener: ProgressListener?) {
        this.progressListener.set(listener)
    }

    // Called from JNI
    fun notifyProgress(current: Int, total: Int) {
        progressListener.get()?.onProgress(current, total)
    }

    // Called from JNI
    fun notifyAudioChunk(data: ByteArray) {
        progressListener.get()?.onAudioChunk(data)
    }

    @Volatile
    private var isCancelled = false

    fun setCancelled(cancelled: Boolean) {
        isCancelled = cancelled
    }

    // Called from JNI
    fun isCancelled(): Boolean {
        return isCancelled
    }

    @Synchronized
    fun generateAudio(text: String, stylePath: String, speed: Float = 1.0f, bufferDuration: Float = 0.0f, steps: Int = 5): ByteArray? {
        if (nativePtr == 0L) {
            Log.e("SupertonicTTS", "Engine not initialized")
            return null
        }
        // isCancelled = false // Removed to prevent race condition. Service must reset this.
        val data = synthesize(nativePtr, text, stylePath, speed, bufferDuration, steps)
        return if (data.isNotEmpty()) data else null
    }

    fun getSoC(): Int {
        if (nativePtr == 0L) return -1
        return getSocClass(nativePtr)
    }

    fun getAudioSampleRate(): Int {
        if (nativePtr == 0L) return 24000
        return getSampleRate(nativePtr)
    }

    fun release() {
        if (nativePtr != 0L) {
            close(nativePtr)
            nativePtr = 0
        }
    }
}
