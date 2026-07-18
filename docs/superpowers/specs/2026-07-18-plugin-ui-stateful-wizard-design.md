# 官方插件 UI 状态化分步指引器设计

- **日期**：2026-07-18
- **状态**：已确认，实施计划已编写
- **适用版本**：FengYu 4.0.0 插件模型
- **核心消费者**：`OfficialPlugins/plugin-excel`

## 1. 背景

`@infinia/plugin-ui` 已公开 `FyStepWizard`，Excel 官方插件也已经用它承载
`Source → Mode → Output → Run` 四步拆分流程。现有组件仍是基于数组索引的线性控制器，只能
表达当前步骤、前进、后退和一次 `canContinue` 校验，不能完整表达官方插件需要的完成、错误、
校验中、跳过、条件分支、断点恢复和最终完成状态。

本设计把 `FyStepWizard` 升级为官方插件 UI 的通用状态化分步指引器，并首先在 Excel 插件中
落地。它属于 Vue/Vuetify iframe 插件体系，不恢复或引用历史 JavaFX `StepWizard`。

本设计细化并取代以下旧设计中关于 `FyStepWizard` 的有限约定：

- `2026-07-14-codex-vuetify-plugin-ui-design.md` 的复合组件清单。
- `2026-07-13-excel-four-step-wizard-design.md` 的纯线性导航描述。

其他插件运行时、worker JSON-RPC 和文件授权契约仍以当前仓库实现为准。

## 2. 目标与非目标

### 2.1 目标

1. 支持步骤的 `pending`、`active`、`validating`、`complete`、`error`、`skipped` 状态。
2. 支持同步或异步校验、错误后原地重试、重复操作保护和旧请求取消。
3. 支持由业务上下文决定的条件分支，并记录实际访问路径。
4. 支持回到已完成步骤；业务数据变化后使依赖的下游步骤失效。
5. 通过纯数据快照支持断点恢复，持久化位置由插件决定。
6. 支持明确的最终完成状态，并允许从完成页返回检查配置。
7. 采用响应式混合布局：桌面横向全步骤，窄屏聚焦当前步骤和异常步骤。
8. 保持 Excel 的四步业务流程和三种拆分模式完整可用。
9. 所有用户可见文案可由 props 或 slots 本地化，不在组件内硬编码英文。

### 2.2 非目标

- `FyStepWizard` 不保存 Excel 表单、worker session、`FileRef` 或业务结果。
- 组件不直接写 `localStorage`、数据库或插件 worker。
- 组件不理解 BY_SHEET、BY_COLUMN 或 COMPLEX 等 Excel 领域概念。
- 不改变 `.fyp`、manifest、SDK 消息或 JSON-RPC worker 协议。
- 不在本功能中迁移其他官方插件的页面结构。
- 不引入第三方状态机库。

## 3. 方案选择

采用受控状态机组件：

- `FyStepWizard` 管理转换规则、临时校验状态、响应式展示和无障碍语义。
- 消费插件通过受控 props 提供当前步骤、步骤状态和业务上下文。
- 组件在有效转换后发出新的步骤状态及纯数据快照。
- 插件决定是否以及如何把快照保存到 worker、配置文件或其他存储。

未采用的方案：

- 在现有数组索引逻辑上继续增加布尔字段，会让分支、恢复和下游失效互相耦合。
- 组件内部持久化会把 UI 与宿主授权、插件生命周期和业务版本迁移绑定在一起。
- 第三方状态机库会增加插件包体和公开 API 学习成本，而当前转换模型足够明确。

## 4. 公共数据模型

```ts
export interface FyWizardStep {
  value: string
  title: string
  description?: string
  optional?: boolean
}

export type FyWizardStepStatus =
  | 'pending'
  | 'active'
  | 'validating'
  | 'complete'
  | 'error'
  | 'skipped'

export interface FyWizardStepState {
  status: FyWizardStepStatus
  error?: string
}

export interface FyWizardSnapshot {
  version: 1
  activeStep: string
  visitedPath: string[]
  states: Record<string, FyWizardStepState>
  completed: boolean
}

export interface FyWizardValidationResult {
  valid: boolean
  message?: string
}
```

约束：

- `steps[].value` 在同一向导中唯一且稳定。
- `activeStep` 必须出现在 `steps` 中。
- `visitedPath` 按实际访问顺序保存，不等同于 `steps` 的声明顺序。
- `error` 仅在状态为 `error` 时展示；进入其他状态时清理旧错误。
- 快照只包含 JSON 可序列化数据。

## 5. 组件 API

### 5.1 Props

```ts
interface FyStepWizardProps<TContext = unknown> {
  steps: FyWizardStep[]
  modelValue: string
  states: Record<string, FyWizardStepState>
  context?: TContext
  completed?: boolean
  snapshot?: FyWizardSnapshot
  validateStep?: (
    step: string,
    context: TContext,
    signal: AbortSignal,
  ) => boolean | FyWizardValidationResult | Promise<boolean | FyWizardValidationResult>
  resolveNext?: (
    step: string,
    context: TContext,
  ) => string | null
  invalidateAfter?: (changedStep: string, context: TContext) => string[]
  backText?: string
  nextText?: string
  finishText?: string
  retryText?: string
  optionalText?: string
}
```

`snapshot` 是一次恢复输入：它的对象标识变化时，组件先按第 7 节归一化，再通过受控更新事件
把恢复结果交回消费方。恢复完成后，`modelValue`、`states` 和 `completed` 继续作为事实来源；
组件不会在内部维护第二份长期持久化状态。

`resolveNext` 返回：

- 下一步骤 ID：进入该步骤。
- `null`：当前步骤是实际路径的最后一步，成功后进入完成状态。
- 未提供：按 `steps` 声明顺序进入下一步骤。

`invalidateAfter` 返回受当前业务数据变化影响的步骤 ID。未提供时，组件按当前
`visitedPath` 使变更步骤之后的已访问步骤回到 `pending`。

### 5.2 Events

```ts
type FyStepWizardEmits = {
  'update:modelValue': [value: string]
  'update:states': [states: Record<string, FyWizardStepState>]
  'update:completed': [completed: boolean]
  transition: [from: string, to: string]
  'validation-error': [step: string, message?: string]
  'restore-error': [message: string]
  snapshot: [snapshot: FyWizardSnapshot]
  complete: [snapshot: FyWizardSnapshot]
}
```

组件在一次有效状态变化结束后发出 `snapshot`。消费方可以直接保存，也可以将它与自己的
业务快照合并后保存。

### 5.3 Slots

- 以步骤 `value` 命名的内容 slot，继续兼容现有 Excel 用法。
- `#step-label`：自定义步骤标题与状态补充信息。
- `#error`：自定义当前步骤错误呈现。
- `#actions`：覆盖默认的后退、继续、重试按钮区。
- `#complete`：最终完成页。

每个步骤 slot 获得当前步骤、状态、上下文和转换动作；业务组件不需要访问状态机内部实现。

## 6. 状态转换

### 6.1 前进

```text
用户点击继续
  → 锁定本次转换并创建 AbortController
  → 当前步骤进入 validating
  → 调用 validateStep
    → 失败：当前步骤进入 error，保留当前步骤，发出 validation-error + snapshot
    → 成功：当前步骤进入 complete
  → 调用 resolveNext
    → 返回步骤：更新分支 skipped 状态，激活目标，发出 transition + snapshot
    → 返回 null：completed=true，发出 complete + snapshot
```

同一时间最多存在一次转换。再次点击不会启动第二次校验。

### 6.2 后退与直接跳转

- 用户可以回到 `visitedPath` 中已完成或错误的步骤。
- 未访问的未来步骤保持锁定，不能直接跳过校验。
- 后退不执行前进校验，也不自动清除已完成状态。
- 修改业务数据后，插件调用受控状态更新或 `invalidateAfter`，使依赖步骤重新变为
  `pending`；如果当前步骤被失效，则回到最近一个仍有效的步骤。

### 6.3 条件分支

- `resolveNext` 是唯一的分支决策入口。
- 组件记录实际路径，不根据声明顺序猜测业务分支。
- 当前分支未采用且已经存在状态的步骤标记为 `skipped`。
- 上游数据改变后重新计算分支；旧分支状态失效，不能带入新分支。

### 6.4 取消与卸载

- 新转换开始前取消上一个未结束的校验。
- 组件卸载时取消活动校验。
- `AbortError` 不显示为用户错误，也不发送错误通知。
- 其他异常转为 `error` 状态；展示文本优先使用验证结果的 `message`，其次使用安全的
  `Error.message`。

## 7. 快照与恢复

### 7.1 持久化边界

`FyWizardSnapshot` 只保存导航状态。Excel 插件另行保存：

- 当前拆分模式和模式专属配置。
- 工作表分析摘要。
- worker session 标识。
- 输入、输出引用的可恢复描述。

组件不承诺 `FileRef` 跨宿主重启有效。Excel 恢复业务快照后必须重新验证 session 和文件授权。

### 7.2 恢复算法

1. 检查快照 `version`；不支持时发出 `restore-error` 且不应用快照，由插件迁移或放弃。
2. 丢弃 `states` 和 `visitedPath` 中不存在于当前 `steps` 的 ID。
3. 去除重复路径项，并保证路径起点有效。
4. 验证 `activeStep`；无效时回到最后一个有效已访问步骤，否则回到第一步。
5. 插件重新验证 worker session、输入文件和输出目录。
6. 无效输入使 `source` 进入 `error` 并回到该步骤；无效输出使 `output` 进入 `error`。
7. 只有业务资源和导航状态都有效时才恢复 `completed=true`。
8. 归一化成功后依次发出 `update:modelValue`、`update:states`、`update:completed` 和新的
   `snapshot`，由消费方接管受控状态。

## 8. 响应式 Codex 布局

采用已确认的“响应式混合”方案。

### 8.1 桌面

- 顶部横向展示完整步骤路径。
- 已完成步骤显示图标与文本，当前步骤使用高对比中性强调。
- 错误步骤同时显示错误图标和文本，不只依赖颜色。
- 未进入和跳过步骤保持低强调；跳过状态具有可读标签。
- 内容区与操作区保持扁平表面、细边框和官方 Codex UI 间距。

### 8.2 窄屏

- 顶部显示“步骤 N / M”、当前步骤和状态。
- 已完成步骤折叠；错误步骤即使不在当前步骤也保持可见。
- 用户可展开查看实际访问路径，但不能点击未访问的未来步骤。
- 操作按钮保持可见标签并自然换行，不依赖图标表达方向。

### 8.3 无障碍

- 当前步骤使用 `aria-current="step"`。
- 步骤列表使用有序列表语义。
- 校验中状态通过可读文本和适当 live region 通知。
- 错误内容与对应步骤关联。
- 焦点在转换成功后进入新步骤标题；校验失败时移至错误摘要。
- `prefers-reduced-motion` 下不执行步骤转换动画。

## 9. Excel 集成

Excel 保留以下步骤：

| 步骤 | 校验与副作用 | 失效条件 |
| --- | --- | --- |
| `source` | 必须有有效 `FileRef`，等待 `analyze` 成功 | 重新选择文件 |
| `mode` | 验证模式字段并调用 `configure` | 来源或模式配置变化 |
| `output` | 必须有有效、可写的目录引用 | 来源、模式或目录变化 |
| `run` | 调用 `split`；失败可原地重试 | 任一上游步骤变化 |

三种模式继续由 Excel 自己管理：

- `BY_SHEET`：空选择沿用当前“全部工作表”语义。
- `BY_COLUMN`：必须选择工作表和列。
- `COMPLEX`：至少一条规则，且每条规则包含有效工作表。

Excel 的 `resolveNext` 当前返回固定四步路径，但采用通用 API，以便将来按平台、文件类型或
配置跳过输出确认等步骤，而无需修改 `FyStepWizard`。

执行失败时 `run` 进入 `error`，保留 session、模式配置和输出目录。用户可重试或返回上游
修改；返回不会自动再次执行。执行成功后显示 `#complete`，列出文件数量和结果，并保留返回
检查配置的入口。

## 10. 错误处理

- 用户取消文件或目录选择是正常空结果，不把步骤标记为错误。
- 业务校验失败显示插件提供的本地化消息。
- worker 请求失败停留在发生错误的步骤，不丢失仍有效的上游数据。
- 超时与可重试错误使用同一重试入口；重试创建新的请求。
- 恢复时发现过期授权，明确要求重新选择，不展示宿主绝对路径。
- 无效步骤定义、重复 ID 或分支返回未知 ID 在开发模式抛出明确错误；生产模式安全停止转换并
  发出 `validation-error`。

## 11. 测试与验收

### 11.1 `plugin-ui` 单元与组件测试

- 默认线性前进和后退。
- 异步校验成功、失败、抛错和取消。
- 校验期间重复点击只执行一次。
- 条件分支、跳过状态和实际访问路径。
- 上游变更后的下游失效。
- 错误后重试和成功恢复。
- 最终完成状态和从完成页返回。
- 有效快照恢复、未知步骤清理、版本不兼容处理。
- 键盘导航、焦点移动、ARIA 当前步骤和 live region。
- 用户可见状态不只依赖颜色。

### 11.2 视觉回归

- 截图范围包含完整 `FyPluginShell + FyStepWizard`，不只截取内容 slot。
- 明确设置桌面和窄屏 viewport，不依赖 Playwright 设备默认值。
- 覆盖亮色、暗色、正常、校验中、错误、跳过分支和完成状态。
- 窄屏无页面级横向溢出，步骤和操作按钮不重叠。

### 11.3 Excel 集成测试

- BY_SHEET、BY_COLUMN、COMPLEX 各走通完整四步流程。
- 分析失败、配置失败、输出引用失效、拆分失败均落在正确步骤。
- 修改来源或模式后，下游完成状态被清除。
- 拆分失败重试不重复创建规则或自动更换输出目录。
- 快照恢复后重新验证 session 与文件授权。
- 成功完成后展示文件数量与结果列表。

### 11.4 聚焦验证命令

实施计划应至少运行：

```bash
cd plugin-ui/vue && npm test && npm run build && npm run test:visual
cd OfficialPlugins/plugin-excel/ui-src && npm test && npm run build
```

如 Excel worker RPC 参数或恢复接口发生变化，再运行其 Maven 测试；纯 UI 状态机修改不扩大到
整个 reactor。

## 12. 文档与兼容性

- 更新 `docs/en/plugins/ui-components.md` 与对应中文页，记录完整 API、状态和快照格式。
- 更新 Excel 官方插件文档，展示四步集成、三种模式和恢复边界。
- 更新 CLI 模板示例时只展示通用线性用法，不把 Excel 领域逻辑复制到新插件模板。
- 保留现有命名 slot 和线性 `steps` 的迁移路径；实施计划应明确兼容层或一次性更新所有仓库内
  消费方。
- 不接受继续硬编码 Back、Next、Finish、optional 等英文文案。

## 13. 完成标准

1. Excel 四步流程完整使用升级后的官方 `FyStepWizard`。
2. 所有要求的步骤状态、分支、校验、失效、恢复和完成行为均有自动化测试。
3. 响应式混合布局在亮暗主题、桌面和窄屏下通过视觉回归。
4. 快照由 Excel 持久化，组件本身没有存储副作用。
5. 过期 session 或文件授权恢复失败时安全回退到可操作步骤。
6. 中英文组件文档与实际导出 API 一致。
7. 聚焦测试、类型检查和生产构建通过。
