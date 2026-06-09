package com.example.shopguide

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image as FoundationImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val recordState by vm.recordState.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val constraints by vm.constraints.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // toast 一次性事件：消费后立即清空，避免重组重复弹
    LaunchedEffect(toast) {
        toast?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.toggleRecording()
        else android.widget.Toast.makeText(context, "需要麦克风权限才能语音输入", android.widget.Toast.LENGTH_SHORT).show()
    }

    val tts = remember { TtsManager(context) }
    var ttsSpeakingId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(tts) {
        tts.setOnStateChange { id -> ttsSpeakingId = id }
        onDispose { tts.shutdown() }
    }

    // 语音导购模式：assistant 流式打字结束后自动朗读
    LaunchedEffect(Unit) {
        vm.assistantFinished.collect { (id, text) ->
            if (Config.autoTtsEnabled) {
                val clean = stripProductTags(text)
                if (clean.isNotBlank()) tts.speak(clean, id)
            }
        }
    }
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val captured = withContext(Dispatchers.IO) { ImageUtils.captureFromUri(context, uri) }
                if (captured != null) {
                    vm.sendImage(captured.base64, vm.input.value.trim())
                } else {
                    android.widget.Toast.makeText(context, "图片读取失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var showCamera by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showCamera = true
        else android.widget.Toast.makeText(context, "需要相机权限才能拍照搜商品", android.widget.Toast.LENGTH_SHORT).show()
    }
    val openCamera: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) showCamera = true
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    if (showCamera) {
        CameraScreen(
            onCancel = { showCamera = false },
            onConfirm = { captured ->
                showCamera = false
                vm.sendImage(captured.base64, vm.input.value.trim())
            },
        )
        return
    }

    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        ServerSettingsDialog(
            onDismiss = { showSettings = false },
            onSaved = { showSettings = false },
        )
    }

    // 自动话题隔离：检测到新话题时停掉 TTS（旧话题的朗读不该带到新话题里）
    LaunchedEffect(Unit) {
        vm.topicChanged.collect { (_, _) -> tts.stop() }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    pendingDeleteId?.let { id ->
        val title = conversations.firstOrNull { it.id == id }?.title ?: "该对话"
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「$title」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteConversation(id)
                    pendingDeleteId = null
                }) { Text("删除", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                currentId = currentId,
                onNew = {
                    vm.newConversation()
                    drawerScope.launch { drawerState.close() }
                },
                onSelect = { id ->
                    vm.selectConversation(id)
                    drawerScope.launch { drawerState.close() }
                },
                onDelete = { id -> pendingDeleteId = id },
            )
        },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ShopGuideTopBar(
                onMenu = { drawerScope.launch { drawerState.open() } },
                onNew = { vm.newConversation() },
                onSettings = { showSettings = true },
            )
        },
        modifier = Modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            if (messages.isEmpty()) {
                EmptyHint(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        Column {
                            if (msg.isTopicBoundary) {
                                TopicBoundaryDivider()
                                Spacer(Modifier.height(6.dp))
                            }
                            MessageBubble(
                                msg = msg,
                                products = products,
                                ttsSpeakingId = ttsSpeakingId,
                                onPlayTts = { id, text ->
                                    if (ttsSpeakingId == id) tts.stop() else tts.speak(text, id)
                                },
                                onClarifyOption = { qIdx, option ->
                                    vm.answerClarification(msg.id, qIdx, option)
                                },
                            )
                        }
                    }
                }
            }

            ConstraintTracker(
                snapshot = constraints,
                onDismiss = vm::dismissConstraint,
            )

            InputBar(
                text = input,
                busy = busy,
                recordState = recordState,
                onChange = vm::onInputChange,
                onSend = vm::send,
                onTakePhoto = openCamera,
                onPickFromGallery = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onMicPressStart = {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) vm.pressToTalkStart()
                    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                },
                onMicPressEnd = vm::pressToTalkEndAndSend,
                onMicPressCancel = vm::cancelRecording,
            )
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShopGuideTopBar(
    onMenu: () -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        navigationIcon = {
            IconButton(onClick = onMenu) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "历史对话",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "AI 导购助手",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Text(
                        "懂你需求的购物专家",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onNew) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "新建对话",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "服务器设置",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/**
 * 左侧抽屉：仿 ChatGPT 历史对话栏。
 *
 *  顶部一行 "新建对话"（永远在最上方，方便单手戳）
 *  下面 LazyColumn 列出所有会话；当前会话高亮；行尾"垃圾桶"按钮长按或点击删除。
 *
 *  视觉：宽度 ~280dp，紫色高亮当前项，标题最多 1 行省略号。
 */
@Composable
private fun ConversationDrawer(
    conversations: List<ConversationStore.ConversationMeta>,
    currentId: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(280.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            // 顶部标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotAvatar(size = 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "历史对话",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            // 新建对话按钮：粘在顶部、紫色描边胶囊
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    ),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                onClick = onNew,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "新建对话",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (conversations.isEmpty()) {
                Text(
                    "还没有历史对话\n输入第一句话就会自动保存～",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(conversations, key = { it.id }) { meta ->
                        ConversationRow(
                            meta = meta,
                            isCurrent = meta.id == currentId,
                            onSelect = { onSelect(meta.id) },
                            onDelete = { onDelete(meta.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    meta: ConversationStore.ConversationMeta,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    // 不用 NavigationDrawerItem，因为它整行响应一个 onClick，
    // 会拦截子节点（删除按钮）的点击。这里手写 Surface(onClick) + 子 IconButton。
    val bg = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surface
    val fg = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bg,
        shape = RoundedCornerShape(10.dp),
        onClick = onSelect,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                meta.title.ifBlank { "新对话" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除该对话",
                    tint = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun BotAvatar(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.SmartToy,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BotAvatar(size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            "你好，我是你的电商导购助手",
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "可以这样问我：\n· 推荐一款适合油皮的洗面奶\n· 200元以下的蓝牙耳机\n· 我想要一双跑鞋，预算1000以内\n· 推荐保湿面霜，但我不要日系品牌",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun MessageBubble(
    msg: UiMessage,
    products: Map<String, ProductWire>,
    ttsSpeakingId: String? = null,
    onPlayTts: (String, String) -> Unit = { _, _ -> },
    onClarifyOption: (Int, String) -> Unit = { _, _ -> },
) {
    val isUser = msg.role == Role.User
    val thumbnail = remember(msg.imageBase64) {
        msg.imageBase64?.let { ImageUtils.decodeThumbnail(it) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            BotAvatar(size = 32.dp)
            Spacer(Modifier.width(8.dp))
        }
        val maxBubbleWidth = if (!isUser && msg.compareData != null) 360.dp else 320.dp
        Column(
            modifier = Modifier.widthIn(max = maxBubbleWidth),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (!isUser && (msg.thinkingSteps.isNotEmpty() || msg.retrievedSummary != null)) {
                ThinkingPanel(
                    steps = msg.thinkingSteps,
                    summary = msg.retrievedSummary,
                    constraints = msg.constraintsSnapshot,
                    isStreaming = msg.isStreaming,
                )
                Spacer(Modifier.height(6.dp))
            }

            if (!isUser && msg.retrieved.isNotEmpty()) {
                RetrievedHint(items = msg.retrieved)
                Spacer(Modifier.height(4.dp))
            }

            if (isUser && thumbnail != null) {
                FoundationImage(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "用户图片",
                    modifier = Modifier
                        .size(180.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(14.dp),
                        ),
                )
                if (msg.visionKeywords != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "正在按这些关键词搜：${msg.visionKeywords}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (msg.text.isNotBlank()) Spacer(Modifier.height(6.dp))
            }

            // 场景化组合推荐：套装卡片承担一切，跳过普通文本气泡和商品横滑列表
            val isCombo = !isUser && msg.combo != null
            // 澄清场景下文本气泡为空：不渲染空白气泡，让 ClarificationBubble 单独承担
            val isClarification = !isUser && msg.clarification != null
            if (!isCombo && !isClarification && (!isUser || msg.text.isNotBlank() || msg.imageBase64 == null)) {
                val bubbleShape = if (isUser) {
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
                } else {
                    RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                }
                Surface(
                    color = if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    shape = bubbleShape,
                    shadowElevation = if (isUser) 0.dp else 1.dp,
                ) {
                    val display = stripProductTags(msg.text)
                    Text(
                        text = if (display.isEmpty() && msg.isStreaming) "思考中…" else display,
                        color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }

            // 主动澄清气泡：圆角胶囊选项，FlowRow 自动换行；点过一次后整组变灰
            if (isClarification) {
                ClarificationCard(
                    data = msg.clarification!!,
                    selected = msg.clarificationSelected,
                    onPick = onClarifyOption,
                )
            }

            // 场景化组合推荐套装卡片：场景标题 + 横滑品类小卡片 + 整体搭配建议
            if (isCombo) {
                ComboCardView(data = msg.combo!!)
            }

            // 对比模式结构化数据：先渲染雷达图 + 表格（在文本气泡下方、商品卡片上方）
            if (!isUser && msg.compareData != null) {
                Spacer(Modifier.height(10.dp))
                ComparisonView(data = msg.compareData)
            }

            // 商品卡片：横向滚动条，参考演示稿样式（combo 模式由 ComboCardView 接管）
            if (!isCombo && msg.productIds.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val items = msg.productIds.mapNotNull { products[it] }
                // pid -> match_source 映射，用于商品卡角标
                val sourceMap = remember(msg.retrieved) {
                    msg.retrieved.associate { it.product_id to it.match_source }
                }
                if (items.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items, key = { it.product_id }) { p ->
                            ProductCard(p, matchSource = sourceMap[p.product_id])
                        }
                    }
                }
            }

            if (msg.errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text("出错了：${msg.errorMessage}", color = Color.Red, fontSize = 12.sp)
            }

            if (!isUser && !msg.isStreaming && msg.text.isNotBlank()) {
                val speaking = ttsSpeakingId == msg.id
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onPlayTts(msg.id, stripProductTags(msg.text)) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (speaking) "停止朗读" else "朗读",
                            tint = if (speaking) Color.Red else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        if (speaking) "朗读中…" else "朗读",
                        fontSize = 11.sp,
                        color = if (speaking) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val productTagRegex = Regex("""\s*\[\[PRODUCT:[a-zA-Z0-9_]+]]\s*""")
private val compareDataPrefixRegex = Regex("""\[\[COMPARE_DATA:""")

/**
 * 剥掉所有 LLM 内联控制标签，让文本气泡只显示给人看的正文：
 *  - `[[PRODUCT:xxx]]` → 由 LazyRow 商品卡片承接
 *  - `[[COMPARE_DATA:{...}]]` → 由 ComparisonView 承接（用大括号深度扫到匹配的 `]]`，
 *    不能用贪婪 regex，因为 JSON 字符串里允许有 `]]`）
 */
private fun stripProductTags(text: String): String {
    var s = text.replace(productTagRegex, " ")
    val match = compareDataPrefixRegex.find(s)
    if (match != null) {
        val openIdx = s.indexOf('{', startIndex = match.range.last + 1)
        if (openIdx >= 0) {
            var depth = 0
            var inString = false
            var escape = false
            var endIdx = -1
            for (i in openIdx until s.length) {
                val c = s[i]
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
            if (endIdx > 0) {
                val tail = s.indexOf("]]", startIndex = endIdx)
                val cutEnd = if (tail >= 0) tail + 2 else endIdx + 1
                s = s.substring(0, match.range.first) + s.substring(cutEnd)
            } else {
                // 流式中尚未收齐 `]]`，先把前缀到末尾全部隐藏，避免半截 JSON 闪现
                s = s.substring(0, match.range.first)
            }
        } else {
            s = s.substring(0, match.range.first)
        }
    }
    return s.trim()
}

/**
 * 「思考过程」可观测面板。挂在每条 assistant 气泡顶部。
 *
 * 收起态：一行小字 — "💡 查看检索过程 · 1.2s"。
 * 展开态：四块内容
 *   1) 🔍 检索策略（高亮当前 strategy）
 *   2) 📦 召回数 + Top 相似度
 *   3) 🚫 命中的硬约束（无则不渲染）
 *   4) ⏱️ 时间线 — 各 thinking_step 节点 + 耗时
 *
 * 流式期间 thinking_step 一条条进来时，整块用 animateContentSize 平滑过渡，
 * 用户能看到节点逐条追加的动效（不需要等流结束）。
 */
@Composable
private fun ThinkingPanel(
    steps: List<ThinkingStepWire>,
    summary: RetrievedSummaryWire?,
    constraints: ConstraintSnapshotWire?,
    isStreaming: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val totalMs = steps.sumOf { it.duration_ms }
    val totalLabel = formatDuration(totalMs)
    val headerText = if (isStreaming && steps.isEmpty()) {
        "💡 正在思考…"
    } else if (isStreaming) {
        "💡 思考中 · 已耗时 $totalLabel"
    } else {
        "💡 查看检索过程 · 共 $totalLabel"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .widthIn(max = 320.dp)
            .animateContentSize(animationSpec = tween(durationMillis = 180)),
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    headerText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "收起 ▲" else "展开 ▼",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (summary != null) {
                        StrategyRow(summary.strategy)
                        Spacer(Modifier.height(6.dp))
                        RecallSummaryRow(summary)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (constraints != null && constraintsHasAny(constraints)) {
                        ConstraintsLine(constraints)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (steps.isNotEmpty()) {
                        ThinkingTimeline(steps)
                    }
                }
            }
        }
    }
}

/** 把毫秒转成人话："890ms" / "1.2s" / "12.3s"。 */
private fun formatDuration(ms: Int): String =
    if (ms < 1000) "${ms}ms" else String.format("%.1fs", ms / 1000.0)

private fun constraintsHasAny(c: ConstraintSnapshotWire): Boolean =
    c.price_max != null || c.brand_required != null ||
        c.brand_excludes.isNotEmpty() || c.attr_excludes.isNotEmpty()

/**
 * 高亮"当前在用的"检索策略。三档可视化：
 *   fusion → VLM + CLIP（多模态最强）
 *   vlm_only / hybrid_rerank / hybrid / vector_only → 文本路径（命中的高亮）
 *   clip_only → 图像路径
 *
 * 三个胶囊横向并排，命中的实心紫，未命中的描边灰。
 */
@Composable
private fun StrategyRow(strategy: String) {
    val displayMap = listOf(
        "fusion" to "fusion",
        "hybrid_rerank" to "hybrid+rerank",
        "vlm_only" to "vlm_only",
        "clip_only" to "clip_only",
    )
    val activeKey = when (strategy) {
        "hybrid", "vector_only" -> "hybrid_rerank"
        else -> strategy
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🔍", fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            "检索策略",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            displayMap.forEach { (key, label) ->
                val on = key == activeKey
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (on) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                    border = if (on) null
                    else androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                    ),
                ) {
                    Text(
                        label,
                        fontSize = 10.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecallSummaryRow(summary: RetrievedSummaryWire) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📦", fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                "召回 ${summary.count} 款商品",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (summary.top_scores.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            val scoreText = summary.top_scores.joinToString(" · ") { String.format("%.3f", it) }
            Text(
                "Top 分数：$scoreText",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp),
            )
        }
    }
}

@Composable
private fun ConstraintsLine(c: ConstraintSnapshotWire) {
    val parts = buildList {
        c.price_max?.let { add("¥≤${it.toInt()}") }
        c.brand_required?.let { add("限 $it") }
        if (c.brand_excludes.isNotEmpty()) add("✗品牌:${c.brand_excludes.joinToString("/")}")
        if (c.attr_excludes.isNotEmpty()) add("✗属性:${c.attr_excludes.joinToString("/")}")
    }
    if (parts.isEmpty()) return
    Row(verticalAlignment = Alignment.Top) {
        Text("🚫", fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                "约束过滤",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                parts.joinToString(" · "),
                fontSize = 10.sp,
                color = Color(0xFFB42318),
            )
        }
    }
}

/**
 * 时间线节点。每个 step 一行：
 *   [小圆点] step_label  detail              耗时
 * 圆点上下用细线连起来形成 timeline 效果。
 */
@Composable
private fun ThinkingTimeline(steps: List<ThinkingStepWire>) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.Top) {
        Text("⏱️", fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                "各步骤耗时",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            steps.forEachIndexed { idx, s ->
                Row(verticalAlignment = Alignment.Top) {
                    // 圆点 + 连线列
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(labelColor, CircleShape),
                        )
                        if (idx < steps.size - 1) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(22.dp)
                                    .background(lineColor),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.padding(bottom = if (idx < steps.size - 1) 6.dp else 0.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stepLabel(s.step),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = labelColor,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                formatDuration(s.duration_ms),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (s.detail.isNotBlank()) {
                            Text(
                                s.detail,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 把后端 step 标识翻成中文短标签。未知 step 直接回显。 */
private fun stepLabel(step: String): String = when (step) {
    "query_rewrite" -> "Query 改写"
    "vector_recall" -> "向量召回"
    "bm25_recall" -> "BM25 召回"
    "rrf_fuse" -> "RRF 融合"
    "rerank" -> "CrossEncoder 重排"
    "aggregate" -> "商品聚合"
    "constraint_filter" -> "硬约束过滤"
    "vision_describe" -> "VLM 识图"
    "clip_recall" -> "CLIP 图像召回"
    "multimodal_fuse" -> "多模态融合"
    "generate_first_token" -> "首 Token 生成"
    else -> step
}

@Composable
private fun RetrievedHint(items: List<RetrievedItem>) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                "正在浏览这些商品 · top ${items.size}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            items.take(3).forEach { it ->
                Text(
                    "· ${it.title}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProductCard(p: ProductWire, matchSource: String? = null) {
    Surface(
        modifier = Modifier.width(140.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Column {
            val imageUrl = p.image_path?.let { rel ->
                val encoded = rel.split('/').joinToString("/") { seg ->
                    java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                }
                "${Config.BASE_URL}/static/$encoded"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = p.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("商品", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 多模态来源角标：右上角悬浮，仅多模态请求才下发该字段
                MatchSourceBadge(
                    source = matchSource,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    p.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "¥${p.base_price ?: "-"}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 多模态命中来源角标。三档：
 *   both       双路命中（融合最强）— 紫色实心
 *   image_only CLIP 图像匹配      — 蓝色实心
 *   text_only  VLM 关键词文本召回 — 浅紫描边
 *   null/其他  纯文本对话不显示
 */
@Composable
private fun MatchSourceBadge(source: String?, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (source) {
        "both" -> Triple("双路命中", Color(0xFF7B61FF), Color.White)
        "image_only" -> Triple("视觉相似", Color(0xFF2F80ED), Color.White)
        "text_only" -> Triple("关键词", Color(0xFFEDE7FF), Color(0xFF2D1F6F))
        else -> return
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        shadowElevation = 1.dp,
        modifier = modifier,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 自动话题隔离视觉提示：在消息流里渲染一条淡分割线 + "Started a new topic"。
 * 不弹 modal、不打断输入；用户上下滑动能看到话题边界。
 */
@Composable
private fun TopicBoundaryDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier
                .weight(1f)
                .height(1.dp),
        ) {}
        Text(
            "  开始了新话题  ",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Surface(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier
                .weight(1f)
                .height(1.dp),
        ) {}
    }
}

/**
 * 已识别约束的可视化追踪条。
 * 后端 SSE event=constraints 一来，这里就把价格上限/必含品牌/排除品牌/排除属性
 * 渲染成可点 × 撤销的 chip。空状态完全不渲染（高度为 0）保持 UI 干净。
 *
 * 这是"对话智能"加分项的最直观展现——让不可见的硬约束变成用户能改的状态。
 */
@Composable
private fun ConstraintTracker(
    snapshot: ConstraintSnapshotWire?,
    onDismiss: (ChatViewModel.ConstraintKind, String?) -> Unit,
) {
    if (snapshot == null) return
    val hasAny = snapshot.price_max != null ||
        snapshot.brand_required != null ||
        snapshot.brand_excludes.isNotEmpty() ||
        snapshot.attr_excludes.isNotEmpty()
    if (!hasAny) return

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "已识别需求",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snapshot.price_max?.let { price ->
                    item("price") {
                        ConstraintChip(
                            label = "≤ ¥${price.toInt()}",
                            tone = ChipTone.Strong,
                            onDismiss = { onDismiss(ChatViewModel.ConstraintKind.PriceMax, null) },
                        )
                    }
                }
                snapshot.brand_required?.let { brand ->
                    item("required") {
                        ConstraintChip(
                            label = "限 $brand",
                            tone = ChipTone.Strong,
                            onDismiss = { onDismiss(ChatViewModel.ConstraintKind.BrandRequired, null) },
                        )
                    }
                }
                items(snapshot.brand_excludes, key = { "ex-brand-$it" }) { brand ->
                    ConstraintChip(
                        label = "✗ $brand",
                        tone = ChipTone.Negative,
                        onDismiss = { onDismiss(ChatViewModel.ConstraintKind.BrandExclude, brand) },
                    )
                }
                items(snapshot.attr_excludes, key = { "ex-attr-$it" }) { attr ->
                    ConstraintChip(
                        label = "不含 $attr",
                        tone = ChipTone.Negative,
                        onDismiss = { onDismiss(ChatViewModel.ConstraintKind.AttrExclude, attr) },
                    )
                }
            }
        }
    }
}

private enum class ChipTone { Strong, Negative }

@Composable
private fun ConstraintChip(
    label: String,
    tone: ChipTone,
    onDismiss: () -> Unit,
) {
    val (bg, fg) = when (tone) {
        ChipTone.Strong -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ChipTone.Negative -> Color(0xFFFCE7E7) to Color(0xFFB42318)
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text(label, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium)
            IconButton(onClick = onDismiss, modifier = Modifier.size(22.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "撤销该约束",
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 主动澄清卡片 — 当 query 太短或缺关键属性时，由后端下发 1~2 个问题 + 每题 2~4 个快捷选项。
 * 用户点其中一个：
 *   - 该选项变为填充实心（紫底白字），与未点的描边胶囊形成对比
 *   - 整组变灰且不可再点（防止误触补发多次请求）
 *   - 由 ChatViewModel.answerClarification 把"原始query + 选项文本"作为新 user 消息重发
 *
 * 视觉要点：与对话风格一致 — 顶部带"💬 帮我更准确地理解一下"小标签，圆角卡片，FlowRow 让选项
 * 超出宽度自动换行。问题之间留 8dp 间距，卡片整体 widthIn(max=320) 与气泡宽度对齐。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClarificationCard(
    data: ClarificationWire,
    selected: List<String?>,
    onPick: (Int, String) -> Unit,
) {
    val anyPicked = selected.any { it != null }
    Surface(
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "💬 帮我把需求说得更具体些～",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            data.questions.forEachIndexed { qIdx, question ->
                if (qIdx > 0) Spacer(Modifier.height(10.dp))
                Text(
                    question.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                val pickedOpt = selected.getOrNull(qIdx)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    question.options.forEach { option ->
                        ClarificationChip(
                            label = option,
                            picked = pickedOpt == option,
                            // 整组变灰：任何一题被点过，所有 chip 都不可再点
                            disabled = anyPicked,
                            onClick = { onPick(qIdx, option) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个气泡选项胶囊。三态：
 *  - 未选 / 可点：描边胶囊，紫色文字
 *  - 已选         ：紫色实心填充 + 白字（无论 disabled 与否，让用户清楚看到自己点了哪个）
 *  - 未选 / 已 disabled：灰描边 + 灰字，不可点击
 */
@Composable
private fun ClarificationChip(
    label: String,
    picked: Boolean,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    val bg: Color
    val fg: Color
    val borderColor: Color
    when {
        picked -> {
            bg = MaterialTheme.colorScheme.primary
            fg = Color.White
            borderColor = MaterialTheme.colorScheme.primary
        }
        disabled -> {
            bg = Color.Transparent
            fg = MaterialTheme.colorScheme.onSurfaceVariant
            borderColor = MaterialTheme.colorScheme.outlineVariant
        }
        else -> {
            bg = Color.Transparent
            fg = MaterialTheme.colorScheme.primary
            borderColor = MaterialTheme.colorScheme.primary
        }
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = if (!disabled) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 场景化组合推荐套装卡片。
 *
 * 视觉布局（自上而下）：
 *   ┌───────────────────────────────────────┐
 *   │ 🏖️ 三亚度假搭配                        │  ← 场景标题（居左，粗体）
 *   │ 已选 4 件 · ¥X / 总预算 ¥1500          │  ← 价格汇总行（有预算时显示对比）
 *   ├───────────────────────────────────────┤
 *   │ ┌─防晒─┐  ┌─T恤─┐  ┌─帽子─┐  ┌─背包─┐  │  ← 品类徽标 + 商品图 + 标题 + 价格
 *   │ │图   │  │图   │  │图   │  │图   │  │     横向滚动可见全部
 *   │ └─────┘  └─────┘  └─────┘  └─────┘   │
 *   ├───────────────────────────────────────┤
 *   │ 整套覆盖 SPF50+ 防晒、速干透气 ...     │  ← LLM summary 文字
 *   └───────────────────────────────────────┘
 *
 * 设计要点：
 *  - 不复用普通 ProductCard：这里需要把 LLM 给的 reason 显示在卡片底下，且品类徽标在标题位置
 *  - 价格汇总：有 budget_total 时显示"¥已选合计 / 总预算 ¥X"，超预算红色标记
 *  - LazyRow 让套装数量超过屏宽（4-5 个时）能横向滚动
 */
@Composable
private fun ComboCardView(data: ComboDataWire) {
    if (data.items.isEmpty()) {
        // 极端兜底：召回全空。给一个简短文字气泡，让用户知道发生了什么。
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
        ) {
            Text(
                "未能为该场景找到完整套装，可以再补充一些细节让我重新搭配～",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
        return
    }
    val totalPrice = data.items.sumOf { it.product.base_price ?: 0.0 }
    val overBudget = data.budget_total != null && totalPrice > data.budget_total
    val title = data.scene_label.ifBlank { data.scene.ifBlank { "搭配方案" } }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.widthIn(max = 360.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 ${data.items.size} 件 · 合计 ¥${"%.0f".format(totalPrice)}",
                    fontSize = 11.sp,
                    color = if (overBudget) Color(0xFFB42318) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (overBudget) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (data.budget_total != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "/ 预算 ¥${"%.0f".format(data.budget_total)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (overBudget) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFCE7E7),
                        ) {
                            Text(
                                "超出预算",
                                fontSize = 10.sp,
                                color = Color(0xFFB42318),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                items(data.items, key = { it.product_id }) { item ->
                    ComboItemCard(item = item)
                }
            }
            if (data.summary.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        data.summary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 套装内单件商品小卡片。
 * 视觉与 ProductCard 一致（保持品牌一致），但顶部多一个紫色品类徽标，
 * 标题下方多一行 LLM 给的 reason — 如果有的话。
 *
 * 点击：复用现有商品卡片"点击跳转落地页"模式 — 这里目前没有真实落地页，
 * 仅做按下视觉反馈（Surface(onClick)）保留扩展点。后续接微信支付/小店时挂在这里。
 */
@Composable
private fun ComboItemCard(item: ComboItemWire) {
    val p = item.product
    Surface(
        modifier = Modifier.width(140.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        onClick = { /* TODO: 接入落地页时跳转 */ },
    ) {
        Column {
            val imageUrl = p.image_path?.let { rel ->
                val encoded = rel.split('/').joinToString("/") { seg ->
                    java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                }
                "${Config.BASE_URL}/static/$encoded"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = p.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("商品", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 左上角品类徽标
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                ) {
                    Text(
                        item.category,
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    p.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "¥${p.base_price ?: "-"}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (item.reason.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.reason,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 13.sp,
                    )
                }
            }
        }
    }
}

/**
 * 对比模式整体卡片：顶部 tab 切换"雷达图 / 表格 / 全部"，下方按选择渲染。
 * 仅在 LLM 在正文末尾下发了完整 [[COMPARE_DATA:{...}]] 时被父消息渲染。
 *
 * 默认进入"全部"——首次展示信息密度最高；用户点 tab 单独看其中一个，免得长卡片占屏。
 */
@Composable
private fun ComparisonView(data: CompareData) {
    if (data.products.isEmpty() || data.dimensions.isEmpty()) return

    val canRadar = data.scores.size == data.products.size &&
        data.scores.all { it.size == data.dimensions.size }
    val canTable = data.rows.size == data.products.size &&
        data.rows.all { it.size == data.dimensions.size }

    var selectedTab by remember { mutableStateOf(CompareTab.All) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "横向对比 · ${data.products.size} 款",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                CompareViewTabs(
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                    showRadar = canRadar,
                    showTable = canTable,
                )
            }
            Spacer(Modifier.height(10.dp))

            val showRadar = canRadar &&
                (selectedTab == CompareTab.Radar || selectedTab == CompareTab.All)
            val showTable = canTable &&
                (selectedTab == CompareTab.Table || selectedTab == CompareTab.All)

            if (showRadar) {
                RadarChart(
                    products = data.products.map { it.name },
                    dimensions = data.dimensions,
                    scores = data.scores,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
                if (showTable) Spacer(Modifier.height(12.dp))
            }

            if (showTable) {
                ComparisonTable(
                    products = data.products.map { it.name },
                    dimensions = data.dimensions,
                    rows = data.rows,
                    scores = if (canRadar) data.scores else emptyList(),
                )
                Spacer(Modifier.height(8.dp))
            }

            if (data.conclusion.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        data.conclusion,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private enum class CompareTab { Radar, Table, All }

/** 仅在数据齐全的视图选项里渲染对应 tab。三个 tab 都不可用就压根不显示这一行。 */
@Composable
private fun CompareViewTabs(
    selected: CompareTab,
    onSelect: (CompareTab) -> Unit,
    showRadar: Boolean,
    showTable: Boolean,
) {
    val tabs = buildList {
        if (showRadar) add(CompareTab.Radar to "雷达图")
        if (showTable) add(CompareTab.Table to "表格")
        if (showRadar && showTable) add(CompareTab.All to "全部")
    }
    if (tabs.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { idx, (tab, label) ->
            val on = tab == selected
            Surface(
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(8.dp),
                color = if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (idx < tabs.size - 1) Spacer(Modifier.width(4.dp))
        }
    }
}

/** 雷达图配色（前 3 款商品） */
private val RadarPalette = listOf(
    Color(0xFF7B61FF),
    Color(0xFFFF9F43),
    Color(0xFF2F80ED),
)

/**
 * 多边形雷达图。每款商品一种颜色，半透明面 + 实线边。
 *  - 维度数 = dimensions.size，从正上方开始顺时针均分角度
 *  - 分数 0~5 整数；半径按 score/5 缩放
 *  - 网格圈每圈分数（5/4/3/2/1）；最外圈深灰，内圈虚化
 *  - 每个维度上"得分最高"的商品顶点画一个加粗实心圆 —— 一眼看出谁赢在哪
 */
@Composable
private fun RadarChart(
    products: List<String>,
    dimensions: List<String>,
    scores: List<List<Int>>,
    modifier: Modifier = Modifier,
) {
    val gridColor = Color(0xFFE5E5EA)
    val axisColor = Color(0xFFB0B0B8)
    val labelMeasurer = rememberTextMeasurer()
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 10.sp,
        color = onSurfaceVar,
    )

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // 给标签留 36dp 内边距，避免文字被裁
            val radius = (minOf(size.width, size.height) / 2f) - 36.dp.toPx()
            val n = dimensions.size
            if (n < 3 || radius <= 0f) return@Canvas

            // 顶点角度（弧度），从正上方开始顺时针
            fun angleAt(i: Int): Double = -Math.PI / 2 + 2 * Math.PI * i / n
            fun pointAt(i: Int, r: Float): Offset {
                val a = angleAt(i)
                return Offset(cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat())
            }

            // 5 圈网格
            for (ring in 1..5) {
                val r = radius * ring / 5f
                val path = Path()
                for (i in 0 until n) {
                    val p = pointAt(i, r)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(
                    path = path,
                    color = gridColor.copy(alpha = if (ring == 5) 0.9f else 0.45f),
                    style = Stroke(width = if (ring == 5) 1.2.dp.toPx() else 0.8.dp.toPx()),
                )
            }
            // 轴线
            for (i in 0 until n) {
                drawLine(
                    color = axisColor.copy(alpha = 0.5f),
                    start = Offset(cx, cy),
                    end = pointAt(i, radius),
                    strokeWidth = 0.8.dp.toPx(),
                )
            }

            // 各商品的多边形
            scores.forEachIndexed { idx, sList ->
                val color = RadarPalette[idx % RadarPalette.size]
                val path = Path()
                for (i in 0 until n) {
                    val s = sList.getOrNull(i)?.coerceIn(0, 5) ?: 0
                    val r = radius * s / 5f
                    val p = pointAt(i, r)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, color = color.copy(alpha = 0.18f))
                drawPath(path, color = color, style = Stroke(width = 1.8.dp.toPx()))
            }

            // 每维度获胜商品在顶点上画加粗实心圆 + 白心，让"赢在哪"一眼可见
            for (dim in 0 until n) {
                val winners = topScorers(scores, dim)
                for (winner in winners) {
                    val s = scores[winner].getOrNull(dim)?.coerceIn(0, 5) ?: 0
                    if (s == 0) continue
                    val color = RadarPalette[winner % RadarPalette.size]
                    val center = pointAt(dim, radius * s / 5f)
                    drawCircle(color = color, radius = 5.dp.toPx(), center = center)
                    drawCircle(color = Color.White, radius = 2.dp.toPx(), center = center)
                }
            }

            // 维度标签（顶点外侧）
            for (i in 0 until n) {
                val a = angleAt(i)
                val labelR = radius + 14.dp.toPx()
                val cx2 = cx + (labelR * Math.cos(a)).toFloat()
                val cy2 = cy + (labelR * Math.sin(a)).toFloat()
                val layout = labelMeasurer.measure(dimensions[i], style = labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = cx2 - layout.size.width / 2f,
                        y = cy2 - layout.size.height / 2f,
                    ),
                )
            }
        }
        // 图例
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            products.forEachIndexed { idx, name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(RadarPalette[idx % RadarPalette.size], CircleShape),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 找出某维度得分最高的商品 idx 列表（并列就全返回）。 */
private fun topScorers(scores: List<List<Int>>, dim: Int): List<Int> {
    var best = Int.MIN_VALUE
    val winners = mutableListOf<Int>()
    scores.forEachIndexed { i, sList ->
        val s = sList.getOrNull(dim) ?: 0
        if (s > best) {
            best = s
            winners.clear()
            winners.add(i)
        } else if (s == best) {
            winners.add(i)
        }
    }
    return winners
}

/**
 * 维度对比表。视觉重排：
 *  - 表头紫色背景 + 商品色点，与雷达图图例颜色对应
 *  - 隔行底色，列间细线分割
 *  - 每行"获胜商品"的单元格在角落加一个商品色点，呼应雷达图
 */
@Composable
private fun ComparisonTable(
    products: List<String>,
    dimensions: List<String>,
    rows: List<List<String>>,
    scores: List<List<Int>>,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val zebra = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val headerBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val dimColWeight = 1f
    val productColWeight = 1.5f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)),
    ) {
        // 表头：维度 | 商品1 (色点) | 商品2 (色点) ...
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCell("维度", weight = dimColWeight)
            products.forEachIndexed { idx, name ->
                Row(
                    modifier = Modifier
                        .weight(productColWeight)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                RadarPalette[idx % RadarPalette.size],
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // 数据行
        dimensions.forEachIndexed { dimIdx, dim ->
            val winners = if (scores.isNotEmpty()) topScorers(scores, dimIdx).toSet() else emptySet()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (dimIdx % 2 == 1) zebra else Color.Transparent),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = dim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(dimColWeight)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                products.indices.forEach { pIdx ->
                    val cell = rows.getOrNull(pIdx)?.getOrNull(dimIdx).orEmpty()
                    val isWinner = pIdx in winners
                    Row(
                        modifier = Modifier
                            .weight(productColWeight)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (isWinner) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 5.dp, end = 5.dp)
                                    .size(6.dp)
                                    .background(
                                        RadarPalette[pIdx % RadarPalette.size],
                                        CircleShape,
                                    ),
                            )
                        }
                        Text(
                            text = cell,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isWinner) FontWeight.SemiBold else FontWeight.Normal,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    text: String,
    weight: Float,
) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun InputBar(
    text: String,
    busy: Boolean,
    recordState: ChatViewModel.RecordState,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    onMicPressStart: () -> Unit,
    onMicPressEnd: () -> Unit,
    onMicPressCancel: () -> Unit,
) {
    val recording = recordState == ChatViewModel.RecordState.Recording
    val recognizing = recordState == ChatViewModel.RecordState.Recognizing
    var imageMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (recording) {
            Surface(
                color = Color(0xFFFFEBEE),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.Red, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在录音…松手发送，或再次点击结束",
                        fontSize = 12.sp,
                        color = Color(0xFFB00020),
                    )
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 输入胶囊：左侧麦克风、中间文本、右侧加号菜单，整体一个圆角白色容器
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(28.dp),
                        ),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // mic 按钮支持两种交互：
                        //   - 短按（onTap）：toggle 录音，识别结果落到输入框，由用户编辑后再发
                        //   - 长按（onPress 持续）：按住说话，松手自动 ASR + 立即发送
                        // 这里用 detectTapGestures 同时挂 onTap 和 onPress，press 早于 tap 触发；
                        // 通过 isLongPress 标志判断是否走 push-to-talk 路径。
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .pointerInput(busy, recognizing) {
                                    if (busy || recognizing) return@pointerInput
                                    detectTapGestures(
                                        onPress = { _ ->
                                            // 立刻开始录音；松手时根据时长决定走 push-to-talk 还是取消
                                            val startedAt = System.currentTimeMillis()
                                            onMicPressStart()
                                            val released = tryAwaitRelease()
                                            val heldMs = System.currentTimeMillis() - startedAt
                                            when {
                                                released && heldMs >= 400 -> onMicPressEnd()
                                                released -> onMicPressCancel() // 误触：直接丢，避免"录得太短"打扰
                                                else -> onMicPressCancel()      // 手指划走：当撤回
                                            }
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                recognizing -> CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                recording -> Icon(
                                    Icons.Filled.Mic,
                                    contentDescription = "正在录音",
                                    tint = Color.Red,
                                    modifier = Modifier.size(26.dp),
                                )
                                else -> Icon(
                                    Icons.Filled.Mic,
                                    contentDescription = "按住说话",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = text,
                            onValueChange = onChange,
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    "输入你的问题…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { onSend() }),
                            enabled = !busy && !recording,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent,
                                errorBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                            ),
                        )
                        Box {
                            IconButton(
                                onClick = { imageMenuExpanded = true },
                                enabled = !busy && !recording && !recognizing,
                            ) {
                                Icon(
                                    Icons.Filled.AddAPhoto,
                                    contentDescription = "拍照搜商品",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            DropdownMenu(
                                expanded = imageMenuExpanded,
                                onDismissRequest = { imageMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("拍照") },
                                    leadingIcon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                                    onClick = {
                                        imageMenuExpanded = false
                                        onTakePhoto()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("从相册选择") },
                                    leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                                    onClick = {
                                        imageMenuExpanded = false
                                        onPickFromGallery()
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 发送按钮：紫色实心圆
                val canSend = !busy && text.isNotBlank() && !recording && !recognizing
                Surface(
                    shape = CircleShape,
                    color = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(44.dp),
                ) {
                    IconButton(onClick = onSend, enabled = canSend) {
                        if (busy) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerSettingsDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf(Config.BASE_URL) }
    var autoTts by remember { mutableStateOf(Config.autoTtsEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column {
                Text(
                    "服务器地址",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Mac 局域网 IP（终端 ipconfig getifaddr en0）。切网络时改这里即可，不用重装 APK。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(Config.DEFAULT_BASE_URL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "语音导购模式",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "AI 回复后自动朗读，免按喇叭",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoTts, onCheckedChange = { autoTts = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Config.update(context, input)
                Config.setAutoTts(context, autoTts)
                android.widget.Toast.makeText(
                    context,
                    "已保存",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                onSaved()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
