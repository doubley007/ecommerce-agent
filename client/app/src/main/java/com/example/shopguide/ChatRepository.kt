package com.example.shopguide

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * 与后端通信。
 * 暴露：
 *  - chatStream(messages): Flow<StreamEvent> 把 SSE 事件转成 Kotlin Flow
 *  - fetchProduct(productId): suspend 拉取商品详情（卡片渲染用）
 */
class ChatRepository {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 长连接，禁用读超时
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    sealed class StreamEvent {
        data class Constraints(val snapshot: ConstraintSnapshotWire) : StreamEvent()
        data class Vision(val keywords: String, val elapsedMs: Int) : StreamEvent()
        /** 检索阶段汇总：策略 / 召回数 / top scores / 商品列表。
         *  D??: 老协议下发的是 List<RetrievedItem>；新协议下发对象。两种都解析。 */
        data class Retrieved(val summary: RetrievedSummaryWire) : StreamEvent()
        data class ThinkingStep(val step: ThinkingStepWire) : StreamEvent()
        data class Token(val text: String) : StreamEvent()
        /** 后端判定 query 歧义高时下发；同流不会再有 token / retrieved。 */
        data class Clarification(val payload: ClarificationWire) : StreamEvent()
        /** 场景化组合推荐：仅 /chat/stream/scene 端点会下发。同流不会有 token。 */
        data class SceneInfo(val info: SceneInfoWire) : StreamEvent()
        data class ComboResult(val data: ComboDataWire) : StreamEvent()
        data object Done : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }

    /** 兼容新（对象）/老（数组）两种 retrieved data 格式。 */
    private fun parseRetrieved(data: String): RetrievedSummaryWire? {
        val element = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return null
        return when (element) {
            is kotlinx.serialization.json.JsonObject ->
                runCatching {
                    json.decodeFromJsonElement(RetrievedSummaryWire.serializer(), element)
                }.getOrNull()
            is kotlinx.serialization.json.JsonArray -> {
                val items = runCatching {
                    json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(RetrievedItem.serializer()),
                        element,
                    )
                }.getOrDefault(emptyList())
                RetrievedSummaryWire(
                    strategy = "hybrid_rerank",
                    count = items.size,
                    top_scores = items.take(5).map { it.score },
                    items = items,
                )
            }
            else -> null
        }
    }

    fun chatStream(
        messages: List<ChatMessageWire>,
        topicSummary: String = "",
        priorPriceMax: Double? = null,
        excludePids: List<String> = emptyList(),
    ): Flow<StreamEvent> = callbackFlow {
        val payload = json.encodeToString(
            ChatRequestWire.serializer(),
            ChatRequestWire(
                messages = messages,
                topic_summary = topicSummary,
                prior_price_max = priorPriceMax,
                exclude_pids = excludePids,
            ),
        )
        val request = Request.Builder()
            .url("${Config.BASE_URL}/chat/stream")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            // 关掉 gzip 自动协商，否则 OkHttp 会攒一段才解压送出，破坏流式
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "constraints" -> {
                        val obj = runCatching {
                            json.decodeFromString(ConstraintSnapshotWire.serializer(), data)
                        }.getOrNull()
                        if (obj != null) trySend(StreamEvent.Constraints(obj))
                    }
                    "retrieved" -> {
                        val summary = parseRetrieved(data)
                        if (summary != null) trySend(StreamEvent.Retrieved(summary))
                    }
                    "thinking_step" -> {
                        val obj = runCatching {
                            json.decodeFromString(ThinkingStepWire.serializer(), data)
                        }.getOrNull()
                        if (obj != null) trySend(StreamEvent.ThinkingStep(obj))
                    }
                    "token" -> {
                        val obj = runCatching {
                            json.decodeFromString(TokenWire.serializer(), data)
                        }.getOrNull()
                        if (obj != null) trySend(StreamEvent.Token(obj.text))
                    }
                    "clarification" -> {
                        val obj = runCatching {
                            json.decodeFromString(ClarificationWire.serializer(), data)
                        }.getOrNull()
                        if (obj != null && obj.questions.isNotEmpty()) {
                            trySend(StreamEvent.Clarification(obj))
                        }
                    }
                    "done" -> {
                        trySend(StreamEvent.Done)
                        close()
                    }
                    "error" -> {
                        val obj = runCatching {
                            json.decodeFromString(ErrWire.serializer(), data)
                        }.getOrNull()
                        trySend(StreamEvent.Error(obj?.message ?: "unknown"))
                        close()
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                trySend(StreamEvent.Error(t?.message ?: "network error"))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    /**
     * 场景化组合推荐。query 命中 trip/gift/搭配/运动等关键词时调用。
     *
     * 事件序列：scene（场景元信息） → thinking_step (×N) → combo_result（套装数据）→ done。
     * 与 /chat/stream 不同：此端点**不下发 token**，UI 直接拿 combo_result 渲染套装卡片。
     * history 用于 LLM 编排时辅助理解上下文（当前会话 takeLast 几条即可）。
     */
    fun sceneChatStream(
        query: String,
        history: List<ChatMessageWire> = emptyList(),
    ): Flow<StreamEvent> = callbackFlow {
        val payload = json.encodeToString(
            SceneChatRequestWire.serializer(),
            SceneChatRequestWire(query = query, history = history),
        )
        val request = Request.Builder()
            .url("${Config.BASE_URL}/chat/stream/scene")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "scene" -> {
                        val obj = runCatching {
                            json.decodeFromString(SceneInfoWire.serializer(), data)
                        }.getOrNull()
                        if (obj != null) trySend(StreamEvent.SceneInfo(obj))
                    }
                    "thinking_step" -> {
                        val obj = runCatching {
                            json.decodeFromString(ThinkingStepWire.serializer(), data)
                        }.getOrNull()
                        if (obj != null) trySend(StreamEvent.ThinkingStep(obj))
                    }
                    "combo_result" -> {
                        val obj = runCatching {
                            json.decodeFromString(ComboDataWire.serializer(), data)
                        }.getOrNull()
                        if (obj != null) trySend(StreamEvent.ComboResult(obj))
                    }
                    "done" -> { trySend(StreamEvent.Done); close() }
                    "error" -> {
                        val obj = runCatching { json.decodeFromString(ErrWire.serializer(), data) }.getOrNull()
                        trySend(StreamEvent.Error(obj?.message ?: "unknown"))
                        close()
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                trySend(StreamEvent.Error(t?.message ?: "network error"))
                close()
            }

            override fun onClosed(eventSource: EventSource) { close() }
        }

        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    /**
     * 多模态拍照搜商品。事件比 chatStream 多一个 Vision（流首），其他一致。
     * imageBase64 期望已是纯 base64 字符串（不含 data:image/... 前缀）。
     */
    fun multimodalChatStream(
        imageBase64: String,
        textHint: String,
        history: List<ChatMessageWire>,
    ): Flow<StreamEvent> = callbackFlow {
        val payload = json.encodeToString(
            MultimodalChatRequestWire.serializer(),
            MultimodalChatRequestWire(
                image_base64 = imageBase64,
                text_hint = textHint,
                history = history,
            ),
        )
        val request = Request.Builder()
            .url("${Config.BASE_URL}/chat/stream/multimodal")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "constraints" -> {
                        val obj = runCatching {
                            json.decodeFromString(ConstraintSnapshotWire.serializer(), data)
                        }.getOrNull()
                        if (obj != null) trySend(StreamEvent.Constraints(obj))
                    }
                    "vision" -> {
                        val obj = runCatching { json.decodeFromString(VisionWire.serializer(), data) }.getOrNull()
                        if (obj != null) trySend(StreamEvent.Vision(obj.keywords, obj.elapsed_ms))
                    }
                    "retrieved" -> {
                        val summary = parseRetrieved(data)
                        if (summary != null) trySend(StreamEvent.Retrieved(summary))
                    }
                    "thinking_step" -> {
                        val obj = runCatching {
                            json.decodeFromString(ThinkingStepWire.serializer(), data)
                        }.getOrNull()
                        if (obj != null) trySend(StreamEvent.ThinkingStep(obj))
                    }
                    "token" -> {
                        val obj = runCatching { json.decodeFromString(TokenWire.serializer(), data) }.getOrNull()
                        if (obj != null) trySend(StreamEvent.Token(obj.text))
                    }
                    "done" -> { trySend(StreamEvent.Done); close() }
                    "error" -> {
                        val obj = runCatching { json.decodeFromString(ErrWire.serializer(), data) }.getOrNull()
                        trySend(StreamEvent.Error(obj?.message ?: "unknown"))
                        close()
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                trySend(StreamEvent.Error(t?.message ?: "network error"))
                close()
            }

            override fun onClosed(eventSource: EventSource) { close() }
        }

        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    /**
     * 上传一段 WAV 字节流给后端 /asr，同步返回识别文本。
     * 失败返回 null（已经在 Log 里打印原因）。
     */
    suspend fun recognizeAudio(wavBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val b64 = android.util.Base64.encodeToString(wavBytes, android.util.Base64.NO_WRAP)
        val payload = json.encodeToString(
            AsrRequestWire.serializer(),
            AsrRequestWire(audio_base64 = b64, format = "wav", sample_rate = 16000),
        )
        val request = Request.Builder()
            .url("${Config.BASE_URL}/asr")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("ChatRepo", "asr HTTP ${resp.code}")
                    return@runCatching null
                }
                val body = resp.body?.string() ?: return@runCatching null
                val r = json.decodeFromString(AsrResponseWire.serializer(), body)
                if (r.error != null) {
                    android.util.Log.e("ChatRepo", "asr error: ${r.error}")
                    null
                } else r.text
            }
        }.onFailure {
            android.util.Log.e("ChatRepo", "asr request failed", it)
        }.getOrNull()
    }

    suspend fun fetchProduct(productId: String): ProductWire? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${Config.BASE_URL}/products/$productId")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("ChatRepo", "fetchProduct $productId HTTP ${resp.code}")
                    return@runCatching null
                }
                val body = resp.body?.string() ?: return@runCatching null
                json.decodeFromString(ProductWire.serializer(), body)
            }
        }.onFailure {
            android.util.Log.e("ChatRepo", "fetchProduct $productId failed", it)
        }.getOrNull()
    }

    @kotlinx.serialization.Serializable
    private data class TokenWire(val text: String)

    @kotlinx.serialization.Serializable
    private data class ErrWire(val message: String)

    @kotlinx.serialization.Serializable
    private data class VisionWire(val keywords: String, val elapsed_ms: Int = 0)
}
