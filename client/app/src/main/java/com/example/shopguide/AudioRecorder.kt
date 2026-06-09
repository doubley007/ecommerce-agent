package com.example.shopguide

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 简单的 PCM 录音 → WAV 封装。火山一句话识别支持 wav/mp3/ogg，
 * 走 PCM+WAV 头是最稳的：不依赖编码器、headers 完全可控。
 *
 * 16kHz / 16bit / mono：火山推荐参数，识别质量与带宽平衡最好。
 *
 * 用法：
 *   val rec = AudioRecorder()
 *   rec.start()                  // 持有 RECORD_AUDIO 权限
 *   ...等用户点停...
 *   val wavBytes = rec.stopAndGetWav()
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val maxSeconds: Int = 30,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var recording = false
    private val pcmBuffer = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize 返回 $minBuf，设备不支持？")
            return false
        }
        val bufSize = minBuf * 2
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            rec.release()
            return false
        }
        pcmBuffer.reset()
        rec.startRecording()
        record = rec
        recording = true

        val maxBytes = sampleRate * 2 * maxSeconds  // 16bit mono
        thread = Thread {
            val buf = ByteArray(bufSize)
            while (recording && pcmBuffer.size() < maxBytes) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) pcmBuffer.write(buf, 0, n)
            }
        }.also { it.start() }
        return true
    }

    /**
     * 停止录音，返回带 WAV 头的字节数组。失败或时长 <0.3s 返回 null。
     */
    fun stopAndGetWav(): ByteArray? {
        if (!recording) return null
        recording = false
        thread?.join(500)
        thread = null
        val rec = record
        record = null
        if (rec != null) {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        val pcm = pcmBuffer.toByteArray()
        val minBytes = sampleRate * 2 * 3 / 10  // 0.3s
        if (pcm.size < minBytes) {
            Log.w(TAG, "录音过短 ${pcm.size} bytes，丢弃")
            return null
        }
        return wrapWav(pcm, sampleRate)
    }

    fun cancel() {
        recording = false
        thread?.interrupt()
        thread = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
        pcmBuffer.reset()
    }

    /** 给原始 PCM 数据加 44 字节 WAV header。单声道、16bit、PCM。 */
    private fun wrapWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2  // 16bit mono
        val totalDataLen = pcm.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)              // PCM fmt chunk size
        header.putShort(1)             // audio format = 1 (PCM)
        header.putShort(1)             // channels = 1
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)             // block align
        header.putShort(16)            // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
