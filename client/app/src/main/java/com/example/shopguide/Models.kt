package com.example.shopguide

import kotlinx.serialization.Serializable

/**
 * 客户端 ↔ 后端 ↔ UI 三层模型。
 *
 * 1) Wire 层（@Serializable）：与后端 JSON 严格对应
 * 2) UI 层：含临时状态（如"流式中"标志），不直接序列化
 */

// ----- 后端线协议 -----

@Serializable
data class ChatMessageWire(
    val role: String,
    val content: String,
)

@Serializable
data class ChatRequestWire(
    val messages: List<ChatMessageWire>,
    val temperature: Double = 0.6,
    val use_rag: Boolean = true,
    val top_k: Int = 5,
    /** 自动话题隔离的产物：当前话题主线（通常是话题首句）。
     *  非空时后端会塞进检索 query 和 system prompt，避免被滑窗截掉的开场被遗忘。 */
    val topic_summary: String = "",
    /** 上一轮已识别的价格上限（来自客户端 ConstraintTracker chip）。
     *  用于"再便宜点呢"这类相对降价：本轮文本没具体数字时，后端按系数从此压低。 */
    val prior_price_max: Double? = null,
    /** 当前会话已经展示给用户的商品 id 列表。仅在用户说"再来几款"时后端会用 — 命中"再来"
     *  关键词后从召回结果剔除这批 id，避免连续两轮推荐重复商品。 */
    val exclude_pids: List<String> = emptyList(),
)

@Serializable
data class AsrRequestWire(
    val audio_base64: String,
    val format: String = "wav",
    val sample_rate: Int = 16000,
)

@Serializable
data class AsrResponseWire(
    val text: String? = null,
    val error: String? = null,
)

@Serializable
data class SceneChatRequestWire(
    val query: String,
    val history: List<ChatMessageWire> = emptyList(),
    val top_k_per_sub: Int = 3,
    val temperature: Double = 0.6,
)

@Serializable
data class MultimodalChatRequestWire(
    val image_base64: String,
    val text_hint: String = "",
    val history: List<ChatMessageWire> = emptyList(),
    val temperature: Double = 0.6,
    val top_k: Int = 5,
)

@Serializable
data class RetrievedItem(
    val product_id: String,
    val score: Double,
    val title: String,
    /**
     * 多模态融合来源标签：
     *   "text_only"  — 仅 BM25/向量文本召回命中（VLM 关键词路径）
     *   "image_only" — 仅 CLIP 图像向量召回命中
     *   "both"       — 双路同时命中（fusion 最强信号）
     * 纯文本对话（/chat/stream）不下发该字段，保持 null
     */
    val match_source: String? = null,
)

/**
 * /chat/stream event=retrieved 的载体：包含策略名、数量、top 分数与展开列表。
 * 给"思考过程"折叠面板可视化用——单独抽出来比从 RetrievedItem 列表二次推断更直观。
 */
@Serializable
data class RetrievedSummaryWire(
    val strategy: String = "hybrid_rerank",
    val count: Int = 0,
    val top_scores: List<Double> = emptyList(),
    val items: List<RetrievedItem> = emptyList(),
)

/**
 * /chat/stream event=thinking_step 单步：
 *   step 取值如 query_rewrite / vector_recall / bm25_recall / rrf_fuse / rerank /
 *               aggregate / constraint_filter / vision_describe / clip_recall /
 *               multimodal_fuse / generate_first_token
 *   detail 为后端拼好的人话描述，前端直接展示
 *   duration_ms 为该阶段耗时，前端面板按"X ms"显示，并相加得总耗时
 */
@Serializable
data class ThinkingStepWire(
    val step: String,
    val detail: String = "",
    val duration_ms: Int = 0,
)

@Serializable
data class SkuWire(
    val sku_id: String,
    val properties: Map<String, String> = emptyMap(),
    val price: Double,
)

@Serializable
data class ProductWire(
    val product_id: String,
    val title: String,
    val brand: String? = null,
    val category: String? = null,
    val sub_category: String? = null,
    val base_price: Double? = null,
    val image_path: String? = null,
    val skus: List<SkuWire> = emptyList(),
    val marketing_description: String = "",
)

/**
 * 后端 SSE event=constraints 的下发体。
 * 字段全部可空 — 只下发"本轮命中的"约束，前端用空判断决定是否渲染该 chip。
 * brand_required 是单值，brand_excludes / attr_excludes 是列表（可同时排除多个）。
 */
@Serializable
data class ConstraintSnapshotWire(
    val price_max: Double? = null,
    val brand_excludes: List<String> = emptyList(),
    val attr_excludes: List<String> = emptyList(),
    val brand_required: String? = null,
    val is_compare: Boolean = false,
)

/**
 * 对比模式下，模型在正文末尾用 [[COMPARE_DATA:{...}]] 内联块下发的结构化数据。
 * 前端 ChatViewModel.runStream 解析后塞进 UiMessage.compareData，
 * MessageBubble 检测到非空时渲染 ComparisonTable + RadarChart。
 */
@Serializable
data class CompareProductRef(
    val product_id: String,
    val name: String,
)

/**
 * 后端 SSE event=clarification 下发体。当 query 歧义高（如"推荐手机"）时，
 * 后端跳过 RAG，直接抛 1~2 个澄清问题让用户点选项快速补全意图。
 *  - score   : 当前 query 的明确度分（仅调试用）
 *  - missing : 缺失维度名（category/price/scene/length）
 *  - questions: 实际渲染的问题列表
 */
@Serializable
data class ClarificationQuestionWire(
    val text: String,
    val options: List<String> = emptyList(),
)

@Serializable
data class ClarificationWire(
    val type: String = "clarification",
    val score: Double = 0.0,
    val missing: List<String> = emptyList(),
    val questions: List<ClarificationQuestionWire> = emptyList(),
)

@Serializable
data class CompareData(
    val products: List<CompareProductRef>,
    val dimensions: List<String>,
    val scores: List<List<Int>>,        // [products][dimensions] 0~5 整数
    val rows: List<List<String>>,       // [products][dimensions] 文字描述
    val conclusion: String = "",
)

/**
 * 场景化组合推荐 — SSE event=combo_result 下发体。
 *
 * 后端 /chat/stream/scene 已经把每个品类选好的商品完整对象塞到 product 字段里，
 * 客户端不用再调 /products/{id}，直接渲染 ComboCardView 即可。
 *  - scene / scene_label : 给标题用，前者来自 LLM（更精炼），后者来自规则识别（带 emoji）
 *  - items               : 套装内每件商品 + 理由 + 品类徽标
 *  - summary             : LLM 给的整体搭配建议
 *  - budget_total        : 用户原话里抽到的总预算（可空）；前端在底部显示"已选合计 / 总预算"
 */
@Serializable
data class ComboItemWire(
    val category: String,
    val product_id: String,
    val reason: String = "",
    val product: ProductWire,
)

@Serializable
data class ComboDataWire(
    val type: String = "combo",
    val scene: String = "",
    val scene_label: String = "",
    val scene_type: String? = null,
    val budget_total: Double? = null,
    val items: List<ComboItemWire> = emptyList(),
    val summary: String = "",
)

/**
 * 场景识别后立刻下发的元信息（event=scene），客户端可在 LLM 编排前
 * 先渲染骨架/loading 提示。当前 UI 直接等到 combo_result 再画，所以这个事件
 * 只用于"思考过程"面板的可视化（拆了多少子类目、目标场景）。
 */
@Serializable
data class SceneInfoWire(
    val is_scene: Boolean = false,
    val scene_type: String? = null,
    val scene_label: String = "",
    val destination: String? = null,
    val occasion: String? = null,
    val budget_total: Double? = null,
    val gender_hint: String? = null,
    val sub_queries: List<SubQueryWire> = emptyList(),
)

@Serializable
data class SubQueryWire(
    val label: String,
    val query: String,
)

// ----- UI 层 -----

@Serializable
enum class Role { User, Assistant }

/**
 * 一条消息的 UI 状态。
 * 文本流式拼接 + 商品卡片懒加载 都通过更新这个对象的 mutable 字段触发重组。
 *
 * 标 @Serializable 是为了 [ConversationStore] 能把它整段落盘到 JSON。
 * 注意 imageBase64 — 多模态消息会带一张几十 KB 的 base64 字符串进来，
 * 多张图累计可能让会话 JSON 变大，但目前演示场景一个会话不会拍太多张，先这么存。
 */
@Serializable
data class UiMessage(
    val id: String,
    val role: Role,
    val text: String = "",
    val isStreaming: Boolean = false,
    /** 流式首事件给到的检索元数据，UI 在文本上方渲染"正在浏览：xxx"提示 */
    val retrieved: List<RetrievedItem> = emptyList(),
    /** 已从文本中解析出的商品卡片（按 [[PRODUCT:xxx]] 出现顺序，去重） */
    val productIds: List<String> = emptyList(),
    val errorMessage: String? = null,
    /** 用户消息附带的图片缩略图（base64），多模态拍照搜商品用 */
    val imageBase64: String? = null,
    /** 多模态：VLM 抽出的关键词，渲染在用户图片下方作"正在按这些关键词搜：xxx" */
    val visionKeywords: String? = null,
    /** 对比模式结构化数据。非空时 MessageBubble 渲染 ComparisonTable + RadarChart。 */
    val compareData: CompareData? = null,
    /** 话题分隔标记。某条消息上挂 true，则它在 UI 上方渲染一条"开始了新话题"的淡分割。
     *  与商品/对比/约束都正交——纯 UI 提示，不进 LLM。 */
    val isTopicBoundary: Boolean = false,
    /**
     * 思考过程（流式累计）：每收到一个 event=thinking_step 就 append 一条。
     * MessageBubble 顶部"💡 查看检索过程"折叠面板直接订阅它渲染时间线。
     */
    val thinkingSteps: List<ThinkingStepWire> = emptyList(),
    /**
     * 检索阶段汇总：策略 / 召回数 / top 分数。event=retrieved 一次性下发后填充。
     * 与 retrieved（仅商品列表）解耦：这里给面板用，retrieved 给商品卡片用。
     */
    val retrievedSummary: RetrievedSummaryWire? = null,
    /**
     * 后端 SSE event=constraints 推到此条 assistant 消息时的快照。
     * 顶部全局 ConstraintTracker 仍订阅 ChatViewModel.constraints，
     * 这里挂一份是为了让"思考过程"面板能展示该轮命中的硬过滤——历史消息回看也保留。
     */
    val constraintsSnapshot: ConstraintSnapshotWire? = null,
    /**
     * 主动澄清气泡。后端判定 query 歧义高时下发问题 + 选项；
     * 用户点其中一个选项后：clarificationSelected 记录已选文本（每题独立）+
     *  整组变灰不可点击，客户端把"原始 query + 选项文本"作为新一轮 user 消息重新发请求。
     * - clarification: 问题列表（非空时 MessageBubble 渲染气泡组件）
     * - clarificationSelected: 与 clarification.questions 等长，元素是用户在该题选中的 option，
     *   还没点的为 null
     * - clarificationOriginalQuery: 触发澄清的那条 user 原话，用于点击后拼出新 query
     */
    val clarification: ClarificationWire? = null,
    val clarificationSelected: List<String?> = emptyList(),
    val clarificationOriginalQuery: String = "",
    /**
     * 场景化组合推荐结果。来自 /chat/stream/scene 的 event=combo_result。
     * 非空时 MessageBubble 用 ComboCardView 替代普通文本气泡 + 商品横滑列表。
     */
    val combo: ComboDataWire? = null,
)
