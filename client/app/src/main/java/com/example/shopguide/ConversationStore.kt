package com.example.shopguide

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 多会话持久化 — 仿 ChatGPT 左侧"历史对话"列表。
 *
 * 物理布局：app 内部存储下 conversations/ 目录
 *   ├─ index.json                    会话元数据列表（id/title/updatedAt）
 *   └─ <conversationId>.json         单个会话的完整 ConversationData
 *
 * 选这种"一个会话一个文件 + 一份 index"的拆法是因为：
 *   1) 切换会话只读一个文件，不会随会话越来越多变慢
 *   2) 删除会话直接 delete file，不用整体重写大 JSON
 *   3) 调试时人眼可读
 *
 * 线程：所有 IO 都期望在调用者侧切到 IO dispatcher（ViewModel 用 viewModelScope.launch + Dispatchers.IO）
 */
object ConversationStore {

    private const val DIR_NAME = "conversations"
    private const val INDEX_FILE = "index.json"

    /** 标题最大长度 — 超过会被截断并加 "…" */
    const val MAX_TITLE_LEN = 20

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    @Serializable
    data class ConversationMeta(
        val id: String,
        val title: String,
        /** 用本地单调递增的 epoch 毫秒，新建/更新时由 ViewModel 传进来。
         *  ViewModel 可以直接用 System.currentTimeMillis()，store 自己不取时钟。 */
        val updatedAt: Long,
    )

    /**
     * 单个会话的全部状态 — ViewModel 切换会话时整体加载/落盘。
     * 字段对应 ChatViewModel 里的内部状态：
     *   messages         — 整条消息流（含商品标签、对比 JSON 都已嵌在 text 里）
     *   topicSummary     — 自动话题隔离的当前 topic 主线
     *   topicStartIndex  — 当前 topic 在 messages 列表里的起点
     *   constraints      — 已识别的硬约束快照（chip 行）
     */
    @Serializable
    data class ConversationData(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: List<UiMessage> = emptyList(),
        val topicSummary: String = "",
        val topicStartIndex: Int = 0,
        val constraints: ConstraintSnapshotWire? = null,
    )

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun indexFile(context: Context) = File(dir(context), INDEX_FILE)
    private fun convFile(context: Context, id: String) = File(dir(context), "$id.json")

    /** 读取 index 列表；文件不存在或解析失败都返回空列表（避免崩在冷启动）。 */
    fun loadIndex(context: Context): List<ConversationMeta> {
        val f = indexFile(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val txt = f.readText()
            if (txt.isBlank()) emptyList()
            else json.decodeFromString(ListSerializer(ConversationMeta.serializer()), txt)
        }.onFailure {
            Log.w("ConvStore", "loadIndex failed: ${it.message}")
        }.getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }

    /** 全量写 index — 列表小，不值得增量。 */
    private fun writeIndex(context: Context, list: List<ConversationMeta>) {
        runCatching {
            val txt = json.encodeToString(ListSerializer(ConversationMeta.serializer()), list)
            indexFile(context).writeText(txt)
        }.onFailure { Log.e("ConvStore", "writeIndex failed", it) }
    }

    /** 加载某个会话；不存在或读不出来就返回 null（调用方决定要不要新建）。 */
    fun load(context: Context, id: String): ConversationData? {
        val f = convFile(context, id)
        if (!f.exists()) return null
        return runCatching {
            json.decodeFromString(ConversationData.serializer(), f.readText())
        }.onFailure {
            Log.w("ConvStore", "load($id) failed: ${it.message}")
        }.getOrNull()
    }

    /** 保存单个会话 + 同步刷新 index 项（标题、updatedAt）。 */
    fun save(context: Context, data: ConversationData) {
        runCatching {
            val txt = json.encodeToString(ConversationData.serializer(), data)
            convFile(context, data.id).writeText(txt)
        }.onFailure {
            Log.e("ConvStore", "save(${data.id}) failed", it)
            return
        }
        // 同步 index
        val current = loadIndex(context).toMutableList()
        val newMeta = ConversationMeta(id = data.id, title = data.title, updatedAt = data.updatedAt)
        val idx = current.indexOfFirst { it.id == data.id }
        if (idx >= 0) current[idx] = newMeta else current.add(0, newMeta)
        writeIndex(context, current.sortedByDescending { it.updatedAt })
    }

    /** 删除单个会话（json 文件 + index 项）。 */
    fun delete(context: Context, id: String) {
        runCatching { convFile(context, id).delete() }
        val current = loadIndex(context).filterNot { it.id == id }
        writeIndex(context, current)
    }

    /** 生成一个新会话 id — UUID 足够。 */
    fun newId(): String = UUID.randomUUID().toString()

    /**
     * 从首条用户消息生成会话标题。空消息（图片直接发的）退到 "新对话"。
     * 取前 [MAX_TITLE_LEN] 字符；超出加 "…"。
     */
    fun titleFromFirstMessage(text: String): String {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return "新对话"
        return if (cleaned.length <= MAX_TITLE_LEN) cleaned
        else cleaned.substring(0, MAX_TITLE_LEN) + "…"
    }
}
