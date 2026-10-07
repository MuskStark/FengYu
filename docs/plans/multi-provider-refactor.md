# 多供应商层重构（专项）——规划书

创建日期：2026-10-07（规划版 2；本文是**待执行任务书**，执行时逐批追加验证记录）

适用项目：Infinia / FengYu **4.1.0**（全部批次在 4.1.0 一个版本内引入；4.1.0 尚未
发布，当前分支 `release/4.1.0` 带未提交批次），Spring Boot 4.1.x + Spring AI 2.0（BOM）

规划基线源码：`release/4.1.0` 工作区（2026-10-06 读取）。上游参考：earendil-works/pi
（`packages/ai`，commit 浅克隆 2026-10-06）、zai-org/ZCode（`packages/provider` + CLI
adapters，v3.14.3）。两份参考的结论性对比见会话研究记录；本文只引用落地的先例文件。

**版本策略（2026-10-07 用户决策）**：原规划版 1 把批次摊到 4.1.x/4.2.0/4.2.x/4.3，
用户拍板**全部在 4.1.0 引入**。批次概念保留（A→B→C→D 仍是严格的合入顺序与依赖链），
但只作为 4.1.0 内部的 merge 顺序，不再对应外部版本。由此带来的三处实质调整见
§6（商店依赖时点）、§8.1（迁移置空策略）、§10（单版本引入的风险节律）。

## 1. 定位与目标

把「供应商」从代码身份（枚举 + 平铺 DB key + `ai.mode` 单选）重构为**数据身份**
（Provider Registry），同时补齐三个正确性缺口（跨供应商重放、思考控制、凭证治理）。
协议层维持现状：Spring AI 2.0 两个协议族 + Ollama，**不自研 wire protocol**（pi 的
10 适配器路线明确不采纳，理由见 §3-D1）。

**目标**（全部随 4.1.0 交付，括号为合入批次）：

| 目标 | 批次 |
|---|---|
| 新增任意 OpenAI/Anthropic 兼容供应商 = 配置一条数据，零 Java 代码改动 | B |
| 思考控制按模型数据化（目录驱动档位），Ollama 硬编码迁移 | B |
| 会话历史跨供应商重放正确（reasoning/toolCallId/孤儿 tool call/媒体） | A |
| 模型目录可远程热更（复用商店 Ed25519 通道，基线随包） | C |
| 凭证加密存储 + 配置面脱敏；错误事件结构化 | B/D |
| 每模型采样默认值 + 成本统计 | D |

**非目标**（不做，防过度设计）：

- 不自研协议适配器、不引入第四个协议族（国产长尾全部落在两族 + baseUrl）。
- 不建 OAuth 联邦（pi 的 12 家 OAuth 流）。FengYu 当前只有 API key 一种凭证形态；
  `credentialRef` 间接层为将来订阅制留缝，不预建。
- 不上 CEL 表达式解释器（ZCode 的 `model-option-map` 路线）。思考映射先用声明式
  字段表 + 少量硬模板；目录真需要异形映射时再升级。
- 不动 `ToolLoopDriver`（1072 行回合循环，编排层与供应商层正交，是存量最值钱的
  分离）。所有改动收敛在 `Transport` 实现之下与 prompt 组装之前。
- 不做每会话独立模型选择（B 批之后具备可行性，是否做是产品决策，不在本专项内承诺）。

## 2. 现状基线（源码锚点）

| # | 事实 | 锚点 |
|---|---|---|
| S1 | 供应商硬编码：`Provider { OPENAI, ANTHROPIC, DEEPSEEK }` 枚举 + 三家各 3 个平铺 key（`ai.<p>.endpoint/api_key/model`）+ Ollama 2 key + `ai.mode` 单选 | `ai/service/SpringAiCloudBackend.java:56`；`ai/AiConfigService.java:55-72`；`ai/config/AiConfigProperties.java` |
| S2 | 协议两族 + Ollama 已正确分离，static builder 显式值构造（热修复换已就位：直连构造不查 stale bean） | `ai/config/ChatModelConfig.java:125,198,273`；`SpringAiCloudBackend.java:113-153` |
| S3 | 供应商怪癖散在代码：`BaseUrlNormalizer` 按族归一化；Ollama 思考探测缓存 + `contains("gpt-oss")` 硬编码；`endpointRejectsMediaContent` 粘性探测；Spring AI 2.0 双客户端陷阱绕过 | `ChatModelConfig.java:296-332`；`SpringAiCloudBackend.java:93` |
| S4 | 云端从不主动请求 thinking（Anthropic 注释明确不请求；OpenAI 族仅被动接 `reasoningContent` metadata） | `SpringAiCloudBackend.java:47-54` |
| S5 | 跨供应商重放缺口：`reasoningContent` 无条件塞 metadata 重放给任意目标端点；toolCallId 无规范化；孤儿 tool call 无合成结果 | `ai/service/AiMessageBridge.java:52-58` |
| S6 | 模型元数据目录已具雏形：exact id + 有序 family 正则（contextWindow/supportsImage/maxOutputTokens），classpath 内置 | `ai/config/ModelMetadataCatalog.java`；`resources/ai/model-metadata.json` |
| S7 | 采样参数全局一份（temperature/topP/maxTokens 服务所有供应商模型） | `AiConfigService`（`ai.temperature` 等） |
| S8 | API key 平铺存 settings 表（H2）；GET 面以 `providerMap` 直接组装 | `AiConfigController.java:50-113` |
| S9 | 前端配置面：`services/ai-config.ts` + `components/settings/AiProviderSection.tsx`（四供应商固定 UI） | `frontend/src/services/ai-config.ts`；`frontend/src/components/settings/AiProviderSection.tsx` |
| S10 | 重试：自建 policy 2 次/500ms/×2/上限 4s（spring-ai-adoption A2 的既定结论，交互式轮次不用 SDK 默认 10 次） | `SpringAiCloudBackend.java:265-276` |

## 3. 架构决策（决策记录）

| # | 决策 | 理由 | 先例 | 被拒备选 |
|---|---|---|---|---|
| D1 | 协议层维持「外包给 Spring AI」，不自研适配器 | 两协议族已覆盖国产长尾；pi 自研是库约束（tree-shaking/统一流协议卖给集成者），FengYu 不背 | ZCode 三 kind 分派（`model-execution.ts:281`） | pi 10 适配器路线 |
| D2 | 供应商 = 数据（表驱动实例），协议 = 代码（唯一分派点 `Protocol` 枚举） | 新供应商零代码；与两家共识一致 | ZCode `ProviderTemplate` + personal provider；pi provider 工厂 | 继续扩枚举（现状不可扩展） |
| D3 | 怪癖进目录不进代码：思考映射/URL 归一化/能力断言全部挂 `model-metadata.json` 及其扩展 | 新模型不发版；`ModelMetadataCatalog` 的 family 正则结构与 ZCode `modelApiRules` 同构，扩展比重造便宜 | ZCode `zcode-builtin.json`（正则规则 + 数据映射） | pi compat 枚举（每格式改代码发版） |
| D4 | 思考映射先「霰弹默认 + 正则精修」+ 硬模板三选一，不上 CEL | 覆盖 90% 场景 10% 复杂度；未知 OpenAI 兼容端点开箱即用 | ZCode 默认规则一次发全部思考字段的做法 | ZCode CEL 解释器 |
| D5 | 请求前归一化层（`TranscriptNormalizer`），会话中立格式不变 | 换模型续聊/多供应商并存的地基；现有 `AiChatMessage` 已是中立格式，只缺转换 | pi `transform-messages.ts`（四规则） | 在各 backend 内各自处理（重复 + 漏） |
| D6 | 凭证独立表 + AES-256-GCM（机器派生密钥），GET 面只回布尔 + 尾四位 | settings 平铺明文不可接受；为 Registry 多实例做准备 | ZCode `credential-cipher.ts`（`enc:v1:` 方案） | 继续 settings 平铺；接入系统 keychain（桌面跨平台复杂度不成比例，延后评估） |
| D7 | 目录远程化走商店 Ed25519 通道，内置基线随包、失败回退基线 | 商店基建（签名目录校验）是既有资产，强于 ZCode 自建同步器；且**客户端先行发布也安全**（商店资源未就绪 = 检查失败 → 回退基线，商店上架后无需客户端发版即激活） | ZCode remote synchronizer（锁/租约/退避/fail-safe） | 仅随包内置（新模型等发版）；裸 HTTP 拉取（无签名） |
| D8 | 错误合同三枚举（code/failureReason/retryReason）落 SSE 结构化事件 | 前端日志面板（4.1.0 日志整合）与提示吃同一份合同，不再解析 message | ZCode `@zcode/contracts` 错误三元组 | 继续字符串 message |
| D9 | 全部批次并入 4.1.0，批次退化为内部合入顺序（A→B→C→D 依赖链不变） | 用户决策（2026-10-07）；4.1.0 未发布，单版本引入免跨版本兼容矩阵；代价与缓解见 §10 | — | 版本 1 的跨版本摊派 |

## 4. 批次 A —— 正确性（最先合入；B 的地基）

不动 schema、不动 API 形状；一个 `TranscriptNormalizer`（新文件，位于
`ai/service/`）插在 `ToolLoopDriver` 组装 prompt 之后、`AiMessageBridge.toSpringAi`
之前，对 outbound 历史做四规则归一化。

| ID | 任务 | 涉及文件 | 验收 | 依赖 |
|---|---|---|---|---|
| A1 | reasoning 按「同模型才保留」：`(provider, protocol, model)` 与当前目标不一致时丢弃 `reasoningContent`（UI 思考流展示不受影响——归一化只作用于 outbound 拷贝）；一致时保留 metadata 重放（DeepSeek 类端点要求） | 新 `TranscriptNormalizer`；`ToolLoopDriver` 调用点 | 单测：同模型保留/跨模型丢弃/空值透传 3 例；现有 `ToolLoopDriver` 回归全绿 | — |
| A2 | toolCallId 跨协议规范化：非 `[A-Za-z0-9_-]{1,64}` 时截断+替换+冲突去重，回填关联 toolResult 的 ID | 同上 | 单测：OpenAI 超长含 `\|` ID → Anthropic 合法 ID 且 toolResult 关联一致；同协议原样 | — |
| A3 | 孤儿 tool call 合成 `"No result provided"` isError 结果（取消/中断残留）；error/aborted 尾轮 assistant 跳过重放 | 同上 | 单测：孤儿调用补结果；中断轮不重放；正常序列零改写 | — |
| A4 | 媒体降级目录化：目录断言 `supportsImage=false` 时 media → 占位文本（用户消息与 tool 媒体两条路径）；运行时 `endpointRejectsMediaContent` 粘性探测保留为兜底 | `TranscriptNormalizer`；`ModelMetadataCatalog`（只读） | 单测：断言不支持→占位；未知→维持现状（permissive + 探测兜底） | — |

**批次门禁**：`./mvnw -f FengYu/pom.xml test`（后端全量）；`scripts/e2e-smoke.sh`；
`git diff --check`。前端零改动（行为对 UI 透明，思考流展示数据源不变）。

## 5. 批次 B —— 结构（Registry + 思考数据化 + 凭证）

### B1 Provider Registry 数据模型 → B6 凭证治理

| ID | 任务 | 涉及文件 | 验收 | 依赖 |
|---|---|---|---|---|
| B1 | 新表 `ai_provider`（id/display_name/protocol/base_url/headers/credential_ref/default_model_id/builtin/sort）+ `Protocol` 枚举（OPENAI_CHAT/ANTHROPIC_MESSAGES/OLLAMA）；内置四家 seed（openai/anthropic/deepseek/ollama，DeepSeek 从枚举身份降为预设数据） | 新 `ai/provider/` 包（`ProviderDefinition`、`ProviderRegistryService`、repository）；`AiModeService` 改读 active provider | 迁移测试：旧 key 存在→生成 4 实例 + `ai.mode`→active 映射，幂等重跑零副作用 | A |
| B2 | backend 构造收敛：`SpringAiCloudBackend.openAi/anthropic/deepSeek` 三工厂 → 单一 `create(ProviderDefinition, credential)`，内部按 `Protocol` 分派到现有三个 static builder（builder 一行不改）；`BackendReactivator` 改为「配置变更→按 active provider 重建」 | `SpringAiCloudBackend`；`BackendReactivator`；`ChatModelConfig`（不动） | 现有 backend 单测全部保持（构造路径换、断言不换）；三协议 × 未配置/配置两态 6 例 | B1 |
| B3 | REST：`/api/ai/providers`（GET 列表/POST 创建/PUT 更新/DELETE/POST test/PUT activate）；旧 `/api/ai/config` 保留 deprecated 兼容壳（读写代理到 registry，响应加 `deprecated: true` 标记），**删除时点：4.2.0**；`ConnectionTester` 参数化 protocol | `web/controller/AiConfigController`（改造）+ 新 `AiProviderController`；`ConnectionTester` | controller 测试：CRUD/activate/test 各例 + 旧端点兼容断言（GET 形状向后兼容）；loopback 安全断言不变 | B2 |
| B4 | 前端设置页：四宫格 → 供应商列表 + 「添加供应商」（预设模板下拉：内置四家 + 常用预设智谱/Kimi/通义/MiniMax/OpenRouter 的 baseUrl 预填——纯前端预设数据）+ 自定义（选协议族/填 baseUrl）；凭据只显示 `已配置 + 尾四位` | `frontend/src/services/ai-config.ts`（扩展 provider API）；`components/settings/AiProviderSection.tsx` 重写；i18n 文案（en/zh） | `yarn test` 组件测试；手动/浏览器技能过一遍添加-测试-激活流 | B3 |
| B5 | 思考控制数据化：`model-metadata.json` 的 ModelInfo 增 `thinking`（levels + requestFields 声明式映射，支持硬模板 `reasoning_effort`/`enable_thinking`/`anthropic-thinking` 三型 + `${level}` 占位）；`OPENAI_CHAT` 族默认规则 = 霰弹（一次发全字段），具体模型正则精修；OpenAI options 增 extra-body 透传通道；Anthropic options 接 `thinking` 字段；Ollama `contains("gpt-oss")` 迁入目录；设置/每轮可调档位 | `ModelMetadataCatalog`（扩解析）；`ChatModelConfig`（options 通道）；`resources/ai/model-metadata.json`（数据）；`ToolLoopDriver`（档位注入点，最小接触）；前端档位 UI | 目录解析单测（exact/family/默认霰弹三层命中）；端到端：ScriptedChatModel 断言请求体字段按映射生成（三型各 1 例）；Ollama 探测路径回归 | B2 |
| B6 | 凭证治理：新表 `ai_credential`（provider_ref + AES-256-GCM 密文，密钥机器派生或 `FENGYU_CREDENTIAL_SECRET` 覆盖，格式 `enc:v1:<iv>.<tag>.<ct>`）；迁移**即置空**旧 `ai.<p>.api_key`（幂等迁移天然覆盖回滚场景，见 §8.1）；所有 GET 面脱敏（布尔 + 尾四位）；日志/rollout 断言不含明文 key | 新 `ai/provider/CredentialStore`（加密 + 表锁）；`AiConfigService` 读写改道；`AiProviderController` | 加密往返/错误密钥/损坏密文单测；GET 脱敏断言；迁移幂等测试；`grep` 断言响应与日志无明文 | B1（B3 之前合入更顺，但非硬依赖） |

**批次门禁**：后端全量测试 + `scripts/e2e-smoke.sh`（含 fixture 插件路径）+ 前端
`yarn test` + 桌面 E2E `launch.spec.ts`（release 流程内）。

## 6. 批次 C —— 目录远程化（4.1.0 内合入；商店依赖按 D7 处理）

| ID | 任务 | 涉及文件 | 验收 | 依赖 |
|---|---|---|---|---|
| C1 | 目录叠加：远程 revision 化 JSON（结构同内置，加 `revision`/`generatedAt`）经 `StoreClient` Ed25519 校验通道下发；本地缓存（文件，原子写 + 进程锁）；启动 fail-safe：远程不可达 → 基线；合并策略 = 远程条目按 id/正则覆盖基线（新增可追加）。**商店资源未上架时行为 = 永远回退基线**（D7），故客户端可先行合入 4.1.0，不阻塞于商店 | `StoreClient/StoreService`（新目录资源类型消费，类型枚举扩展走商店仓库侧）；`ModelMetadataCatalog`（叠加读取，保持同步读语义） | 单测：签名校验失败拒收/revision 回退不应用/离线用基线/叠加覆盖优先级/**资源不存在→基线不报错**；e2e store-offline 探测路径（缓存 key 按日志整合批次的教训换新） | B5 |
| C2 | 手动刷新 + 设置页入口（「检查模型目录更新」）；自动刷新低频（日级），多进程/多窗口不并发（复用 B6 的锁） | `AiProviderController`（refresh 端点）；前端设置页 | e2e：刷新端点 200 + 幂等 | C1 |

**商店协调（4.1.0 发布窗口内）**：FengYu-Store 仓库需新增 `MODEL_CATALOG` 资源类型
（现仅 PLUGIN/SKILL/MCP）。目标时点 = 4.1.0 发布前上架；即便滞后，客户端侧无感
（fail-safe），商店上架后**无需客户端发版**即激活远程目录。

## 7. 批次 D —— 治理收尾（错误合同 + 成本）

| ID | 任务 | 涉及文件 | 验收 | 依赖 |
|---|---|---|---|---|
| D1 | 错误合同：`AiErrorContract`（code: auth-missing/rate-limited/provider-4xx/network/context-overflow/aborted + failureReason + retryable）；`AiServiceException` 携带合同；SSE 错误事件结构化；前端日志面板/提示改吃合同 | `ai/AiServiceException`；`ToolLoopDriver` 错误出口；`web/controller` SSE；前端 services/日志面板 | 合同映射单测（异常→合同六型）；前端组件测试；message 字符串仅作展示不再承担语义 | B3 |
| D2 | 采样默认值 + 成本：目录 ModelInfo 增 `sampling`（temperature/topP 默认）与 `cost`（input/output/cacheRead/cacheWrite，$/M tokens）；usage 回收后计费（pi `calculateCost` 四则）；会话侧累计成本展示 | `ModelMetadataCatalog`；`ToolLoopDriver` usage 落账；前端会话面板 | 计费单测（含未知成本模型 = 0 且标注未知）；UI 展示 | C1 |

## 8. 数据迁移与 API 兼容策略（单版本内收敛）

1. **迁移即完成，幂等兜底回滚**：4.1.0 升级启动时把 `ai.<p>.endpoint/model/api_key`
   写入 `ai_provider`/`ai_credential`（加密）后**当场置空旧 api_key**；endpoint/model
   旧 key 保留只读到 4.2.0。回滚 4.1.0→4.0.x 的代价 = 用户重填一次 API key（release
   notes 明示）。**幂等迁移天然覆盖回滚再升级**：用户回滚→在 4.0.x 重填 key→再升级
   4.1.0 时迁移重新捡起新值，无需任何特殊分支。
2. **旧 `/api/ai/config`**：GET 形状向后兼容（平铺字段从 registry 投影），PUT 代理
   写入；响应带 `deprecated` 标记；删除时点 4.2.0。前端 B4 与新端点同批切换，兼容层
   只服务第三方/脚本调用者。
3. **`ai.mode` → `ai.provider.active`**：值映射 `openai→builtin:openai` 等；旧 key
   迁移后保留只读（`AiModeService` 读新 key，缺省回读旧 key），删除时点 4.2.0。
4. **目录 JSON**：扩展字段全部可选，旧格式在新代码下行为不变（`thinking`/`sampling`/
   `cost` 缺省 = 现状）；`ModelMetadataCatalog.load` 现有解析就跳过未知字段，降级
   运行 4.0.x 读到 4.1.0 的 JSON 天然兼容。

## 9. 验证门禁总表

| 门禁 | 命令/标准 | 适用 |
|---|---|---|
| 后端模块 | `./mvnw -f FengYu/pom.xml test`（全量，基线 1548+ 全绿——以 4.1.0 当前未提交批次合入后的数字为准） | 每任务 |
| 冒烟 | `scripts/e2e-smoke.sh`（REST/SSE + fixture 插件运行时） | 每批次合入前 |
| 前端 | `cd frontend && yarn test`（注意 macOS vite.close 挂起坑：按汇总行判定退出） | B4/C2/D1/D2 |
| 桌面 E2E | CI `launch.spec.ts`（release 流程门禁；e2e-smoke 不覆盖 Electron 链路——既定教训） | 4.1.0 发行 |
| 文档 | docs-updater：CHANGELOG 4.1.0 条目（`🐛 fix(ai)` A 批 + `✨ feat(ai)` B/C/D 批）+ README 设置章节 + en/zh 镜像 + `sync:changelog` | 4.1.0 发行 |
| 回归红线 | 不改 `ToolLoopDriver` 编排语义（B5 档位注入是最小接触点）；spring-ai-adoption 的边界规则与 A1-A6 采纳项全部保持 | 全程 |

## 10. 风险与回滚（单版本引入版）

| 风险 | 缓解 |
|---|---|
| **四个批次挤一个版本，回归面大** | 批次间硬依赖链 A→B1→B2→B3→B4 /（B5,B6 并行）→C→D，逐批合入逐批全门禁；沿用 4.1.0 既有流程的五区并行审查先例，在 D 批合入后、发行前跑一轮专项审查（覆盖 registry/迁移/归一化/思考通道/凭证五个面） |
| B2 构造收敛引入回归 | builder 零改动（只换调用方）；现有 backend 测试断言不换，先改后跑全量 |
| B5 extra-body 通道被部分网关拒（未知字段 400） | 霰弹默认仅对家族规则命中且目录声明的模型生效；运行时可关（目录条目删除即回落）；沿用 S3 的粘性探测思路：收到 400 后该端点停发 thinking 字段并提示 |
| 迁移破坏用户现有配置 | 幂等迁移 + §8.1 回滚再升级自愈；e2e-smoke 前置「升级场景」（带旧 key 的 H2 启动 → 断言 registry 就绪 + 旧 key 已置空） |
| 凭证密钥派生跨机器不可用（用户拷贝数据目录） | 密钥含机器标识时密文解密失败 → 明确报错引导重填（不静默清空）；文档说明 `FENGYU_CREDENTIAL_SECRET` 固定密钥的场景 |
| C1 商店资源滞后于 4.1.0 发布 | 按 D7 设计：客户端先行安全（检查失败→基线），商店上架后零发版激活；发布 checklist 注明「建议商店先上架，非阻塞」 |
| C1 远程目录污染/错签名 | Ed25519 校验失败拒收（商店既有语义）；revision 单调；本地缓存原子写 |
| 与 4.1.0 既有未提交批次冲突 | 开工前先落定工作区现状（二轮审查 stash 已并入 a407471a 基座的确认 + 当前未提交文件清单），A 批触点（`AiMessageBridge`/`ToolLoopDriver`）与既有未提交改动逐文件比对 |

## 11. 与既有专项的关系

- **spring-ai-adoption**（已执行）：其边界规则（§3 保留自研清单）与 A1-A6 采纳项
  全部保持；本专项 B5 的 thinking 请求通道走 Spring AI options/SDK 透传，不绕开
  连接层结论；A2 重试策略不变（S10）。
- **agent-os-sandbox / agent-code-mode**：无交集（沙箱与代码模式不感知供应商层）。
- **商店架构（FengYu-Store）**：C 批消费端随 4.1.0；`MODEL_CATALOG` 资源类型扩展与
  FengYu-Store 仓库的改动在 4.1.0 发布窗口内协调（§6，非阻塞依赖）。
- **4.1.0 二轮审查批次**（stash 已并入基座的确认）：A 批开工前确认其重放状态，
  避免与 `AiMessageBridge`/`ToolLoopDriver` 触点冲突。

## 12. 里程碑（全部收敛于 4.1.0）

| 里程碑 | 内容 | 时点 |
|---|---|---|
| M1 | 批次 A 合入 + 门禁全绿 | 4.1.0 开发期，最先 |
| M2 | B1-B4（Registry + API + 前端）合入 | M1 后串行 |
| M3 | B5-B6（思考数据化 + 凭证）合入 | 与 M2 的 B3 之后并行 |
| M4 | C1-C2（目录远程化）合入 | M3 后 |
| M5 | D1-D2（错误合同 + 成本）合入 | M4 后 |
| M6 | 专项五区审查 + 全门禁 + docs-updater → **4.1.0 发行** | 终点 |

执行时逐批在本文追加验证记录（沿 `spring-ai-adoption.md` 的「执行版」体例）。

---

## 13. 执行记录（2026-10-07，全部批次并入 4.1.0 工作区）

**验证基线**：开工前全量 1548 tests, 0 failures, BUILD SUCCESS。完工全量
**1597 tests, 0 failures, 0 errors, 5 skipped（既有 Windows-only），BUILD SUCCESS**
（+49 新测试）。前端 typecheck 0 错误 + **127/127**（基线 123 + 4 新）。e2e-smoke
与 git diff --check 见下。

| 批次 | 交付 | 新增测试 | 状态 |
|---|---|---|---|
| A | `ai/service/TranscriptNormalizer`（四规则）+ `AiChatMessage.origin`（8参记录+兼容构造）+ `ToolLoopDriver` 接线（唯一桥接点 `buildSpringAiMessages`）+ `ModelMetadataCatalog.supportsImageExact` | TranscriptNormalizerTest 13 | ✅ 全量 1561 绿 |
| B1 | `ai/provider/`（`Protocol`/`ProviderDefinition`/`ProviderRegistryService`）：JSON-in-settings 存储、读路径零迁移（legacy 派生视图）、首写快照迁移 + 旧 api_key 置空、builtin 镜像回写、activate 双写 | ProviderRegistryServiceTest 10 | ✅ |
| B2 | `SpringAiCloudBackend.create(definition, key)` + label 化（providerLabel/rolloutProvider）；三旧工厂原样保留（CloudSubagentRunner/ExploreSubagentTool 零改动）；`BackendReactivator` registry 优先 + legacy 回退 | —（既有 backend 测试断言不变全过） | ✅ |
| B3 | `AiProviderController`（CRUD/test/activate + C2 刷新端点，GET 永不回 key）；`AiConfigController` 兼容壳（mode→activate 镜像、provider 子映射→registry 同步）；SETUP 模式排除新 controller（SetupApplicationContextTest 钉住） | AiProviderControllerTest 5 | ✅ |
| B4 | `AiRegistrySection`（列表/预设添加[智谱/Kimi/通义/MiniMax/OpenRouter+自定义]/测试/激活/删除 + 思考档位 + 目录刷新）替换四宫格（旧组件删除）；`aiForm` 载荷修剪为生成参数（防陈旧 provider 值经镜像覆盖 registry）；i18n en/zh | aiRegistry.test 4 | ✅ 127/127 |
| B5 | 目录 `thinking:{style,levels,defaultLevel}`（26 存量规则改造 + 3 追加锚定 + 1 exact）；`ThinkingOptions`（8 风格渲染：reasoning_effort/thinking_type/enable_thinking/shotgun/anthropic_enabled/adaptive/ollama_boolean/level）；OpenAI `extraBody`+原生 `reasoningEffort`、Anthropic `thinkingEnabled/Adaptive`、Ollama 硬编码迁出；`ai.thinking.level` 设置 + GET/PUT；**未设置语义**：云=零字段（B5 前线上原样）、Ollama=探测通过即启用（旧契约），显式 off 才关闭 | ThinkingOptionsTest 11 | ✅ |
| B6 | 存量核实：CryptoUtil 机器绑定 ENC 信封、GET maskKey、掩码占位回写跳过**均已存在**——残余=registry 每实例密文（存于 registry JSON）+ 测试断言（cipher 非明文、GET 永不出 key） | （并入 registry/controller 测试） | ✅ |
| C1/C2 | `RemoteModelCatalogService`（fetch→验签[有头则验，错签拒收]→结构校验→revision 单调→setting 缓存→叠加[remote 优先]）；启动加载缓存+后台异步刷新；手动刷新端点 + 设置页按钮；404=not-available 静默基线（商店未上架即不激活） | RemoteModelCatalogServiceTest 5 | ✅ |
| D1 | `AiErrorCode`（七型，cause-chain 深分类，字符串缝+真类名钉住）+ `AiServiceException.code` + SSE error 附加 `errorCode`（message 保留仅展示） | AiErrorCodeTest 4 | ✅ |
| D2 | 目录 `cost:{input,output}`（12 family + 3 exact）+ SSE done 附加 `outputCostEstimate`（诚实标注：仅输出侧估算，无价格模型缺省） | ModelMetadataCatalogTest +1 | ✅ |

**执行中的修正/偏差（对照规划的决策记录）**：

1. **B1 存储改 JSON-in-settings 而非 `ai_provider` 表**（规划 §5 B1）：仓库先例（权限规则/hooks 均为 settings 内序列化 JSON，`AppSettingEntity` javadoc 明载），少一套 entity/repository/迁移，语义不变（CRUD/幂等迁移/镜像全实现）。
2. **B6 大部分为存量能力**（规划 §5 B6 假设需新建加密表）：核实 CryptoUtil 信封 + maskKey + 占位跳过已在线上，方案收敛为 registry 内嵌密文 + 断言。专表与 `FENGYU_CREDENTIAL_SECRET` 未新增（机器密钥机制既有）。
3. **A4 只对 exact 断言降级**（规划 A4 原文含 family）：目录自述 family 为近似值且 18 条 false 断言是启发式，误杀即回归（family 猜错时现有 400 回退本就是权威兜底）——精度取舍记此。
4. **B5 未设置默认值**：规划未写明 unset 语义；实现取「云零字段 + Ollama 旧契约」以保零回归（ChatModelConfigThinkOptionTest 两例存量测试即此契约的钉子，曾红后绿）。
5. **B4 载荷修剪**：`aiFormToPartial` 停发 provider/mode 字段——否则旧表单陈旧快照会经兼容壳镜像覆盖 registry 编辑（发现于自审，非规划明文）。

**门禁**：后端全量 1597 绿（如上）；前端 typecheck 0 + 127/127；`git diff --check` clean；
e2e-smoke 首跑失败为**陈旧 JAR**（target 内 9-30 构建，protocol v4 之前），重建
`package -DskipTests` 后复跑（结果见提交时附加）。docs：CHANGELOG 增 4 Added + 1 Fixed
条目并 `sync:changelog` 镜像 en/zh；README 无 REST 细节需同步。

**遗留（不阻塞 4.1.0，记入后续）**：每会话模型选择（B 后具备可行性，产品决策）；
C1 商店侧 `MODEL_CATALOG` 资源类型上架后签名强制；D2 输入侧成本（需每轮输入 token
落账）；旧 `/api/ai/config` provider 子映射兼容壳的 4.2.0 删除。

### 13.1 全新 agent 独立审查（2026-10-07）

独立 general-purpose agent（与实现零共享上下文）按目标审查章程复核：门禁全部实跑
复现（目标 71/71、全量 1597 绿、前端 127/127+typecheck 0、e2e-smoke PASS[并验证
JAR 非陈旧]、diff-check clean、文档同步）；§4-§7 逐任务对照；安全核查通过（key
全链路无明文出库/出 wire/入日志）。**判定 PASS（无 P0）**，附 2 项 P1 + 14 项 P2。

**P1 已当轮修复并钉测试**：
1. SSE `outputCostEstimate` 公式单位错千倍（`/1000`→`/1_000_000`，提为
   `CostRates.outputCostUsd`；`ModelMetadataCatalogTest.outputCostFormulaUsesPerMillionRates`
   钉死 $15/M×10k=$0.15）。
2. registry update 未跳过掩码占位（GET 回环 `•••• (id)` 或含 `***` 的值会覆盖真
   key）——update 现按与旧端点一致的跳过规则处理；
   `ProviderRegistryServiceTest.maskedPlaceholderRoundTripNeverReplacesTheCredential` 钉死。

**P2 遗留清单（审查员编号 3-16，归入后续）**：回滚后轮换 key 的 legacy→registry
重导入缺口；旧端点 `deprecated:true` 标记；C2 日级自动调度；D1 前端 errorCode 消费；
D2 会话成本 UI 与每模型 sampling 默认值（规划 D2 首半未做，此处置认）；e2e
升级场景；`ProviderDefinition.headers` 目前为死数据（API 可设但未接 client）；
`supportsImageExact` 不查 overlay；`mirrorLegacy` 形成双份密文（换兼容 GET 真实性，
置认）；rest-api.md 未收录新端点；overlay spec 无 "off" 档时无法关思考；Anthropic
budget 与小 maxTokens 的理论边界；normalizer 病态 ID 边界；encryptKey 失败静默丢
凭证（应改明确报错）。§13 表述修正：CloudSubagentRunner"零改动"限指 provider 构
造路径（该文件另有并发批次的审批门改动）。

### 13.2 第二轮独立全面审查（2026-10-07）

第二个全新 agent（与实现者及首轮审查员零共享上下文）按互补章程复审：首轮 P1 闭合
验证（两项均确认真实闭合、钉测有效、无新问题）；门禁实跑复现（1599 绿 / 前端
127+typecheck 0 / diff-check clean）；首轮 14 项 P2 逐条复核（**定级全部恰当，无应
升级漏判**）；并以与首轮不同的角度深查 a-h：归一化×压缩交互（偏差单向保守、无害）、
mid-turn 路径自洽、user-scoped 语义一致、静态 overlay 无测试污染、前端竞态仅轻
微、**取消路径 ABORTED 映射实证可靠**、目录规则抽查 14+ id（发现一处 exact 遮蔽）、
SSE 附加字段对既有消费方纯增量、SSRF 无新面（catalog fetch 复用商店 UrlPolicy 防线）。

**判定 PASS（无 P0）**。P1 一项（流程）：本地 JAR 陈旧（08:33 构建 < 08:53 的首轮
P1 修复落点）——首轮 e2e-smoke 验证的是修复前二进制；已当轮闭合（重建 JAR + 复跑
e2e-smoke + `find src/main -newer jar` 置零，结果见下）。

**当轮顺手修复的三项交付内瑕疵（均钉测）**：
1. R2-P2-1 exact 条目 `o3`/`o4-mini`/`claude-sonnet-4-20250514` 缺 thinking，被
   exact-恒-胜规则遮蔽致档位选择器只剩 Off——三条补齐 +
   `exactReasoningEntriesCarryThinkingNotShadowedByFamily` 钉测。
2. R2-P2-4 `RemoteModelCatalogService.refresh()` 的 `apiBase.get()`（跑 UrlPolicy、
   错配可抛）在 try 之外，违反 never-throws 契约——移入 try。
3. R2-P2-7 `persist()` javadoc 回滚自愈表述过强——修正为与实际语义一致（registry
   持久化后读路径不再回看 legacy key；轮换 key 需经 registry UI 重录）。

**遗留新增（R2 记录，随 §13.1 清单合并跟踪）**：AiErrorCode 边角
（FileNotFoundException→4XX 误分类、UnknownHostException 未映射）；前端 busyId 并发
竞态；SSE error 事件 `code`/`errorCode` 双字段命名；thinking 档位 PUT 失败静默；回滚
轮换 key 需 UI 重录（文档已明示，4.1.x 可补导入路径）。

**终态门禁（R2 修复后）**：后端全量 1600 tests, 0 failures, 0 errors, 5 skipped
（既有 Windows-only），BUILD SUCCESS；e2e-smoke PASS（重建后 JAR，全部主源码改动
含于二进制）；git diff --check clean；前端 127/127 + typecheck 0。

### 13.3 第三轮独立终审（2026-10-07）

第三个全新 agent（与前两轮零共享上下文）以互补角度终审：R2 修复 3/3 闭合；**测试
质量变异抽查**（6 个假设破坏：去重循环/unset 霰弹/成本公式/掩码形态/迁移顺序/
revision 边界——4 个被钉死，2 个不红即本轮修复项）；**origin 字段全生命周期**（纯
进程内瞬态、无序列化路径、rollout resume 的 reasoning 缺失为预先存在行为、Ollama
originKey 语义正确、displayName 撞键列为 P2）；双激活确认无窗口（activate 只写键、
reactivate 唯一构建点）；update 协议×URL 不匹配定级可接受（错误结构化 + 测试按钮
兜底）；CHANGELOG 逐条吻合（仅 MiMo 失实）；前端收尾无泄漏实体。**判定 PASS（无
P0）**。

**当轮修复（均钉测）**：
1. R3-P1 view() 掩码形态钉死（`•••• (id)` 形状 + 非 `ENC(` 前缀——密文外泄变异
   现在会红）。
2. R3-P2 persist() 置空前**回读确认** registry 写入成功（writeSetting 吞异常的窄
   窗口不再可能丢 legacy key）。
3. R3-P2 `revision<=0` 拒收钉测（0 与负数两例）。
4. R3-P2 MiMo 目录失实修正：补 `^mimo.*` family thinking 规则（CHANGELOG/javadoc
   声称的 `thinking:{type}` 现在真实存在）+ 钉测并入 exact-shading 测试。
5. R3-P2 javadoc 措辞：`ollama_boolean` 标注为 unset 兜底而非目录使用中的风格。

**R3 遗留记录（不修，预先存在或防御性）**：displayName 重复/含 `/` 的 originKey
撞键（刻意触发才可达，后果保守）；rollout `messageOf()` 写出 reasoning 不回读
（预先存在，建议后续批次修复）；writeSetting 全局吞异常（架构性，非本重构引入）。

**终态门禁（R3 修复后实跑）**：后端全量 **1600 tests, 0 failures, 0 errors,
5 skipped（既有 Windows-only），BUILD SUCCESS**；e2e-smoke PASS（重建 JAR，源码
新鲜度=0）；git diff --check clean；前端 127/127 + typecheck 0 + 生产 build exit 0
+ biome（改动文件）0 问题。三轮独立审查均 PASS，累计修复 P1 5 项、P2 内交付瑕疵
8 项，全部钉测。

### 13.4 第四轮独立终审补漏（2026-10-07）

第四个全新 agent 以剩余未扫面终审：**自定义 OLLAMA 协议实例激活路径**（确证 P1：
`activateDefinition` 丢弃 definition、`OllamaLocalBackend` 全程读 builtin 平铺 key、
`mirrorLegacy` 对非 builtin 不回写——自定义实例 test 按钮验证自身端点而激活后静默
路由到 builtin 端点，重启持续，零测试覆盖；UI 预设未暴露该路径故非 P0）；D1 合同
仅覆盖聊天 SSE 出口（AgentController 的 `onError(String)` 形状所限，加法一致，P2 并
入遗留）；rollout label 消费方安全（纯字符串、零解析方）；桌面端零耦合；**docs 用户
面失实**（configuration/ai-chat/features 仍述四模式；rest-api.md 自述完备却缺新端
点组）；i18n 40/40 键穷举完备；**测试对账严丝合缝**（52 新增=13+11+5+11+5+4+3，
既有断言零删除，在飞批次排除干净）；PUT 并发最终一致（switchMode 成对原子，无需
行动）；门禁全复现。**判定 PASS（无 P0）**。

**当轮修复**：
1. R4-P1 自定义 OLLAMA 实例路由：`OllamaLocalBackend` 增 `(baseUrl, modelTag)` 显
   式构造（fixed 字段，`effectiveBaseUrl()` 解析；无参构造保持平铺热读语义），
   `activateDefinition` 传入 definition 值——**不走** mirrorLegacy 扩展（会污染
   builtin 镜像破坏回滚）；钉测
   `customOllamaInstanceActivatesWithItsOwnEndpointNotTheBuiltinMirror`（自定义 tag
   送达 backend，平铺 key 同刻指向别的值）。
2. R4-P2（docs 义务部分）：`docs/{en,zh}/reference/rest-api.md` 补全
   `/api/ai/providers` 全组 8 端点（结构镜像），旧 config 行标注 deprecated 兼容面。

**R4 遗留记录（不修）**：AgentController 错误出口的 errorCode 透传（接口形状改
造，并入 D1 前端消费遗留项）；docs guide 三页（configuration/ai-chat/features）
的四模式叙述重写（叙述性重写，超出本批，4.1.x docs 更新批次处理）；README 能力
句更新（随 guide 一起）。

**终态门禁（R4 修复后实跑）**：后端全量 1601 tests, 0 failures, 0 errors,
5 skipped（既有），BUILD SUCCESS；e2e-smoke PASS（重建 JAR，新鲜度=0）；git diff
--check clean；前端 127/127 + typecheck 0。四轮独立审查均 PASS，累计修复 P1 6
项、交付内 P2 瑕疵 10 项，全部钉测。
