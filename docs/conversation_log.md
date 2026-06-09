# 项目启动对话记录

> 记录时间：2026-05-24
> 项目：基于 RAG 的多模态电商智能导购 Agent（字节 AI 全栈挑战赛）
> 开发周期：2026-05-20 ~ 2026-06-10（剩余 17 天）

---

## 1. 用户需求

用户开启新项目「实现一个基于 RAG 的多模态电商智能导购 Agent」，要求：

- 基于 iOS/Android 原生 + Node.js/Python/Go 后端 + 向量数据库 + 大模型 OpenAPI
- 打通"意图理解-智能咨询-决策辅助"核心路径
- 支持非结构化商品/营销文档构建知识库，RAG 保障专业性
- 客户端原生流式交互（媲美豆包），商品卡片实时渲染，多模态输入
- 端到端质量评测与反馈闭环

用户首问："你觉得这个项目你可以帮我做完吗？"

---

## 2. 评估结论

**能做完**，前提：

- 客户端只做一端（iOS 或 Android）
- 加分项聚焦 1 个深做（评审说"做精一项胜过浅尝三项"）
- 题目要求的"评测闭环"必须做（不是加分项）
- 用户每天投入 ≥4h，关键节点 ≥6h

---

## 3. 关键决策对话

### 3.1 客户端平台选择

- 用户："我的手机是华为鸿蒙系统"
- 助手澄清：鸿蒙分两种——老鸿蒙（HarmonyOS 4.x 及更早，兼容 AOSP，可装 APK）vs 纯血鸿蒙（HarmonyOS NEXT/5.x，不兼容 APK）
- 用户："4.2.0 老鸿蒙"
- **决议：选 Android（Kotlin + Jetpack Compose），用户华为手机可直接装 APK 真机演示**

### 3.2 用户技术背景

- 后端：Python/FastAPI 写过接口，读过 RAG 概念
- Android：从未写过
- 加分项：先跑通基础闭环再选（1 周后决定）

→ 助手对应策略：后端走架构层节奏；Android 给极详细注释样板代码 + 原理同步讲解。

### 3.3 项目目录

新建 `/Users/yangyang/Desktop/字节AI挑战赛/ecommerce-agent/`，与原始素材分离。

---

## 4. 拍板的技术选型

| 项 | 选型 | 原因 |
| --- | --- | --- |
| 客户端 | Android (Kotlin + Compose, minSdk 26) | 真机演示，匹配评审"原生"要求 |
| 后端 | FastAPI + SSE | 用户写过 FastAPI；SSE 比 WebSocket 简单 |
| 向量库 | Chroma（本地嵌入） | 100 条规模零运维 |
| Embedding | Doubao-embedding-text | 火山方舟，多模态加分项升级用 vision 版 |
| LLM | Doubao-Seed-2.0-lite (`ep-20260514111645-lmgt2`) | 题目提供 |
| 流式协议 | Server-Sent Events | OkHttp-EventSource 接入 |

---

## 5. 三周作战图

| 周 | 时间 | 目标 |
| --- | --- | --- |
| W1 | 5.24–5.31 (8 天) | 基础最小闭环跑通 |
| W2 | 6.01–6.07 (7 天) | 加分项 1 个 + 评测闭环 + 多轮对话 |
| W3 | 6.08–6.10 (3 天) | 打磨 + 文档 + Demo 视频 |

### W1 每日任务（已建入 TaskList）

1. **D1** 初始化骨架 + 数据预处理 ✅ 已完成
2. **D2** 后端最小服务 + 火山方舟联调
3. **D3** 接入 Chroma + Embedding，构建 RAG 检索
4. **D4** 模型输出商品卡片结构化数据（流式协议）
5. **D5–D6** Android 工程初始化 + 对话 UI 骨架
6. **D7** Android 接入 SSE 流式 + 商品卡片渲染
7. **D8** 缓冲日 / W1 验收 / 决定 W2 加分项方向

---

## 6. D1 实际产出

- 解压并修复 zip 中文文件名（cp437→utf-8）
- 4 类目各 25 条共 **100 条商品**
- `data/products.jsonl`（100 行）+ `data/chunks.jsonl`（1199 行）
- Chunking 策略：每商品分 4 类粒度 —— `meta`/`marketing`/`faq`/`review`，每个 chunk 前置 `[商品][片段类型]《商品名》` 标签
- chunk 类型分布：meta 100 / marketing 207 / faq 439 / review 453

---

## 7. D2 实际产出

- `server/.venv` Python 3.14 虚拟环境
- 已装依赖：fastapi、uvicorn、pydantic、httpx、sse-starlette、openai、python-dotenv
- `server/app/config.py`：dotenv 自动加载 + Settings 单例
- `server/app/llm_client.py`：火山方舟豆包客户端（兼容 OpenAI 协议），暴露 `chat_once` 与 `chat_stream`
- `server/app/main.py`：三个路由 `/health`、`/chat`、`/chat/stream`(SSE)
- `server/.env`：实际密钥配置（不入库）

**联调结果：**
- `/health` 返回 200 ✅
- 旧 key 401 失效；用户后续提供新 key（已脱敏）通过 ✅
- `/chat` 非流式：豆包以"专业电商导购"身份回答，system prompt 生效
- `/chat/stream` SSE：逐 token 流式正常
- **关键观察**：无 RAG 时豆包**编造了"旁氏竹炭控油氨基酸洗面奶"**——这是 D3 必须做 RAG 的强证据，也是答辩金句

---

## 8. D3 实际产出（含 D4 合并）

**技术决策：本地 BGE-base-zh-v1.5 vs 火山豆包 embedding**
- 经讨论选定本地 BGE，理由：检索准确率更高（C-MTEB SOTA）、零网络依赖、节省 RPM 限流、可独立扩展多模态
- 文件：`server/app/embedder.py` 封装本地模型，对 query 加 BGE 推荐前缀

**核心组件：**
- `app/embedder.py`：BGE-base-zh-v1.5（768维，~400MB），离线加载
- `app/vector_store.py`：Chroma PersistentClient，集合 `products_chunks`，cosine 距离
- `app/retriever.py`：检索 top_k×4 的 chunk → 按 product_id 聚合 → 返回带元信息的商品列表
- `scripts/build_index.py`：1199 chunk 全量入库（CPU 上 93s）
- `scripts/test_retrieval.py`：5 条典型 query 手测，召回质量优良
- `app/main.py`：RAG 接入聊天链路 + 商品卡片标签协议 + `/products/{id}` 端点

**SSE 流式事件结构（最终）：**
- `event: retrieved`：流首事件，给客户端检索元数据，可立刻显示"正在浏览这些商品"
- `event: token`：模型增量 token
- `event: done`：流结束
- `event: error`：上游错误

**商品卡片协议：**
- prompt 要求模型在商品名后输出 `[[PRODUCT:product_id]]`
- 客户端 D7 解析此标签并替换为商品卡片 Composable
- 实测：模型 100% 遵守，三个不同问题里都正确生成对应 product_id 标签

**端到端验证：三个关键证据**
1. **不再幻觉**：D2 那个"旁氏竹炭控油氨基酸洗面奶"消失，模型严格用库内商品 `p_beauty_011` 珊珂洁面
2. **价格约束生效**：「跑步鞋预算1000以内」候选 3 款（999/1399/1099），模型只推 999 的特步并主动说"刚好落在你的预算"
3. **否定语义生效**：「保湿面霜不要日系」推荐了薇诺娜（中国）、玉兰油（美国）、理肤泉（法国），还自动剔除了候选里的"面膜"

**答辩可讲的工程亮点：**
- Chunking 4 类粒度（meta/marketing/faq/review）+ chunk 标签前置
- 召回时 N=top_k×4 然后按商品聚合，避免同商品多 chunk 占满
- 向量召回 + prompt 工程双层架构：向量做语义相关，prompt 处理结构化约束（价格/否定）
- 商品卡片标签内联在文本流里，客户端边流边渲染

---

## 9. D5–D7 实际产出（Android 端）

> 用户零 Android 经验，全程从环境搭建到真机演示一次走通。

### 9.1 环境初始化

- 工程目录：`client/`，AGP 8.7.3 + Kotlin 2.0.21 + Compose BOM 2024.09.02
- compileSdk/targetSdk = 36（用户机器只装了 SDK 36），minSdk = 26
- Gradle wrapper 8.9，下载 `gradle-wrapper.jar`，可执行 `gradlew`
- JDK 直接复用 Android Studio 内置 JBR：`/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- `local.properties` 指向 `~/Library/Android/sdk`
- 真机：华为 P40 Pro（HarmonyOS 4.2，AOSP 兼容），USB 连 Mac，ADB 授权后可 `adb install`

### 9.2 模块结构（6 个 Kotlin 文件，653 行）

| 文件 | 职责 | 行数 |
|---|---|---|
| `Config.kt` | BASE_URL = `http://192.168.1.42:8000` | 13 |
| `Models.kt` | Wire 协议 + UI 状态模型（UiMessage 含 streaming/retrieved/productIds 等） | 73 |
| `ChatRepository.kt` | OkHttp + okhttp-sse 实现 SSE，`fetchProduct` 走 `Dispatchers.IO` | 123 |
| `ChatViewModel.kt` | StateFlow 单一可信源 + 打字机节奏器 | 157 |
| `ChatScreen.kt` | Compose UI：消息气泡、检索提示、商品卡片、输入栏 | 268 |
| `MainActivity.kt` | ComponentActivity 入口 | 19 |

### 9.3 三个关键工程难点

**(a) 豆包-Seed-2.0-lite 的"思考再吐"问题**

- 现象：用户首次真机测试反馈「转了几秒一次性出来」
- 根因：通过 `curl -N` 时间戳确认——模型先 reasoning 12s，再 0ms 间隔吐光所有 token
- 选定方案：客户端打字机节奏器（pendingChars StringBuilder + 25ms/字 release）
- 实现：`ChatViewModel.send()` 启动两个协程——SSE collector 把 token 追加到 buffer，typewriter coroutine 从 buffer 每 25ms 取 1 字喂 UI
- 同步效果：约 40 字/秒，接近豆包打字感；副作用：商品卡有时间懒加载，避免空白

**(b) 商品标签缺失**

- 现象：模型回复里有商品名却没 `[[PRODUCT:xxx]]`，客户端拿不到 product_id → 卡片不出
- 根因：原 prompt 只在第 4 条规则里轻描淡写一句"商品名出现时，请同时输出商品标签"
- 修复：升级为 [极重要 ⚠] 强约束 + 两条 few-shot（单款/多款），把示例放在规则正文里而不是脚注
- 验证：5 条端到端 query，标签出现率 100%

**(c) 商品详情接口在主线程死锁**

- 现象：标签解析正常但卡片仍不出
- 根因：`ChatRepository.fetchProduct` 是 `suspend` 但里面同步 `execute()`，调用方 `viewModelScope.launch{}` 默认 Main → `NetworkOnMainThreadException` 被外层 `runCatching` 静默吞掉
- 修复：用 `withContext(Dispatchers.IO)` 包住网络调用 + 失败路径加 `Log.e` 防再次静默
- 调试线索：当时已加 `android.util.Log.w/e` 在 fetchProduct 各分支，下次类似问题可直接 `adb logcat -s ChatRepo`

### 9.4 已交付的端到端能力

- ✅ 流式打字机渲染（豆包同款节奏）
- ✅ 检索提示气泡（"正在浏览这些商品 · top N"）
- ✅ 商品卡片（72dp 商品图 + 标题 + 品牌¥价格，浅紫卡片浮于消息气泡下方）
- ✅ Chinese URL 路径分段 URLEncoder 编码后交给 Coil
- ✅ 输入栏：发送中显示 CircularProgressIndicator，自动收键盘

---

## 10. W1 验收（D8，2026-05-24）

### 10.1 端到端 5 query 抽测

| Query | 检索 | 模型回复 | 标签 | 抗幻觉 |
|---|---|---|---|---|
| 推荐一款适合油皮的洗面奶 | top4 命中 p_beauty_011 | 推荐珊珂¥52，描述贴用户场景 | ✅ p_beauty_011 | ✅ |
| 200元以下的蓝牙耳机 | top4 全部 ≥¥1000 | "候选库内暂无满足该条件的商品" | — | ✅ 拒答 |
| 推荐保湿面霜，但我不要日系品牌 | top5 含 1 日系 | 推荐 3 款（中/美/法），自动剔除日系 | ✅ ×3 | ✅ |
| 拍照好的旗舰手机，预算5000以内 | top5 多款超预算 | 仅推 OPPO Reno 16 Pro ¥3299 | ✅ ×1 | ✅ |
| 推荐一款100块以内的笔记本电脑 | top3 全部 ¥6000+ | "最低售价 6299 元，远高于 100 元预算" | — | ✅ 拒答 |

**结论：抗幻觉 / 价格约束 / 否定语义 / 标签协议 全部过线。**

### 10.2 代码体量

- 后端 Python：718 行（normalize/build_index/test_retrieval/app 全套）
- 客户端 Kotlin：653 行（6 个文件）
- 数据：100 商品 / 1199 chunks / Chroma 已持久化
- APK：17.5 MB debug 包，已装在用户华为 P40 Pro

### 10.3 W1 完成度核对（对照赛题）

| 赛题要求 | 状态 | 说明 |
|---|---|---|
| 客户端原生开发 | ✅ | Android Kotlin + Compose |
| 后端 + 向量库 + 大模型 | ✅ | FastAPI + Chroma + 豆包 Seed-2.0-lite |
| 流式交互媲美豆包 | ✅ | SSE + 客户端打字机 |
| 商品卡片实时渲染 | ✅ | `[[PRODUCT:xxx]]` 内联标签 |
| RAG 保障专业性/抗幻觉 | ✅ | 1199 chunk × 4 粒度 |
| 端到端评测闭环 | ⏳ W2 | 已有手测脚本，需扩展为自动化 |
| 多模态输入 | ⏳ W2 | 候选 bonus #1 |
| 加分项做精 1 项 | ⏳ W2 | 待用户决策 |

---

## 11. W2 待用户决策

加分项三个候选（已向用户重述，等待选择）：

1. **拍照搜商品（多模态）**：相机 → VLM 提关键词 → 文本检索。最贴"多模态"赛点，演示效果最炸。建议方向。
2. **购物车 + 下单 CRUD**：加购/改量/对比/结算。常规电商功能，工程量大但不出彩。
3. **对话智能升级**：长期记忆 + 多轮约束累计 + 主动追问。Agent 味浓但视觉冲击弱。

W2 必做（无论加分项选哪个）：
- 评测闭环：扩 5 条手测 → 30+ 条标注 query，跑 retrieval@k / 抗幻觉率 / 标签覆盖率
- 多轮稳定性：会话内连续 5–8 轮提问的语义一致性

---

## 12. 待办杂项

- [ ] git 初始化（当前不是 git 仓库）—— W2 开始前做掉，方便回滚
- [ ] 后端 BASE_URL 改为可配置（演示日如换 IP 不用改代码）
- [ ] APK 签 release 包，演示视频拍摄前装一次稳定版

---

## 13. W2 启动 - bonus 决策（2026-05-24）

用户确认 bonus 方向：**多模态拍照搜商品**（推荐方案 #1）。

W2 任务拆解：
- D9：评测闭环 - 30 条 query + 自动跑分脚本（**必做**，赛题硬性要求）
- D10：多轮对话稳定性 - 约束累计
- D11–D12：多模态后端 VLM 链路
- D13：多模态客户端 - 相机/相册接入
- D14：W2 验收 + 评测报告

---

## 14. D9 - 评测闭环（已完成）

### 14.1 评测集设计

`eval/eval_set.jsonl` 共 30 条 query，覆盖 6 个能力维度：

| 类别 | 条数 | 用途 |
|---|---|---|
| 直推 | 5 | 已给品类 + 关键属性，应直接推 |
| 价格约束 | 6 | 价格上限硬约束（含 3 条不可满足→拒答） |
| 否定约束 | 4 | 品牌排除 / 否定属性 |
| 多约束 | 5 | 价格 + 品牌 + 属性叠加 |
| 稀缺拒答 | 5 | 库内确实没有该品类/品牌 |
| 模糊追问 | 5 | 信号不足应反问 |

### 14.2 评测脚本指标

`eval/run_eval.py`：
- `retrieval@k`：gold_ids 在 top-k 中的命中率（仅推荐题适用）
- `tag_coverage_rate`：模型回复含 `[[PRODUCT:xxx]]` 标签的比例
- `constraint_ok_rate`：标签对应商品是否违反价格上限/品牌排除/品类（**按最低 SKU 价计算**，不只是 base_price）
- `refuse_rate`：拒答题中真正拒答 + 关键词命中的比例
- `ask_back_rate`：反问题中真正反问的比例

### 14.3 三轮调优过程

**第 1 跑：86.7%（26/30）**
- 4 条失败：q16 把¥1499 SKU 当超 1500 上限（评测 bug）；q22 资生堂没货却推完美日记替代；q28 "买点零食"直接推没反问；q30 ipad 反问被判 fail（评测集错）

**第 2 跑：76.7%（23/30）**——prompt 矫枉过正
- 把"≥2 个关键约束缺失就反问"加进去后，"敏感肌面霜"这种已给品类+属性的题也被反问，掉 6 条。

**第 3 跑：100%（30/30）**——精修 prompt 区分"该反问"vs"该直推"
- 直推标准：已给品类（跑鞋/面霜）或关键属性（敏感肌/油皮）→ 直推 1-3 款
- 反问标准：连具体品类都没说清（"买点零食"/"想买双鞋"/"推荐个化妆品"）才反问
- 强化品牌指名缺货的"直接拒答，不主动推替代品"

### 14.4 关键工程亮点

**SKU 最低价判约束**：商品有多 SKU 时，¥1699 base 但有¥1499 SKU，模型推荐"¥1499 有线充基础版"在评测脚本里也算合规。判定逻辑写在 `score_constraints()`。

**问号识别多元化**：除"？/?"外加入"想先/想了解/想确认"等中文反问起手词，避免模型用感叹号收尾被误判。

---

## 15. D10 - 多轮对话稳定性（已完成）

### 15.1 问题与方案

**问题**：原检索逻辑只用最后一条 user 消息。多轮"想买跑鞋 → 预算 1000 → 要轻便点"中，最后一句"要轻便点"丢失了"跑鞋"和"预算 1000"两个约束，导致检索 top-5 全错（背包/瑜伽裤/速干裤）。

**方案**：
1. 后端 `_build_retrieval_query()`：取最近 3 条 user 消息合并作为检索 query
2. system prompt 加规则 6 强制"约束累计 vs 否定覆盖"
3. 否定覆盖判定：用户明说"算了/换个方向"才覆盖，否则累计

### 15.2 多轮评测集

`eval/eval_multiturn.jsonl` 6 条多轮场景：
- m01：跑鞋 → 1000 以内 → 要轻便（3 约束累计）
- m02：面霜 → 敏感肌 → 不要日系（3 约束累计）
- m03：笔记本 → 8000 以内 → 不要苹果（3 约束累计）
- m04：防晒霜 → "算了，看看面霜"（意图覆盖）
- m05：速溶咖啡 → 100 元以内（2 约束累计）
- m06：想买双鞋 → 日常跑步（追问后落地推荐）

### 15.3 终评结果

**总测 36 条（30 单轮 + 6 多轮）：100% 通过**

| 指标 | 值 |
|---|---|
| 综合通过率 | **100% (36/36)** |
| Retrieval any-hit @5 | 95.7% (n=23) |
| 商品标签覆盖率 | 100% |
| 约束合规率 | 100% |
| 拒答正确率 | 100% |
| 反问正确率 | 100% |

报告路径：`eval/results/run-*.md`，每跑生成一份 markdown + jsonl，可作答辩材料。

---

## 16. D11 启动 - 多模态拍照搜（进行中）

### 16.1 决策

- 用户已选 bonus 方向：拍照搜商品
- VLM 来源：用户选择"我去开通"火山方舟视觉模型，回头给 endpoint ID
- 期间助手把后端骨架搭起来，接口/prompt/服务层全部就位，等 endpoint ID 填 `.env`

### 16.2 设计方案

链路：客户端拍照 → base64 → 后端 `/chat/stream/multimodal` → VLM 抽取关键属性 JSON（品类、品牌、外观特征） → 拼成文本 query → 走现有 RAG 链路 → 流式返回。

> 不直接走"VLM 端到端推荐"，因为 VLM 看不到我们的商品库；让 VLM 只负责"看图说关键词"，把检索交给已经验证好的文本 RAG，最稳。

### 16.3 待用户确认

等 VLM endpoint ID 后继续 D11–D13。

---

## 17. D11–D13 多模态客户端落地

### 17.1 后端骨架（D11 已完成，D12 待 endpoint）

- `server/app/llm_client.py` 新增 `vision_describe(image_base64, hint_text)`：调用 ARK Vision API，content 数组同时塞 image_url + text，要求模型输出单行中文关键词（品类、品牌、外观、属性）便于后续走文本 RAG。
- `server/app/main.py` 新增 `/chat/stream/multimodal`：先 emit `vision` SSE 事件（关键词 + 用时），随后将关键词拼入合成 user 消息，复用现有 `_chat_stream_generator` 的 RAG/打字流。
- `server/app/config.py` 新增 `ark_vision_model: str = ""`，无值时返回友好错误而不是崩溃。
- 决策：让 VLM 只做"看图→关键词"，检索仍走我们调好的文本 RAG，避免 VLM 端到端不可控。

### 17.2 客户端骨架（D13 已完成）

- `Models.kt`：`UiMessage` 增加 `imageBase64`、`visionKeywords` 两个可选字段；新增 `MultimodalChatRequestWire`。
- `ImageUtils.kt`（新文件）：`loadAndEncode(context, uri)` 把相册 URI 的图压到 ≤512px 长边、JPEG 80%、Base64 NO_WRAP；`decodeThumbnail(base64)` 用于气泡缩略图。
- `ChatRepository.kt`：新增 `multimodalChatStream(...)`，事件比文本流多一个 `Vision`，其余复用同样的 SSE 解析。`fetchProduct` 顺手修了之前的 `NetworkOnMainThreadException`（漏 `withContext(Dispatchers.IO)`）。
- `ChatViewModel.kt`：抽出 `appendUserAndStartAssistant()` / `currentHistoryWire()` / `runStream()`，文本和多模态共用同一条流式管线；新增 `sendImage(base64, userText)`，多模态分支收到 `Vision` 事件时回填 `userMsg.visionKeywords` —— 这样关键词就显示在用户那张图下面，符合"我看到这些关键词在帮你搜"的交互预期。
- `ChatScreen.kt`：
  - `Scaffold` 内挂 `rememberLauncherForActivityResult(PickVisualMedia())`，回调里 `Dispatchers.IO` 跑压缩+base64，再 `vm.sendImage(...)`。
  - `InputBar` 加一个相册图标按钮（`Icons.Filled.Image`），busy 时禁用。
  - `MessageBubble` 用户消息分支：先渲染 180dp 缩略图（`ImageUtils.decodeThumbnail` + `asImageBitmap`），keywords 非空就渲染"正在按这些关键词搜：xxx" 灰字小提示；纯图（text 空）就不再渲染空气泡。
  - 命名冲突坑：`Icons.Filled.Image` 是个扩展属性，和 `androidx.compose.foundation.Image` 同名 —— 用 `import ... .Image as FoundationImage` 把 Composable 改名解决。
- 选 Photo Picker 而不是 `READ_EXTERNAL_STORAGE` 的相册：API 33+ 系统组件，免运行时权限，符合 minSdk=26 + 现代 Android 推荐做法（≤32 会自动 fall back，但目标真机鸿蒙 4.2 = Android 12，原生 picker 也 OK）。
- 构建：`JAVA_HOME=.../jbr ./gradlew :app:assembleDebug` BUILD SUCCESSFUL → `adb install -r` 已推到真机。

### 17.3 待用户操作

1. 用户开通火山方舟视觉模型 → 把 `ep-xxxx` 填入 `server/.env` 的 `ARK_VISION_MODEL=`
2. 重启后端，APP 内点输入栏左侧的图标按钮 → 选商品图 → 验证：
   - 用户气泡显示缩略图
   - 缩略图下方出现"正在按这些关键词搜：xxx"
   - 助手按现有流程检索 + 推荐 + 渲染卡片
3. 真机走通后写 W2 验收报告（D14）。

---

## 18. VLM 接入受阻 - 账号策略限制

### 18.1 客户端首次真机点开多模态报错

- 用户点输入栏图标按钮选图，APP 显示："出错了：服务端未配置ARK_VISION_MODEL，多模态接口不可用"
- 这是 `main.py` 在 `settings.ark_vision_model` 为空时主动 emit 的友好错误，链路本身没坏

### 18.2 用户控制台账号缺失

- 助手让用户去火山方舟控制台拿 endpoint ID
- 用户反馈："这都是公司给我的信息，我不知道我什么时候注册火山引擎了"
- 用户手上只有：`ARK_API_KEY` + `ARK_BASE_URL` + 文本模型 `ARK_CHAT_MODEL=ep-20260514111645-lmgt2`
- 没有控制台账号、没有 vision endpoint，公司是把 API 凭据脱给他用的

### 18.3 路线 A 探测：直接用模型名调

- 假设：新版 Ark API 可能允许直接用模型名调用
- 改 `.env`：`ARK_VISION_MODEL=doubao-1-5-vision-pro-32k-250115`
- 写 `server/scripts/test_vision.py` 一次性脚本，拿 `data/raw/.../p_beauty_019_live.jpg` 试调
- **结果失败，错误码明确**：
  ```
  404 InvalidEndpointOrModel.ModelIDAccessDisabled
  Accessing the model via Model ID is not allowed for your account.
  Please use a custom endpoint ID instead.
  ```
- 结论：用户公司的工作区配置强制要求 endpoint ID 调用，不允许 Model ID
- 已把 `.env` 还原为空，避免后端误用

### 18.4 测试脚本

`server/scripts/test_vision.py` 留下，用于将来拿到 endpoint ID 后 5 秒验证：
```
python scripts/test_vision.py <image_path>
```
打印 `ARK_VISION_MODEL` 配置、调用 `vision_describe`、输出关键词或异常详情。

### 18.5 下一步三选一（待用户决策）

1. **找公司同事在他们控制台开一个 vision endpoint**，用同一个 API Key 绑定。最省事，拿到 `ep-xxx` 填回 `.env` 即可。
2. **改做"图像 embedding 以图搜图"**：不走 VLM，用 CLIP/中文 image encoder 把用户图和商品库图都映射到同一向量空间，余弦近邻取 top-K 直接当检索结果（绕过文本 RAG）。约 1.5 天工作量。
3. **跳过多模态 bonus**：文本 RAG 已 36/36 满分，剩下 17 天投到答辩材料、演示视频、多轮场景扩充。"做精一项胜过浅尝三项"。

用户暂未决策。本节 17 标注的 D11-D12 后端、D13 客户端代码全在，链路验证只差一个 `ep-xxx`。

---

## 19. 双账号双 Key 解法 - 多模态全链路打通

### 19.1 用户决策

- 用户用自己的火山方舟个人账号建了 vision endpoint：`ep-20260524165749-kbdh5`（Doubao-1.5-Vision-Pro-32k）
- 用户给出对应 Key（已脱敏，存于 `.env` 的 `ARK_VISION_API_KEY`）
- 用户明确：公司给的文本模型不能丢（`ARK_CHAT_MODEL=ep-20260514111645-lmgt2` + 公司 Key）
- 选择：方案 B（双 Key 双账号），不放弃公司文本

### 19.2 方案 A 试错（可作为后人参考）

直接把公司 Key 切到个人账号试调 vision：跑通了，但 `chat_once` 立刻 403 —— 跨账号 endpoint 互不可见。证明账号之间是硬隔离，**只能拆 client**。

### 19.3 实现：双 client，最小耦合

- `server/app/config.py`：`Settings` 增加 `ark_vision_api_key: str = ""`，空时回退到 `ark_api_key`
- `server/app/llm_client.py`：
  - 保留原 `get_client()`（文本流用，公司 Key）
  - 新增 `get_vision_client()`（视觉流用，个人 Key 或回退公司 Key）
  - `vision_describe()` 改用 `get_vision_client()`，其它函数不动
- `server/.env`：恢复 `ARK_API_KEY=` 公司 Key + 新增 `ARK_VISION_API_KEY=` 个人 Key
- 改动总计 < 20 行，文本流路径零侵入

### 19.4 双端验证

```
[chat OK]  '你好，很高兴能在这里与你相遇...'        ← 公司 Key + ep-...lmgt2
[vision OK] '化妆水 兰蔻 粉色瓶身 日常护肤'          ← 个人 Key + ep-...kbdh5（识图准确度极高）
```

### 19.5 真机端到端

后端重启 → 用户在 APP 内点输入栏图标 → 选商品图 → 链路全通。用户回复"应该没有问题了"，W2 多模态 bonus 验收通过。

### 19.6 经验沉淀

- 火山方舟 endpoint 与 API Key 绑账号；跨账号互调一律 403/404
- "用模型名直接调"看似省事，但工作区策略可禁用（`ModelIDAccessDisabled`）
- 双 Key 拆 client 是最干净的隔离方案，比改 `_chat_stream_generator` 加判断好得多
- `server/scripts/test_vision.py` 留作回归测试入口，未来换 endpoint 5 秒验证

---

## 20. W2 验收 + README + 评测扩充

### 20.1 W2 验收 (D14)

文本主链路重跑：30 单轮 + 6 多轮 = **36/36 100% 通过**，无回归。多模态 vision 单帧脚本 `test_vision.py` 通过（`化妆水 兰蔻 粉色瓶身 日常护肤`）。W2 全功能合格。

### 20.2 README 重写

- 全文重写 `README.md`：项目简介 / 架构 ASCII 图 / 目录树 / 快速开始 / 接口表 / 评测复现 / 关键决策 / RAG chunk 设计 / 安全提示
- 修复 `server/.env.example` 安全问题：原文件明文写了一个旧 Key（虽已失效），脱敏为 `ark-xxxxxxxx-...` 占位
- 补 `.env.example` 缺失字段：`ARK_VISION_MODEL` + `ARK_VISION_API_KEY`，附用法说明
- 校验 `.gitignore`：`.env` 受保护，`!.env.example` 例外允许提交，安全策略 OK

### 20.3 多轮评测扩充 6 条 → 12 条

为覆盖之前没测的盲区，新增：

- m07 品牌限定（`brand_in`，篮球鞋→李宁）：之前只有 `brand_not_in`，没正向限定
- m08 约束累计后变无解：1000→500 跳水，应识别拒答而不是硬推 ¥899 的鞋
- m09 切品类不污染：精华→防晒，验证不被前一轮品类带偏
- m10 排除独家品牌即无解：瑜伽裤库内仅 1 件露露乐蒙，排除即无候选
- m11 三约束累计 + brand_in：华为 + 大屏 + 6000 以内 + 平板，三轮信息全保留
- m12 "算了"的精准范围：仅否定 Nike + 上调预算，原"跑鞋"诉求保留

### 20.4 终评结果（v2）

```
pass_rate: 100.0
n_total:   42
n_passed:  42
retrieval_any_hit_rate: 96.3% (n=27)
tag_coverage_rate: 100%
constraint_ok_rate: 100%
refuse_rate: 100%
ask_back_rate: 100%
```

亮点：
- m11 输出 "结合你华为品牌、大屏、6000以内、轻办公的需求…" → 三轮信息全部累积
- m12 "算了，不要 Nike 的，预算放宽到2000" → 模型只覆盖被显式撤回的两项，跑鞋诉求保留
- m08/m10 拒答行为正确：约束→不可达时主动说"暂无满足"，没硬塞商品

报告：`eval/results/run-20260524-173736.md`

---

## 21. D15 - 加分项 4.2：语音输入（ASR）+ 语音播报（TTS）

### 21.1 决策

对照课题说明会重读后用户挑了 4.2 ⭐ + ⭐⭐：语音输入（ASR）+ TTS 语音播报。
拍板技术路线：
- ASR 走**火山引擎"录音文件识别极速版"**（独立网关，与 Ark 不共账号），识别质量与现有 Vision 一脉相承。
- TTS 走 **Android 原生 `TextToSpeech`**，零后端改动、零额外 key，华为机自带中文 voice。

### 21.2 ASR 网关与鉴权（关键差异于 Ark + v1/v3 路线分叉）

火山 ASR 同一个网关 `openspeech.bytedance.com` 下其实有**两套互不兼容的 API**：

| 项 | 文本/视觉（Ark） | ASR v3 flash（极速版同步） | ASR v1 AUC（提交-轮询，本项目用） |
|---|---|---|---|
| 路径 | `/api/v3/...` | `/api/v3/auc/bigmodel/recognize/flash` | `/api/v1/auc/submit` + `/api/v1/auc/query` |
| 鉴权 | `Authorization: Bearer ark-xxx` | `X-Api-App-Key` + `X-Api-Access-Key` + `X-Api-Resource-Id` 等 5 个 header | 单 header `x-api-key: <uuid>` |
| 凭据形态 | 一条 `ark-xxx` Key | App ID + Access Token | 单个 API Key（控制台直接生成） |
| 响应模式 | 同步 | 同步（响应头看 `X-Api-Status-Code=20000000`） | 异步：submit→拿 `id`→poll query 直到完成 |
| body 关键字段 | 标准 OpenAI | `audio.data/format/rate/bits/channel` + `request.model_name` | `app.cluster=volc_auc_common` + `audio.data/format/rate` + `additions.language=zh-CN` |

**关键坑 1**：`volcengine` 官方 Python SDK 不含 ASR，httpx 直接 POST。

**关键坑 2（这次踩了）**：调研一开始写了 v3 flash 客户端（多 header 同步模式），但用户从控制台拿到的是 v1 AUC 的单 `x-api-key`。两套 API 完全不互通，凭据塞错版本就 401/404。**全部推翻重写为 v1 submit/query 异步模式**。

**关键坑 3（也踩了）**：v1 query 响应里的状态码与"成功=0"的常识相反——
- `code=2000` 是中间态（`message="Aed is finished. The next step is asr"`）
- `code=1000` 才是成功（`message="Success"`，text 已 ready）

教训：见到不熟悉 API 别按经验猜，第一轮就把每次 poll 的 raw body 打 INFO 日志，看 1 个完整生命周期再写状态机。

### 21.3 后端实现（终版 = v1 提交-轮询）

- `server/app/asr_client.py`（新文件）：`recognize(audio_bytes, audio_format)`，httpx 直连 openspeech v1 网关。
  - `POST /api/v1/auc/submit` 携带 base64 内联音频 + `app.cluster=volc_auc_common`，拿任务 `id`
  - 每 0.5s `POST /api/v1/auc/query` 轮询，最多 30s 超时
  - 状态机：`code=2000` 继续 / `code=1000 + text 非空` 返回 / 其它抛 `ASRError`
- `server/app/main.py`：`POST /asr`，body `{audio_base64, format, sample_rate}`，返回 `{text}` 或 `{error}`。
- `server/app/config.py`：单字段 `volc_asr_api_key: str = ""`（取代之前的 app_id+access_token 双字段），未配置时 `/asr` 主动报"未配置"。
- `server/.env` 新增 `VOLC_ASR_API_KEY=`；`.env.example` 注明控制台入口与 v1 路径。
- `server/scripts/test_asr.py` 拿 wav 一条命令验证联通性，输出"识别结果：..."。
- 烟囱测试通过：`/tmp/asr_sample.wav`（macOS `say -v Tingting "推荐一款适合油皮的洗面奶"` + afconvert 转 16kHz/mono/16bit WAV）→ 1.0s 内返回正确文本。

### 21.4 客户端实现

- `AndroidManifest.xml`：加 `RECORD_AUDIO` 权限。
- `AudioRecorder.kt`（新文件）：`AudioRecord` 16kHz/16bit/mono PCM 录音，`stopAndGetWav()` 给 PCM 加 44 字节 WAV 头，最大时长 30s，<0.3s 自动丢弃。
- `Models.kt`：新增 `AsrRequestWire` / `AsrResponseWire`。
- `ChatRepository.kt`：`recognizeAudio(wavBytes)` POST `/asr`，返回识别文本。
- `ChatViewModel.kt`：
  - 新增 `RecordState.Idle / Recording / Recognizing` 三态机。
  - `toggleRecording()` 第一次点开录、第二次点停录+识别+回填。**回填策略：识别结果追加到现有输入框，不覆盖**——用户可以"先打几个关键词，再口述补充"。
- `ChatScreen.kt`：
  - `InputBar` 加麦克风按钮（`Icons.Filled.Mic` / `Icons.Filled.Stop`），录音中输入栏顶部出现红色"正在录音…"指示条，识别中按钮变 `CircularProgressIndicator`。
  - 麦克风权限 `rememberLauncherForActivityResult(RequestPermission)` 现取现授，deny 给 Toast。
- `TtsManager.kt`（新文件）：包装 `android.speech.tts.TextToSpeech`，单例锁中文 Locale，`UtteranceProgressListener` 回调维护 `currentSpeakingId`，UI 据此切换播放/停止图标。
- `MessageBubble`：助手消息**流式结束 + 文本非空** 才挂"朗读"按钮（避免边流边播错乱）；点击切换播放/停止；播放时按钮变红 + 文字"朗读中…"。
- 朗读文本走 `stripProductTags`，不会把 `[[PRODUCT:xxx]]` 念出来。

### 21.5 编译/装机

`./gradlew :app:assembleDebug` BUILD SUCCESSFUL；`adb install -r` 已推到华为 P40 Pro。

### 21.6 待用户操作

ASR 后端已联通：用户在 https://console.volcengine.com/speech/app 开通"豆包·语音识别"应用并生成 API Key 后，填入 `server/.env`：

```
VOLC_ASR_API_KEY=<控制台生成的 uuid 形 key>
```

后端已重启（PID 18988，含 `/asr` 路由 + v1 客户端）；本地 curl 验证 `{"text":"推荐一款适合油皮的洗面奶？"}` ✅。

下一步真机端到端：APP 内点麦克风按钮 → 录一句"推荐一款适合油皮的洗面奶" → 识别文本回填输入框 → 发送 → 助手回复后点"朗读"听 TTS。

TTS 不需要任何配置，装机后立即可用（华为机自带中文引擎）。

---

## 22. ASR 真机失败诊断 + v1 凭据 pivot（2026-05-24 晚）

### 22.1 用户反馈

> "你给我的测试，失败了。没有识别回填"

链路按 21.x 实现完装机后，真机点麦克风→录音→识别 完全没回填。先按从远到近的顺序定位。

### 22.2 三个并发问题诊断

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 后端 `/asr` 返回 404 | 在跑的 uvicorn 是 17:13 启动的旧进程（PID 13130），早于 21.3 加路由的提交 | `kill 13130` + 用新代码重启 → PID 18988 |
| 2 | 用户控制台找不到"录音文件识别极速版" | 火山把它改名为"豆包·语音识别"，入口移到 https://console.volcengine.com/speech/app 而非"语音技术" | 用 general-purpose agent 查官网走通后告诉用户 |
| 3 | 用户开通后给的是单 `x-api-key`，21.3 的客户端用的是 v3 flash 多 header 鉴权 | 火山同一域名下 v1/v3 是两套 API，凭据形态不同（详见 21.2） | `asr_client.py` 全部推翻重写为 v1 submit/query |

### 22.3 v1 状态机的两轮试错

第 1 轮：当成"code=0 成功"，烟囱测试报错 `code=2000 message="Aed is finished. The next step is asr"`。把 2000 加进 in-progress 集合。

第 2 轮：30s 一直轮询都是 `code=1000 message=Success text="推荐一款适合油皮的洗面奶？"`，超时退出。看 INFO 日志才发现 v1 的 1000 = 成功（不是 0、不是 200000xx）。修改：`text 非空 + code∈{1000}` 即返回。

第 3 轮：1.0s 内返回正确文本。

教训沉淀进 21.2 "关键坑 3"，避免下次再撞。

### 22.4 当前状态

- `server/app/asr_client.py` 已是 v1 submit/query 终版，状态机已校准
- `server/app/config.py` 已切单字段 `volc_asr_api_key`
- `server/.env` 已写入用户提供的 key（uuid 形）
- 后端 PID 18988 监听 0.0.0.0:8000，含 `/asr` 路由
- 本地两层验证均通过：脚本 `python scripts/test_asr.py /tmp/asr_sample.wav` ✅；HTTP `curl POST /asr` ✅
- 真机端到端待测（用户操作）：APP 麦克风→录音→看输入框是否回填→发送→听 TTS

### 22.5 测试样本生成命令

供后续回归用：
```
say -v Tingting "推荐一款适合油皮的洗面奶" -o /tmp/sample.aiff
afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/sample.aiff /tmp/sample.wav
```
得到 16kHz/mono/16bit PCM WAV，与 Android 端 `AudioRecorder` 录出的格式一致。

### 22.6 真机验收（2026-05-24 22:50）

用户回复："真机测试没问题"。后端日志佐证整条链路 200：

```
192.168.1.2  POST /asr            200
192.168.1.2  POST /chat/stream    200
192.168.1.2  GET  /products/p_beauty_011  200
192.168.1.2  GET  /static/1_美妆护肤/images/p_beauty_011_live.jpg  200
```

W2 加分项 4.2 ⭐ + ⭐⭐ 全数验收通过。

---

## 23. 完整功能清单 + 漏洞审计（2026-05-24 22:50）

### 23.1 已 ship 的能力（对照赛题）

| 赛题维度 | 状态 | 实现要点 |
|---|---|---|
| 客户端原生开发 | ✅ | Android Kotlin + Jetpack Compose，minSdk 26，鸿蒙 4.2 真机演示 |
| 后端 + 向量库 + 大模型 | ✅ | FastAPI + Chroma + 本地 BGE-base-zh-v1.5 + 豆包 Seed-2.0-lite |
| 流式交互媲美豆包 | ✅ | SSE + 客户端打字机节奏器（25ms/字 ≈ 40 字/秒）|
| 商品卡片实时渲染 | ✅ | `[[PRODUCT:xxx]]` 内联标签 + 客户端边流边解析 + Coil 异步图 |
| RAG 保障专业性/抗幻觉 | ✅ | 100 商品 / 1199 chunk × 4 粒度（meta/marketing/faq/review）|
| 端到端评测闭环 | ✅ | 42 query（30 单轮 + 12 多轮），retrieval@k / 标签覆盖 / 约束合规 / 拒答 / 反问 五指标 100% |
| 多模态输入（加分） | ✅ | 拍照搜：相册 PickVisualMedia → 客户端压 ≤512px → VLM 抽关键词 → 走文本 RAG |
| 语音输入 ASR（加分 4.2 ⭐） | ✅ | 火山 v1 submit/query + Android `AudioRecord` 16kHz PCM→WAV |
| 语音播报 TTS（加分 4.2 ⭐⭐） | ✅ | Android 系统 `TextToSpeech` 单例 + UtteranceProgressListener 双向同步 |
| 多轮对话稳定性 | ✅ | 检索 query 拼最近 3 条 user 消息 + system prompt 第 6 条"约束累计 vs 否定覆盖"|

### 23.2 已知漏洞与潜在风险（按严重度）

#### 高（演示/答辩前建议修）

1. **`/asr` 路由把意外异常吞成 500**
   `main.py:282` 只 catch `ASRError`。base64 太大（如 ≥30s 录音）时 httpx/asyncio 抛 `TimeoutError` 不是 `ASRError`，FastAPI 默认 500，客户端 `recognizeAudio` 直接返回 null，用户看不到原因。
   修：在 ASR 路由外层多加一个 `except Exception as e: return {"error": f"asr 失败: {e}"}`。

2. **客户端 `BASE_URL` 写死局域网 IP**
   `Config.kt:12` = `192.168.1.42:8000`。比赛现场换 WiFi/换网段就崩。题目说明 7.4 已要求"远程联调"。
   修：抽到 BuildConfig 或加内置 settings 页面，演示前用 `adb` 改一次。

3. **CORS 全开 + 明文 HTTP**
   后端 `allow_origins=["*"]`、Manifest `usesCleartextTraffic="true"`，赛事评审若做静态扫描会扣分。
   修：`allow_origins` 限制 LAN 段，或答辩材料里说明"局域网演示场景"。

#### 中（不影响演示，留作技术债）

4. **ASR 状态机的"无限轮询"分支**
   `asr_client.py:117` `if code is None: continue` —— 如果服务端返回缺 `code` 字段，会一直转到超时。已加 30s 上限兜底，但日志看不到中间响应（已降级为 `log.debug`）。
   修：第一次见 `code is None` 就 warn 一次，避免静默吞错。

5. **submit 阶段非 200 错误透传**
   `r.status_code != 200` 直接抛 `ASRError(f"...{r.text[:200]}")`，截 200 字。火山实际错误体可能更长且含中文。
   影响小，可保留。

6. **`AudioRecorder` 缺 try/catch start**
   `start()` 里 `AudioRecord(...)` 构造可能抛 `IllegalArgumentException`（极端机型）。当前直接崩。
   修：包 `runCatching` 兜底返回 false。

7. **TTS 初始化竞态**
   `TtsManager.init` 里 `tts = TextToSpeech(...)` 是异步回调式 ready；用户在 onInit 完成前就点"朗读"，`speak()` 早走的是 `ready=false` 分支（已处理：log.warn + return）。功能正确，但用户感知是"点了没反应"。
   修：UI 在 `ready=true` 之前禁用"朗读"按钮（多挂一层 state）。低优。

8. **`updateMessage` 在每个 token 都全列表 map 一遍**
   `ChatViewModel.kt:191`：每 25ms 一次 token + 商品标签 regex 全量重扫整段文本。100 token 长回复 = O(n²) ≈ 10k 次正则匹配。当前 LazyColumn 还能跟得上，但消息历史长了会卡。
   修：在 message 上缓存 `processedTagOffset`，只扫新增片段。

9. **`/chat/stream/multimodal` 错误事件未关闭流**
   `main.py:217` ARK_VISION_MODEL 未配置时 yield error 后 generator 直接结束，sse_starlette 自动关流；但客户端 `EventSourceListener` 未必收到 `onClosed`，已观察到 OkHttp 会触发 `onFailure`。已通过 try/runCatching 兜住，UI 显示 "出错了：..."。低优。

#### 低（仅文档/演示打磨）

10. **`.env.example` 与 `.env` 字段稍有漂移**
    `.env.example` 有 `ARK_VISION_MODEL=` 注释，`.env` 已填值，没问题；`VOLC_ASR_API_KEY=` 在 example 里也只留空字段，符合预期。无需改。

11. **`config.py` 把 `volc_asr_url` 拆成 `submit/query` 两个**
    旧字段 `volc_asr_url` 已删掉，但旧 `.env.example`（如果别处复制过）可能还引用，已同步清理。无残留。

12. **服务端没有 graceful shutdown 钩子**
    Chroma client 是全局单例，进程被 `kill -9` 时偶发写日志告警，对功能无影响。

### 23.3 高优漏洞修复（2026-05-24 23:30）

按 §23.2 的"高严重度"清单一次性收掉：

**(1) `/asr` 路由异常吞掉**
- `main.py:295` 多挂一层 `except Exception`，意外异常（超时/反序列化/网络）也会以 `{"error": "..."}` 返回，客户端能拿到原因
- 不再裸跑到 FastAPI 默认 500，UI 可以显示"出错了：xxx"

**(2) BASE_URL 抽到 SharedPreferences + APP 内 Dialog**
- `Config.kt` 改造：`BASE_URL` 变成 `get` 属性，从 `shopguide_config` prefs 读
- `MainActivity.onCreate` 调 `Config.init(applicationContext)` 一次（必须早于任何网络访问）
- `ChatScreen` TopBar 加 `Icons.Filled.Settings` 齿轮按钮 → 弹 `ServerSettingsDialog`
- Dialog 内 `OutlinedTextField` 显示当前 URL，placeholder = `DEFAULT_BASE_URL`，保存即写 prefs + Toast 反馈
- `Config.update()` 自带 normalize：去末尾斜杠、缺协议补 `http://`
- 演示日换 WiFi 网段不需要重编 APK，齿轮里改 IP 即可

**(3) CORS 限制 + cleartext 注释**
- `main.py` CORS 从 `allow_origins=["*"]` 改为 `allow_origin_regex` 仅匹配 RFC 1918 局域网网段（10.x / 192.168.x / 172.16-31.x / 127.0.0.1 / localhost）
- `allow_methods` 收到 `["GET", "POST"]`、`allow_headers` 收到 `["Content-Type", "Accept"]`
- Manifest 保留 `usesCleartextTraffic="true"`（局域网真机演示场景必需），但加注释说明"生产部署应反向代理上 https"
- 中途试过写 `network_security_config.xml` 限定网段，发现 Android NSC 不支持 CIDR/通配 IP 又会变，删除回退到 manifest 注释方案最干净

**编译/装机**：`./gradlew :app:assembleDebug` BUILD SUCCESSFUL（3 秒增量）→ `adb install -r` Success → 后端重启 PID 19865，`/health` + `/asr` 错误路径均验证 200。

### 23.4 答辩可讲的工程亮点汇总

1. **抗幻觉证据链**：D2 没 RAG 时模型编"旁氏竹炭洗面奶"——这是"为什么必须做 RAG"的最强论据
2. **检索质量**：1199 chunk × 4 粒度 + chunk 标签前置 + 召回后按 product_id 聚合
3. **流式交互**：豆包"思考再吐"被客户端打字机节奏器抹平，达到豆包同款 40 字/秒的舒适阅读速度
4. **多轮约束累计**：检索 query 取最近 3 条 user + prompt 显式"否定才覆盖"双保险
5. **多模态分层**：VLM 只做"看图→关键词"，检索仍走已验证的文本 RAG，避免端到端不可控
6. **双账号 Key 隔离**：文本/视觉/ASR 三个独立网关 + 三个 Key，证据沉淀 19 节
7. **42 条评测集 100% 通过**：30 单轮 + 12 多轮，覆盖 6 个能力维度
8. **答辩可复现**：`run_eval.py` 一键跑 markdown 报告

---

## 24. D16 - 检索深度优化与 RAG 论证（2026-05-25）

W2 验收已 100%，本节为"答辩素材深度补强"，目标是让"为什么 RAG / 为什么 4 类 chunk / 为什么混合检索"用**对比数据**说话，而不是靠口述。

### 24.1 决策树（用户挑了 1+2+3）

提供三档候选：
1. 混合检索 + 重排（vector + BM25 + cross-encoder reranker）— 拉 retrieval@5 上限
2. Groundedness 句级评测 — 量化"模型有没有在事实里编"
3. Ablation 对比实验 — no_rag / vector_only / hybrid / hybrid_rerank 4 配置同评测集对比

未选的二档：技术债收尾、消息持久化（Room）。
未选的三档：扩 200 条数据、单测、Prometheus、prompt injection 防御。

### 24.2 混合检索 + 重排实现

新文件 `server/app/hybrid_retriever.py`：
- **召回层**
  - 向量：复用 BGE-base-zh-v1.5 + Chroma，top-30
  - BM25：jieba 分词建索引，top-30，懒加载缓存（首次调用 ~0.2s 建词典）
- **融合层**：RRF（Reciprocal Rank Fusion，K=60），不需要分数标定，对异构得分鲁棒
- **重排层**：`BAAI/bge-reranker-base` cross-encoder，max_length=512，对 top-30 chunk 重排到 top-K
- **聚合层**：原 retriever 聚合逻辑搬过来，按 product_id 聚合，每商品最多 3 个 matched_chunks
- **mode 三态**：vector_only / hybrid / hybrid_rerank（默认）

`retriever.py` 重构为 hybrid_retriever 的薄壳，保留 `retrieve()` 公开 API + `mode` 参数。
`main.py` 加 `ChatRequest.retrieval_mode` 字段（默认 hybrid_rerank）+ `@app.on_event("startup")` 钩子触发 `warmup_retriever()`，避免首请求被冷启动拖慢。

依赖增量：`rank-bm25==0.2.2 jieba==0.42.1`，requirements.txt 已同步。

**关键坑 1**：reranker 首次下载 ~400MB / 5 分钟（HF Hub），后续走本地缓存秒级。装机环境要预留时间。

**关键坑 2**：CrossEncoder 用 `model.predict(pairs, show_progress_bar=False)` 才不会污染 stdout（默认会打 tqdm 条）。

### 24.3 Groundedness 评测指标

`eval/run_eval.py` 新增 `score_groundedness`：
- 把模型回复用 `[。！？!?；;\n]` 切句，过滤 <6 字 + 纯标点行
- 切句前先用 `PRODUCT_TAG_RE.sub("", text)` 剥掉 `[[PRODUCT:xxx]]` 标签，避免污染句向量
- 用 BGE-base-zh-v1.5 编码每句 + 召回 top-8 商品的所有 chunk
- 对每句取 `max(cosine_sim(sent, chunk))`，<0.4 算 hallucination
- 汇总 `grounded_sentence_rate = 有据可依的句数 / 总句数`

### 24.4 Ablation 对比实验

`run_eval.py` 加 `--mode` / `--ablation` 开关，`--ablation` 一键跑 4 mode：
- `no_rag`：客户端发 `use_rag=False`，模型裸跑
- `vector_only`：纯向量召回（旧 baseline）
- `hybrid`：vector + BM25 RRF
- `hybrid_rerank`：默认配置

每 mode 单独输出 `run-<ts>-<mode>.{md,jsonl}`，最后合成 `ablation-<ts>.md` 对比表。

**关键坑 3**：第一次跑完发现 Retrieval top-1 hit **全 0%**——row 里没存 `gold_ids` 字段，聚合时 `r.get("gold_ids", [])` 永远是空。修两处：
1. `run_eval.py` 的 result 加上 `gold_ids` 字段
2. 新增 `eval/build_ablation_report.py`：从已存 jsonl 反查 eval_set 补齐 gold_ids，重新聚合，免重跑后端 30 分钟

**关键坑 4**：`write_ablation_report` 的 markdown 模板里写了中文双引号嵌在 Python 双引号字符串里，SyntaxError。改成全角"…"或避免嵌引号。

### 24.5 终评结果（42 条同评测集 4 mode）

| 指标 \ 模式 | `no_rag` | `vector_only` | `hybrid` | `hybrid_rerank` |
|---|---|---|---|---|
| 综合通过率 | 31.0% | **100%** | 92.9% | **100%** |
| Retrieval @5 | 0.0% | 96.3% | 85.2% | 96.3% |
| Retrieval top-1 hit | 0.0% | 55.6% | **59.3%** | 55.6% |
| 商品标签覆盖率 | 0.0% | 100% | 88.9% | 100% |
| 约束合规率 | 0.0% | 100% | 88.9% | 100% |
| 拒答正确率 | 100% | 100% | 100% | 100% |
| 反问正确率 | 60.0% | 100% | 100% | 100% |
| 句级 Groundedness | 48.1% | 100% | 97.3% | 100% |

报告：`eval/results/ablation-20260525-171634.md`。

### 24.6 三个发现（答辩可直接用）

1. **no_rag → vector_only 是 RAG 必要性的铁证**
   - groundedness 48.1% → 100%，标签覆盖 0% → 100%，通过率 31.0% → 100%
   - 无 RAG 时近一半句子在编（含编品牌、编价格、编功效）。这条数据足以钉死答辩中"为什么必须 RAG"的论点。

2. **hybrid 单跑反而比 vector_only 差（-7.1pp）**
   - 100 条小数据集 + 中文电商场景，BM25 没做领域 IDF 加权 → 高频词（"洗面奶"）会让多个商品同时升排名，把语义最相关的挤出 top-5
   - **诚实结论**：当前评测集没有强字面 query（"500 元李宁韦德之道"这类），BM25 的优势点没被覆盖，答辩时不要硬讲"hybrid 完胜"

3. **hybrid_rerank 把 hybrid 救回 100%**
   - cross-encoder 拼 query+chunk 过 BERT 直接打分，比双塔 cosine 强得多
   - 通过率从 92.9% → 100%，groundedness 从 97.3% → 100%
   - **答辩话术**：混合检索为字面查询提供保险（线上场景有用），重排为最终决策提供精度保险

### 24.7 已知遗留 / 未做

- 评测集没扩字面 query 子集，`hybrid` 真实增益没量化（半小时活，按需做）
- `hybrid` 在通用电商场景的劣势未必出现在线上业务集——结论仅对当前 42 条有效

### 24.8 用户对话保存（本节末）

D16 新增文件清单：
- `server/app/hybrid_retriever.py` 新增
- `server/app/retriever.py` 重构为薄壳
- `server/app/main.py` ChatRequest 加 retrieval_mode + 启动 warmup
- `server/requirements.txt` 加 rank-bm25 / jieba
- `eval/run_eval.py` 加 groundedness + ablation 模式
- `eval/build_ablation_report.py` 新增（jsonl 反查工具）
- `eval/results/run-20260525-171634-{no_rag,vector_only,hybrid,hybrid_rerank}.{md,jsonl}` × 4 mode
- `eval/results/ablation-20260525-171634.md` 对比表

---

## 25. APIKEY 泄漏审计与轮换（2026-05-25 19:48）

### 25.1 项目组通报

> 自 5 月 20 日项目启动以来，已经出现 6 次 APIKEY/EP 账号被上传至 GitHub、印象笔记、小红书等公开媒体，导致账号信息公开泄漏，强制封禁。
> 自 5 月 26 日起，对于上传代码至 GitHub 未删除 APIKEY、印象笔记、网盘、小红书等媒体渠道导致账号泄漏的同学，强制启动退赛处理。

### 25.2 用户主动要求审计

### 25.3 扫描结果

```
grep -rEn "ark-[0-9a-f]{8}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" .
  排除 .venv / node_modules / .gradle / build / chroma_db / __pycache__ / *.docx
```

发现 **2 处文本字面 Key 泄漏**：
- `docs/conversation_log.md:111` 旧 ARK_API_KEY 字面值
- `docs/conversation_log.md:473` 旧 ARK_VISION_API_KEY 字面值

排除项：
- `server/.env`（含当前 Key，已 .gitignore 排除）
- 项目根目录下**未初始化 git**，从未推送任何代码到 GitHub

### 25.4 处理动作

1. `conversation_log.md:111` 旧 Key → "（已脱敏）"
2. `conversation_log.md:473` 旧 Key → "（已脱敏，存于 .env 的 ARK_VISION_API_KEY）"
3. `server/.env` 切到第 4 个 Key（尾段 `-dd134`）
4. 后端重启 PID 10649，`/chat` 联通验证通过

### 25.5 沉淀给用户的红线规则

- 不要把 `.env` 内容贴到聊天 / 笔记 / 小红书
- 不要把 `docs/conversation_log.md` 整段对话贴到公开平台（云笔记同步会泄漏）
- 推 GitHub 前 `git status` 确认 `.env` 在 ignore 里
- 截图 / 录屏前关掉显示 `.env` 的编辑器标签

### 25.6 Key 轮换历史（仅留账号编号尾段，便于追溯）

| 日期 | 用途 | 状态 |
|---|---|---|
| 2026-05-20 | ARK_API_KEY (尾 -2af51d30) | 401 失效 |
| 2026-05-21 | ARK_API_KEY (尾 -40a6c) | 已脱敏，已轮换 |
| 2026-05-25 16:25 | ARK_API_KEY (尾 -a3c54) | Key 不存在，401 |
| 2026-05-25 16:31 | ARK_API_KEY (尾 -dd663) | 通过 |
| 2026-05-25 19:48 | ARK_API_KEY (尾 -dd134) | 当前使用 |
| 2026-05-23 | ARK_VISION_API_KEY (尾 -0a196) | 已脱敏，仍在使用 |
| 2026-05-24 | VOLC_ASR_API_KEY (尾 -16cc) | 仍在使用 |

---

## 26. D17 - 多模态深化 #1：CameraX 端侧采集 + 端侧预处理（2026-05-26）

### 26.1 决策背景

用户决定停掉"多个加分项浅尝"的思路，对着 4.2 ⭐⭐⭐ 拍照搜深做。课题考察点拆成两条：
1. **端侧多模态输入采集与预处理能力**（本节落地）
2. **后端如何将非文本输入转化为可检索的语义表示**（下一节，CLIP 图像 embedding）

字面漏洞：题目原话"调用摄像头拍摄实物"，原实现只走相册 Photo Picker，演示时被追问会尴尬。

### 26.2 端侧采集链路（CameraX）

新文件 `client/.../CameraScreen.kt`：
- `androidx.camera:camera-{core,camera2,lifecycle,view}:1.3.4` 全套依赖
- `AndroidManifest.xml` 加 `CAMERA` 权限 + `<uses-feature ... required="false">` 避免没相机的设备装不上
- `PreviewView` 全屏 + `ImageCapture(CAPTURE_MODE_MINIMIZE_LATENCY)`
- 居中虚线方框（Canvas + dashPathEffect）+ "把商品放在框内"提示
- 经典快门按钮（白色圆）+ busy 时换 spinner
- `bindCamera()` 单独抽出，处理 `ProcessCameraProvider.getInstance(...).addListener` 异步绑定生命周期

### 26.3 端侧预处理（重写 `ImageUtils.kt`）

升级为答辩可讲的"采集→矫正→缩放→质量评估"流水线：

1. **EXIF 旋转矫正**：`ExifInterface(ByteArrayInputStream(jpegBytes))` 读 Orientation tag，按 90/180/270 度 `Matrix.postRotate`。CameraX 在多数机型上回的是横向 buffer + Orientation tag，不矫正会让竖拍图躺平，VLM 看到躺平图识别会偏。
2. **512px 缩放 + JPEG 80%**：上传体积 ~70KB，VLM 不需要更大尺寸（D11 实测 SENKA 洗面奶用的就是 512px JPEG）。
3. **拉普拉斯方差锐度估值**：3×3 二阶差分核扫灰度图取方差，方差小=高频信息少=模糊。256×256 单线程 ~10ms。返回 `CapturedImage(base64, w, h, sizeKb, sharpness, isBlurry)`。

API：
- `captureFromBytes(jpegBytes)` 给相机用
- `captureFromUri(context, uri)` 给相册用
- 两条路径汇聚到同一个预处理函数，保证一致性

### 26.4 模糊软提醒交互

- 阈值标定：用户按指示在华为 P40 Pro 上拍三张
  - 稳拍清晰：sharpness=1456.1
  - 快速拖糊 1：491.6
  - 快速拖糊 2：279.2
- 初版阈值 80 漏判全部拖糊（手机运动稳像把轻糊救回到 ~500），改为 **500**，注释里注明数据来源、机型、日期，便于换机标定
- 触发软提醒后弹 `AlertDialog`：「图片有点模糊（锐度估值 xx，参考阈值 500）」二选一【重拍 / 照样使用】，符合"取得考察分但不陷用户手脱"

### 26.5 入口下拉菜单

`ChatScreen.kt` 输入栏左侧 `AddAPhoto` 图标 → `DropdownMenu`【拍照（CameraAlt）/ 从相册选择（PhotoLibrary）】：
- 拍照走新 CameraScreen，权限按需申请
- 相册走原 PickVisualMedia，作为兜底
- 老 `Icons.Filled.Image` 图标改 `AddAPhoto`，更直观传达"拍照搜"

`showCamera` 状态在 ChatScreen 内 `if (showCamera) { CameraScreen(...); return }` 形式覆盖整个 Composable，避开 Navigation 库的引入成本。

### 26.6 编译装机

- BUILD SUCCESSFUL，仅 1 个 deprecation warning（`LocalLifecycleOwner` 移到 lifecycle-runtime-compose，不影响功能）
- `libimage_processing_util_jni.so` 不能 strip，是 CameraX 已知行为，无害
- adb install -r 推到真机，两轮迭代（首次阈值 80 漏判 → 实测 + 改 500 → 二次装机）后两个场景都正确：清晰直接通过、拖糊弹对话框

### 26.7 答辩话术（端侧预处理可讲点）

1. 三层预处理：EXIF 旋转 + 长边缩放 + JPEG 量化，体积从 ~2MB 降到 ~70KB，弱网现场演示秒发
2. 拉普拉斯方差检测放在端侧的理由："采集→提示重拍"窗口只在端侧存在，到服务端再做就晚了
3. 阈值由真机标定而非拍脑袋——附上稳拍/拖糊对比数据
4. 取景框居中提示把"主体居中"语义引导前置到采集端，给后端 VLM 减一份噪声

### 26.8 待开始

下一节 §27：CLIP 中文图像 embedding 入库 + 双路召回（VLM 关键词文本 RAG + CLIP 图像向量）+ ablation 对比表。

---

## 27. D18 — 多模态深化 #2：CLIP 图×图检索 + 双路融合 + ablation（2026-05-26）

**目标**："后端如何把非文本输入转成可检索的语义表示"——给端到端多模态加上真正的视觉空间检索。
不再依赖"VLM 把图变成文字 → 文本 RAG"的桥接，而是用 Chinese-CLIP 把用户图和商品主图都映射到同一向量空间做 cosine。

### 27.1 模型与封装

- 选型：`OFA-Sys/chinese-clip-vit-base-patch16`（512 维投影），中文 caption 友好，本地推理无 API 依赖
- `server/app/clip_embedder.py`：
  - 懒加载 + 全局锁单例（首次 ~600MB / ≈ 5 min 下载）
  - 三个出口：`encode_image` / `encode_images_batch(batch_size=8)` / `encode_text`
  - `warmup()` 给 FastAPI 启动钩子调用
- transformers 5.x 与 OFA-Sys repo `preprocessor_config.json` 不兼容（缺 `image_processor_type`）：
  绕过 `ChineseCLIPProcessor`，分别用 `ChineseCLIPImageProcessor.from_pretrained()` + `BertTokenizer.from_pretrained()`
- transformers 5.x 下 `get_image_features` 返回 `BaseModelOutputWithPooling` 而非张量，
  512 维投影在 `pooler_output` 字段；加 `_unwrap_features()` 兼容新旧两种返回

### 27.2 离线灌库

- `server/scripts/build_image_index.py`：扫 `data/products.jsonl` 的 100 张主图，CLIP 编码后写入 Chroma 新集合 `products_images`
- `server/app/vector_store.py` 新增 `IMAGE_COLLECTION_NAME = "products_images"` + `get_or_create_image_collection` / `reset_image_collection`（cosine space）
- 100 张 / batch=8 / CPU 上 10.8s 完成；shape=(100, 512)；集合大小 100

### 27.3 检索路径接入 SSE

- 新建 `server/app/image_retriever.py`：
  - `image_search(image_bytes, top_k)` 返回 `[(product_id, sim), ...]`，product 级
  - `fuse_with_text_retrieval(text_results, image_hits, top_k)` 在 product 级做 RRF 融合（避免 chunk 级"同商品多次重复打分"）
  - 仅图像命中的商品给 `matched_chunks=[{type: image_match, text: 'CLIP 相似度 X.XXX'}]` 占位
- `MultimodalChatRequest` 新增 `retrieval_strategy: vlm_only | clip_only | fusion`（默认 fusion）
- `/chat/stream/multimodal` 三档可切：`clip_only` 不依赖 VLM 模型可独立跑，新事件 `event=clip` 透出 CLIP 命中 + 耗时

### 27.4 评测脚本（两套）

**A. `eval/run_image_eval.py` — 主图自查（健壮性 + 聚类质量）**
- Track A: Self-recall@1（每张主图作 query top-1 应 = 自身）
- Track B: 同子类目 Recall@5（top-5 去自己后落在同 sub_category 占比）
- 100 件全量结果（耗时 7s）：
  - **Self-recall@1 = 100.0%**（索引和编码无 bug 的硬证据）
  - **同子类目 R@5 = 35.4%**（随机基线 ≈ 6%，远高于随机 → CLIP 视觉聚类有强信号）
  - 数码品类聚类最强：智能手机 94% / 笔记本 63% / 平板 54%
  - 弱项：单件子类目（卸妆/眉笔/瑜伽裤）天然 R@5=0，分母小可忽略

**B. `eval/run_image_ablation.py` — vlm_only / clip_only / fusion 三档对比**
- 4 大 category 各抽 5 件 = 20 件分层样本
- 对每件主图三档配置各跑一次，统计 self@1 / self@5 / sub@5 / 平均延迟

| 指标 | vlm_only | clip_only | fusion |
| --- | --- | --- | --- |
| Self-recall@1 | 80.0% | **100.0%** | 95.0% |
| Self-recall@5 | 100% | 100% | 100% |
| 同子类目 R@5 | 35.0% | 31.0% | **37.0%** |
| 平均延迟 | 6864ms | **88ms** | 5757ms |

### 27.5 关键论证（答辩用）

1. **vlm_only self@1=80% vs clip_only self@1=100%**：VLM 抽出的关键词没法精确还原原标题（笔记本电脑等品类丢得最多），而 CLIP 在像素空间天然能命中同款商品 → 证明视觉表征比文本桥接对"同款检索"更鲁棒
2. **clip_only 88ms vs vlm_only 6864ms**：CLIP 路径无需 VLM API 调用，延迟低 78 倍。clip_only 单独跑可在没有 VLM 配额的演示场景下保留多模态能力
3. **fusion 在 sub@5 上最高（37% > 35% > 31%）**：RRF 融合在子类目聚类上同时拿到了 VLM 的语义优势（"运动裤"识别）和 CLIP 的视觉优势（同款式包装），是端到端推荐质量最高的一档
4. **fusion 比 clip_only self@1 略低（95% < 100%）**：因为 RRF 给文本路径相同权重，文本路径偶发把同品类替代品挤进 top-1。要绝对的"找同款"用 clip_only，要"找同款 + 推同类替代"用 fusion

### 27.6 答辩话术（双路检索可讲点）

1. **多模态语义统一空间**：用户图和商品库主图共享同一个 Chinese-CLIP 编码器 + 同一个 cosine 度量，不依赖文本桥接
2. **product 级 RRF 而非 chunk 级**：文本侧聚合后才融合，免去"同商品 5 个 chunk 重复推分"的偏差
3. **三档可切的工程价值**：评测时切 `clip_only` 跳过 VLM API 配额限制；演示时切 `fusion` 拿最佳推荐质量；ablation 表是这三档的客观对比，不是拍脑袋
4. **数据可信**：100 件 self-recall 全通过 + 20 件三档对比表，不是"看着挺好"，而是有 evaluator 复跑能稳定复现的指标

### 27.7 踩坑修复（按时间顺序）

1. **`ChineseCLIPProcessor.from_pretrained` ValueError**：`Unrecognized image processor in OFA-Sys/...`，
   transformers 5.x 与该 repo 的 `preprocessor_config.json` 不兼容（缺 `image_processor_type` 键）。
   绕过：分别用 `ChineseCLIPImageProcessor.from_pretrained()` + `BertTokenizer.from_pretrained()`，不走统一 Processor。
2. **`ImportError: ChineseCLIPImageProcessor requires PIL/Torchvision`**：补装 `pillow torchvision`（Pillow 12.2.0 + torchvision 0.27.0）
3. **`AttributeError: 'BaseModelOutputWithPooling' object has no attribute 'norm'`**：transformers 5.x 下
   `get_image_features` / `get_text_features` 返回 `BaseModelOutputWithPooling`，512 维投影向量在 `pooler_output` 字段，
   而非旧版本直接返回张量。加 `_unwrap_features()` 兼容两种返回。
4. **eval 报告路径写错**：`RESULTS_DIR = PROJECT_ROOT.parent / "eval" / "results"` 把报告落到了仓库外的
   `字节AI挑战赛/eval/results/`，而 `PROJECT_ROOT` 已经指向 `ecommerce-agent`，应该是 `PROJECT_ROOT / "eval" / "results"`。
   已把误存的报告 mv 回正确目录并删除空壳。
5. **Python 字符串里中文双引号嵌套语法错误**：在 markdown 报告字符串里写了 `"...这是"自查"行为..."`，
   ASCII 双引号撞到外层字符串边界导致 `SyntaxError`。统一改成中文全角引号 `“”`。

### 27.9 沉淀文件清单

- 新增：`server/app/clip_embedder.py` / `server/app/image_retriever.py`
- 新增：`server/scripts/build_image_index.py`
- 修改：`server/app/vector_store.py`（图像集合）/ `server/app/main.py`（多模态分支三档）
- 新增评测：`eval/run_image_eval.py` / `eval/run_image_ablation.py`
- 报告：`eval/results/image_eval-20260526-162216.md` / `eval/results/image_ablation-20260526-163259.md`
- Chroma 集合：`server/chroma_db/` 下新增 `products_images`（100 条 × 512 维）

### 27.10 待开始

下一节 §28：把客户端 ChatScreen 拍照路径接入 `retrieval_strategy=fusion`（当前默认就是 fusion，但要在 UI 上把 `event=clip` 的耗时和命中数显示出来——给评委看到"图×图链路真的在跑"）

---

## 28. D19 — 进入 W3：API Key 轮换 + UI 紫色换肤 + 5 项 Agent 能力补齐 + 3 项多模态深化（2026-06-02）

距赛点剩 8 天。这一天集中做"答辩可见的优化"：先把模型 Key 轮换到当前可用的一把，再围绕赛题硬性能力做一次自查（5 项 Agent / 3 项多模态加分项），把所有发现的 gap 一并修掉。

### 28.1 ARK_API_KEY 轮换

- 用户提供新 Key（尾段 `-c7bc9`），仅 `ARK_API_KEY` 字段更新，`ARK_BASE_URL` / `ARK_CHAT_MODEL=ep-20260514111645-lmgt2` / `ARK_VISION_MODEL=ep-20260524165749-kbdh5` / `ARK_VISION_API_KEY` / `VOLC_ASR_API_KEY` 不变
- 仅改 `server/.env`，不入 git；后端重启后 `/health`、`/chat` 联通验证 OK
- Key 轮换历史新增一行：`2026-06-02 ARK_API_KEY (尾 -c7bc9) 当前使用`
- 用户口头补充红线："切记，上线云服务器端时候，不要泄漏我模型的 api"——同步落到 Claude 自身的持久化 memory（`feedback_secret_protection.md`），后续任何分享/上线动作都会先校验

### 28.2 真机同步（无需 Android Studio）

- 路径：`~/Library/Android/sdk/platform-tools/adb`，`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- 一行命令：`./gradlew installDebug`（构建 + 推 APK 到已开开发者模式的 HUAWEI P40 Pro / ELS-AN00）
- 中途两次设备掉线（USB 抖动），重新插拔后 retry 成功；记录给后续答辩日避坑

### 28.3 后端常驻与停机说明（用户答疑）

- 用 `nohup uvicorn ... &` 起后台进程时常驻，停止：`lsof -ti:8000 | xargs kill`
- 停掉后端 → 客户端发消息会卡在"发送中"或弹"网络异常"——因此演示前必须先 `curl /health` 自检
- 用户**明确决定推迟上云**："我打算之后再上线服务器，毕竟我还要继续优化文件"——不动 Caddy / Nginx / TLS / Cloudflare，本地 LAN 直连作为答辩日方案

### 28.4 UI 紫色换肤（参考用户截图）

参考图：紫色主色 + 白底气泡 + 横向滚动卡片的 AI 助手风格。落地：

- `MainActivity.kt` 新增 `ShopGuideColors` lightColorScheme：
  - `primary = #7B61FF`（主紫，按钮 / 输入框 focus / 高亮）
  - `primaryContainer = #EDE7FF`（弱紫，AI 气泡 / 角标底）
  - `surface = #FFFFFF` / `background = #F7F5FC`（淡紫白底，护眼）
- `ChatScreen.kt` 整页重写：
  - 顶部 `ShopGuideTopBar`：双行标题（主标题 + 副标题"基于 RAG 的智能购物助手"）+ 齿轮设置入口
  - `BotAvatar`：紫色圆形头像，气泡左对齐 + 顶部圆角 + 左下方圆角缩进
  - 用户气泡：右对齐 + 紫色填充 + 顶部圆角 + 右下方圆角缩进（不对称形状是设计感的关键）
  - 商品卡片：`LazyRow` 横向滚动，单卡 140dp 宽，含品图 / 标题 2 行省略 / 品牌 / 价格
- 编译装机一次过；视觉调性与豆包 / Kimi 同档

### 28.5 5 项 Agent 能力自查与补齐

按用户列出的 5 条能力清单逐条对照实现：

| 能力 | 自查发现 | 补齐 |
|---|---|---|
| 单轮模糊推荐 | 已支持（"推荐个洗面奶"直推） | — |
| 条件过滤 | 价格上限是软约束（system prompt 写了，但模型偶尔越界） | **加硬过滤**：`_PRICE_MAX_RE` 抽数字 → `_filter_by_price` 在送给 LLM 前剔除超价候选；过滤后为 0 时显式提示模型走"暂无满足条件" |
| 多轮约束累计 | `_build_retrieval_query` 已合最近 3 句 user，prompt 第 6 条强调"叠加不覆盖" | — |
| 横向对比 | 系统 prompt 没区分推荐 vs 对比模式，遇到"A 和 B 哪个好"会发散推第三款 | **新增意图分支**：`_COMPARE_RE` 命中 + 候选里至少 2 款被用户提及 → 进入"对比模式"，输出 markdown 表 + 维度横评 + 末尾结论 |
| 主动澄清 | prompt 第 2 条已划清"反问 vs 直推"边界（已给品类+属性 → 直推；连品类都没说清 → 反问 1-2 个最关键问题，必带 ?） | — |

#### 关键代码（`server/app/main.py`）

```python
_PRICE_MAX_RE = re.compile(
    r"(?:不超过|低于|预算|低于|少于|≤|<=|<)\s*(\d+)\s*(?:元|块|¥)?"
    r"|(\d+)\s*(?:元|块|¥)?\s*(?:以内|以下|内)"
)
_COMPARE_RE = re.compile(r"(对比|比较|区别|差别|哪个更|哪款更|哪个好|哪款好|\bvs\b|VS)")

def _extract_price_max(text: str) -> float | None:
    candidates: list[float] = []
    for m in _PRICE_MAX_RE.finditer(text):
        num = m.group(1) or m.group(2)
        if num: candidates.append(float(num))
    return min(candidates) if candidates else None

def _is_compare_intent(text, retrieved):
    if not _COMPARE_RE.search(text): return False
    hits = sum(1 for r in retrieved if (r["product"].get("brand") or "").strip() in text)
    return hits >= 2

def _filter_by_price(retrieved, price_max):
    kept = []
    for r in retrieved:
        p = r["product"]
        price = p.get("base_price") or (
            min((sku["price"] for sku in p.get("skus", []) if sku.get("price") is not None), default=None)
        )
        if price is not None and price <= price_max: kept.append(r)
    return kept
```

`_build_messages` 内的整合策略：

1. 多轮 query 拼接 → 抽 `price_max` → 命中则 `_filter_by_price` 硬过滤
2. 命中对比意图 → `effective_top_k = max(top_k, 8)`（扩召回池保证两款目标都进 top-K）
3. 把 `price_max`、对比意图作为"本轮提取的硬约束"显式追加到 system content，杜绝模型"忘记"

#### system prompt 新增意图分支

```text
【意图分支 — 优先判断】
- 若用户语句包含"对比/比较/vs/哪个更/..."且至少提及两款【候选商品】里的商品 → 进入【对比模式】：
  · 只输出这两款的对标，不发散推其他商品
  · markdown 表格或分点列表，3-5 个维度横向对比
  · 每款商品名后必须跟 [[PRODUCT:product_id]]
  · 末尾给一句结论："如果你更看重 X，选 A；更看重 Y，选 B。"
- 否则进入【推荐模式】，按下方【强制规则】走。
```

### 28.6 3 项多模态加分项深化

按用户列出的 3 条加分项清单逐条对照：

| 加分项 | 自查发现 | 补齐 |
|---|---|---|
| 语音输入 (ASR) | 短按 toggle 录制 + 长字符串中转输入框，体验割裂 | **加长按说话**：`pressToTalkStart` / `pressToTalkEndAndSend`，松手 ≥400ms 自动 ASR + 直接 send()，`<400ms` 视为误触取消 |
| 多模态拍照 | 三档融合已实现，但前端无法分辨"哪条命中是双路 / 仅图 / 仅文" | **加 match_source 角标**：服务端在 `event=retrieved` payload 里下发 `match_source ∈ {both, image_only, text_only}`；前端商品卡片右上角渲染色标 |
| TTS 语音导购 | 之前是手动点喇叭播放 | **加自动朗读**：设置抽屉新增"语音导购模式" Switch；ViewModel 通过 `_assistantFinished` SharedFlow 发结束事件，UI 层 `LaunchedEffect` 收到后调 `TtsManager.speak`（仅当 `Config.autoTtsEnabled = true`） |

#### match_source 落点

`server/app/image_retriever.py:fuse_with_text_retrieval`：

```python
in_text  = pid in text_pids_set
in_image = pid in image_pids_set
source = "both" if (in_text and in_image) else ("image_only" if in_image else "text_only")
fused.append({..., "match_source": source})
```

`server/app/main.py:/chat/stream/multimodal` 在下发 `event=retrieved` 时透出 `match_source`，且对 `vlm_only / clip_only` 两条单一路径补默认值（避免前端 null 判空分支）。

`client/.../ChatScreen.kt:MatchSourceBadge`：紫色"双路命中" / 蓝色"视觉相似" / 浅紫"关键词"，渲染在 LazyRow 商品卡片右上角。

#### 长按说话手势（`ChatScreen.kt:InputBar`）

```kotlin
detectTapGestures(
    onPress = { _ ->
        val startedAt = System.currentTimeMillis()
        onMicPressStart()
        val released = tryAwaitRelease()
        val heldMs = System.currentTimeMillis() - startedAt
        when {
            released && heldMs >= 400 -> onMicPressEnd()  // 录音 → ASR → 直接 send()
            released                  -> onMicPressCancel() // 误触取消
            else                      -> onMicPressCancel()
        }
    }
)
```

`ChatViewModel`：`pressToTalkEndAndSend` 内"识别成功 → 塞 input → 调 send()"复用既有发送链路；失败分两类 toast：null = 网络/服务端错（"语音识别失败，请检查网络"），空串 = 没识别到内容（"录得太短了"）。

#### 自动 TTS（`ChatViewModel`）

```kotlin
private val _assistantFinished = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
val assistantFinished: SharedFlow<Pair<String, String>> = _assistantFinished
// runStream 末尾 typewriter 真正打完字后:
if (finalText.isNotBlank()) _assistantFinished.tryEmit(assistantId to finalText)
```

`ChatScreen` 顶层 `LaunchedEffect(Unit) { vm.assistantFinished.collect { (_, text) -> if (Config.autoTtsEnabled) tts.speak(text) } }`。

`Config.kt`：新增 `KEY_AUTO_TTS` / `cachedAutoTts` / `autoTtsEnabled` getter / `setAutoTts(context, enabled)`，落 SharedPreferences。

### 28.7 Toast 通道（一次性事件）

`ChatViewModel` 加 `_toast: MutableStateFlow<String?>` + `consumeToast()`，UI 层 `LaunchedEffect(toast)` 拿到值后弹 `Toast.makeText` 再 `consumeToast()` 清空——典型"事件流以 StateFlow 实现"的小套路，比 SharedFlow 更适合 toast 这种"最近一次值"语义。

### 28.8 答辩话术（D19 可讲点）

1. **价格硬过滤**：不是把"200 以内"塞进 prompt 让模型自觉，而是在送给 LLM 前先剔超价候选——把不可控的"软对齐"换成可证伪的"代码硬过滤"
2. **对比模式触发条件**：`_COMPARE_RE` + 候选品牌字符串至少 2 命中，避免"哪个好"误触发对比；`top_k` 自动放大到 8 给两款目标都留召回位
3. **多模态来源角标**：`both` / `image_only` / `text_only` 三色标签让评委肉眼看到"双路真的有不同结果，融合不是噱头"
4. **长按说话**：≥400ms 阈值是误触保护值，松手立即"录音→ASR→send"零中转，逼近豆包的语音体验
5. **自动 TTS**：完全可控（设置开关），结合长按说话形成"按住说 → 听 AI 答"的纯语音回路，对老人/盲操友好

### 28.9 沉淀文件清单

- 修改：`server/.env`（仅 `ARK_API_KEY`）/ `server/app/main.py`（意图分支 + 价格硬过滤 + 对比扩 top_k）/ `server/app/image_retriever.py`（match_source）
- 修改：`client/.../MainActivity.kt`（紫色 colorScheme）/ `ChatScreen.kt`（整页换肤 + 长按手势 + 角标 + 自动 TTS hookup + toast）/ `ChatViewModel.kt`（toast / assistantFinished / pressToTalk）/ `Config.kt`（autoTts）/ `Models.kt`（match_source 字段）
- 持久化 memory：`feedback_secret_protection.md`（API Key 上线红线）

### 28.10 W3 后续可选项（未启动）

用户提出"我的项目不注广度，注重深度，关于之前提到的加分点还有什么要优化"，已盘了 6 个深度方向，按 ROI 排序：

1. **三模检索消融报告 + 可视化图表**（已有 `eval/build_ablation_report.py` 基础设施，1-2h 出图）
2. **多模态三路消融可视化**（vlm_only / clip_only / fusion 胜率矩阵）
3. **价格违规率 / 多轮约束保留率指标**（量化本次 prompt 工程的提升幅度）
4. **延迟分解面板**（VLM / CLIP / 检索 / LLM TTFB 瀑布图，半天）
5. **检索失败兜底 → 主动澄清**（score < 阈值 → 不硬塞低质上下文 → 让模型说"再描述具体些"）
6. **Chinese-CLIP 在本数据集上微调**（高成本高 wow，文末备选）

用户未拍板具体方向，先冻结代码、回头优先做 1+2+3。

---

## 29. D20 — 4.3 对话智能加分点深化：约束抽取统一化 + 自动话题隔离 + 对比模式可视化（2026-06-02）

### 29.1 决策背景

用户提出"项目不注重广度而注重深度"，要把 4.3 加分点（对话智能与 RAG 增强）三档全部做扎实：

- ⭐ 多轮记忆 / 约束累计（已在 D19 完成，本期补"再便宜点"相对降价）
- ⭐⭐ 否定 / 排除（"不要含酒精"、"除了耐克"——本期把 D19 散落在 main.py 里的正则下沉到独立 `constraints.py` 模块，并扩出 `brand_excludes / attr_excludes / brand_required` 三组硬过滤）
- ⭐⭐⭐ 对比模式：在 ⭐⭐ 已经支持文字 markdown 对比的基础上，要求模型同步下发结构化 JSON，前端渲染**雷达图 + 对比表**

并且用户在中途追加了一条强 UX 要求：**"软件并没有再开一个新对话的功能"**——但他不要"+ 新对话"按钮，而是要**自动话题隔离（Automatic Topic Isolation）**：让客户端用纯启发式判断"用户是在追问还是开新话题"，开新话题时不让旧约束/旧检索结果污染本轮，且 UI 上画一条淡分隔线提示。

### 29.2 服务端：`server/app/constraints.py`（新模块）

把 D19 散在 main.py 里的 4 段正则全部下沉，定义成统一管线 `raw_query → ConstraintSet → apply_to(retrieved) → filtered`。

```python
# 价格上限（已有）
_PRICE_MAX_RE = re.compile(
    r"(?:不超过|低于|预算|不超|少于|≤|<=|<)\s*(\d+)\s*(?:元|块|¥)?"
    r"|(\d+)\s*(?:元|块|¥)?\s*(?:以内|以下|内)"
)
# 属性排除：抓 1~4 字成分/特征，断尾用前瞻
_EXCL_ATTR_RE = re.compile(r"(?:不含|不要含|无)\s*([一-龥A-Za-z]{1,4})(?=的|，|,|。|！|!|\s|$)")
# 品牌排除：2~4 字
_EXCL_BRAND_RE = re.compile(r"(?:不要|除了|排除|不喜欢|讨厌)\s*([一-龥A-Za-z]{2,4})(?=的|还|和|或|也|吧|了|呢|啊|，|,|。|！|!|\s|$)")
# 必含品牌
_REQUIRED_BRAND_RE = re.compile(r"(?:指定|只要|想要|我要)\s*([一-龥A-Za-z]{2,5})\s*(?:的|品牌)")
# 撤销既有约束
_CANCEL_PRICE_RE   = re.compile(r"(?:不要预算|不要价格|算了不限价|取消价格|忽略价格)")
_CANCEL_EXCLUDE_RE = re.compile(r"(?:可以接受|不排除|算了.*?也行)")
# A 项：相对降价 — 命中则按系数压低 prior_price_max
_PRICE_DOWN_RE = re.compile(r"(?:再便宜|更便宜|再低|再降|便宜点|便宜些|实惠点)")
_PRICE_UP_RE   = re.compile(r"(?:再贵|更贵|高端点|贵点)")
PRICE_DOWN_RATIO = 0.7
PRICE_DOWN_FLOOR = 10.0
```

`ConstraintSet` 是 dataclass，五个字段：`price_max / brand_excludes / attr_excludes / brand_required / is_compare`，支持：

- `is_empty()`：客户端首事件 `event=constraints` 仅在非空时下发
- `to_dict()`：直接给 SSE event 用
- `to_prompt_notes()`：转成中文短句拼进 system prompt 末尾"【本轮提取的硬约束】"
- `apply_constraints(retrieved, cs)`：对 retrieved list 做硬过滤（价格 ≤ price_max、品牌不在 brand_excludes、marketing_description 不含 attr_excludes、品牌严格命中 brand_required）

`extract_constraints(query, retrieved=None, prior_price_max=None)` 是入口，特别处理 A 项：

```python
elif (prior_price_max is not None
      and _PRICE_DOWN_RE.search(query)
      and not _PRICE_UP_RE.search(query)):
    # "再便宜点" — 没具体数字时按系数压 prior，圆整到 10 元
    new_max = prior_price_max * PRICE_DOWN_RATIO
    cs.price_max = max(round(new_max / 10.0) * 10.0, PRICE_DOWN_FLOOR)
```

### 29.3 服务端：`main.py` 接入

`ChatRequest` 加两个新字段：

```python
topic_summary: str = ""           # 客户端话题主线（首句）
prior_price_max: float | None = None   # 客户端 ConstraintTracker 持有的当前 chip 值
```

`_build_messages` 改造：

1. 把 `topic_summary` 拼进 `retrieval_query`（避免被 takeLast 截掉的开场被遗忘）
2. `extract_constraints(query, retrieved, prior_price_max=req.prior_price_max)`——支持"再便宜点"
3. `apply_constraints(retrieved, cs)` 过滤
4. 过滤后再判一次 `is_compare`（避免被排除掉的品牌仍误标）
5. system prompt 末尾追加 `【当前话题主线】` 块（topic_summary）+ `【本轮提取的硬约束】` 块（notes）
6. `is_compare` 命中时，prompt 末尾追加大段**对比模式结构化数据要求**——硬性 7 条规则 + JSON schema + 一行单行示例，要求 LLM 在正文末尾下发 `[[COMPARE_DATA:{...}]]`：

```
{
  "products":[{"product_id":"p_xxx","name":"≤8字"}, ...],
  "dimensions":["维度1","维度2", ...],
  "scores":[[0~5整数], ...],   // [products][dimensions] 长度严格相等
  "rows":[["≤12字描述", ...], ...],
  "conclusion":"如果你更看重 X，选 A；更看重 Y，选 B。"
}
```

prompt 里**显式要求** "JSON 不能换行" / "不能包 ```json 围栏" / "JSON 字符串里禁止出现 `]]`（用『』替换）" / "score 不要全 5 分要拉差距"，否则前端会解析失败。

### 29.4 客户端：自动话题隔离（`ConversationTopicManager.kt`，新文件）

纯启发式 `object`，无外部依赖：

```kotlin
const val RECENT_MESSAGE_WINDOW = 6
const val OVERLAP_THRESHOLD = 0.18

private val PRONOUN_RE = Regex("(它|这个|那个|这款|那款|刚才|刚刚|上面|that)")
private val FOLLOWUP_PHRASES = listOf("再便宜", "更便宜", "再贵", "贵点", "换一个", "另一款")

data class Decision(val sameTopic: Boolean, val confidence: Double, val reason: String)

fun detectConversationMode(history, current): Decision {
    // 1) 含代词 → sameTopic
    // 2) 短追问短语（≤8字，整句即追问） → sameTopic
    // 3) 词袋 jaccard 与最近 N 条 user 比较 → 有任一 ≥ threshold 视作 sameTopic
}

fun tokenize(s: String) = s.lowercase().split(Regex("[\\s，。！？,.!?]+")).filter { it.isNotBlank() }
fun jaccard(a: Set<String>, b: Set<String>): Double = ...
fun updateSummary(messages, current, sameTopic): String =
    if (sameTopic && oldSummary.isNotBlank()) oldSummary else current
```

### 29.5 客户端：`ChatViewModel` 接入话题状态

新增三个状态 + 一个 SharedFlow：

```kotlin
var topicSummary: String = ""             // 当前话题主线（首句），跟随 detect 决策更新
var topicStartIndex: Int = 0              // 本话题在 messages 列表中的起始下标（剪上下文用）
private val _topicChanged = MutableSharedFlow<Unit>(...)
val topicChanged: SharedFlow<Unit> = _topicChanged

// B 项：避免 chip × dismiss 后第一句反而被判成新话题
var forceSameTopicOnNextSend: Boolean = false
```

`send()` 流程：

1. 在拼 user 消息前调 `decideTopic`：若 `forceSameTopicOnNextSend == true`，直接强同话题、清标志位
2. 否则 `ConversationTopicManager.detectConversationMode(...)`
3. 新话题：`topicSummary = current`、`topicStartIndex = messages.size`、给本条 UiMessage 打 `isTopicBoundary = true`、`_topicChanged.tryEmit(Unit)`
4. 同话题：`topicSummary` 仍由 `updateSummary` 维护
5. 调 `repo.chatStream(messages, topicSummary = topicSummary, priorPriceMax = _constraints.value?.price_max)`

`dismissConstraint(kind)` 在发送"忽略 ¥1000 上限/取消排除耐克"指令前 **`forceSameTopicOnNextSend = true`**——避免这种短指令因为词袋重叠极低而被误判成新话题。

### 29.6 客户端：`Models.kt` / `ChatRepository.kt` / `ChatScreen.kt` 联动

- `Models.kt`：`ChatRequestWire` 加 `topic_summary: String = ""`、`prior_price_max: Double? = null`；`UiMessage` 加 `isTopicBoundary: Boolean = false`、`compareData: CompareData? = null`；新增 `CompareProductRef / CompareData` 序列化类
- `ChatRepository.kt`：`chatStream(messages, topicSummary = "", priorPriceMax = null)`，把两个字段透传给后端
- `ChatScreen.kt`：
  - 移除"+ 新对话"按钮（按用户要求"自动隔离不要手动按"）
  - 在每条 `isTopicBoundary == true` 的消息上方画 `TopicBoundaryDivider`（淡灰水平线 + "开始了新话题"文字）
  - 顶层 `LaunchedEffect` 订阅 `topicChanged`，话题切换时立刻 `tts.stop()`，避免上一个话题的 TTS 还在播

### 29.7 对比模式可视化（C+D）

#### C 项：解析器从 regex 改为 brace-counting

旧版 `compareDataRegex = """\[\[COMPARE_DATA:(\{.*?})]]"""` 在 Android Pattern engine 上抛 `PatternSyntaxException` 让 app 启动崩溃，且对"JSON 字符串里出现 `]]`"无能为力。

`ChatViewModel.parseCompareData(text)` 重写：

```kotlin
private val compareDataPrefix = Regex("""\[\[COMPARE_DATA:""")

private fun parseCompareData(text: String): CompareData? {
    val match = compareDataPrefix.find(text) ?: return null
    val openIdx = text.indexOf('{', startIndex = match.range.last + 1)
    if (openIdx < 0) return null
    var depth = 0; var inString = false; var escape = false; var endIdx = -1
    for (i in openIdx until text.length) {
        val c = text[i]
        if (escape) { escape = false; continue }
        if (c == '\\') { escape = true; continue }
        if (c == '"') { inString = !inString; continue }
        if (inString) continue
        when (c) { '{' -> depth++; '}' -> { depth--; if (depth == 0) { endIdx = i; break } } }
    }
    if (endIdx < 0) return null
    return runCatching {
        compareJson.decodeFromString(CompareData.serializer(), text.substring(openIdx, endIdx + 1))
    }.getOrNull()
}
```

正确处理大括号嵌套 + 字符串内转义引号 + 字符串内的 `]]`。每来一个 token 就尝试解析一次，解析成功就把 `compareData` 落到这条 UiMessage。

#### D 项：雷达图 + 对比表的视觉重做

- `RadarPalette = listOf(0xFF7B61FF, 0xFFFF9F43, 0xFF2F80ED)`——3 款商品 3 种区分色
- `topScorers(scores, dim)`：返回该维度并列最高分的 product 索引集合
- `RadarChart`（Canvas）：
  - 网格圈每 1 分一圈，最外深灰内圈虚化
  - 每款商品多边形：半透明面 `color.alpha=0.25` + 实线边
  - **每个维度上得分最高的商品顶点画加粗实心圆**（外圈商品色 + 中心白点），一眼看出谁赢在哪
- `ComparisonTable(products, dimensions, rows, scores)`：
  - 表头紫色淡底 + 商品色点（呼应雷达图图例）
  - 数据行隔行 zebra
  - 维度列固定 weight=1，商品列 weight=1.5
  - "获胜单元格"（topScorers 命中）：左上角加 6dp 商品色点 + 文字 SemiBold

`ComparisonView` 总装：标题 → 雷达图（仅 scores 齐全时）→ 对比表 → 结论紫色卡片。`compareData` 非空时由 `MessageBubble` 调用渲染。

### 29.8 编译装机

```
JAVA_HOME=Android Studio JBR ./gradlew assembleDebug   # BUILD SUCCESSFUL in 3s
~/Library/Android/sdk/platform-tools/adb install -r app-debug.apk   # Success
```

设备 `W9F0220427006991` 已安装，可现场跑 4 个用例：
- (a) 多轮约束累计 + "再便宜点"相对降价（A 项）
- (b) "除了耐克"排除 + chip × dismiss 不会触发话题切换（B 项）
- (c) "不要含酒精"属性排除（⭐⭐ 否定）
- (d) 双品牌对比 → 雷达图 + 对比表（C+D）

### 29.9 答辩话术（D20 可讲点）

1. **从约束散落到统一管线**：D19 时 4 段正则散在 main.py 里，D20 下沉到 `constraints.py` 单独模块，5 字段 + 2 取消信号 + 2 相对调整 → 同一个 `ConstraintSet` 既给硬过滤也给 prompt 注入也给 SSE 下发，**单一可信源**
2. **"再便宜点"= 客户端把当前 chip 值（`prior_price_max`）回传 + 服务端按 70% 系数 / 10 元圆整 / 10 元下限**——这是把"上下文"从 messages 列表迁移到结构化字段的微型例子，比让模型记忆数字鲁棒得多
3. **自动话题隔离三规则**（代词 / 追问短语 / jaccard 词袋）+ **forceSameTopicOnNextSend** 修复"撤销 chip 第一句被误判"——纯客户端启发式无外部模型，零延迟零额外成本
4. **对比模式 LLM ↔ 前端协议**：`[[COMPARE_DATA:{json}]]` 内联标签让 LLM 在 markdown 流里同步下发结构化数据；解析器用 brace-counting 容忍 JSON 字符串内的 `]]` 与转义；前端 Canvas 雷达图 + 对比表中**每个维度的赢家**用同一组 RadarPalette 着色——视觉一致性强化用户对"哪款赢在哪"的认知

### 29.10 沉淀文件清单

- 新建：`server/app/constraints.py`（约束抽取与硬过滤管线）
- 新建：`client/.../ConversationTopicManager.kt`（启发式话题判定）
- 修改：`server/app/main.py`（`ChatRequest` + 字段；`_build_messages` 注入 topic_summary / prior_price_max；is_compare 大段结构化输出指令）
- 修改：`client/.../Models.kt`（ChatRequestWire 加字段、UiMessage 加 isTopicBoundary/compareData、新增 CompareData/CompareProductRef）
- 修改：`client/.../ChatRepository.kt`（chatStream 透传 topicSummary / priorPriceMax）
- 修改：`client/.../ChatViewModel.kt`（topicSummary / topicStartIndex / topicChanged / forceSameTopicOnNextSend / parseCompareData brace-count 重写）
- 修改：`client/.../ChatScreen.kt`（移除"+ 新对话"按钮、TopicBoundaryDivider、RadarPalette/topScorers、RadarChart 重写、ComparisonTable 加 scores 参数 + 视觉重做、HeaderCell helper、订阅 topicChanged 停 TTS）
- 修复：`PatternSyntaxException` 启动崩溃（旧 `compareDataRegex` 不被 Android Pattern engine 接受 → brace-counting 替换）

### 29.11 待启动（D20 后续）

用户已点头 1+2+3 的评测可视化任务（W3 后续），但本轮没启动：
- 三模检索消融报告 + 可视化图表
- 多模态三路消融可视化
- 价格违规率 / 多轮约束保留率（量化 D19/D20 的 prompt 工程提升幅度）

---

## 30. D20 后续 — 对比模式实测翻车与三处根因修复（2026-06-02 当晚）

### 30.1 翻车现象

D20 装包后用户首次实测 "对比 sk-II 和欧莱雅隔离霜"，截图反馈：
- **雷达图没出现**
- **正文是 LLM 自己写的 markdown 管道符表格**（`| 对比维度 | SK-II护肤精华神仙水 | 巴黎欧莱雅多重防护隔离露 |`），格式很乱
- 表格底下还跟了一大段重复的"使用场景：日常护肤..."条目

### 30.2 三件事一起出错

#### 根因 A — `is_compare` 漏判（最主要，导致整条链路降级）

`extract_constraints` 第 4 段判定要求"候选商品的 brand 字段以**完整原样**在 query 里出现，且 ≥2 个不同品牌"。实测：

- 候选 `brand="SK-II"` vs query 里 `"sk-II"` → 大小写不一致，`"SK-II" in query` False
- 候选 `brand="巴黎欧莱雅"` vs query 里 `"欧莱雅"` → 用户只说了简称，整串子串不命中

两个都漏 → `is_compare=False` → prompt 走推荐分支不注入对比模式指令 → 模型自由发挥 markdown 表格。

#### 根因 B — 推荐分支 prompt 文案给了模型"写 markdown 表格"的口子

旧 SYSTEM_PROMPT_TEMPLATE 的对比分支说"用 markdown 表格或分点列表横向对比"。即便 `is_compare` 命中，模型也可能走 markdown 表 + 不下发 `[[COMPARE_DATA:`。

#### 根因 C — 客户端 `stripProductTags` 没剥 `[[COMPARE_DATA:...]]`

即便 LLM 正确下发了 JSON 内联块，旧版 `stripProductTags` 只剥 `[[PRODUCT:...]]`，**整段 JSON 会原样显示在文字气泡里**。这是用户视觉吐槽"格式不好看"的另一个原因（如果这次 LLM 凑巧吐了 JSON，文字气泡就会变成长篇 JSON 字符串）。

### 30.3 修复 A — `_brand_mentioned_in` 滑窗子串匹配（`server/app/constraints.py`）

```python
# 4) 对比意图 — 全部 .lower() 比较；brand 整名 OR 任意 ≥2 字滑窗子串命中也算
if retrieved and has_compare_hint(query):
    q_lower = query.lower()
    seen_brands: set[str] = set()
    for r in retrieved:
        brand = (r["product"].get("brand") or "").strip().lower()
        if brand and _brand_mentioned_in(brand, q_lower):
            seen_brands.add(brand)
    if len(seen_brands) >= 2:
        cs.is_compare = True

def _brand_mentioned_in(brand: str, query_lower: str, min_len: int = 2) -> bool:
    if brand in query_lower:
        return True
    n = len(brand)
    for L in range(min_len, n + 1):
        for i in range(0, n - L + 1):
            seg = brand[i:i + L]
            if not any(c.isalnum() for c in seg):       # 跳过纯标点子串（如 "-", " "）
                continue
            if seg in query_lower:
                return True
    return False
```

> **设计权衡**：滑窗最短 2 字 + 必须含字母数字 → 不会让"对比 苹果和小米"误把"果"或"米"匹到不相关品牌。命中阈值仍保留 ≥2 个不同品牌，"对比 sk-II 神仙水"（只有一个品牌）不会被误判。

实测：
```
=== constraints: {'is_compare': True}
=== compare blob: [[COMPARE_DATA:{"products":[{"product_id":"p_beauty_003","name":"SK-II神仙水"},
{"product_id":"p_beauty_006","name":"欧莱雅隔离露"}],"dimensions":["价格友好度","核心功效力",...
"scores":[[2,4,3,2,3],[4,4,4,4,4]],"rows":[...],"conclusion":"侧重护肤调理肤质选SK-II神仙水..."}]]
```

### 30.4 修复 B — 收紧对比分支 prompt（`server/app/main.py:120-126`）

```diff
-  - 用 markdown 表格或分点列表，从 3-5 个用户关心的维度横向对比（例如保湿/续航/拍照/价格/适用场景）；
-  - 每款商品名后必须跟 `[[PRODUCT:product_id]]` 标签；
-  - 末尾给一句结论："如果你更看重 X，选 A；更看重 Y，选 B。"
+  - **禁止使用 markdown 管道符表格**（`| ... | ... |`）—— 前端不会渲染；
+  - 正文只写 1-2 句导语，**不要逐项展开**；
+  - 每款商品名第一次出现时必须跟 `[[PRODUCT:product_id]]` 标签；
+  - 详细的 3-5 维度横评数据 **必须** 写进末尾的 `[[COMPARE_DATA:{{...}}]]` 单行 JSON 块，由前端渲染雷达图 + 对比表；
+  - **不要在正文里重复 JSON 里的内容**——重复会让消息变得又长又乱。
```

注意：模板用 `str.format(context=...)` 渲染，所以 `{...}` 字面量必须写成 `{{...}}` 转义——首次改完直接报 `IndexError: Replacement index 0 out of range for positional args tuple`，立刻补转义。

### 30.5 修复 C — `stripProductTags` 升级为 brace-counting（`client/.../ChatScreen.kt:481-525`）

```kotlin
private val productTagRegex = Regex("""\s*\[\[PRODUCT:[a-zA-Z0-9_]+]]\s*""")
private val compareDataPrefixRegex = Regex("""\[\[COMPARE_DATA:""")

private fun stripProductTags(text: String): String {
    var s = text.replace(productTagRegex, " ")
    val match = compareDataPrefixRegex.find(s)
    if (match != null) {
        val openIdx = s.indexOf('{', startIndex = match.range.last + 1)
        if (openIdx >= 0) {
            // 大括号深度计数 + 字符串/转义状态机扫到匹配的 `}`，再吃掉收尾的 `]]`
            var depth = 0; var inString = false; var escape = false; var endIdx = -1
            for (i in openIdx until s.length) { ... }
            if (endIdx > 0) {
                val tail = s.indexOf("]]", startIndex = endIdx)
                val cutEnd = if (tail >= 0) tail + 2 else endIdx + 1
                s = s.substring(0, match.range.first) + s.substring(cutEnd)
            } else {
                // 流式中尚未收齐 `]]`，先把前缀到末尾全部隐藏，避免半截 JSON 闪现
                s = s.substring(0, match.range.first)
            }
        }
    }
    return s.trim()
}
```

> 不能用 regex `\[\[COMPARE_DATA:.*?]]` —— LLM prompt 允许 JSON 字符串里出现 `]]`（用『』替换是软建议），稳妥起见客户端必须用栈深 + 字符串状态机解析。

### 30.6 联调实测（命令行）

```bash
$ curl -s -X POST http://127.0.0.1:8000/chat \
    -d '{"messages":[{"role":"user","content":"对比 sk-II 和欧莱雅隔离霜"}]}'

constraints: {'is_compare': True}                                    # ← 修复前 {}
reply head: 两款属于完全不同的美妆护肤品类，SK-II护肤精华露神仙水[[PRODUCT:p_beauty_003]]
            主打护肤调理，巴黎欧莱雅新多重防护隔离露[[PRODUCT:p_beauty_006]]主打防晒
            隔离，核心差异在功效定位和使用场景上。                  # ← 短小导语，无 markdown 表
has_compare_block: True                                              # ← 修复前 LLM 用 schema items/values 跑偏
compare blob: [[COMPARE_DATA:{"products":[...],"dimensions":["价格友好度",...],
              "scores":[[2,4,3,2,3],[4,4,4,4,4]],"rows":[...],
              "conclusion":"侧重护肤调理肤质选SK-II神仙水，需要日常防晒提亮选欧莱雅隔离露。"}]]
```

后端 PID 15195 已重启加载新 prompt；客户端重新 `assembleDebug` + `adb install -r` 完成。

### 30.7 答辩话术（D20 后续可讲点）

1. **品牌字面对比意图判定**：从"严格 brand 整名 ≥2 命中"→"lower() + 滑窗 ≥2 字子串 ≥2 命中"，让简称（"欧莱雅" vs "巴黎欧莱雅"）和大小写差异（"sk-II" vs "SK-II"）都能识别——同时 `min_len=2 + alnum` 约束防止单字误命中
2. **Prompt 工程的"漏斗收紧"**：D20 一开始给模型留了"markdown 表格 OR 分点列表"两条路，模型偏好走最熟悉的 markdown 表（训练数据偏置）；收紧成"禁止管道表格 + 短导语 + 必须 JSON 块"后，模型严格按结构化输出，正文也不再臃肿
3. **客户端兜底**：哪怕将来 LLM 在非对比场景误吐 `[[COMPARE_DATA:`，brace-counting 剥离器也不会让裸 JSON 漏到 UI 上；流式半截 JSON 也不会闪现

### 30.8 沉淀文件清单（D20 后续）

- 修改：`server/app/constraints.py`（`_brand_mentioned_in` 子串滑窗 + lower() 比较）
- 修改：`server/app/main.py`（对比分支 prompt 收紧 + `{{...}}` 转义）
- 修改：`client/.../ChatScreen.kt`（`stripProductTags` 升级为 brace-counting，剥 COMPARE_DATA）

---

## 31. D21 — 多会话历史 + 启动脚本 + 对比模式追问修复 + 4.3 对话智能十连深化（2026-06-03）

### 31.1 用户主诉与本日交付清单

用户主诉按时间顺序四件：

1. **多会话历史抽屉**："像 ChatGPT 那样左侧扩展可以保留每次对话，新建/删除"。
2. **后端启停脚本**：每次手动敲长命令累，要 `start_server.sh` / `stop_server.sh`。
3. **对比模式追问翻车**：连续对话下"对比一下"出不来雷达图——原因有两个：①客户端 detector 把对比短句判成新话题；②后端 `is_compare` 要求"query 里字面提到 ≥2 个品牌"，纯指代式追问永远过不去。
4. **4.3 加分项十连深化**：用户认可改进路线图后说"都做了"——把 #1~#11 全部落地。

本日交付：

- 多会话持久化（一会话一文件 + index.json）
- `ModalNavigationDrawer` 历史抽屉 + 顶栏 ☰ + ＋ 按钮
- `start_server.sh` / `stop_server.sh`（含健康探活、端口冲突保护）
- `ConversationTopicManager` 短句追问词库扩三类
- 后端 `_previous_recommended_pids` + pin 候选 + 隐式对比兜底
- `constraints.py` 三大新能力：同义词归并、显式重置、正面属性、价格区间
- `main.py` 三处接入：`exclude_pids`、放宽建议、对比 N 锁定
- 客户端三处改动：`exclude_pids` 上行、`ComparisonView` 视图切换 tab
- 评测脚本三个细分指标：反选遵守 / 价格上限 / 对比 JSON 合法

### 31.2 多会话历史（仿 ChatGPT 左侧栏）

#### 31.2.1 决策三连问

实施前先用 AskUserQuestion 明确三件事：

| 问题 | 用户选择 | 理由 |
| --- | --- | --- |
| 标题策略 | 自动取首条消息前 20 字 | 与 ChatGPT 一致，零交互成本 |
| 商品缓存 | 跨会话共享 | 演示场景下切回旧会话不用重拉商品图 |
| 持久化 | 内部存储 JSON 文件 | 无新依赖、人眼可读、调试方便 |

#### 31.2.2 持久化层（`client/.../ConversationStore.kt`，新文件）

物理布局：

```
app filesDir/conversations/
├─ index.json                # ConversationMeta 列表 [{id,title,updatedAt}]
└─ <conversationId>.json     # ConversationData 全量
```

**为什么不用单一大 JSON / Room**：

- 切换会话只读单个文件，不会随会话数增长变慢
- 删除直接 `delete file`，不重写整体
- 不引 Room：当前没有按字段查询的需求；Room 还要 schema migration

API：`loadIndex / load / save / delete / titleFromFirstMessage`。`titleFromFirstMessage` 把空白消息（图片直发）退为"新对话"。

#### 31.2.3 数据模型 `@Serializable` 化（`Models.kt`）

`Role` 和 `UiMessage` 加 `@Serializable`，让 ConversationData 可以整段落盘。`imageBase64` 字段会让多模态消息 JSON 变大几十 KB，但演示场景一个会话不会拍很多张，先这么存。

#### 31.2.4 ViewModel 改造（`ChatViewModel.kt`）

改成 `AndroidViewModel(Application)` 拿 context 做 IO。新增：

```kotlin
val conversations: StateFlow<List<ConversationMeta>>
val currentId: StateFlow<String?>

fun newConversation()
fun selectConversation(id: String)
fun deleteConversation(id: String)
```

**关键设计**：

- `bootstrap()` 启动时读 index，最新一条作当前；空索引则起一个**内存空会话**（不落盘，避免冷启动堆空对话）
- `persistCurrentIfNeeded()` 仅当当前 messages 非空才写盘——这是"空会话不入历史"的实现点
- 流式结束（`runStream` 里 typewriterJob 完成）时调一次写盘，标题/updatedAt 自动刷新
- 切换/删除/新建前先 `persistCurrentIfNeeded()` 防丢
- 商品缓存 `productCache` 是 ViewModel 单例字段，跨会话共享（按用户决策）
- 删除当前会话：还有别的→加载最新；没了→起空白会话（保持 UI 永远可输入）

#### 31.2.5 抽屉 UI（`ChatScreen.kt`）

`ModalNavigationDrawer` 包 `Scaffold`。顶栏左侧 ☰、右侧 ＋ 和齿轮。抽屉宽 280dp：

- 顶部"新建对话"紫色描边胶囊（粘顶部，方便单手戳）
- 下方 LazyColumn 列会话；当前会话紫色高亮
- 自定义 `ConversationRow`（不用 `NavigationDrawerItem`，因为它整行响应一个 `onClick` 会拦截子节点删除按钮）：`Surface(onClick=onSelect)` 包一行 Text + IconButton(垃圾桶)
- 删除走二次确认 AlertDialog，"确定删除「标题」吗？此操作不可撤销"

### 31.3 后端启停脚本

#### 31.3.1 痛点

每次启动手敲一长串：

```bash
cd server && nohup .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000 > /tmp/shopguide-server.log 2>&1 &
```

健康探活、端口冲突、IP 提示也都得手动做。

#### 31.3.2 `server/scripts/start_server.sh`

要点：

1. 端口已占用→直接报错退出，不重复启动（避免 `Address already in use`）
2. 起 nohup 后 `for i in $(seq 1 30)` 等 `/health` 就绪，最多 30 秒
3. 就绪后自动 `ipconfig getifaddr en0` 把局域网 BASE_URL 打印出来，方便填进 APP

#### 31.3.3 `server/scripts/stop_server.sh`

`lsof ... -t | xargs kill` 默认 SIGTERM，等 5 秒确认释放；`-9` 参数走 SIGKILL 兜底。

#### 31.3.4 踩坑：bash 中文 locale 下 `$PID` 与中文字节粘连

第一次跑报 `PID�: unbound variable`——某些 zsh/bash 的 locale 配置下 `$PID` 后面紧跟的中文字节会被当成变量名延伸。修复：所有变量引用强制大括号 `${PID}` / `${PORT}` / `${IP}`。

### 31.4 自动话题隔离 detector 扩词（`ConversationTopicManager.kt`）

#### 31.4.1 翻车现场

用户发现："连续对话时再问'对比一下'会自动开启新话题。"

#### 31.4.2 根因

`FOLLOWUP_PHRASES` 里没有任何对比/选择类追问词；句长上限 `q.length <= 8` 也太苛刻——"对比一下这两款"刚好 7 字，"对比一下这个和那个"9 字直接被踢出短句路径。

#### 31.4.3 修复

```kotlin
private val FOLLOWUP_PHRASES = listOf(
    /* 旧词保留 */
    "对比", "比较", "对比一下", "比较一下", "比一比", "比一下",
    "哪个好", "哪款好", "哪个更好", "哪款更好", "哪个值得", "哪款值得",
    "选哪个", "选哪款", "选一个", "买哪个", "买哪款", "推荐哪个", "推荐哪款",
    "更值得", "更推荐", "更划算", "更好", "区别", "差别", "差异",
    "compare", "vs",
)
private const val SHORT_FOLLOWUP_MAX_LEN = 12  // 8 → 12

// PRONOUN_TOKENS 加 "这两/那两/前两/上面那/两款/两个/几款"
```

### 31.5 对比模式追问的后端 pin 兜底（`server/app/main.py`）

#### 31.5.1 根因（与 31.4 是两条独立链路上的同一现象）

即使客户端不再切话题、把上下文带过去了，后端 `is_compare` 还要求 query 字面提到 ≥2 个品牌（D20 修过 `_brand_mentioned_in`，但前提仍是 query 里要复述商品名）。**真实用户不会复述**——只说"对比一下"指代上一轮。

#### 31.5.2 修复：扫上一条 assistant 消息提取 pin

```python
_PRODUCT_TAG_RE = re.compile(r"\[\[PRODUCT:([a-zA-Z0-9_]+)]]")

def _previous_recommended_pids(messages):
    last_assistant = next(
        (m.content for m in reversed(messages) if m.role == "assistant" and m.content),
        None,
    )
    if not last_assistant: return []
    seen = []
    for m in _PRODUCT_TAG_RE.finditer(last_assistant):
        if m.group(1) not in seen: seen.append(m.group(1))
    return seen

def _build_pinned_retrieved_for_compare(pids):
    products = _load_products()
    return [{"product_id": pid, "score": 1.0, "product": products[pid],
             "matched_chunks": [], "match_source": "pinned_compare"}
            for pid in pids if pid in products]
```

`_build_messages` 中接入：

```python
if has_compare_hint(last_user_text):
    pinned_pids = _previous_recommended_pids(req.messages)[:3]   # cap 3
# pin 到候选头部（去重）
if pinned_pids:
    pinned = _build_pinned_retrieved_for_compare(pinned_pids)
    pinned_ids = {p["product_id"] for p in pinned}
    retrieved = pinned + [r for r in retrieved if r["product_id"] not in pinned_ids]
# 隐式对比兜底
if not constraints.is_compare and len(pinned_pids) >= 2:
    constraints.is_compare = True
```

干跑验证 `is_compare=True` ✓、pinned 占 retrieved 前 N 位 ✓、system content 含 `COMPARE_DATA` 指令 ✓。

### 31.6 4.3 对话智能加分项十连（`server/app/constraints.py` 大改）

按"投入产出比"做了 10 项，覆盖 ⭐ / ⭐⭐ / ⭐⭐⭐ 三档。

#### 31.6.1 ⭐⭐ 同义词归并（#4）

**为什么需要**：数据集里同一品牌存两种字面（"耐克" vs "Nike"，"苹果" vs "Apple 苹果"）；用户用群组词指代（"日系/国货/法系"）。

```python
_BRAND_LITERAL_ALIAS = {
    "苹果": ["Apple 苹果", "Apple", "苹果", "iPhone", "iPad", "Mac"],
    "耐克": ["Nike", "耐克"],
    "欧莱雅": ["巴黎欧莱雅"],
    # ...
}
_BRAND_GROUP_ALIAS = {
    "日系": ["资生堂", "SK-II", "芳珂", "珊珂", "安热沙"],
    "国货": [/* 30+ 国货品牌全名 */],
    "法系": ["巴黎欧莱雅", "兰蔻", "理肤泉"],
    # ...
}

def expand_brand_aliases(token: str) -> list[str]:
    """耐克 -> [Nike, 耐克]；日系 -> 一组日系品牌"""
```

应用面：

- `extract_constraints` 抽到品牌排除词时调用 `expand_brand_aliases`，把所有 alias 写进 `brand_excludes`（chip 也会显示展开后的列表，便于用户撤销）
- `_filter_by_brand_excludes` 加大小写不敏感比较（`Nike` vs `nike`）
- `_filter_by_brand_required` 也按 alias 双向匹配，"我要 Apple 的"匹配 brand="Apple 苹果"

#### 31.6.2 ⭐ 显式重置约束（#1）

**痛点**：旧版只通过点 chip × 撤销。语音/文字"算了不限价格"会被 detector 当新话题，误清所有约束。

```python
_CANCEL_PRICE_RE = re.compile(
    r"(?:不限价格|不限预算|不要预算|不要价格|算了不限价|取消价格|忽略价格|"
    r"价格随便|价格不限|不在意价格|多少钱都行|多少钱都可以|不管价格|不考虑价格)"
)
_CANCEL_BRAND_RE = re.compile(
    r"(?:不限品牌|任何品牌|不挑品牌|什么品牌都行|品牌不限|不在意品牌|不管品牌|不考虑品牌)"
)
```

`extract_constraints` 在抽各类约束前先看是否命中 cancel——命中则该类约束本轮跳过抽取（保留其他约束）。

#### 31.6.3 ⭐⭐ 正面属性约束（#5）

**新能力**："想要防水的耳机"、"要无糖的可乐"、"支持快充"。

```python
_ATTR_REQUIRED_ALIAS = {
    "防水": ["防水", "防泼水", "IPX", "ipx"],
    "无糖": ["无糖", "0糖", "0卡", "零糖"],
    "降噪": ["降噪", "主动降噪", "ANC"],
    # ...
}
_REQUIRED_ATTR_RE = re.compile(
    r"(?:想要|得是|必须|得有|要有|支持|带|有|要)\s*"
    r"([一-龥A-Za-z0-9]{1,5}?)"  # 非贪婪——避免吃进"防水的耳机"
    r"(?=的|！|!|，|,|。|\s|$)"
)
```

`ConstraintSet.attr_required` 字段 + `_filter_by_attr_required` 过滤函数：候选 haystack（marketing_description + matched chunks + title）必须命中所有 required 属性的任一 alias，缺一就剔。

#### 31.6.4 ⭐ 相对涨价（#2）

D20 已有"再便宜点 ×0.7"。本次补上"再贵点"对称分支：

```python
_PRICE_UP_RE = re.compile(r"(?:再贵|更贵|高端点|贵点|预算高点|提高预算)")
PRICE_UP_RATIO = 1.4
PRICE_UP_CEIL = 100000.0

# 价格分支重构成 if-elif 三连
# 1.b 再便宜点（cs.price_max is None + DOWN_RE 命中 + UP_RE 不命中）
# 1.c 再贵点  （cs.price_max is None + UP_RE 命中 + DOWN_RE 不命中）
```

#### 31.6.5 ⭐⭐ 价格区间（#6）

**新能力**：500-1000 之间 / 至少 100 / 高于 200 / 差不多 800 元（±20%）。

```python
_PRICE_MIN_RE      # 至少|高于|起步|≥|>=|>
_PRICE_RANGE_RE    # 500-1000 之间
_PRICE_TARGET_RE   # 约|大概|差不多|...左右
PRICE_TARGET_TOLERANCE = 0.2

# 优先级：RANGE > TARGET > MAX > MIN（独立）
# 因为 RANGE 含两个数字，会被 MAX/MIN 各自抓走
```

`ConstraintSet.price_min` 字段 + `_filter_by_price_min`（用 SKU max 价做下限——商品最贵 SKU 还低于下限就丢弃，避免被低价 SKU 蒙混）。

#### 31.6.6 ⭐⭐⭐ 用户指定对比维度（#8）

**新能力**："对比一下，按音质和续航" → 强制 LLM 用这两个维度。

```python
_KNOWN_COMPARE_DIMENSIONS = [
    "音质", "续航", "降噪", "性价比", "拍照", "屏幕",
    "保湿", "成分", "肤感", "甜度", "缓震", "透气", # ...
]

# extract_constraints 末尾：has_compare_hint 命中时扫子串
if has_compare_hint(query):
    for dim in _KNOWN_COMPARE_DIMENSIONS:
        if dim in query: cs.compare_dimensions.append(dim)
```

`to_prompt_notes` 输出："用户指定的对比维度：音质 / 续航；【对比模式】下 dimensions 数组必须包含上述维度。"

#### 31.6.7 ⭐⭐⭐ 三款对比稳定（#9）

旧版让 LLM 自己选 2-3 款，经常退化成 2 款。新版按 `pinned_pids` 数量硬性约束：

```python
n_compare = len(pinned_pids) if pinned_pids else 0
n_compare_clause = (
    f"⚠ 本轮 `products` 长度**必须严格等于 {n_compare}**"
    if n_compare >= 2 else "本轮 `products` 长度建议 2-3 款"
)
# 注入对比模式 prompt
```

#### 31.6.8 ⭐⭐⭐ 多约束冲突放宽建议（#3）

**痛点**："500 以下且必须 Apple"候选 0 件 → 旧版只回"暂无"。新版做单约束 leave-one-out 分析：

```python
def suggest_relaxations(raw_retrieved, cs):
    """对每个非空约束，单独去掉后看还剩几件，给具体放宽建议"""
    # 价格：试用候选最低价 ×1.05 作新上限
    # 品牌排除：去掉看剩多少
    # 属性排除/必含、品牌必含 同上
```

`_build_messages` 在 `retrieved` 被清空时调用，把建议清单注入 system prompt：

```
【候选商品已被全部硬过滤掉】
请明确告知用户当前条件下没有匹配商品，并推荐以下放宽建议：
- 放宽价格上限到 ¥800 后还有 3 款可选
- 去掉品牌排除还有 5 款可选
禁止编造商品；不要用模糊词敷衍，必须给具体的数字 / 品牌建议。
```

#### 31.6.9 ⭐ 排除已展示商品（#7）

**痛点**："再来几款"会推一样的。客户端把当前 topic 内所有已展示 pid 上传，后端命中"再来/还有别的/换一批"时剔除。

```kotlin
// ChatViewModel
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
```

```python
# main.py
repeat_intent = bool(re.search(
    r"(再来|还有别的|换一批|换几款|还有吗|其他款|其他选择|更多)",
    last_user_text,
))
if repeat_intent and req.exclude_pids:
    excl = set(req.exclude_pids)
    retrieved = [r for r in retrieved if r["product_id"] not in excl]
```

#### 31.6.10 ⭐⭐⭐ 对比卡片视图切换（#10，纯客户端）

`ComparisonView` 顶部加 tab："雷达图 / 表格 / 全部"，默认"全部"（信息密度最高），用户点单 tab 节省屏幕：

```kotlin
private enum class CompareTab { Radar, Table, All }

@Composable
private fun CompareViewTabs(selected, onSelect, showRadar, showTable) {
    val tabs = buildList {
        if (showRadar) add(CompareTab.Radar to "雷达图")
        if (showTable) add(CompareTab.Table to "表格")
        if (showRadar && showTable) add(CompareTab.All to "全部")
    }
    // 渲染紫色实心/灰底胶囊...
}
```

数据不全（缺 scores 或 rows）时对应 tab 自动隐藏。

#### 31.6.11 单元测试

`server` 下跑了 12 条 case 全过：

```
OK    我要买跑鞋，不要耐克的         → ['Nike', '耐克']
OK    推荐精华，不要日系             → ['资生堂', 'SK-II', '芳珂', ...]
OK    200以内的耳机，算了不限价格    → price_max=None
OK    不要苹果的，算了不限品牌       → brand_excludes=[]  brand_required=None
OK    我要 Apple 的笔记本            → brand_required='Apple'
OK    500到1000之间的鞋              → price_min=500  price_max=1000
OK    差不多800元的笔记本            → price_min=640   price_max=960
OK    200以上的耳机                  → price_min=200
OK    要无糖的可乐                   → attr_required=['无糖']
OK    想要防水的耳机                 → attr_required=['防水']
OK    对比一下，按音质和续航         → compare_dimensions=['音质','续航']
OK    再贵点 (prior=500)             → price_max=700
```

踩了两个**贪婪正则坑**：

- `_EXCL_BRAND_RE` 把"不要耐克的"抓成 token="耐克的"——`{2,4}` 改非贪婪 `{2,4}?`
- `_REQUIRED_ATTR_RE` 把"要无糖的可乐"抓成"无糖的可乐"——同样改非贪婪

### 31.7 评测脚本三个细分指标（`eval/run_eval.py`）

题目要求"对话智能 4.3"的三档加分都要有量化指标。新增：

```python
def score_brand_exclude_compliance(reply_tags, checks, products):
    """checks 含 brand_not_in 时 applicable，
    检查 reply_tags 里没有任何被排除品牌即 ok"""

def score_price_max_compliance(reply_tags, checks, products):
    """所有推荐商品最低价 ≤ checks.max_price 即 ok"""

def score_compare_json(reply_text):
    """COMPARE_DATA 块：present / parsed / schema_ok / radar_ok 四级"""
```

`evaluate` 中按消息历史最后一句 user query 是否含"对比/比较/vs"判断 compare_json 是否 applicable。

`aggregate` 输出三个新比率：

- `brand_exclude_compliance_rate` — 反选/排除遵守率
- `price_max_compliance_rate` — 价格上限遵守率
- `compare_json_valid_rate` — 对比 JSON schema + 雷达图字段合法率

`write_report` 在总体指标表里加三行 `↳` 缩进细分项。

### 31.8 联调与装机

```bash
# 后端
./scripts/stop_server.sh && ./scripts/start_server.sh
# ... 健康检查通过，BASE_URL: http://192.168.43.15:8000

# SSE 冒烟（确认价格区间 + 群组排除）
curl -N -X POST http://127.0.0.1:8000/chat/stream \
  -d '{"messages":[{"role":"user","content":"想要500到1000之间的跑鞋，不要日系"}]}'

event: constraints
data: {"price_max":1000.0,"price_min":500.0,
       "brand_excludes":["资生堂","SK-II","芳珂","珊珂","安热沙"]}
event: retrieved
data: [{"product_id":"p_clothes_007","title":"Nike Air Zoom Pegasus 41 ..."},...]

# 客户端
JAVA_HOME=".../jbr/Contents/Home" ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # Success
```

### 31.9 答辩话术（D21 可讲点）

1. **多会话持久化的"延迟落盘"**：内存里起空白会话不入历史，第一条消息发出后才写文件——避免"用户每次冷启动都堆一个空对话"的常见 UX 反模式。
2. **对比模式的隐式指代解析**：题目示例都是"对比 X 和 Y"显式句，但真实用户只说"对比一下"。我们扫上一条 assistant 消息里的 `[[PRODUCT:pid]]` 标签拿到指代对象，pin 到候选头部并强制 `is_compare=True`，让 ⭐⭐⭐ 三档加分项在最常见的口语场景下也能触发。
3. **同义词归并的两条路径**：字面 alias（耐克↔Nike）走 substring + lower()，群组 alias（日系→5 个具体品牌）走显式展开写入 `brand_excludes`，让 chip 也能逐个撤销。
4. **多约束冲突的"放宽建议"**：候选清空时不直接拒答，做单约束 leave-one-out 分析告诉用户"放宽哪一项就有几件"——把硬约束系统从"二选一过滤器"升级成"协商助手"。
5. **评测的三档加分项量化**：`brand_exclude_compliance_rate` / `price_max_compliance_rate` / `compare_json_valid_rate` 三个指标直接对应 4.3 三档评分点，在报告里以 `↳` 缩进呈现，PPT 一页表能讲清"加分项落地了多少"。
6. **bash 中文 locale 下的 `${VAR}` 强制大括号**：演示日如果在用户的 zsh 下出现 `unbound variable`，不是脚本有 bug 而是 locale 不一致——脚本一律用 `${PORT}` 而非 `$PORT` 兜底。

### 31.10 沉淀文件清单（D21）

**新增**：

- `client/.../ConversationStore.kt` — 多会话持久化层
- `server/scripts/start_server.sh` — 后端启动 + 健康探活 + IP 提示
- `server/scripts/stop_server.sh` — 优雅停止 / `-9` 强杀

**修改**：

- `client/.../Models.kt` — `Role` / `UiMessage` 加 `@Serializable`，`ChatRequestWire` 加 `exclude_pids`
- `client/.../ChatViewModel.kt` — 改 `AndroidViewModel`；多会话 API（new/select/delete/persist）；`currentTopicShownPids()`；`cancel()` 顺手清掉 isStreaming 占位
- `client/.../ChatScreen.kt` — `ModalNavigationDrawer` + 顶栏 ☰/＋；`ConversationDrawer` + `ConversationRow`；`ComparisonView` 加视图切换 tab
- `client/.../ChatRepository.kt` — `chatStream` 接收 `excludePids`
- `client/.../ConversationTopicManager.kt` — 短句追问词扩三类；上限 8→12；代词补"两款/这两/前面那"
- `server/app/constraints.py` — 同义词表 / 显式重置 / 正面属性 / 价格区间 / 对比维度 / 放宽建议（10 个特性）
- `server/app/main.py` — `_previous_recommended_pids` + pin；`exclude_pids` 字段；放宽建议注入；对比 N 锁定
- `eval/run_eval.py` — `score_brand_exclude_compliance` / `score_price_max_compliance` / `score_compare_json` + aggregate 输出三个新指标 + write_report 加三行

### 31.11 待启动 / 后续可选

- 评测脚本端到端跑一遍（需要 ARK_API_KEY 余额，预计 12 query × ~6s = 1-2 min），输出新版 markdown 报告
- 对比卡片视图切换的截图入 PPT（雷达图 / 表格各一张，用户主推哪个就在 demo 里点哪个 tab）
- 同义词表补强：当前手工维护，后续如有大数据集可考虑用 BGE 句向量自动聚类品牌别名

---

## 32. D21 后续 — 思考过程可观测面板（2026-06-03）

### 32.1 用户主诉

答辩前补"过程可见性"：每条 AI 回复气泡顶部加一个折叠面板，展开能看到检索策略 / 召回数 / Top 相似度 / 命中的硬约束 / 各阶段耗时。目标是让评审一眼就能确认"RAG / 多模态 / 约束过滤都真的在跑"，而不只是看最终回复。

题目原话："event=thinking_step / event=retrieved 含 strategy+top_scores / event=constraints 已含 brand_excludes 等 — 缺字段则补；客户端做时间线 + 折叠 + 流式逐条追加。"

### 32.2 后端：分阶段计时检索

新增 `server/app/hybrid_retriever.py:retrieve_hybrid_with_trace()`，对原 `retrieve_hybrid` 同链路做 `time.perf_counter()` 打点：

| step | 来源 | 含义 |
| --- | --- | --- |
| `vector_recall` | 向量召回 | BGE → Chroma top-30 chunk |
| `bm25_recall` | BM25 召回 | jieba 分词召回 top-30（仅 hybrid/hybrid_rerank） |
| `rrf_fuse` | RRF 融合 | 两路融合到候选池 |
| `rerank` | CrossEncoder 重排 | bge-reranker-base（仅 hybrid_rerank） |
| `aggregate` | 商品聚合 | 按 product_id 聚合到 top_k 款 |

`server/app/retriever.py` 加 `retrieve_with_trace(query, top_k, mode) -> (retrieved, trace)` 透传，避免 main 直接耦合 hybrid 模块。

### 32.3 后端：`_build_messages` 升签到 4 元组

```python
def _build_messages(req) -> tuple[list[dict], list[dict], ConstraintSet, list[dict]]:
    # 新增：trace 元素 {"step", "detail", "duration_ms"}
    trace.append({"step": "query_rewrite", ...})              # 多轮 query 拼接
    retrieved, retrieval_trace = retrieve_with_trace(...)     # 检索五子步骤
    trace.extend(retrieval_trace)
    # 命中硬约束时 (before != after) 追加 constraint_filter 步骤
    trace.append({"step": "constraint_filter", ...})
```

调用方 `/chat`、`/chat/stream`、`/chat/stream/multimodal` 全部解包到 4 元组。`/chat` JSON 响应额外返回 `"trace": [...]`。

### 32.4 后端：SSE 新增 `event=thinking_step` + 升级 `retrieved` 结构

**`/chat/stream`** 新增事件：

```
event: thinking_step
data: {"step":"vector_recall","detail":"BGE 向量召回 30 条 chunk","duration_ms":42}
```

按 trace 顺序逐条下发，再加一条 `generate_first_token`（LLM 首 token 到达后打点，用 `time.perf_counter()` 测 TTFB）。

**`event=retrieved` 升级**：data 从数组 → 对象

```
event: retrieved
data: {
  "strategy": "hybrid_rerank",
  "count": 5,
  "top_scores": [0.876, 0.812, 0.765, 0.711, 0.689],
  "items": [{"product_id":"p_xxx","title":"...","score":0.876}, ...]
}
```

**`/chat/stream/multimodal`** 同步改造：vision_describe / clip_recall / multimodal_fuse 三步打点，retrieved 同样新结构（保留 `match_source` 字段）。

### 32.5 客户端：协议解析 + 数据模型

**`Models.kt`**：

```kotlin
@Serializable
data class ThinkingStepWire(val step: String, val detail: String = "", val duration_ms: Int = 0)

@Serializable
data class RetrievedSummaryWire(
    val strategy: String = "hybrid_rerank",
    val count: Int = 0,
    val top_scores: List<Double> = emptyList(),
    val items: List<RetrievedItem> = emptyList(),
)

// UiMessage 新增三个字段
val thinkingSteps: List<ThinkingStepWire> = emptyList(),
val retrievedSummary: RetrievedSummaryWire? = null,
val constraintsSnapshot: ConstraintSnapshotWire? = null,
```

**`ChatRepository.kt`**：

- `StreamEvent.Retrieved` 由 `List<RetrievedItem>` 改为 `RetrievedSummaryWire`
- 新增 `StreamEvent.ThinkingStep(step: ThinkingStepWire)`
- `parseRetrieved(data)` 兼容新（对象）/老（数组）两种 retrieved 格式 — 单独升级一端时不会炸：

```kotlin
private fun parseRetrieved(data: String): RetrievedSummaryWire? {
    val element = json.parseToJsonElement(data)
    return when (element) {
        is JsonObject -> json.decodeFromJsonElement(RetrievedSummaryWire.serializer(), element)
        is JsonArray  -> /* 退化为只有 items + count + top_scores 推导 */
        else -> null
    }
}
```

**`ChatViewModel.runStream`**：

```kotlin
StreamEvent.Constraints -> {
    _constraints.value = ev.snapshot
    // 同步把约束快照挂到本条 assistant 消息上，让历史回看也能看到
    updateAssistant(assistantId) { it.copy(constraintsSnapshot = ev.snapshot) }
}
StreamEvent.Retrieved -> {
    updateAssistant(assistantId) { it.copy(
        retrieved = ev.summary.items,
        retrievedSummary = ev.summary,
    ) }
}
StreamEvent.ThinkingStep -> {
    updateAssistant(assistantId) { it.copy(thinkingSteps = it.thinkingSteps + ev.step) }
}
```

### 32.6 客户端：`ThinkingPanel` 折叠面板（`ChatScreen.kt`）

挂在 `MessageBubble` 顶部、`RetrievedHint` 之上。

- **收起态**：`💡 查看检索过程 · 共 1.2s`（流式中显示"💡 思考中 · 已耗时 X.Xs"）
- **展开态**四块：
  1. 🔍 **检索策略胶囊**（fusion / hybrid+rerank / vlm_only / clip_only）— 命中的胶囊紫色实心，其余描边灰
  2. 📦 **召回汇总** — `召回 N 款商品` + `Top 分数：0.876 · 0.812 · 0.765`
  3. 🚫 **约束过滤** — `¥≤500 · ✗品牌:Nike · 不含酒精`，仅命中时渲染
  4. ⏱️ **时间线** — 圆点 + 连线 + 中文步骤名（"向量召回 / BM25 召回 / RRF 融合 / CrossEncoder 重排 / 商品聚合 / 硬约束过滤 / VLM 识图 / CLIP 图像召回 / 多模态融合 / 首 Token 生成"）+ 单步耗时
- **动画**：`animateContentSize(tween(180ms))` + `AnimatedVisibility(fadeIn + expandVertically)`，流式期间 `thinkingSteps + ev.step` 触发列表增量重组，逐条追加效果天然平滑
- **`stepLabel(step)`** 把后端的 snake_case 翻成中文短标签；未知 step 直接回显（向前兼容新增步骤）
- **`formatDuration(ms)`** 统一 < 1000ms 显示 `Xms`，否则 `X.Xs`

### 32.7 关键设计决策

1. **trace 在后端组装而不是前端推断**：流式期间客户端只负责 append，无需"看到 retrieved 后才追加 aggregate 步骤"这种容易出错的隐式状态机
2. **thinking_step 与 retrieved 解耦**：thinking_step 给面板可视化，retrieved 给商品卡片渲染；两个事件互不依赖，单独失败不影响另一边
3. **`generate_first_token` 在 LLM 首 chunk 到达时打点**：TTFB 是评审最关心的延迟指标之一，单独成步比"用 done - retrieved 总耗时差值"准确得多
4. **constraints 快照挂到消息上**：原本只放 `ChatViewModel.constraints`（全局当前值），但回看历史消息时全局状态已被新一轮覆盖。挂消息上才能让"历史回看的思考过程面板"也展示当时的硬过滤
5. **兼容老 retrieved 格式**：`parseRetrieved` 对 JsonArray 退化处理，避免后端没升级时客户端整条流挂掉

### 32.8 答辩话术

1. **过程可见性是 RAG 项目的差异化**：评审看到"思考过程"折叠面板能直接确认"BM25 + 向量都在跑、CrossEncoder 重排有 X ms 收益、CLIP 图像召回花了 Y ms"，比口头讲"我们做了混合检索"有说服力得多
2. **时间线 + 单步耗时**：每个阶段独立计时打点，瓶颈一眼可见——例如演示日 LLM 首 token 慢可以打开面板说"这次模型 TTFB 1.8s，检索本身只 80ms"
3. **流式逐条追加 vs 等流结束渲染**：`thinkingSteps` 是 `List<>` immutable replace + `animateContentSize`，每来一条都触发局部重组动画，用户感知"思考过程在实时进行"而不是黑盒
4. **多模态独立路径透出**：`vision_describe` / `clip_recall` / `multimodal_fuse` 三个步骤让评审能区分"这次是文本路径还是图像路径"，对照 `match_source` 角标讲三档融合的工程价值
5. **协议向前兼容**：`stepLabel` 未知 step 回显原文，后端新增步骤（如未来加 query_expansion）客户端无需改动也能渲染

### 32.9 沉淀文件清单（§32）

**新增 / 修改后端**：

- `server/app/hybrid_retriever.py` — `retrieve_hybrid_with_trace()` 新增，按阶段 `time.perf_counter()` 打点
- `server/app/retriever.py` — 导出 `retrieve_with_trace()` 透传
- `server/app/main.py` — `_build_messages` 升 4 元组；`/chat`、`/chat/stream`、`/chat/stream/multimodal` 三处下发 thinking_step + retrieved 升级为对象；多模态分支新增 vision_describe / clip_recall / multimodal_fuse 步骤

**新增 / 修改客户端**：

- `client/.../Models.kt` — `ThinkingStepWire` / `RetrievedSummaryWire` 新增；`UiMessage` 加 `thinkingSteps` / `retrievedSummary` / `constraintsSnapshot`
- `client/.../ChatRepository.kt` — `StreamEvent.ThinkingStep` 新增；`Retrieved` 改持 `RetrievedSummaryWire`；`parseRetrieved` 双格式兼容
- `client/.../ChatViewModel.kt` — `runStream` 收 `thinking_step` / `Retrieved.summary` / `Constraints` 三事件分别落到 UiMessage 字段
- `client/.../ChatScreen.kt` — `ThinkingPanel` Composable + `StrategyRow` / `RecallSummaryRow` / `ConstraintsLine` / `ThinkingTimeline` 子组件 + `formatDuration` / `stepLabel` 工具函数；`MessageBubble` 顶部接入

### 32.10 待联调

- 真机端到端：发"推荐一款适合油皮的洗面奶" → 看面板 ⏱️ 时间线是否包含 query_rewrite / vector_recall / bm25_recall / rrf_fuse / rerank / aggregate / generate_first_token 七步
- 拍照搜：选张商品图 → 面板应包含 vision_describe / clip_recall / multimodal_fuse / generate_first_token
- 长对话回看：滚回到第 1 条 assistant 消息，展开面板看 `constraintsSnapshot` 是否还原当时的硬过滤

---

## 33. 主动澄清功能（2026-06-03）

### 33.1 用户需求

输入"推荐手机"这种 query 太短/缺关键属性的句子时，不该硬塞给 RAG（召回噪声大、用户也未必想要那个），而应该先抛 1-2 个澄清问题 + 快捷气泡选项让用户两秒点完，再用拼好的完整 query 走 RAG。

要求点：
- 后端 `intent_classifier.py` 计算 `ambiguity_score`（词数 < 6 或缺品类/价格/场景视为高歧义）
- 触发后构建 prompt 让 LLM 返回 1-2 个澄清问题 + 每题 2-4 个选项
- 阈值可配置，命中走澄清，否则直接 RAG
- SSE 新增 `event=clarification` 事件
- 客户端解析事件 → 渲染圆角胶囊气泡按钮（横排超出换行），点击后填充态、整组变灰、自动发送"原 query + 选项文本"

### 33.2 关键决策

**(a) 命名"明确度分数"而非"歧义分数"**：内部命名用 clarity（满分 1.0），`score < AMBIGUITY_THRESHOLD` (默认 0.6) 触发澄清。这与"维度命中越多分数越高"的直觉一致，避免反义混乱。

**(b) 四维加权而非 LLM 判定**：
| 维度 | 权重 | 关键词来源 |
| --- | --- | --- |
| 词数 ≥6 | 0.3 | 中文字符 + 英文单词数 |
| 命中品类 | 0.3 | `_CATEGORY_KEYWORDS`（30+ 词，对齐 data 实际 sub_category） |
| 命中价格表达 | 0.2 | 复用 constraints.py 的价格正则口径 |
| 命中场景/属性 | 0.2 | `_SCENE_KEYWORDS`（油皮/通勤/送礼/降噪 等 60+ 词） |

不调 LLM 是因为：① 首响要快（要在 ms 级判定），② 规则可解释（答辩时能直接展示打分明细）。LLM 仅在判定为高歧义之后用来生成澄清问题。

**(c) 仅在话题首句触发**：多轮追问中即便用户偶尔打个短句（"再来几款"）也不该被澄清打断。判定方式简单：当前 `ChatRequest.messages`（已是话题切片）里 user==1 且 assistant==0 → 视为话题首句。

**(d) LLM 失败必定兜底**：网络错 / 超时 / JSON 解析失败时不抛，从 `_FALLBACK_QUESTIONS` 模板取 missing 维度对应的问题（最多 2 题）。保证演示链路永远不阻塞。

**(e) 客户端"已选锁定"**：用户点击任一选项后，整个 question 组的所有 chip 都不可再点（描边变灰）。避免连点导致同一条 user 消息被发多次。

### 33.3 后端实现要点

新文件 `server/app/intent_classifier.py`：

```python
AMBIGUITY_THRESHOLD = float(os.environ.get("AMBIGUITY_THRESHOLD", "0.6"))

def compute_ambiguity_score(query) -> tuple[float, list[str]]:
    score = 0.0; missing = []
    if _count_tokens(q) >= 6: score += 0.3
    else: missing.append("length")
    if _hit_any(q, _CATEGORY_KEYWORDS): score += 0.3
    else: missing.append("category")
    if _PRICE_PATTERN.search(q): score += 0.2
    else: missing.append("price")
    if _hit_any(q, _SCENE_KEYWORDS): score += 0.2
    else: missing.append("scene")
    return score, missing

async def maybe_build_clarification(query) -> ClarificationResult | None:
    need, score, missing = should_clarify(query)
    if not need: return None
    questions = await generate_clarification(query, missing)  # LLM + 兜底
    return ClarificationResult(should_clarify=True, score=score, ...)
```

`generate_clarification` 的 prompt 关键约束：
1. questions 长度 1-2，options 每题 2-4 个，每项 ≤6 字
2. 选项必须互斥，**禁止"其他/都行/随便"**（选了等于没选）
3. 紧扣 missing 维度，已给信息不再问
4. 严格 JSON，不带代码围栏

**`main.py` 改动**：
```python
def _is_first_user_turn(messages):
    return sum(1 for m in messages if m.role == "user") == 1 \
       and sum(1 for m in messages if m.role == "assistant") == 0

@app.post("/chat/stream")
async def chat_stream_endpoint(req):
    last_user_text = next((m.content for m in reversed(req.messages) if m.role == "user"), "")
    if last_user_text and _is_first_user_turn(req.messages):
        result = await maybe_build_clarification(last_user_text)
        if result is not None:
            # 只下发 clarification + done，跳过 RAG 与 LLM 生成
            return EventSourceResponse(...)
    # 否则正常走 _build_messages → 检索 → chat_stream
```

**烟雾测试结果**：
| query | score | clarify | missing |
| --- | --- | --- | --- |
| "推荐手机" | 0.30 | ✅ | length, price, scene |
| "面霜" | 0.30 | ✅ | length, price, scene |
| "买点零食" | 0.30 | ✅ | length, price, scene |
| "推荐一款适合油皮的洗面奶" | 0.80 | ❌ | price |
| "1000以内的轻便跑鞋日常通勤" | 1.00 | ❌ | — |

LLM 真实输出（"推荐手机"）：
```
Q1: 你的预算是多少？  ['千元以内','2-4千元','4-6千元','6千以上']
Q2: 你主要用它做什么？['日常使用','拍照为主','重度游戏','商务办公']
```

### 33.4 客户端实现要点

**`Models.kt`** 新增：
- `ClarificationWire(type, score, missing, questions:[ClarificationQuestionWire])`
- `UiMessage` 加 `clarification` / `clarificationSelected: List<String?>` / `clarificationOriginalQuery`

**`ChatRepository.kt`**：`StreamEvent.Clarification(payload)` 分支；listener 解析 `event=clarification`。

**`ChatViewModel.kt`**：
- `runStream` 收到 Clarification 时把问题、空选中表、原始 query 挂到 assistant 消息
- 新增 `answerClarification(messageId, qIdx, option)`：
  - busy 中或已点过任一选项 → 直接忽略（防连点）
  - 把 selected[qIdx] = option 更新到消息（触发 UI 变灰）
  - `_input = "原始query，选项文本"` + `forceSameTopicOnNextSend = true` + `send()`

**`ChatScreen.kt`**：
- `MessageBubble` 检测 `clarification != null` → 跳过空文本气泡（避免空白占位）
- 新增 `ClarificationCard`：紫色"💬 帮我把需求说得更具体些～"标题 + 问题文本 + `FlowRow` 横排胶囊（超出自动换行）
- 新增 `ClarificationChip` 三态：未选描边、已选实心填充紫底白字、disabled 灰描边不可点击
- import `androidx.compose.foundation.layout.{ExperimentalLayoutApi, FlowRow}`

### 33.5 答辩话术

1. **首响零延迟**：澄清判定纯正则 + 词典，<1ms 命中。比"先调 RAG 再判结果"省了 ~1s 检索 + ~1.5s LLM TTFB
2. **Prompt-engineering 约束**：选项 ≤6 字 + 互斥 + 禁"其他" — 直接量化输出质量，前端不用做后处理
3. **失败兜底**：LLM 不可用时模板自动接管，演示日就算 ARK 限流也不会出现"思考中…"卡住
4. **多轮不打扰**：仅话题首句触发，避免"再来几款"被反复问预算
5. **闭环可视化**：问题 → 用户点击 → 自动拼成新 query → 第二轮请求 score=0.8+ → 走 RAG 返回卡片，整个状态机在 ChatViewModel 里 30 行代码闭合

### 33.6 沉淀文件清单（§33）

**新增后端**：`server/app/intent_classifier.py`

**修改后端**：`server/app/main.py`（import + `_is_first_user_turn` + `/chat/stream` 入口分支）

**修改客户端**：`Models.kt` / `ChatRepository.kt` / `ChatViewModel.kt` / `ChatScreen.kt`

---

## 34. 场景化组合推荐（2026-06-03）

### 34.1 用户需求

用户说"下周去三亚帮我搭配度假方案" / "送女友礼物预算1500" 这种"组合需求"时，不该只推一款商品 — 应该并行检索多个子类目（防晒/T恤/帽子/背包），让 LLM 编排成一套搭配方案，并在客户端用横滑套装卡片渲染。

要求点：
- 后端 `scene_detector.py` 识别 `scene_type` (trip/gift/daily_routine/workout) + 抽属性（destination/occasion/budget_total/gender_hint）+ 拆 2-4 个子类目 query
- 新增 `/chat/stream/scene` 端点：`asyncio.gather` 并行 RAG，combo_prompt 让 LLM 编排，SSE `event=combo_result` 下发结构化数据
- 总预算约束：用户提到时注入 prompt，要求各品类价格之和不超限
- 客户端新增 `ComboCardView`：场景标题 + 横向品类小卡片（图+名称+价格+品类徽标）+ 底部 LLM 搭配建议

### 34.2 关键决策

**(a) 子类目模板表 vs 让 LLM 自己拆**：
- 选了模板表（手工整理）。原因：① 数据集只有 100 款商品，模板能保证拆出的 sub_query 都能召回到东西（如"沙滩裙/凉鞋"在数据里没有，必须改成"短袖T恤/背包"），② 启动延迟可控（无需先调一次 LLM 拆 query），③ 答辩时能直接展示"trip → 4 路 sub_query"映射，可解释性强
- 模板按 scene_type × 子场景细分：trip(海岛/徒步/城市) × gift(美妆/数码/食品) × daily(护肤/穿搭) × workout(跑步/健身/瑜伽) = 10 套子类目

**(b) ChromaDB 多线程并发 init 崩了**：第一次跑 `asyncio.gather(retrieve × 4)` 时全部抛异常：
```
AttributeError: 'RustBindingsAPI' object has no attribute 'bindings'
ValueError: Could not connect to tenant default_tenant
```
原因：`PersistentClient(path=...)` 不是线程安全，多个 to_thread 同时初始化会抢同一份 Rust bindings。
修复：在 `hybrid_retriever.warmup()` 里加 `get_or_create_collection()` 预热，让 lru_cache 单线程提前完成 init。

**(c) LLM 跨类目幻觉防御**：让 LLM 输出 `{category, product_id, reason}` 数组时，它有时会把"防晒"类目的位置塞一个"T恤"的 product_id。
防御：
```python
allowed_pids = {sub.label: {r.product_id for r in recs} for sub, recs in groups}
# 跨类目幻觉 → 强制改回该 category 的 top1
if pid not in allowed_pids[cat]:
    pid = next(iter(allowed_pids.get(cat, set())), None)
```
此外 LLM 漏掉的品类用 top1 自动补齐，保证套装结构完整。

**(d) `combo_result` 直接展开商品对象，客户端不用再调 `/products/{id}`**：
- 单品 RAG 路径：retrieved 只下发 product_id + score + title，客户端用 `ensureProductLoaded` 按需补拉详情
- 套装路径：4 件商品都要立即渲染图片+价格，并发拉 4 次 GET 不如服务端一次塞好；况且服务端已经从 `_load_products()` 拿到了完整对象，零额外开销

**(e) 触发时机：客户端正则粗筛 → 后端精细判定**：
- 客户端 `sceneKeywordRegex` 命中（"度假/送礼/搭配/健身/跑步..."）→ 调 `/chat/stream/scene`
- 后端 `detect_scene` 再做精细识别 + 子类目拆分；若发现不是场景（is_scene=False）→ 下发 error，让客户端 fallback 到普通 `/chat/stream`
- 这样客户端不用懂场景内部分类，后端有自由迭代模板表的空间

**(f) 场景请求强制视为新话题**：套装搭配是独立的强意图，不该继承上一轮的"再便宜点 / 不要日系"约束。客户端 `sendSceneRequest` 直接构造 `Decision(sameTopic=false)`。

### 34.3 后端实现要点

**新文件 `server/app/scene_detector.py`**：
- `SubQuery(label, query)` — label 给 UI 徽标，query 给 RAG 检索
- `_TRIP_BEACH / _TRIP_HIKE / _TRIP_CITY / _GIFT_BEAUTY / _GIFT_DIGITAL / _GIFT_FOOD / _DAILY_SKINCARE / _DAILY_OUTFIT / _WORKOUT_RUN / _WORKOUT_GYM / _WORKOUT_YOGA` 11 个模板
- `detect_scene(query) -> SceneInfo`：场景判定优先级 trip > gift > workout > daily（daily 关键词最泛放最后）
- `_extract_budget` 复用 `constraints._PRICE_MAX_RE` + 兼容"总预算 X" 兜底正则
- `_detect_gender` 命中"送女友/送男友"等返回 female/male

**`main.py` 新增 `/chat/stream/scene` 端点**：
```python
@app.post("/chat/stream/scene")
async def chat_stream_scene(req: SceneChatRequest):
    async def event_gen():
        # Step 1: 场景识别（同步）
        scene = detect_scene(req.query)
        if not scene.is_scene: yield error; return
        yield {"event": "scene", "data": scene.to_dict()}
        yield thinking_step("scene_detect", duration_ms=...)

        # Step 2: 并行 RAG（核心）
        tasks = [asyncio.to_thread(retrieve, sub.query, top_k_per_sub, "hybrid_rerank")
                 for sub in scene.sub_queries]
        recall_lists = await asyncio.gather(*tasks, return_exceptions=True)
        groups = list(zip(scene.sub_queries, recall_lists))
        yield thinking_step("parallel_recall", detail=f"并行召回 {N} 路 / {total} 个候选")

        # Step 3: LLM 编排（含预算约束）
        prompt = _COMBO_PROMPT_TEMPLATE.format(
            scene_label=scene.scene_label, query=req.query,
            constraints_block=..., groups=_format_groups_for_prompt(groups),
            budget_hint=f"items 中所有 product 的 base_price 之和不得超过 ¥{budget}",
            scene_attrs="海岛度假, 三亚",
        )
        raw = await chat_once(messages=[{"role":"user","content":prompt}])
        parsed = _parse_combo_json(raw) or _fallback_combo(scene, groups)

        # Step 4: 幻觉防御 + 漏类目补齐 + 展开 product 对象
        normalized_items = [...]
        yield {"event": "combo_result", "data": {
            "type":"combo", "scene":..., "scene_label":..., "scene_type":...,
            "budget_total":..., "items":[{"category","product_id","reason","product":{...}}],
            "summary":...
        }}
        yield {"event": "done", "data": "{}"}
```

**`combo_prompt`** 关键约束：
1. `items` 长度必须等于候选块数（每个品类必须出现）
2. `product_id` 必须来自对应品类候选；编造或跨类目都视为错误
3. 总预算注入硬性约束：`base_price` 之和不得超过 ¥X；候选最便宜组合也超出时必须在 summary 末尾说明
4. `reason` 紧扣场景属性（"三亚海岛度假"），禁止"高品质值得购买"空话
5. **只输出 JSON**，不要代码围栏 / 解释

**`hybrid_retriever.warmup`** 修复：
```python
def warmup():
    _get_bm25()
    _get_reranker()
    get_or_create_collection()  # ⭐ 修复并发首请求 ChromaDB 多线程竞争 init
```

### 34.4 客户端实现要点

**`Models.kt`**：
- `ComboItemWire(category, product_id, reason, product: ProductWire)`
- `ComboDataWire(type, scene, scene_label, scene_type, budget_total, items, summary)`
- `SceneInfoWire / SubQueryWire / SceneChatRequestWire`
- `UiMessage.combo: ComboDataWire?`

**`ChatRepository.kt`**：
- `StreamEvent.SceneInfo(info)` / `StreamEvent.ComboResult(data)`
- 新增 `sceneChatStream(query, history)`：post 到 `/chat/stream/scene`，listener 处理 `scene` / `thinking_step` / `combo_result` / `done` / `error`

**`ChatViewModel.kt`**：
- `sceneKeywordRegex` 正则（去X/度假/送礼/搭配/健身/跑步...）
- `send()` 命中 → `sendSceneRequest` → `collectSceneStream`：
  - 不走 typewriter（scene 流没有 token）
  - `combo_result` 到达时把 4 件商品塞进 productCache（让后续追问能引用），把 productIds 也写到消息上保持兼容
  - try/finally 保证 isStreaming 收尾
- `runStream`（普通流）的 when 加 `SceneInfo, ComboResult -> Unit` 空分支保持穷尽性

**`ChatScreen.kt`**：
- `MessageBubble` 检测 `combo != null` → 跳过普通文本气泡 + 商品横滑列表
- 新增 `ComboCardView`：
  - 顶部场景标题（含 emoji，如"🏖️ 三亚度假搭配"）
  - "已选 N 件 · 合计 ¥X / 预算 ¥Y" 价格汇总行 — 超预算时红字 + "超出预算"角标
  - `LazyRow` 横向滚动套装小卡片
  - 底部紫色 summary 卡片（LLM 整体搭配建议）
- 新增 `ComboItemCard`：
  - 复用商品图加载逻辑（`Config.BASE_URL/static/{encoded path}`）
  - 左上角紫色品类徽标（"防晒"/"T恤"/"帽子"/"背包"）
  - 标题 / 价格 / LLM reason 三段式

### 34.5 端到端测试

输入："下周去三亚，帮我搭配度假方案，预算1500"

后端 SSE 事件序列：
```
1. scene           → {scene_type:"trip", scene_label:"🏖️ 三亚度假搭配", destination:"三亚",
                       budget_total:1500, sub_queries:[防晒/T恤/帽子/背包]}
2. thinking_step   → scene_detect (0ms)
3. thinking_step   → parallel_recall (10993ms, 4 路 / 12 个候选)
4. thinking_step   → llm_compose (17172ms, 编排 4 件)
5. combo_result    → {items:[
     {category:"防晒",  product:{安热沙金灿倍护防晒乳, ¥298}, reason:"高倍防水防汗，适配三亚海边"},
     {category:"T恤",   product:{迪卡侬速干T恤, ¥79},        reason:"速干轻薄透气"},
     {category:"帽子",  product:{...},                       reason:"..."},
     {category:"背包",  product:{...},                       reason:"..."}
   ], summary:"整套覆盖SPF50+防晒、速干透气...", budget_total:1500}
6. done
```

四件套总价 ≤1500 ✅，所有 reason 紧扣"三亚海岛度假"✅。

### 34.6 答辩话术

1. **场景化是与单品 RAG 的核心差异**：用户说"度假" 而不是"防晒霜"时，单品 RAG 只能推 1 款防晒；场景路径并行 4 路检索 → 编排成套装，是真正"导购"的语义
2. **并行检索的工程价值**：`asyncio.gather + to_thread` 让 4 路 RAG 与单路同时长（受最慢一路约束），不会 4 倍延迟
3. **预算硬约束**：用户提到"1500"时，prompt 显式注入"price 之和 ≤1500"，超预算时 LLM 必须在 summary 里说明 — 客户端再用红字 + "超出预算"角标双重提示
4. **跨类目幻觉防御**：`allowed_pids` 字典把 LLM 选错的 product_id 强制改回该类目 top1，演示日不会出现"防晒位置塞了一件 T 恤"的尴尬
5. **触发架构**：客户端粗筛正则 + 服务端精细模板。客户端零业务知识，服务端模板可热改不影响 APK
6. **可观测性**：thinking_step 时间线展示"场景识别 0ms / 并行召回 11s / LLM 编排 17s"，瓶颈一目了然

### 34.7 沉淀文件清单（§34）

**新增后端**：`server/app/scene_detector.py`

**修改后端**：
- `server/app/main.py` — `SceneChatRequest` + `/chat/stream/scene` + `_COMBO_PROMPT_TEMPLATE` + `_parse_combo_json` / `_fallback_combo` / `_format_groups_for_prompt` 工具
- `server/app/hybrid_retriever.py` — `warmup()` 加 `get_or_create_collection()` 预热

**修改客户端**：
- `Models.kt` — 5 个新 Wire 类 + `UiMessage.combo`
- `ChatRepository.kt` — 2 个新 StreamEvent + `sceneChatStream`
- `ChatViewModel.kt` — `sceneKeywordRegex` + `looksLikeScene` + `sendSceneRequest` + `collectSceneStream` + 普通流 when 穷尽性
- `ChatScreen.kt` — `ComboCardView` + `ComboItemCard` + MessageBubble 路由

### 34.8 待联调

- 真机演示：输入"下周去三亚搭配度假方案" → 看 4 件横滑套装 + 红字预算标记
- 边界：故意把预算压到 ¥100（最便宜组合也超） → LLM 应该照实在 summary 末尾说明
- 多场景对照：分别试 trip(三亚/徒步)、gift(送女友/送爸喝茶)、workout(跑步/瑜伽)、daily(护肤/通勤) 看每条路径的子类目模板都能召回


