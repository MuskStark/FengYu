# AI 配置功能设计

**日期**: 2026-07-10
**分支**: 4.0.0-ZhiFlow
**状态**: 已确认，待实现

## 背景与问题

SwissKitJ 的 AI 功能（AI Chat、AI Agent）目前实际不可用，根因是缺少 AI 配置入口：

- 后端 `AiConfigService` 已能从 `app_setting` 表读取 18 个 AI 配置项（mode、各 provider 的 endpoint/api_key/model、采样参数、ollama 等），全部带默认值。
- 但**没有任何写入这些 provider 密钥的入口**——`AiConfigServiceHeadless` 仅有 temperature/top_p/max_tokens/system_prompt 的 setter，缺少 `ai.mode`、各 provider 的 endpoint/key/model、ollama 的 setter。
- `SettingsController`（`/api/settings`）只暴露 theme/language/sidebarCollapsed，**没有 AI 配置的 REST 端点**。
- 默认 mode 是 `local`（Ollama），所有云端密钥默认空。不手动往 `app_setting` 插数据，AI 永远是本地模式 / 密钥为空 → AI 不可用。
- 前端完全没有 AI 配置 UI：`Settings.vue` 仅有主题/语言两个 select；`AiAgent.vue` / `AiChat.vue` 假设 AI 已配好，调用时报 "AI backend not configured or not ready"。

## 目标

让用户在 `/settings` 页配置 AI provider/密钥/模型/采样参数，保存即热切换生效，并可测试连接。

## 需求决策（已与用户确认）

| 决策点 | 选择 |
|--------|------|
| Provider 范围 | 全部 4 种：OpenAI / Anthropic / DeepSeek / Ollama(本地) |
| 生效方式 | 热切换（保存即生效，无需重启） |
| UI 入口 | 并入 `/settings` 页 |
| 页面布局 | 单页顺序排列（外观区 + AI 配置区） |
| API key 回显 | 返回掩码（`前4***后4`），可显示明文 |
| 连接测试 | 提供测试按钮，保存前可验证 |
| 热切换实现 | 重建 backend + `AiModeService.switchMode()`（方案 A′，不碰 Spring bean） |

### 方案选型说明

用户初选方案 B（"刷新 ChatModel bean + 重跑初始化器"），但代码核查发现该方案无法实现字面承诺：`SpringAiCloudBackend` 在构造时就把 `ChatModel` 引用存进 `final` 字段（`SpringAiCloudBackend.java:70,142`），chat 时用缓存引用、从不重新解析 bean（`streamAndCollect` 在 line 275 直接用 `chatModel.stream()`）。即便运行时替换 `openAiChatModel` bean，已激活的 backend 不感知变化。因此热切换最终都必须走"重建 backend 对象 → switchMode"——纯 bean 刷新是无效中间步骤。经确认采用方案 A′：直接重建 backend + switchMode，不碰 Spring bean，代码最干净。

## 架构与数据流

### 后端改动（4 处）

1. **`AiConfigServiceHeadless`**（`ai/service/AiConfigServiceHeadless.java`）——补齐缺失的 setter：`setAiMode`、各 provider 的 endpoint/key/model setter、`setAiOllamaBaseUrl`、`setAiOllamaModel`。写入逻辑复用现有 `writeSetting(key, value)`，沿用既有 key 常量。

2. **新增 `AiConfigController`**（`web/controller/AiConfigController.java`，`/api/ai/config`）——仿 `SettingsController` 模式：
   - `GET /api/ai/config` → 返回配置快照，API key 用掩码。
   - `PUT /api/ai/config` → 接收部分 JSON，写入 DB，然后热切换。
   - `POST /api/ai/config/test` → 发探针请求，返回连通性结果。

3. **新增 `BackendReactivator`**（`@Component`，`ai/spring/BackendReactivator.java`）——封装"按当前 mode 重建 backend 并 switchMode"。`AiBackendInitializer` 启动逻辑和 controller 热切换逻辑都调它，消除重复。

4. **新增 `ConnectionTester`**（`ai/service/ConnectionTester.java`）——把 `SpringAiCloudBackend.testOpenAi/testAnthropic` 的探针逻辑提取为独立工具，新增 Ollama 探针。backend 和 controller 都调它，避免测试依赖 backend 状态。

### 前端改动

5. `api/types.ts` 新增 `AiSettings` 等接口。
6. `api/client.ts` 新增 `getAiSettings` / `putAiSettings` / `testAiConnection`。
7. `stores/settings.ts` 扩展：新增 `aiSettings` ref + `loadAi` / `updateAi` / `testAi` actions。
8. `views/Settings.vue` 扩展为单页顺序排列，外观区下方加 AI 配置区。
9. `i18n/en.json` + `zh.json` 新增 `aiSettings` 命名空间。

### 数据流（保存为例）

```
Settings.vue 点保存
  → settingsStore.updateAi(partial)
    → api.putAiSettings(partial)   // PUT /api/ai/config
      → AiConfigController.put()
        → AiConfigServiceHeadless.setXxx()  // 写 app_setting 表
        → backendReactivator.reactivate()   // 重建 backend + switchMode + 注入工具
      → 返回掩码后的最新快照
    ← store.applyAi(snapshot)
```

## 配置项与 REST 契约

### 配置项全集（`app_setting` 表 key）

全部复用 `AiConfigService` 既有 key 常量，不新增 key。setter 补齐到 `AiConfigServiceHeadless`：

| key | 默认值 | 说明 |
|-----|--------|------|
| `ai.mode` | `local` | `local`/`openai`/`anthropic`/`deepseek` |
| `ai.openai.endpoint` | `https://api.openai.com` | |
| `ai.openai.api_key` | `""` | 写入明文，读出掩码 |
| `ai.openai.model` | `gpt-4o` | |
| `ai.anthropic.endpoint` | `https://api.anthropic.com` | |
| `ai.anthropic.api_key` | `""` | |
| `ai.anthropic.model` | `claude-sonnet-4-20250514` | |
| `ai.deepseek.endpoint` | `https://api.deepseek.com` | |
| `ai.deepseek.api_key` | `""` | |
| `ai.deepseek.model` | `deepseek-chat` | |
| `ai.ollama.base_url` | `http://localhost:11434` | |
| `ai.ollama.model` | `qwen3:4b` | |
| `ai.temperature` | `0.7` | |
| `ai.top_p` | `0.9` | |
| `ai.max_tokens` | `2048` | |
| `ai.system_prompt` | `You are a helpful assistant.` | |

`ai.local.backend` / `ai.model.path` 暂不进 UI（本地模式固定走 Ollama），保留代码不动。

### `GET /api/ai/config` 响应

```json
{
  "mode": "openai",
  "openai":    { "endpoint": "https://api.openai.com", "apiKey": "sk-***abcd", "apiKeySet": true, "model": "gpt-4o" },
  "anthropic": { "endpoint": "...", "apiKey": "sk-ant-***wxyz", "apiKeySet": true, "model": "..." },
  "deepseek":  { "endpoint": "...", "apiKey": "", "apiKeySet": false, "model": "..." },
  "ollama":    { "baseUrl": "http://localhost:11434", "model": "qwen3:4b" },
  "temperature": 0.7,
  "topP": 0.9,
  "maxTokens": 2048,
  "systemPrompt": "You are a helpful assistant.",
  "activeMode": "openai",
  "ready": true
}
```

**掩码规则**：key 为空 → `"apiKey": ""`、`"apiKeySet": false`；非空 → `"apiKey": "<前4位>***<后4位>"`、`"apiKeySet": true`。掩码在 controller 层做（`maskKey()` 私有方法），DB 里始终存明文。

`activeMode` 来自 `AiModeService.getCurrentMode()`，`ready` 来自 `AiModeService.getService().isReady()`。

### `PUT /api/ai/config` 请求（部分更新）

```json
{
  "mode": "anthropic",
  "anthropic": { "endpoint": "...", "apiKey": "sk-ant-newkey", "model": "..." },
  "ollama": { "baseUrl": "http://localhost:11434", "model": "qwen3:4b" },
  "temperature": 0.5
}
```

**规则**：
- 只更新顶层出现的字段（仿 `SettingsController` 的 `instanceof` 模式）。
- `apiKey` 字段特殊处理：前端发送的值就是要保存的值。掩码占位符（含 `***`）视为"未修改"→跳过；只有用户输入了新 key（不含 `***`）才写库。
- `ollama.baseUrl` → `setAiOllamaBaseUrl`，`ollama.model` → `setAiOllamaModel`。
- 写完后触发热切换，返回与 GET 相同的掩码快照。

### `POST /api/ai/config/test`

请求形状是 mode 驱动的，两种形态：

```json
// 云端模式 (openai / anthropic / deepseek)
{ "mode": "openai", "endpoint": "https://api.openai.com", "apiKey": "sk-xxx", "model": "gpt-4o" }

// local 模式 (Ollama)
{ "mode": "local", "baseUrl": "http://localhost:11434", "model": "qwen3:4b" }
```

**探针逻辑**：
- 云端（openai/anthropic/deepseek）：用 `java.net.http.HttpClient` 原始 HTTP 探针。openai/deepseek 发 `POST {endpoint}/v1/chat/completions`（`max_tokens=5`，"Hi"）；anthropic 发 `POST {endpoint}/v1/messages`（`x-api-key` + `anthropic-version` 头）。200 成功，否则返回错误。
- local（Ollama）：`GET {baseUrl}/api/tags`，返回 200 即连通。解析 `models[].name`，校验请求的 model 是否已拉取，未拉取时返回 `success:true` + `warning`（如 `"model qwen3:4b not found locally; run: ollama pull qwen3:4b"`）。

**响应**：

```json
{ "success": true }
{ "success": false, "error": "HTTP 401: ..." }
{ "success": true, "warning": "model 'qwen3:4b' not found locally; run: ollama pull qwen3:4b" }
```

**缺失字段回落**：任何字段在请求体里没给，回落到 DB 已存值再测。支持"先保存再测试"和"填完直接测试"两种流程。

**探针实现**：不构造完整 ChatModel，直接用 `java.net.http.HttpClient`（与现有 `testOpenAi`/`testAnthropic` 一致）。逻辑提取到 `ConnectionTester` 工具类——**它是接受参数的纯静态方法**（`testCloud(mode, endpoint, apiKey, model)` / `testOllama(baseUrl, model)`），不依赖任何 backend 实例状态。`AiConfigController.test()` 传请求体参数调它；`SpringAiCloudBackend.testConnection()` 也改为委托给它（传自身字段）。Ollama 探针复用 `OllamaLocalBackend.probeReachable()` 已有的 `GET {base}/api/tags` 逻辑，提取进 `ConnectionTester` 后 backend 原方法保留委托或删除。

## 前端 UI 布局与状态管理

### `Settings.vue` 单页顺序排列

沿用现有 `.settings-page`（max-width 520px）风格，外观区下方追加 AI 配置区。顶部一个 status 横幅提示当前后端就绪状态。

```
设置页 (max-width: 520px)
┌─────────────────────────────────────────────┐
│ 设置                                         │
│                                             │
│ ── 外观 ────────────────────────────────── │
│   主题            [ Dark ▾ ]                │
│   语言            [ English ▾ ]             │
│                                             │
│ ── AI 配置 ─────────────────────────────── │
│   状态指示        ● openai 就绪             │
│   模型来源        [ OpenAI ▾ ]              │
│                                             │
│   (随 mode 切换的条件区块)                  │
│   mode=openai/anthropic/deepseek 时:        │
│     Endpoint       [ https://api.openai... ]│
│     API Key        [ ••••••  ] [👁 显示][测试]│
│     Model          [ gpt-4o          ▾ ]    │
│   mode=local 时:                            │
│     Ollama 地址    [ http://localhost:11434]│
│     模型           [ qwen3:4b          ▾ ]  │
│     [测试]                                  │
│                                             │
│   ── 采样参数 ─────────────────────────── │
│   Temperature     [ 0.7 ]                  │
│   Top-P           [ 0.9 ]                  │
│   Max Tokens      [ 2048 ]                 │
│   系统提示词      [┌─────────────┐]        │
│                   [│ You are...   │]        │
│                   [└─────────────┘]        │
│                                             │
│              [ 保存 AI 配置 ]               │
└─────────────────────────────────────────────┘
```

**关键交互**：
- **模型来源 select**：`local`/`openai`/`anthropic`/`deepseek` 四项。切换立即更新条件区块显示哪组字段，但不同步保存——等用户点"保存"才 PUT。
- **API Key 输入框**：`type="password"` 默认掩码显示后端返回的掩码串（`sk-***abcd`）。右侧一个 👁 toggle 切 `type="text"`。placeholder 显示 `apiKeySet` 状态（已设置 → 掩码串 + "已设置，留空不修改"；未设置 → 空框）。用户输入新值才会覆盖。
- **测试按钮**：紧贴对应 provider 区块。点击 → `POST /api/ai/config/test`，下方显示成功（绿）/失败（红+错误信息）。测试用当前表单值（不要求先保存）。
- **保存按钮**：调 `updateAi`。保存后用返回的掩码快照刷新表单。保存触发后端热切换。
- **status 指示**：`GET /api/ai/config` 返回的 `activeMode` + `ready`。

### `api/types.ts` 新增类型

```ts
export type AiMode = 'local' | 'openai' | 'anthropic' | 'deepseek'

export interface AiProviderConfig {
  endpoint: string
  apiKey: string       // 掩码或空
  apiKeySet: boolean
  model: string
}

export interface AiSettings {
  mode: AiMode
  openai: AiProviderConfig
  anthropic: AiProviderConfig
  deepseek: AiProviderConfig
  ollama: { baseUrl: string; model: string }
  temperature: number
  topP: number
  maxTokens: number
  systemPrompt: string
  activeMode: AiMode
  ready: boolean
}

export interface AiConfigTestRequest {
  mode: AiMode
  endpoint?: string
  apiKey?: string
  model?: string
  baseUrl?: string
}

export interface AiConfigTestResult {
  success: boolean
  error?: string
  warning?: string
}
```

### `api/client.ts` 新增

```ts
getAiSettings: () => http.get<AiSettings>('/api/ai/config').then(r => r.data),
putAiSettings: (partial) => http.put<AiSettings>('/api/ai/config', partial).then(r => r.data),
testAiConnection: (req: AiConfigTestRequest) => http.post<AiConfigTestResult>('/api/ai/config/test', req).then(r => r.data),
```

### `stores/settings.ts` 扩展

在现有 store 里追加 AI 状态与 actions（不新建 store，保持设置集中）：

```ts
const aiSettings = ref<AiSettings | null>(null)
const aiLoaded = ref(false)

async function loadAi() {
  aiSettings.value = await api.getAiSettings()
  aiLoaded.value = true
}
async function updateAi(partial: Partial<AiSettings>) {
  aiSettings.value = await api.putAiSettings(partial)  // 返回掩码快照
}
async function testAi(req: AiConfigTestRequest) {
  return await api.testAiConnection(req)   // 返回结果，由组件展示
}
```

`Settings.vue` 的 `onMounted` 里并行 `load()` + `loadAi()`。

### i18n 新增 `aiSettings` 命名空间

两个 json 文件结构一致：

```json
"aiSettings": {
  "sectionTitle": "AI 配置",
  "status": "状态",
  "ready": "就绪",
  "notReady": "未就绪",
  "mode": "模型来源",
  "endpoint": "Endpoint",
  "apiKey": "API Key",
  "apiKeySet": "已设置，留空不修改",
  "showKey": "显示",
  "hideKey": "隐藏",
  "model": "模型",
  "ollamaUrl": "Ollama 地址",
  "temperature": "Temperature",
  "topP": "Top-P",
  "maxTokens": "Max Tokens",
  "systemPrompt": "系统提示词",
  "test": "测试连接",
  "testing": "测试中…",
  "testSuccess": "连接成功",
  "save": "保存 AI 配置",
  "saved": "已保存",
  "modeLocal": "本地 (Ollama)",
  "modeOpenai": "OpenAI",
  "modeAnthropic": "Anthropic",
  "modeDeepseek": "DeepSeek"
}
```

zh.json 用中文，en.json 用英文。

## 热切换执行器 `BackendReactivator`

### 职责与定位

```
BackendReactivator (@Component, ai/spring/BackendReactivator.java)
  依赖: AiModeService, ToolCallback[] (aiToolCallbacks bean), AiConfigService
  公开方法: reactivate()   // 读最新 DB 配置，重建 backend，switchMode
```

**单一职责**：根据当前 DB 里的 `ai.mode` 重建对应 backend 并切换。不负责写入 DB（写入是 `AiConfigController` 调 `AiConfigServiceHeadless.setXxx` 完成的，写完再调 `reactivate()`）。

### `reactivate()` 逻辑

```java
@Component
public class BackendReactivator {

    private final AiModeService aiMode;
    private final ToolCallback[] toolCallbacks;
    private final AiConfigService aiConfigService;

    // 构造注入 aiToolCallbacks（同 AiBackendInitializer 现有写法）

    public void reactivate() {
        String mode = aiConfigService.getAiMode();
        switch (mode) {
            case "openai" -> activate(SpringAiCloudBackend.openAi(
                aiConfigService.getAiOpenAiEndpoint(),
                aiConfigService.getAiOpenAiApiKey(),
                aiConfigService.getAiOpenAiModel()), mode);
            case "anthropic" -> activate(SpringAiCloudBackend.anthropic(
                aiConfigService.getAiAnthropicEndpoint(),
                aiConfigService.getAiAnthropicApiKey(),
                aiConfigService.getAiAnthropicModel()), mode);
            case "deepseek" -> activate(SpringAiCloudBackend.deepSeek(
                aiConfigService.getAiDeepSeekEndpoint(),
                aiConfigService.getAiDeepSeekApiKey(),
                aiConfigService.getAiDeepSeekModel()), mode);
            default -> activateLocal(mode);   // local → Ollama，延迟加载
        }
    }

    private void activate(SpringAiCloudBackend backend, String mode) {
        backend.setToolCallbacks(Arrays.asList(toolCallbacks));
        aiMode.switchMode(mode, backend);
    }

    private void activateLocal(String mode) {
        // OllamaLocalBackend 无参构造：内部自己 AiConfigService.getAiOllamaModel() 读配置。
        // ChatModel bean 在 loadModel() 时才解析（延迟），所以这里构造出来的 backend
        // isReady()==false，直到 AiController.chat 路径触发 loadModel。
        // 注入工具回调（loadModel 也会自己解析一次 bean，这里注入是双保险 + 语义一致）。
        OllamaLocalBackend backend = new OllamaLocalBackend();
        backend.setToolCallbacks(Arrays.asList(toolCallbacks));
        aiMode.switchMode("local", backend);
    }
}
```

`activate(...)` 直接照搬 `AiBackendInitializer.activate()`（现有 line 79-84），无逻辑改动。

**local 模式的 loadModel 触发时机（需改 `AiController`）**：`OllamaLocalBackend.isReady()` 要求 `chatModel != null`（line 135），而 `chatModel` 只在 `loadModel()` 里赋值。当前 `AiBackendInitializer` 对 local 模式是 deferred（不构造 backend），且 `AiController.stream`（line 76）在 `isReady()==false` 时直接报错 "AI backend not configured or not ready"、**不会触发 loadModel**——这意味着当前 local 模式实际上从未被接通（首用即报错）。

本设计改为 `activateLocal` 主动构造 backend + switchMode，此时 `isReady()==false`（chatModel 未解析）。要让 local chat 能跑，需在 `AiController.stream` 的 ready 检查处补一段：当 backend 是 `OllamaLocalBackend` 且 `!isReady()` 时，先调 `backend.loadModel(null)` 触发延迟解析，再重新检查 `isReady()`。解析成功→chat；失败（如 ollama serve 没跑）→报 "Ollama backend not ready"。这样 `ready` 横幅也正确反映状态变化。

> 注：这是修一个既有缺陷（local 模式从未接通），不是新引入的复杂度。云端 backend 不受影响——它们在构造时已解析 chatModel，`isReady()` 直接可用。

### `AiBackendInitializer` 改造

`AiBackendInitializer` 从"自己执行激活逻辑"瘦身为"调 `BackendReactivator.reactivate()`"：

```java
@Override
public void run(ApplicationArguments args) {
    log.info("AI backend initializing on startup...");
    backendReactivator.reactivate();
}
```

启动路径和热切换路径走同一段代码，删除 `AiBackendInitializer` 里重复的 switch/activate 逻辑。

### `AiConfigController.put()` 的热切换集成

```java
@PutMapping
public Map<String, Object> put(@RequestBody Map<String, Object> body) {
    // 1. 写入各字段（仿 SettingsController 的 instanceof 模式）
    if (body.get("mode") instanceof String m) headless.setAiMode(m);
    applyProvider(body, "openai",  headless::setAiOpenAiEndpoint, ...);
    applyProvider(body, "anthropic", ...);
    applyProvider(body, "deepseek", ...);
    applyOllama(body);
    applySampling(body);

    // 2. 热切换（写入已落库，reactivate 读最新值重建 backend）
    backendReactivator.reactivate();

    // 3. 返回掩码快照（含 activeMode/ready）
    return snapshot();
}
```

热切换放在写入之后：保证 `reactivate()` 读到的是刚写入的最新配置。

### 并发与失败处理

**并发**：`AiModeService.switchMode` 已是 `synchronized`，切换是原子的。`reactivate()` 无需额外加锁。若用户在 chat 进行中切换 backend——`SpringAiCloudBackend.chat` 用 `generating` CAS 保证单次生成互斥（line 193），旧 backend 的在途请求会跑完，新请求走新 backend。不主动打断在途生成（与现有 `cancelGeneration` 注释一致："mid-stream abort not wired in Phase 1"）。

**失败处理**——`reactivate()` 内部容错：
- 云端 backend 构造时 endpoint/key/model 任一为空 → `SpringAiCloudBackend.resolveModel` 已返回 `null`（line 116-120），backend 仍注册但 `isReady()=false`，chat 时抛 "not configured"。不抛异常打断 PUT。
- 构造异常 → `resolveModel` 已 catch 并返回 `null`（line 123-126）。不抛异常打断 PUT。
- 所以"保存不完整的配置"是合法状态：写入成功，switchMode 切到一个 `isReady()=false` 的 backend，UI 通过 `ready: false` 横幅提示用户。

PUT 始终返回 200 + 快照；热切换的失败软化为 `ready: false`，不向用户报错。用户通过测试按钮获得明确的连通性反馈。

## 测试策略

### 后端测试

**`AiConfigControllerTest`（新增）**：
- `GET` 掩码正确性：key 为空 → `apiKey=""`, `apiKeySet=false`；key 非空 → `apiKey="sk-1***abcd"` 形态，`apiKeySet=true`。
- `PUT` 部分更新：只传 `mode` → 仅 `ai.mode` 落库，其他 key 不变。
- `PUT` 的 apiKey 占位符跳过：传 `"sk-***abcd"`（含 `***`）→ 不写库（保留旧值）；传 `"sk-newkey"` → 写库。
- `PUT` 触发 `reactivate()`：用 `@MockBean BackendReactivator` 验证 `reactivate()` 被调用一次。
- `PUT` 返回掩码快照 + `activeMode`/`ready`。
- `POST /test` 各 mode：mock 探针返回，验证成功/失败/warning 的响应形状。

**`BackendReactivatorTest`（新增）**：
- `reactivate()` 按 mode 调对应工厂：`mode=openai` → `aiMode.getService()` 是 OPENAI provider 的 backend，`getCurrentMode()=="openai"`。
- 工具回调注入：激活后 backend 的 toolCallbacks 非空。
- 不完整配置：`openai` 但 key 为空 → backend 注册但 `isReady()==false`，不抛异常。
- `local` mode → switchMode 到 local，不抛异常（延迟加载）。

**`AiConfigServiceHeadlessTest`（扩展）**：
- 新增 setter 往返：`setAiMode("openai")` → `AiConfigService.getAiMode()=="openai"`（写读 round-trip，验证 key 常量一致）。
- 各 provider endpoint/key/model setter 同理。

### 前端

现有前端无测试框架，以手动验证清单为主（见下）。

### 测试连接探针边界情况

| 场景 | 期望 |
|------|------|
| 云端 200 | `success:true` |
| 云端 401（key 错） | `success:false, error:"HTTP 401: ..."` |
| 云端 endpoint 不可达 | `success:false, error:"<异常信息>"` |
| DeepSeek | 走 OpenAI 兼容探针（`/v1/chat/completions`），endpoint 用 `https://api.deepseek.com` |
| Ollama 服务通 + 模型已拉取 | `success:true` |
| Ollama 服务通 + 模型未拉取 | `success:true, warning:"model ... not found locally"` |
| Ollama 服务未启动 | `success:false, error:"Connection refused..."` |
| 请求体字段缺失 | 回落到 DB 已存值再测 |

### 手动验收清单

- [ ] 全新启动（DB 无 `ai.*`）→ `GET` 返回全默认值，mode=local，key 全空。
- [ ] 在 UI 选 openai，填 key，保存 → 横幅变"openai 就绪"，AI Chat 能对话。
- [ ] 切到 anthropic 不填 key → 横幅"未就绪"，chat 报 "not configured"。
- [ ] 切回 local(Ollama) → 若 ollama serve 在跑，就绪；否则未就绪。
- [ ] 测试按钮：填正确 key → 绿；填错 key → 红 + 401 信息。
- [ ] API key 默认掩码，点眼睛可看明文；不改 key 直接保存 → 不破坏旧 key。
- [ ] 保存后刷新页面 → 配置持久（从 DB 读回）。
- [ ] 主题/语言设置不受 AI 配置改动影响（两条数据流独立）。

## 不做的事（YAGNI 边界）

- ❌ 不新增 GLM/智谱 provider（需求已确认仅 4 个）。
- ❌ 不做"打断在途生成"——切换时在途请求跑完，与现有 cancelGeneration 语义一致。
- ❌ 不把 `ai.local.backend`/`ai.model.path` 暴露到 UI（本地模式固定走 Ollama）。
- ❌ 不做配置导入/导出、多 profile 切换。
- ❌ 不引入测试框架到前端（沿用项目现状，用手动清单）。
- ❌ 不改 `AppSettingEntity` schema（通用 key-value 表已够用）。

## 涉及文件清单

### 后端新增
- `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/AiConfigController.java`
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/BackendReactivator.java`
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/ConnectionTester.java`

### 后端修改
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/AiConfigServiceHeadless.java`（补 setter）
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiBackendInitializer.java`（瘦身为调 reactivate）
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackend.java`（testConnection 改为调 ConnectionTester）
- `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/AiController.java`（local 模式 chat 前触发 loadModel）
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/OllamaLocalBackend.java`（probeReachable 提取到 ConnectionTester，保留委托）

### 前端修改
- `frontend/src/api/types.ts`（新增 AI 类型）
- `frontend/src/api/client.ts`（新增 AI config API）
- `frontend/src/stores/settings.ts`（扩展 AI 状态与 actions）
- `frontend/src/views/Settings.vue`（追加 AI 配置区）
- `frontend/src/i18n/en.json` + `zh.json`（新增 aiSettings 命名空间）

### 测试新增
- `ZhiFlow/src/test/java/.../web/controller/AiConfigControllerTest.java`
- `ZhiFlow/src/test/java/.../ai/spring/BackendReactivatorTest.java`
- `ZhiFlow/src/test/java/.../ai/service/AiConfigServiceHeadlessTest.java`（新增）
- `ZhiFlow/src/test/java/.../ai/service/ConnectionTesterTest.java`（新增，mock HTTP 探针）
