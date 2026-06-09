package com.example.shopguide

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 对话状态管理。
 *
 * 关键设计：
 *  1) messages 是单一可信源，UI 通过 collectAsState 订阅
 *  2) 流式更新：每收到一个 token 就改 messages 列表里最后一个 assistant 消息的 text 字段
 *     —— 触发 LazyColumn 局部重组，看到逐字打字效果
 *  3) [[PRODUCT:xxx]] 解析在每次 token 拼接后做一次：发现新 id 就追加到 productIds，
 *     UI 看到 productIds 变化就开始拉商品详情、渲染卡片
 *  4) 自动话题隔离（D20）：每次 send 前用 [ConversationTopicManager.detectConversationMode]
 *     判定追问 vs 新话题。新话题自动重置 messages 滑窗起点 + constraints + topicSummary，
 *     用户无需手动点"新对话"。判定结果通过 _topicChanged 一次性事件给 UI 渲染淡提示。
 *     消息上下文用 topic 内最近 [ConversationTopicManager.RECENT_MESSAGE_WINDOW] 条 + topicSummary
 *     注入，避免 prompt 无限膨胀。商品缓存独立于话题，跨话题保留。
 *  5) 多会话（仿 ChatGPT 左侧栏）：[conversations] 是会话列表，[currentId] 指当前会话。
 *     上面 1)~4) 描述的所有"当前会话状态"在切换/删除时整体替换，并落盘到
 *     [ConversationStore]。商品缓存 [productCache] 是 ViewModel 单例字段，跨会话共享，
 *     避免切回旧会话时所有商品卡片要重新拉一遍。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repo = ChatRepository()

    /** 一次性提示事件（toast 用）。Composable 侧 collect 后立即清空。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun consumeToast() { _toast.value = null }

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 录音状态。Idle=未录, Recording=正在录, Recognizing=已停止+正在调 ASR。 */
    enum class RecordState { Idle, Recording, Recognizing }
    private val _recordState = MutableStateFlow(RecordState.Idle)
    val recordState: StateFlow<RecordState> = _recordState.asStateFlow()
    private val recorder = AudioRecorder()

    /** 商品详情缓存——跨会话共享。切回旧会话不用重新拉所有卡片。 */
    private val productCache = mutableMapOf<String, ProductWire>()
    private val _products = MutableStateFlow<Map<String, ProductWire>>(emptyMap())
    val products: StateFlow<Map<String, ProductWire>> = _products.asStateFlow()

    /**
     * 当前对话累计的"硬约束"：每收到一次 SSE event=constraints，就用最新值覆盖。
     * UI 输入框上方的 ConstraintTracker 直接订阅它渲染 chip。
     * null 表示本次对话还没有命中过任何结构化约束（不渲染 chip 行）。
     */
    private val _constraints = MutableStateFlow<ConstraintSnapshotWire?>(null)
    val constraints: StateFlow<ConstraintSnapshotWire?> = _constraints.asStateFlow()

    private var streamJob: Job? = null

    private val productTagRegex = Regex("""\[\[PRODUCT:([a-zA-Z0-9_]+)]]""")
    /** 对比模式 JSON 内联块的"起手"部分。真正的 JSON 边界用花括号配平自己解析，
     *  正则只负责定位起点 — 因为 LLM 经常把 `dimensions: [..., "适用]"]` 这种含 `]]`
     *  的字符串塞进 JSON，非贪婪正则会断在错误位置。
     */
    private val compareDataPrefix = Regex("""\[\[COMPARE_DATA:""")
    private val compareJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * 助手消息流式结束时触发；UI 层订阅它以决定是否自动朗读。
     * 用 SharedFlow 是因为同一个 id 在不同消息里不会重复触发，但需要"事件流"语义而非"最新值"。
     */
    private val _assistantFinished = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
    val assistantFinished: kotlinx.coroutines.flow.SharedFlow<Pair<String, String>> = _assistantFinished

    // ---------- 自动话题隔离（D20） ----------

    /** 当前话题主线 — 通常是该 topic 第一条 user 消息。送给后端做检索锚点。 */
    private var topicSummary: String = ""

    /**
     * 当前 topic 在 messages 列表里的起始 index：
     * 构造发送给 LLM 的 history 时只取 messages.subList(topicStartIndex, ..)，
     * 保证旧话题不污染新话题。判定 sameTopic=false 时把它推到本轮 user msg。
     */
    private var topicStartIndex: Int = 0

    /**
     * 一次性事件：刚刚检测到话题切换。UI 订阅后弹一个淡提示"开始了新话题"，
     * 不打断流，不弹 modal。Pair<刚发的 user msgId, 检测原因>。
     */
    private val _topicChanged = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 2)
    val topicChanged: kotlinx.coroutines.flow.SharedFlow<Pair<String, String>> = _topicChanged

    /**
     * 一次性"强制 sameTopic"标志：用于 chip × 撤销约束触发的 send。
     * 撤销文本（如"可以接受 耐克"、"不限价格"）跟历史 query 关键词重叠很低，
     * 会被 [ConversationTopicManager] 误判为新话题，连带把其他约束都清掉。
     * 设为 true 时，下一次 send 跳过 detector 强制走追问路径。
     */
    private var forceSameTopicOnNextSend: Boolean = false

    /**
     * 打字机节奏：豆包-Seed-2.0-lite 思考完会一次性吐光所有 token，
     * 客户端用环形 buffer 控制节奏，每 ~50ms 释放 1 个字符到 UI，
     * 实现"豆包同款"的逐字打字效果。
     */
    private val pendingChars = StringBuilder()
    private var typewriterJob: Job? = null
    private val charIntervalMs = 25L  // 25ms/字 ≈ 40 字/秒，接近人眼舒适阅读速度

    // ---------- 多会话（仿 ChatGPT 左侧历史） ----------

    private val _conversations = MutableStateFlow<List<ConversationStore.ConversationMeta>>(emptyList())
    val conversations: StateFlow<List<ConversationStore.ConversationMeta>> = _conversations.asStateFlow()

    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    init {
        bootstrap()
    }

    /**
     * 启动加载：读 index，最新一条会话作为当前；没有任何会话则起一个空白新会话
     * （但暂不落盘，等用户真的发了第一条消息再写文件，避免每次冷启动堆一堆空对话）。
     */
    private fun bootstrap() {
        viewModelScope.launch {
            val index = withContext(Dispatchers.IO) { ConversationStore.loadIndex(appContext) }
            _conversations.value = index
            if (index.isNotEmpty()) {
                loadConversation(index.first().id)
            } else {
                startEmptyInMemoryConversation()
            }
        }
    }

    /**
     * 在内存里起一个新的空会话 — 不写文件。
     * 真正落盘发生在 [persistCurrentIfNeeded]，触发条件是当前会话有非空消息。
     */
    private fun startEmptyInMemoryConversation() {
        _currentId.value = UUID.randomUUID().toString()
        _messages.value = emptyList()
        topicSummary = ""
        topicStartIndex = 0
        _constraints.value = null
    }

    private suspend fun loadConversation(id: String) {
        val data = withContext(Dispatchers.IO) { ConversationStore.load(appContext, id) }
        if (data == null) {
            // 文件丢了/解析失败：当成新会话
            startEmptyInMemoryConversation()
            return
        }
        _currentId.value = data.id
        _messages.value = data.messages
        topicSummary = data.topicSummary
        topicStartIndex = data.topicStartIndex
        _constraints.value = data.constraints
        // 切回旧会话时，把它引用过的商品按需补拉，重新渲染卡片
        data.messages.flatMap { it.productIds }.distinct().forEach { ensureProductLoaded(it) }
    }

    /** 用户点"新建对话"。先把当前会话存盘（若有内容），再开一个空白的。 */
    fun newConversation() {
        if (_busy.value) cancel()  // 流式中也允许切，停掉就行
        persistCurrentIfNeeded()
        startEmptyInMemoryConversation()
    }

    /** 抽屉点击切换。如果点的就是当前会话则忽略。 */
    fun selectConversation(id: String) {
        if (id == _currentId.value) return
        if (_busy.value) cancel()
        persistCurrentIfNeeded()
        viewModelScope.launch { loadConversation(id) }
    }

    /**
     * 删除指定会话。
     * 如果删除的是当前会话：
     *   - 还有别的会话 → 加载最新的一个
     *   - 没了            → 起一个空白的内存会话（保持 UI 始终有可输入界面）
     */
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ConversationStore.delete(appContext, id) }
            val newIndex = withContext(Dispatchers.IO) { ConversationStore.loadIndex(appContext) }
            _conversations.value = newIndex
            if (id == _currentId.value) {
                if (_busy.value) cancel()
                if (newIndex.isNotEmpty()) {
                    loadConversation(newIndex.first().id)
                } else {
                    startEmptyInMemoryConversation()
                }
            }
        }
    }

    /**
     * 写盘 + 同步刷新会话列表。空会话（用户开了但没发任何消息）跳过——
     * 这样冷启动新建的内存空会话不会污染历史列表。
     */
    private fun persistCurrentIfNeeded() {
        val id = _currentId.value ?: return
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        val firstUserText = msgs.firstOrNull { it.role == Role.User }?.text.orEmpty()
        val title = ConversationStore.titleFromFirstMessage(firstUserText)
        val data = ConversationStore.ConversationData(
            id = id,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = msgs,
            topicSummary = topicSummary,
            topicStartIndex = topicStartIndex,
            constraints = _constraints.value,
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ConversationStore.save(appContext, data) }
            _conversations.value = withContext(Dispatchers.IO) {
                ConversationStore.loadIndex(appContext)
            }
        }
    }

    fun onInputChange(text: String) {
        _input.value = text
    }

    /**
     * 命中"场景化组合推荐"关键词的判定 — 用户句子像"度假/送礼/搭配/运动套装"等组合需求时走 /chat/stream/scene。
     * 仅做粗筛，更细的 SceneType 由后端 scene_detector.py 决定。
     * 一旦命中，本轮直接走 sceneChatStream — 不会经过普通 RAG / clarification 路径。
     */
    private val sceneKeywordRegex = Regex(
        "去[一-鿿A-Za-z]{1,8}(?:玩|度假|出差|旅游|旅行)" +
            "|度假|出行|旅游|出差|旅行|出去玩|徒步|登山|爬山" +
            "|送礼|礼物|礼盒|送给|送女友|送男友|送爸|送妈|送朋友|送闺蜜|送同事|送老婆|送老公|送老板" +
            "|搭配|穿搭|搭一套|一套|成套|护肤套装|穿什么" +
            "|健身|跑步|夜跑|晨跑|马拉松|瑜伽|健身房"
    )

    private fun looksLikeScene(query: String): Boolean = sceneKeywordRegex.containsMatchIn(query)

    fun send() {
        val q = _input.value.trim()
        if (q.isEmpty() || _busy.value) return
        _input.value = ""

        // —— 场景化组合推荐路径：命中关键词直接走 /chat/stream/scene ——
        if (looksLikeScene(q)) {
            sendSceneRequest(q)
            return
        }

        // 1) 话题判定：在 append 之前看历史，决定是否开新 topic
        val decision = decideTopic(q)

        // 2) 构造 user 消息（如果开新 topic 则打话题分隔旗标，UI 渲染淡提示）
        val userMsg = UiMessage(
            id = UUID.randomUUID().toString(),
            role = Role.User,
            text = q,
            isTopicBoundary = !decision.sameTopic && _messages.value.isNotEmpty(),
        )
        val assistantId = appendUserAndStartAssistant(userMsg)

        // 3) 应用话题切换的副作用：重置滑窗起点 + 清约束 + 重置 summary
        applyTopicDecision(decision, userMsg)

        // 4) 仅取当前 topic 内的 history 发给 LLM；topicSummary 单独通道传
        val history = currentTopicHistory(excludeAssistantId = assistantId)
        // prior_price_max：把当前 chip 上的价格上限带给后端，让"再便宜点"能继承上一轮基准
        val priorPriceMax = _constraints.value?.price_max
        // 当前 topic 内已展示给用户的所有商品 id — 用户说"再来几款"时后端会剔重
        val excludePids = currentTopicShownPids()
        runStream(assistantId, repo.chatStream(history, topicSummary, priorPriceMax, excludePids))
    }

    /**
     * 场景化组合推荐发送链路 — 与 send() 同样把 user 消息 / 占位 assistant 入栈，
     * 但走 sceneChatStream，由 collectSceneStream 把 combo_result 直接挂到 assistant 消息。
     *
     * 话题副作用：场景请求视为新话题首句（套装搭配是一个独立的强意图，
     * 不该继承上一轮的"再便宜点 / 不要 X"约束）。
     */
    private fun sendSceneRequest(q: String) {
        val decision = ConversationTopicManager.Decision(
            sameTopic = false,
            confidence = 1.0,
            reason = "scene combo request ⇒ new topic",
        )
        val userMsg = UiMessage(
            id = UUID.randomUUID().toString(),
            role = Role.User,
            text = q,
            isTopicBoundary = _messages.value.isNotEmpty(),
        )
        val assistantId = appendUserAndStartAssistant(userMsg)
        applyTopicDecision(decision, userMsg)
        // history 给 LLM 当上下文（最近几条），不参与场景识别
        val history = currentTopicHistory(excludeAssistantId = assistantId, excludeUserId = userMsg.id)
        collectSceneStream(assistantId, repo.sceneChatStream(q, history))
    }

    private fun collectSceneStream(
        assistantId: String,
        flow: Flow<ChatRepository.StreamEvent>,
    ) {
        // 场景流没有 token，不需要 typewriter；用独立 job 收集事件。
        typewriterJob?.cancel()
        pendingChars.clear()

        streamJob = viewModelScope.launch {
            var sawError = false
            try {
                flow.collect { ev ->
                    when (ev) {
                        is ChatRepository.StreamEvent.SceneInfo -> {
                            Log.d("ChatVM", "scene info: ${ev.info.scene_label} subs=${ev.info.sub_queries.size}")
                            // 直接挂到 thinkingSteps 不太合适——场景信息本身不是阶段；这里暂只 log。
                        }
                        is ChatRepository.StreamEvent.ThinkingStep -> {
                            updateAssistant(assistantId) {
                                it.copy(thinkingSteps = it.thinkingSteps + ev.step)
                            }
                        }
                        is ChatRepository.StreamEvent.ComboResult -> {
                            Log.d("ChatVM", "combo: ${ev.data.items.size} items, scene=${ev.data.scene}")
                            // 把 combo 商品 id 也写入 productIds 让现有的"已购买/分享"等逻辑兼容
                            val pids = ev.data.items.map { it.product_id }.distinct()
                            // 同步把 combo 内商品塞进 productCache，便于后续追问引用
                            ev.data.items.forEach { item ->
                                productCache[item.product_id] = item.product
                            }
                            _products.value = productCache.toMap()
                            updateAssistant(assistantId) {
                                it.copy(combo = ev.data, productIds = pids)
                            }
                        }
                        ChatRepository.StreamEvent.Done -> {
                            // 流结束 — 走与普通流一样的收尾
                        }
                        is ChatRepository.StreamEvent.Error -> {
                            Log.e("ChatVM", "scene stream error: ${ev.message}")
                            sawError = true
                            updateAssistant(assistantId) { it.copy(errorMessage = ev.message) }
                        }
                        else -> Unit  // scene 流不预期收到 token / constraints / vision / retrieved
                    }
                }
            } finally {
                updateAssistant(assistantId) { it.copy(isStreaming = false) }
                _busy.value = false
                if (!sawError) persistCurrentIfNeeded()
            }
        }
    }

    /**
     * 多模态拍照搜商品。userText 是相机/相册附带的可选补充文字；image 是已压缩并 base64 编码后的图。
     */
    fun sendImage(imageBase64: String, userText: String) {
        if (_busy.value) return
        _input.value = ""

        // 拍照搜默认视为新话题：图像本身就是新查询锚点，强行继承约束反而干扰
        val decision = ConversationTopicManager.Decision(
            sameTopic = false,
            confidence = 1.0,
            reason = "image input ⇒ new topic",
        )
        val userMsg = UiMessage(
            id = UUID.randomUUID().toString(),
            role = Role.User,
            text = userText,
            imageBase64 = imageBase64,
            isTopicBoundary = _messages.value.isNotEmpty(),
        )
        val assistantId = appendUserAndStartAssistant(userMsg)
        applyTopicDecision(decision, userMsg)
        val history = currentTopicHistory(
            excludeAssistantId = assistantId,
            excludeUserId = userMsg.id,
        )
        runStream(assistantId, repo.multimodalChatStream(imageBase64, userText, history), userMsgId = userMsg.id)
    }

    /** 把用户消息 + 占位 assistant 消息塞进 messages，返回 assistantId。 */
    private fun appendUserAndStartAssistant(userMsg: UiMessage): String {
        val assistantId = UUID.randomUUID().toString()
        val assistantMsg = UiMessage(id = assistantId, role = Role.Assistant, isStreaming = true)
        _messages.update { it + userMsg + assistantMsg }
        _busy.value = true
        return assistantId
    }

    /**
     * 取当前 topic 内的最近若干消息发给 LLM。
     * 切片来源是 messages.subList(topicStartIndex, ..)，再取末尾 RECENT_MESSAGE_WINDOW 条。
     * 这样：
     *   - 跨话题历史完全不进 prompt（topicStartIndex 隔离）
     *   - 同话题内超长对话也只保留最近窗口（节省 token，避免越聊越慢）
     */
    private fun currentTopicHistory(
        excludeAssistantId: String,
        excludeUserId: String? = null,
    ): List<ChatMessageWire> {
        val all = _messages.value
        val start = topicStartIndex.coerceIn(0, all.size)
        val topicSlice = all.subList(start, all.size)
        return topicSlice
            .filter { it.id != excludeAssistantId && it.id != excludeUserId }
            .takeLast(ConversationTopicManager.RECENT_MESSAGE_WINDOW)
            .map {
                ChatMessageWire(
                    role = if (it.role == Role.User) "user" else "assistant",
                    content = it.text,
                )
            }
    }

    /**
     * 当前 topic 内所有 assistant 消息已展示过的商品 id（按出现顺序去重）。
     * 用户在话题内说"再来几款"时，后端会从召回里剔除这批，避免重复推荐。
     */
    private fun currentTopicShownPids(): List<String> {
        val all = _messages.value
        val start = topicStartIndex.coerceIn(0, all.size)
        val seen = LinkedHashSet<String>()
        for (i in start until all.size) {
            val m = all[i]
            if (m.role == Role.Assistant) seen.addAll(m.productIds)
        }
        return seen.toList()
    }

    /** 看历史决定本轮是追问还是新话题。append 前调用。
     *  forceSameTopicOnNextSend=true 时直接返回 sameTopic 决定，跳过 detector，
     *  用于 chip × 撤销约束这种"系统自动构造的句子"。 */
    private fun decideTopic(query: String): ConversationTopicManager.Decision {
        if (forceSameTopicOnNextSend) {
            forceSameTopicOnNextSend = false
            return ConversationTopicManager.Decision(
                sameTopic = true,
                confidence = 1.0,
                reason = "forced (constraint-dismissal)",
            )
        }
        val all = _messages.value
        val recentUserText = all
            .subList(topicStartIndex.coerceIn(0, all.size), all.size)
            .lastOrNull { it.role == Role.User }
            ?.text
        return ConversationTopicManager.detectConversationMode(
            newQuery = query,
            topicSummary = topicSummary,
            recentUserText = recentUserText,
        )
    }

    /**
     * 应用话题判定的副作用：
     *   sameTopic=true  → 仅刷新 topicSummary（首句锚点保持）
     *   sameTopic=false → 把 topicStartIndex 推到本轮 user msg、清约束、重置 summary、发 UI 事件
     */
    private fun applyTopicDecision(
        decision: ConversationTopicManager.Decision,
        userMsg: UiMessage,
    ) {
        if (decision.sameTopic) {
            topicSummary = ConversationTopicManager.updateSummary(topicSummary, userMsg.text)
            return
        }
        // 新话题：找到 userMsg 在 messages 里的 index 当作新起点
        val newStart = _messages.value.indexOfFirst { it.id == userMsg.id }
        if (newStart >= 0) topicStartIndex = newStart
        topicSummary = userMsg.text.trim().take(80)
        _constraints.value = null
        if (_messages.value.size > 1) {
            // 只有真的存在历史时才发"开始新话题"事件，避免首次发消息也提示
            _topicChanged.tryEmit(userMsg.id to decision.reason)
        }
        Log.d("ChatVM", "topic switch reason=${decision.reason} conf=${decision.confidence}")
    }

    /** 启动 typewriter + 收 stream 事件，多模态/纯文本共用。 */
    private fun runStream(
        assistantId: String,
        flow: Flow<ChatRepository.StreamEvent>,
        userMsgId: String? = null,
    ) {
        pendingChars.clear()
        typewriterJob?.cancel()
        var streamFinished = false

        typewriterJob = viewModelScope.launch {
            while (true) {
                val ch: Char? = synchronized(pendingChars) {
                    if (pendingChars.isNotEmpty()) {
                        val c = pendingChars[0]
                        pendingChars.deleteCharAt(0)
                        c
                    } else null
                }
                if (ch != null) {
                    updateAssistant(assistantId) { current ->
                        val newText = current.text + ch
                        val ids = productTagRegex.findAll(newText).map { it.groupValues[1] }.distinct().toList()
                        ids.forEach { id -> ensureProductLoaded(id) }
                        // 对比 JSON 一旦完整出现就解析；解析失败保持上次值（流式过程中很正常）
                        val parsedCompare = parseCompareData(newText) ?: current.compareData
                        current.copy(text = newText, productIds = ids, compareData = parsedCompare)
                    }
                    delay(charIntervalMs)
                } else {
                    if (streamFinished) break
                    delay(30)
                }
            }
            // 流式真正"打字"完成 — 这里发事件给 UI 层，触发自动朗读
            val finalText = _messages.value.firstOrNull { it.id == assistantId }?.text.orEmpty()
            updateAssistant(assistantId) { it.copy(isStreaming = false) }
            _busy.value = false
            if (finalText.isNotBlank()) {
                _assistantFinished.tryEmit(assistantId to finalText)
            }
            // 一轮对话结束：把当前会话整段写盘 + 刷新左侧列表的 updatedAt/title
            persistCurrentIfNeeded()
        }

        streamJob = viewModelScope.launch {
            flow.collect { ev ->
                when (ev) {
                    is ChatRepository.StreamEvent.Constraints -> {
                        Log.d("ChatVM", "constraints: ${ev.snapshot}")
                        _constraints.value = ev.snapshot
                        // 同步把当前轮的硬约束挂到本条 assistant 消息上，
                        // 让"思考过程"面板在历史回看时也能看到本轮命中的过滤条件。
                        updateAssistant(assistantId) { it.copy(constraintsSnapshot = ev.snapshot) }
                    }
                    is ChatRepository.StreamEvent.Vision -> {
                        Log.d("ChatVM", "vision keywords: ${ev.keywords} (${ev.elapsedMs}ms)")
                        if (userMsgId != null) {
                            updateMessage(userMsgId) { it.copy(visionKeywords = ev.keywords) }
                        }
                    }
                    is ChatRepository.StreamEvent.Retrieved -> {
                        Log.d("ChatVM", "retrieved: ${ev.summary.count} items via ${ev.summary.strategy}")
                        updateAssistant(assistantId) {
                            it.copy(retrieved = ev.summary.items, retrievedSummary = ev.summary)
                        }
                        ev.summary.items.forEach { item -> ensureProductLoaded(item.product_id) }
                    }
                    is ChatRepository.StreamEvent.ThinkingStep -> {
                        Log.d("ChatVM", "thinking_step: ${ev.step.step} ${ev.step.duration_ms}ms")
                        updateAssistant(assistantId) {
                            it.copy(thinkingSteps = it.thinkingSteps + ev.step)
                        }
                    }
                    is ChatRepository.StreamEvent.Token -> {
                        synchronized(pendingChars) { pendingChars.append(ev.text) }
                    }
                    is ChatRepository.StreamEvent.Clarification -> {
                        Log.d("ChatVM", "clarification: ${ev.payload.questions.size} qs, missing=${ev.payload.missing}")
                        // 找出最近一条 user 消息文本作为"原始 query"，让用户点选项后能拼上
                        val originalQuery = _messages.value
                            .lastOrNull { it.role == Role.User }
                            ?.text.orEmpty()
                        updateAssistant(assistantId) {
                            it.copy(
                                clarification = ev.payload,
                                clarificationSelected = List(ev.payload.questions.size) { null },
                                clarificationOriginalQuery = originalQuery,
                            )
                        }
                    }
                    ChatRepository.StreamEvent.Done -> {
                        Log.d("ChatVM", "stream done; ${pendingChars.length} chars pending in typewriter")
                        streamFinished = true
                    }
                    is ChatRepository.StreamEvent.Error -> {
                        Log.e("ChatVM", "stream error: ${ev.message}")
                        streamFinished = true
                        updateAssistant(assistantId) { it.copy(errorMessage = ev.message) }
                    }
                    // /chat/stream 不会下发这两个事件（仅 /chat/stream/scene 用），保留分支让 when 穷尽
                    is ChatRepository.StreamEvent.SceneInfo,
                    is ChatRepository.StreamEvent.ComboResult -> Unit
                }
            }
        }
    }

    private fun ensureProductLoaded(productId: String) {
        if (productId in productCache) return
        viewModelScope.launch {
            val p = repo.fetchProduct(productId) ?: return@launch
            productCache[productId] = p
            _products.value = productCache.toMap()
        }
    }

    private inline fun updateAssistant(id: String, transform: (UiMessage) -> UiMessage) {
        updateMessage(id, transform)
    }

    private inline fun updateMessage(id: String, transform: (UiMessage) -> UiMessage) {
        _messages.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    /**
     * 已获权限后调用：第一次点开始录，第二次点停止+识别。
     * 识别结果填到输入框，由用户决定是直接发还是改一改再发。
     */
    fun toggleRecording() {
        when (_recordState.value) {
            RecordState.Idle -> {
                if (_busy.value) return
                if (recorder.start()) {
                    _recordState.value = RecordState.Recording
                } else {
                    Log.e("ChatVM", "AudioRecorder.start() 返回 false")
                }
            }
            RecordState.Recording -> {
                _recordState.value = RecordState.Recognizing
                viewModelScope.launch {
                    val wav = recorder.stopAndGetWav()
                    if (wav == null) {
                        _recordState.value = RecordState.Idle
                        _toast.value = "录音失败，请重试"
                        return@launch
                    }
                    val text = repo.recognizeAudio(wav)
                    _recordState.value = RecordState.Idle
                    if (!text.isNullOrBlank()) {
                        val merged = listOf(_input.value.trim(), text.trim())
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                        _input.value = merged
                    } else {
                        // null = 网络/服务端错；空串 = 没识别出文字（环境噪声/录得太短）
                        _toast.value = if (text == null) "语音识别失败，请检查网络后重试" else "没识别到内容，再说一遍试试"
                    }
                }
            }
            RecordState.Recognizing -> {
                // 识别中再点忽略
            }
        }
    }

    fun cancelRecording() {
        if (_recordState.value == RecordState.Recording) {
            recorder.cancel()
            _recordState.value = RecordState.Idle
        }
    }

    /** 长按 mic 按下：开始录音。已在录则忽略。 */
    fun pressToTalkStart() {
        if (_busy.value) return
        if (_recordState.value != RecordState.Idle) return
        if (recorder.start()) {
            _recordState.value = RecordState.Recording
        } else {
            _toast.value = "录音启动失败"
        }
    }

    /**
     * 松手：停止录音 → ASR → 直接发送（跳过输入框中转），实现"按住说话，松手即问"。
     * 如果识别失败则只弹 toast，不会改输入框。
     */
    fun pressToTalkEndAndSend() {
        if (_recordState.value != RecordState.Recording) return
        _recordState.value = RecordState.Recognizing
        viewModelScope.launch {
            val wav = recorder.stopAndGetWav()
            if (wav == null) {
                _recordState.value = RecordState.Idle
                _toast.value = "录得太短了，再按住久一点"
                return@launch
            }
            val text = repo.recognizeAudio(wav)
            _recordState.value = RecordState.Idle
            if (text.isNullOrBlank()) {
                _toast.value = if (text == null) "语音识别失败，请检查网络" else "没识别到内容，再说一遍"
                return@launch
            }
            // 直接发送：把识别文本塞进 input 然后调 send()，复用现有发送链路
            _input.value = text.trim()
            send()
        }
    }

    fun cancel() {
        streamJob?.cancel()
        typewriterJob?.cancel()
        // 占位 assistant 气泡可能还挂着 isStreaming=true，把它清理掉避免一直转圈
        _messages.update { list ->
            list.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
        }
        _busy.value = false
    }

    /**
     * 用户点 chip 上的 × 主动撤销某条已抽到的约束。
     * 实现：把"忽略 XX 约束"作为一条新 user 消息塞进发送队列，依赖后端正则识别取消语义。
     * 同时本地立刻把对应字段从 _constraints 拿掉，避免 chip 依然亮着造成歧义。
     */
    fun dismissConstraint(kind: ConstraintKind, value: String? = null) {
        val current = _constraints.value ?: return
        val updated = when (kind) {
            ConstraintKind.PriceMax -> current.copy(price_max = null)
            ConstraintKind.BrandRequired -> current.copy(brand_required = null)
            ConstraintKind.BrandExclude ->
                current.copy(brand_excludes = current.brand_excludes.filter { it != value })
            ConstraintKind.AttrExclude ->
                current.copy(attr_excludes = current.attr_excludes.filter { it != value })
        }
        _constraints.value = updated.takeUnless { it.isAllEmpty() }

        val cancelText = when (kind) {
            ConstraintKind.PriceMax -> "不限价格"
            ConstraintKind.BrandRequired -> "不限品牌"
            ConstraintKind.BrandExclude -> "可以接受 ${value ?: ""}"
            ConstraintKind.AttrExclude -> "可以接受 ${value ?: ""}"
        }
        _input.value = cancelText.trim()
        // 强制视为追问，避免 detector 把"可以接受 X"判成新话题、误清掉其他约束
        forceSameTopicOnNextSend = true
        send()
    }

    enum class ConstraintKind { PriceMax, BrandRequired, BrandExclude, AttrExclude }

    /**
     * 用户在 ChatScreen 上点击澄清气泡里的某个选项时调用。
     *  - 在原 assistant 消息上把 questionIndex 题的选中态置为 option（用于 UI 变灰/填充）
     *  - 把 "原始 query + 选项文本" 拼成新 user 消息发起请求
     *
     * 设计说明：
     *  - 一条 assistant 澄清消息只允许触发一次重发（busy=true 时直接忽略），避免连点。
     *  - 强制 sameTopic：澄清场景就是话题首句的延续，不该被 detector 判成新话题。
     */
    fun answerClarification(messageId: String, questionIndex: Int, option: String) {
        if (_busy.value) return
        val target = _messages.value.firstOrNull { it.id == messageId } ?: return
        val clarif = target.clarification ?: return
        if (questionIndex !in clarif.questions.indices) return
        // 已经选过了就忽略（整组应已变灰，但兜底一层防误触）
        if (target.clarificationSelected.any { it != null }) return

        val newSelected = target.clarificationSelected.toMutableList().apply {
            // 列表长度可能因 wire 默认值短了，先补齐
            while (size < clarif.questions.size) add(null)
            this[questionIndex] = option
        }
        updateMessage(messageId) { it.copy(clarificationSelected = newSelected) }

        val originalQuery = target.clarificationOriginalQuery.ifBlank {
            _messages.value
                .lastOrNull { it.role == Role.User }
                ?.text.orEmpty()
        }
        val combined = listOf(originalQuery.trim(), option.trim())
            .filter { it.isNotEmpty() }
            .joinToString("，")
        if (combined.isEmpty()) return

        // 走与 send() 等价的发送链路，但跳过 detector（澄清属于追问的延续）
        _input.value = combined
        forceSameTopicOnNextSend = true
        send()
    }

    /**
     * 流式过程中尝试解析 [[COMPARE_DATA:{...}]] 内联 JSON。
     * 关键点：JSON 内部经常含字符串里的 `]` 或转义符，简单的非贪婪正则会断错位置。
     * 这里手动做花括号配平 — 从 `[[COMPARE_DATA:` 后第一个 `{` 开始一格一格扫，
     * 同时处理字符串和转义，找到匹配的 `}` 才认为 JSON 完整，再尝试 decode。
     * 解析失败 / 还没完整 / 没出现都返回 null。
     */
    private fun parseCompareData(text: String): CompareData? {
        val match = compareDataPrefix.find(text) ?: return null
        val openIdx = text.indexOf('{', startIndex = match.range.last + 1)
        if (openIdx < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        var endIdx = -1
        for (i in openIdx until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { endIdx = i; break }
                }
            }
        }
        if (endIdx < 0) return null
        val json = text.substring(openIdx, endIdx + 1)
        return runCatching {
            compareJson.decodeFromString(CompareData.serializer(), json)
        }.onFailure {
            Log.w("ChatVM", "compareData decode failed: ${it.message}; payload=${json.take(200)}")
        }.getOrNull()
    }
}

private fun ConstraintSnapshotWire.isAllEmpty(): Boolean =
    price_max == null && brand_excludes.isEmpty() && attr_excludes.isEmpty() &&
        brand_required == null && !is_compare
