# 基于 RAG 的多模态电商智能导购 Agent

> 字节跳动 AI 全栈挑战赛参赛项目（开发周期 2026-05-20 ~ 2026-06-10）

一个跑在 Android 真机上的导购助手：用户**文字提问**或**拍照搜商品**或**语音提问**，后端基于 RAG 在本地商品库做检索，再交给豆包大模型流式生成推荐文案，客户端按 token 逐字打字、命中商品 ID 时实时渲染商品卡片。

---

## ✨ 核心亮点

| 维度 | 实现 |
| --- | --- |
| RAG | 4 类粒度 chunk（meta / marketing / faq / review）+ 本地 BGE 中文嵌入 + Chroma 向量库 |
| 流式 | SSE 自定义事件 `retrieved` / `vision` / `token` / `done` / `error`，端侧 25ms/字"豆包同款"打字机节奏 |
| 多模态 | 拍照 → 系统 PhotoPicker → ≤512px Base64 → 豆包 Vision 抽关键词 → 走文本 RAG 路径 |
| 多轮 | 最近 3 条用户消息合并作检索 query，约束累计 + 显式"算了"才覆盖 |
| 评测 | 42 条端到端测试集（30 单轮 + 12 多轮），自动跑分输出 markdown，**当前通过率 100%（42/42）** |
| 抗幻觉 | 系统提示规则 + 检索结果硬约束 + `[[PRODUCT:xxx]]` 标签强校验 |

---

## 🏗 架构图

```
┌────────────────────────────┐         ┌─────────────────────────────────────┐
│   Android (Compose)        │  SSE    │   FastAPI                           │
│                            │ ──────► │                                     │
│  ChatScreen / ChatViewModel│         │  /chat/stream            (文本)     │
│  ImageUtils (压缩+B64)     │         │  /chat/stream/multimodal (图片)     │
│  TypewriterPacer (25ms/字) │         │  /products/{id}                     │
│  ProductCard (按 [[id]] 渲染)         │  /health                            │
└────────────────────────────┘         └────────────┬────────────────────────┘
            ▲                                       │
            │ retrieved / token / vision            │ ① 检索
            │                                       ▼
            │                        ┌──────────────────────────────┐
            │                        │  Retriever (Chroma)          │
            │                        │  ↑ 1200+ chunks @ 768d BGE   │
            │                        └──────────────┬───────────────┘
            │                                       │ ② 拼系统提示
            │                                       ▼
            │                        ┌──────────────────────────────┐
            │                        │  Doubao-Seed-2.0-lite (文本) │
            │                        │  Doubao-1.5-Vision-Pro (图)  │
            │                        └──────────────────────────────┘
            └──────────────────── 流式逐 token ────────────────────────
```

---

## 📁 目录结构

```
ecommerce-agent/
├── client/                Android 原生客户端（Kotlin + Jetpack Compose）
│   └── app/src/main/java/com/example/shopguide/
│       ├── ChatScreen.kt       UI（消息列表 / 输入栏 / 商品卡片 / 缩略图）
│       ├── ChatViewModel.kt    StateFlow + 打字机 pacer + 流式管线
│       ├── ChatRepository.kt   OkHttp-SSE 客户端，文本流 / 多模态流
│       ├── ImageUtils.kt       图片压缩 + Base64 编解码
│       └── Models.kt           Wire / UI 双层数据模型
│
├── server/                后端服务（FastAPI + Chroma）
│   ├── app/
│   │   ├── main.py             FastAPI 路由 + SSE 生成器 + 系统提示
│   │   ├── llm_client.py       豆包文本 client + 视觉 client（双 Key）
│   │   ├── retriever.py        Chroma 向量检索 + 商品聚合
│   │   ├── embedder.py         BGE-base-zh-v1.5 本地嵌入
│   │   ├── vector_store.py     Chroma 持久化客户端
│   │   └── config.py           dotenv + Pydantic Settings
│   ├── scripts/
│   │   ├── normalize_products.py  数据规整：raw/*.json → products.jsonl + chunks.jsonl
│   │   ├── build_index.py         向量化 + 写入 Chroma
│   │   ├── test_retrieval.py      检索质量人工抽样
│   │   └── test_vision.py         单帧视觉 endpoint 联通性验证
│   ├── chroma_db/             向量库持久化目录（首次构建后产生）
│   └── requirements.txt
│
├── data/
│   ├── raw/                   原始数据集（解压后的 ecommerce_agent_dataset/）
│   ├── products.jsonl         规整后的商品（100 条）
│   └── chunks.jsonl           1200+ 条 chunk
│
├── eval/
│   ├── eval_set.jsonl         30 条单轮（直推 / 价格 / 否定 / 多约束 / 拒答 / 反问）
│   ├── eval_multiturn.jsonl   12 条多轮（约束累计 / 意图覆盖 / 品牌限定 / 切品类不污染 / 显式撤回）
│   ├── run_eval.py            自动跑分 + 输出 markdown 报告
│   └── results/               每跑一次生成 run-<时间戳>.{md,jsonl}
│
└── docs/
    └── conversation_log.md    全流程开发对话记录（决策、坑、修复）
```

---

## 🚀 快速开始

### 0. 前置依赖

- macOS / Linux（Windows 路径里有中文需注意编码）
- Python 3.10+
- Android Studio（带 JBR）+ Android SDK，真机或模拟器 API 26+
- 火山方舟账号 + 至少一个豆包文本模型 endpoint（视觉模型可选）

### 1. 克隆 & 进目录

```bash
git clone <repo>
cd ecommerce-agent
```

### 2. 后端

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 配置环境变量
cp .env.example .env       # 若无 example 则手动新建
# 编辑 .env，至少填：
#   ARK_API_KEY=ark-xxxxxxxx     # 文本模型对应账号的 Key
#   ARK_CHAT_MODEL=ep-xxxxxxxx   # 豆包文本 endpoint
#   ARK_VISION_MODEL=ep-xxxxxxxx # （可选）豆包视觉 endpoint
#   ARK_VISION_API_KEY=ark-yyyy  # （可选）若视觉 endpoint 在另一个账号下

# 数据规整 + 向量库构建（首次必须，约 1-2 分钟）
python scripts/normalize_products.py
python scripts/build_index.py

# 启动服务（默认 0.0.0.0:8000）
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

启动后健康检查：

```bash
curl http://127.0.0.1:8000/health
# {"status":"ok"}
```

### 3. 客户端

打开 `client/` 目录到 Android Studio，或命令行：

```bash
cd client
# 修改 app/src/main/java/com/example/shopguide/Config.kt 里 BASE_URL 为后端可达地址
# - 真机：填电脑局域网 IP，如 http://192.168.1.10:8000
# - 模拟器：http://10.0.2.2:8000

# 构建 Debug APK
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug

# 安装到已连接的设备
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

打开 APP 应能看到欢迎语；输入"推荐一款适合油皮的洗面奶"等示例 query 验证文本流；点输入栏左侧图标选商品图验证多模态。

---

## 🔌 后端接口

| 路径 | 方法 | 说明 |
| --- | --- | --- |
| `/health` | GET | 健康检查 |
| `/chat` | POST | 非流式 chat，调试用 |
| `/chat/stream` | POST | 文本 SSE 流 |
| `/chat/stream/multimodal` | POST | 多模态 SSE 流（先吐 vision，再走 RAG） |
| `/products/{product_id}` | GET | 商品详情，客户端卡片渲染用 |
| `/static/...` | GET | 商品图静态资源 |

SSE 事件类型见 `server/app/main.py` 顶部注释。

---

## 🧪 评测复现

后端跑起来后：

```bash
cd eval
python run_eval.py
```

输出：

```
============================================================
总体指标
============================================================
  pass_rate: 100.0
  n_total: 36
  n_passed: 36
  retrieval_any_hit_rate: 95.7
  ...
报告: eval/results/run-<timestamp>.md
原始: eval/results/run-<timestamp>.jsonl
```

评测维度：

- **检索 any-hit @5**：top-5 中是否包含 gold_id
- **商品标签覆盖**：模型是否在文本里输出了对应 `[[PRODUCT:xxx]]`
- **约束合规**：价格上限 / 品牌 in / 品牌 not_in / 类目匹配（价格按**最低 SKU 价**算）
- **拒答正确**：稀缺品类应识别为"暂无完全匹配"而不是硬推
- **反问正确**：仅给品类（如"想买双鞋"）应反问场景而不是盲推

---

## ⚙ 关键技术决策

| 项 | 选型 | 理由 |
| --- | --- | --- |
| 客户端 | Android (Kotlin + Compose, minSdk 26) | 真机演示，匹配评审"原生"硬要求；用户主力机鸿蒙 4.2 兼容 APK |
| 后端 | FastAPI + sse-starlette | Python 异步生态成熟，SSE 比 WebSocket 在单向流式场景更轻 |
| 向量库 | Chroma 本地 PersistentClient | 100 条数据零运维；线上换 Milvus/Qdrant 仅需改 `vector_store.py` |
| Embedding | BAAI/bge-base-zh-v1.5（768d，本地） | 中文电商场景比 Doubao-embedding 更稳；离线推理省外部调用 |
| LLM | Doubao-Seed-2.0-lite（文本）+ Doubao-1.5-Vision-Pro（视觉） | 火山方舟一站式，账号隔离用双 Key 解 |
| 流式协议 | SSE + 自定义事件类型 | OkHttp-EventSource 接入零成本；多事件类型方便 UI 区分阶段 |
| 打字机 | 客户端 25ms/字 pacer | 豆包推理完会一次性吐 token，客户端补节奏才有"流畅打字"观感 |

---

## 🧱 RAG chunk 设计（答辩要点）

商品不同信息块召回价值不同，分别切分以保证特定查询直命中：

- `meta_chunk`：标题 / 品牌 / 类目 / 价格 / 规格 一条 —— **价格 / 品牌**类查询直接命中
- `marketing_chunk`：营销描述按句切（约 150 字一段）—— 卖点、成分、人群匹配
- `faq_chunk`：每个 Q&A 一条 —— 专业问题精确命中
- `review_chunk`：每条评价一条 —— "真实使用感"类提问

每个 chunk 都带 `product_id`，检索后**先聚合回商品**再 top-K，避免同一商品的多 chunk 占满候选位。

---

## 🔐 安全提示

- `server/.env` **含 API Key，禁止提交远端**。仓库 `.gitignore` 已排除。
- 提交前运行 `git status` 确认 `.env` 不在变更列表里。
- 演示视频 / 截图前确认终端 / 日志没把 Key 打出来。
- 公司账号 Key 与个人账号 Key 双 Key 隔离，分别走 `ARK_API_KEY` 和 `ARK_VISION_API_KEY`，避免 endpoint 跨账号 403。

---

## 📚 进一步阅读

- 全流程开发记录（含每个坑的修法）：`docs/conversation_log.md`
- 客户端开发笔记：`client/README.md`
- 评测最新报告：`eval/results/run-*.md`
