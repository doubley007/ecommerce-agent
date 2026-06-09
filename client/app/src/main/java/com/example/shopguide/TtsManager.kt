package com.example.shopguide

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 包装 Android 系统 TextToSpeech：
 *  - 单例，初始化一次（华为机自带中文 voice）
 *  - 暴露 speak(text, msgId) / stop()
 *  - currentSpeakingId 让 UI 知道哪条助手消息在播，按钮变停止图标
 */
class TtsManager(context: Context) {
    private val appCtx = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false

    @Volatile var currentSpeakingId: String? = null
        private set
    private var listener: ((String?) -> Unit)? = null

    init {
        tts = TextToSpeech(appCtx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val r = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS 中文不可用，降级 Locale.CHINA")
                    tts?.setLanguage(Locale.CHINA)
                }
                tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        currentSpeakingId = utteranceId
                        listener?.invoke(utteranceId)
                    }
                    override fun onDone(utteranceId: String?) {
                        if (currentSpeakingId == utteranceId) {
                            currentSpeakingId = null
                            listener?.invoke(null)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (currentSpeakingId == utteranceId) {
                            currentSpeakingId = null
                            listener?.invoke(null)
                        }
                    }
                })
            } else {
                Log.e(TAG, "TextToSpeech init 失败：status=$status")
            }
        }
    }

    /** UI 注册回调感知"哪条消息在播"，传 null 表示停止。 */
    fun setOnStateChange(cb: (String?) -> Unit) { listener = cb }

    fun speak(text: String, utteranceId: String) {
        if (!ready) {
            Log.w(TAG, "TTS 未就绪，忽略 speak")
            return
        }
        if (text.isBlank()) return
        // 重新播放前先停掉前一段
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
        currentSpeakingId = null
        listener?.invoke(null)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}
