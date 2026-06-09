package com.example.shopguide

/**
 * 自动话题隔离（Automatic Topic Isolation）。
 *
 * 旧设计：每次发请求把全部历史消息塞进 prompt，新问题被旧约束污染。
 * 新设计：每条新输入先经 [detectConversationMode] 判定 sameTopic：
 *  - true  → 继承 recentMessages + activeConstraints + topicSummary
 *  - false → 自动开新 topic，丢弃历史 messages 和 constraints，仅保留商品缓存
 *
 * 检测逻辑（v1，纯启发式，零 LLM 调用，毫秒级）：
 *   1) 代词引用（"它/这个/那款/this/that"等）→ 必为追问
 *   2) 超短追问句（"为什么/展开说说/还有呢"）→ 必为追问
 *   3) 与最近 topicSummary + 最近一条 user 消息的关键词重叠率高 → 追问
 *   否则视为新话题。
 *
 * 后续可升级：
 *   - 用 BGE 句向量算 cosine 相似度（更稳，但要本地模型或一次后端调用）
 *   - 用 LLM zero-shot 二分类（最准但延迟高，建议 batched 异步）
 *   - vector memory 存历史 topic embedding，新输入做 top-1 召回判定
 */
object ConversationTopicManager {

    /** 代词引用：出现即视为强追问信号。 */
    private val PRONOUN_TOKENS = listOf(
        "它", "她", "他", "它们", "他们", "她们",
        "这个", "那个", "这款", "那款", "这种", "那种", "这些", "那些",
        "这俩", "那俩", "这几款", "那几款",
        // "对比一下这两款"、"前两个怎么样" 这类典型的追问主语
        "这两", "那两", "前两", "上两", "前面那", "上面那", "上一", "上面",
        "两款", "两个", "几款",
        "刚才", "前面", "之前那", "上一个", "刚刚",
        "this", "that", "these", "those", "they", "them", "it",
    )

    /**
     * 短句追问模板：q.length <= [SHORT_FOLLOWUP_MAX_LEN] 时按 contains 匹配，命中即追问。
     *
     * 关键加项：对比 / 比较 / 哪个好 / 选一个 这类对比/选择类问句。
     * 它们和上一轮 user 文本的关键词几乎不重叠（用户在说"对比一下"而不是再次说"耳机"），
     * jaccard 路径会判成新话题 — 必须在这里截胡。
     */
    private val FOLLOWUP_PHRASES = listOf(
        "为什么", "为啥", "怎么样", "怎么说", "然后呢", "还有呢", "还有吗",
        "展开说说", "详细说说", "再具体点", "举个例子",
        "风险呢", "缺点呢", "优点呢", "区别呢", "差别呢",
        "便宜点", "贵点", "再便宜", "再贵",
        "更轻", "更小", "更大", "更便宜",
        // 对比 / 选择类追问 — 用户继续聊上一轮推荐的商品
        "对比", "比较", "对比一下", "比较一下", "比一比", "比一下",
        "哪个好", "哪款好", "哪个更好", "哪款更好", "哪个值得", "哪款值得",
        "选哪个", "选哪款", "选一个", "买哪个", "买哪款", "推荐哪个", "推荐哪款",
        "更值得", "更推荐", "更划算", "更好",
        "区别", "差别", "差异",
        "why", "how", "and", "really", "compare", "vs",
    )

    /** [FOLLOWUP_PHRASES] 的句长上限。"对比一下这两款" 才 7 字，
     *  "对比一下这个和那个" 9 字 — 上限 8 太苛刻。给到 12 兼顾长度和误判风险。 */
    private const val SHORT_FOLLOWUP_MAX_LEN = 12

    /** 至少这么多 char 才考虑做关键词重叠；过短的句子直接看代词/模板。 */
    private const val MIN_LEN_FOR_OVERLAP = 4

    /** 关键词重叠阈值：jaccard ≥ 该值视为同话题。 */
    private const val OVERLAP_THRESHOLD = 0.18

    /** 一个 topic 中保留多少条最近消息进 prompt（user+assistant 各算一条）。 */
    const val RECENT_MESSAGE_WINDOW = 6

    data class Decision(
        val sameTopic: Boolean,
        val confidence: Double,
        val reason: String,
    )

    /**
     * 判定本轮输入是追问还是新话题。
     * @param newQuery       用户刚输入的内容（未 trim）
     * @param topicSummary   当前话题的主线（通常是 topic 首句的 user 消息）
     * @param recentUserText 最近一条 user 消息（可空）
     */
    fun detectConversationMode(
        newQuery: String,
        topicSummary: String,
        recentUserText: String?,
    ): Decision {
        val q = newQuery.trim()
        if (q.isEmpty()) {
            return Decision(sameTopic = true, confidence = 1.0, reason = "empty input")
        }
        // 没有任何上下文 → 一定是新话题
        if (topicSummary.isBlank() && recentUserText.isNullOrBlank()) {
            return Decision(sameTopic = false, confidence = 1.0, reason = "no prior context")
        }

        // 1) 代词
        for (p in PRONOUN_TOKENS) {
            if (q.contains(p, ignoreCase = true)) {
                return Decision(true, 0.95, "pronoun:$p")
            }
        }
        // 2) 短句追问模板（含对比/选择类）
        if (q.length <= SHORT_FOLLOWUP_MAX_LEN) {
            for (phrase in FOLLOWUP_PHRASES) {
                if (q.contains(phrase, ignoreCase = true)) {
                    return Decision(true, 0.9, "followup-phrase:$phrase")
                }
            }
        }
        // 3) 关键词重叠（以最近 user + topic summary 的并集做参考）
        if (q.length >= MIN_LEN_FOR_OVERLAP) {
            val ref = listOfNotNull(topicSummary.takeIf { it.isNotBlank() }, recentUserText)
                .joinToString(" ")
            if (ref.isNotBlank()) {
                val overlap = jaccard(tokenize(q), tokenize(ref))
                if (overlap >= OVERLAP_THRESHOLD) {
                    return Decision(true, overlap, "keyword-overlap:%.2f".format(overlap))
                }
                return Decision(false, 1 - overlap, "low-overlap:%.2f".format(overlap))
            }
        }
        // 兜底：当作新话题
        return Decision(false, 0.5, "default-new")
    }

    /**
     * 中文/英文混合粗分词：抽出长度 ≥ 2 的中文片段和长度 ≥ 2 的英文/数字 token。
     * 不接 jieba 是为了零依赖、零启动开销；电商语境下品类/品牌词大都 2-4 字，
     * 这种粗分对 jaccard 已经够用。
     */
    private fun tokenize(text: String): Set<String> {
        val out = mutableSetOf<String>()
        var i = 0
        val s = text.lowercase()
        while (i < s.length) {
            val c = s[i]
            when {
                c in '一'..'鿿' -> {
                    // 滑动窗口取 2-gram，捕获"跑鞋""敏感肌"这种复合词
                    var j = i
                    while (j < s.length && s[j] in '一'..'鿿') j++
                    val seg = s.substring(i, j)
                    if (seg.length >= 2) {
                        for (k in 0..seg.length - 2) out.add(seg.substring(k, k + 2))
                    }
                    i = j
                }
                c.isLetterOrDigit() -> {
                    var j = i
                    while (j < s.length && s[j].isLetterOrDigit() && s[j] !in '一'..'鿿') j++
                    val seg = s.substring(i, j)
                    if (seg.length >= 2) out.add(seg)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /**
     * 生成 / 更新当前话题的主线总结。v1 策略：
     *   - 如果还没有 summary，就用本轮 user 第一句作为锚点
     *   - 已有 summary 则保留（避免每轮变化导致 prompt 漂移）
     * 后续可改为 LLM 蒸馏 / running summary。
     */
    fun updateSummary(currentSummary: String, newUserText: String): String {
        if (currentSummary.isNotBlank()) return currentSummary
        return newUserText.trim().take(80)
    }
}
