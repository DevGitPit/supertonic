package com.brahmadeo.supertonic.tts

import android.util.Log

object SupertonicTTS {
    private var nativePtr: Long = 0

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
        if (nativePtr != 0L) {
            // Health check: Can we still talk to the engine?
            if (getSocClass(nativePtr) != -1) {
                Log.i("SupertonicTTS", "Engine already initialized and healthy")
                return true
            } else {
                Log.w("SupertonicTTS", "Engine pointer exists but is unhealthy. Re-initializing...")
                release()
            }
        }
        
        nativePtr = init(modelPath, libPath)
        val success = nativePtr != 0L
        if (success) {
            Log.i("SupertonicTTS", "Engine initialized successfully: $nativePtr")
        } else {
            Log.e("SupertonicTTS", "Engine initialization FAILED")
        }
        return success
    }

    private var listeners = java.util.concurrent.CopyOnWriteArrayList<ProgressListener>()
    
    @Volatile
    private var currentSessionId: Long = 0

    interface ProgressListener {
        fun onProgress(sessionId: Long, current: Int, total: Int)
        fun onAudioChunk(sessionId: Long, data: ByteArray)
    }

    fun addProgressListener(listener: ProgressListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeProgressListener(listener: ProgressListener) {
        listeners.remove(listener)
    }

    // Called from JNI
    fun notifyProgress(current: Int, total: Int) {
        val sid = currentSessionId
        for (l in listeners) l.onProgress(sid, current, total)
    }

    // Called from JNI
    fun notifyAudioChunk(data: ByteArray) {
        val sid = currentSessionId
        for (l in listeners) l.onAudioChunk(sid, data)
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
        
        currentSessionId++ // New session for every sentence
        
        try {
            val data = synthesize(nativePtr, text, stylePath, speed, bufferDuration, steps)
            return if (data.isNotEmpty()) data else null
        } catch (e: Exception) {
            Log.e("SupertonicTTS", "Native synthesis exception: ${e.message}")
            return null
        }
    }

    @Synchronized
    fun getSoC(): Int {
        if (nativePtr == 0L) return -1
        return getSocClass(nativePtr)
    }

    @Synchronized
    fun getAudioSampleRate(): Int {
        if (nativePtr == 0L) return 44100
        return getSampleRate(nativePtr)
    }

    @Synchronized
    fun release() {
        if (nativePtr != 0L) {
            Log.i("SupertonicTTS", "Releasing engine: $nativePtr")
            close(nativePtr)
            nativePtr = 0
        }
    }
}