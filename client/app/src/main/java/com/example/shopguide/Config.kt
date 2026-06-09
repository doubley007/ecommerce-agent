package com.example.shopguide

import android.content.Context

/**
 * 后端地址。运行时可在 APP 内"齿轮"按钮里修改，存 SharedPreferences。
 *
 * 演示场景：Android 真机 + Mac 同一 WiFi 直连
 *   - Mac 上 uvicorn 监听 0.0.0.0:8000
 *   - 这里填 Mac 的局域网 IP（终端 `ipconfig getifaddr en0`）
 *   - 不能用 127.0.0.1 / localhost（那是手机自己的回环地址）
 *
 * 切换网络时演示日只需点齿轮按钮改 IP，无需重新编译 APK。
 */
object Config {
    private const val PREFS = "shopguide_config"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_AUTO_TTS = "auto_tts"
    const val DEFAULT_BASE_URL = "http://192.168.1.42:8000"

    @Volatile
    private var cachedBaseUrl: String? = null
    @Volatile
    private var cachedAutoTts: Boolean = false

    fun init(context: Context) {
        if (cachedBaseUrl == null) {
            cachedBaseUrl = prefs(context).getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL
        }
        cachedAutoTts = prefs(context).getBoolean(KEY_AUTO_TTS, false)
    }

    /** 当前 BASE_URL。调用前必须先 init()，没初始化退回默认值。 */
    val BASE_URL: String
        get() = cachedBaseUrl ?: DEFAULT_BASE_URL

    /** 语音导购模式：AI 回复流式结束后自动朗读。 */
    val autoTtsEnabled: Boolean
        get() = cachedAutoTts

    fun update(context: Context, newUrl: String) {
        val cleaned = normalize(newUrl)
        cachedBaseUrl = cleaned
        prefs(context).edit().putString(KEY_BASE_URL, cleaned).apply()
    }

    fun setAutoTts(context: Context, enabled: Boolean) {
        cachedAutoTts = enabled
        prefs(context).edit().putBoolean(KEY_AUTO_TTS, enabled).apply()
    }

    /** 用户输入清理：去末尾斜杠，缺协议补 http://。 */
    private fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://$s"
        }
        return s
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
