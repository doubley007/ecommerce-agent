# 智能导购 Android 客户端

## 启动步骤

### 1. 后端先跑起来
```bash
cd ../server
source .venv/bin/activate
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
注意：必须 `--host 0.0.0.0`，否则手机连不到。

### 2. 确认 Mac 局域网 IP 与 client/app/src/main/java/com/example/shopguide/Config.kt 里一致
当前已配置：`http://192.168.1.42:8000`

如果你换了网络，重新查 IP：
```bash
ipconfig getifaddr en0
```
然后改 `Config.kt` 里的 `BASE_URL`。

### 3. 用 Android Studio 打开本目录
- File → Open → 选 `client/`
- 等待 Gradle Sync 完成（首次会下载 ~500MB 依赖）
- 顶部 device 下拉选择 **HUAWEI ELS-AN00**（你的 P40 Pro）
- 点绿色三角运行

### 4. 真机调试
- 第一次安装时手机会弹"是否允许安装来自这个来源的应用"，允许即可
- 后续改代码点运行就直接增量装上

## 工程结构

```
app/src/main/java/com/example/shopguide/
├── MainActivity.kt          App 入口
├── ChatScreen.kt            主屏幕 Compose UI
├── ChatViewModel.kt         状态管理 + 流式更新
├── ChatRepository.kt        网络层（SSE + 商品详情）
├── Models.kt                数据模型
└── Config.kt                后端地址配置
```

## 答辩可讲的工程亮点

- **流式打字**：每个 token 触发 LazyColumn 局部重组，逐字渲染
- **检索元数据前置**：流首事件 `retrieved` 让用户秒看到"正在浏览这些商品"，比纯流式更有掌控感
- **商品卡片懒加载**：检索后预加载详情进缓存，模型在文本里输出 `[[PRODUCT:xxx]]` 时直接命中本地缓存即时渲染
- **状态机**：`isStreaming` 区分进行中/完成/错误三态，UI 一目了然
