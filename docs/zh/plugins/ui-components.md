---
title: UI 组件
description: "@infinia/plugin-ui 套件——面向 FengYu 插件的 Codex 风格 Vuetify 3 基础库。外壳、文件/目录选择器、步骤向导、任务表、通知中心，以及脚手架 main.ts 接线的主题/locale 绑定。"
lang: zh-CN
---

# UI 组件

`@infinia/plugin-ui` 是面向 FengYu 插件的官方 Vuetify 3（Material Design 3）组件库。`fengyu init` 生成的项目依赖它，其 `src/main.ts` 已经接好 Vuetify 实例、主题/locale 绑定与 client 注入——你只需组合组件即可。从单一入口导入你用到的部分：

```ts
import {
  FyFilePicker,
  FyStepWizard,
  FyPluginShell,
  useFengYuClient,
  createFengYuVuetify,
  bindFengYuEnvironment,
  provideFengYuClient,
} from '@infinia/plugin-ui'
```

你用来组合的基础控件（`v-btn`、`v-card`、`v-list`、`v-data-table`……）就是普通的 Vuetify 控件，已由 `createFengYuVuetify` 全局注册。`Fy*` 组件是建立在之上的、带有 FengYu 既有约定的封装。

## 基础：Vuetify + 环境绑定

脚手架生成的 `src/main.ts` 会调用本包的三个辅助方法。通常你无需改动它们，但了解其职责会有帮助：

| 导出 | 用途 |
| --- | --- |
| `createFengYuVuetify(options?)` | 构建一个 Vuetify 实例，含 MD3 蓝图、`fengyuCodexDark` / `fengyuCodexLight` 主题、MDI 图标以及 `en` + `zhHans` locale。 |
| `bindFengYuEnvironment(vuetify, client)` | 先调用一次 `client.ready()`，把主题 + locale 应用到 Vuetify，再订阅 `environment` 事件。返回一个取消订阅函数。 |
| `provideFengYuClient(app, client)` | 通过 Vue `provide/inject` 注入 `FengYuClient`，使每个组件都能调用 `useFengYuClient()`。 |
| `useFengYuClient()` | 在任意 `setup()` 中注入 `FengYuClient`。优先使用它，而非导入原始 `fengyu` 单例。 |

## 组件目录

| 组件 | 用途 |
| --- | --- |
| `FyPluginShell` | 应用外壳：一个导航抽屉（标题 + `items[]`）+ 应用栏 + 内容插槽。在 `railBreakpoint`（默认 720px）以下折叠为临时抽屉。v-model 为活动项 value。 |
| `FyPageHeader` | 页面标题、可选 `description` 与尾部的 `#actions` 插槽（放工具栏按钮）。 |
| `FyToolbar` | 用于 `#actions` 的横向按钮行。 |
| `FyFilePicker` | SDK 文件按钮——封装 `client.files.open`。v-model 为 `FileRef` 或 `null`。 |
| `FyDirectoryPicker` | SDK 目录按钮——`mode: 'input' \| 'workspace' \| 'output'` 分别选择只读输入、可写项目工作目录或全新输出目录。 |
| `FyStepWizard` | 有状态、按 value 索引的工作流控制器，支持受控进度、异步校验、分支、失效与 JSON 快照。 |
| `FyTaskTable` | 只读任务列表（`tasks: FyTaskRow[]`），用 `v-data-table` 渲染；状态以图标 + 文字展示。 |
| `FyNotificationCenter` | 兜底 snackbar 堆栈。调用其 `notify(msg)`（经模板 ref）展示消息；转发给宿主，宿主拒绝时本地兜底。 |
| `FyConfirmDialog` | v-model 对话框，支持 `destructive` 样式；发出 `confirm` / `cancel`。 |
| `FyEmptyState` / `FyLoadingState` / `FyErrorState` / `FyPermissionNotice` | 标准化的空/加载中/错误/权限不足面板，含 `title`、`message`、`icon`。`FyErrorState` 发出 `retry`。 |

### 图标契约

Vuetify 图标属性接受 `mdi-home-outline` 这类常规 `mdi-*` 名称。UI 包携带 MDI 字体依赖，并由插件的 Vite 应用把字体输出为同源哈希资源，因此脚手架生成的插件不需要自定义字体 import 或 Vite 补丁。接受图标路径数据的组件也支持从 `@mdi/js` 导入、可 tree-shake 的 SVG path；这类路径通过 `FyIcon` 渲染。

通知组合式 `useFengYuNotify(client)` 与 `sendFengYuNotification(client, message)` 也已导出，供非组件场景使用。

## 示例：文件选择器

`FyFilePicker` 封装 `client.files.open`，让插件通过一个按钮请求宿主文件。取消——即宿主 resolve 为 `null`——是正常的空结果：它会发出 `update:modelValue(null)` 与 `cancel`，且**不**渲染任何错误。权限拒绝渲染 `FyPermissionNotice`；其他错误渲染带重试的 `FyErrorState`。这正是脚手架 `App.vue` 所用的完整模式：

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { FyFilePicker, FyNotificationCenter, useFengYuClient } from '@infinia/plugin-ui'
import type { FileRef } from '@infinia/plugin-sdk'

const client = useFengYuClient()
const selectedFile = ref<FileRef | null>(null)
const notifications = ref<InstanceType<typeof FyNotificationCenter> | null>(null)

async function onFile(file: FileRef | null): Promise<void> {
  selectedFile.value = file
  if (file) await notifications.value?.notify(`Selected ${file.name}`)
}
</script>

<template>
  <FyFilePicker
    label="Choose spreadsheet"
    :extensions="['xlsx', 'csv']"
    :model-value="selectedFile"
    @update:model-value="onFile"
    @cancel="onFile(null)"
  />
  <FyNotificationCenter ref="notifications" />
</template>
```

| Prop | 类型 | 说明 |
| --- | --- | --- |
| `modelValue` | `FileRef \| null` | v-model：选中的文件，或 `null`。 |
| `extensions` | `string[]` | 转发给宿主文件对话框的扩展名白名单。 |
| `filters` | `FileFilter[]` | 转发给宿主的具名扩展名过滤器。 |
| `label` | `string` | 按钮文字（默认 `Choose file`）。 |

发出事件：`update:modelValue`、`cancel`、`error`。`FyDirectoryPicker` 形状相同，另加 `mode: 'input' | 'workspace' | 'output'`（默认 `input`）。工作目录模式调用 `client.files.workspaceDirectory()`，并要求 `files.write` 权限。

## 示例：步骤向导

`FyStepWizard` 是一个有状态、按 value 索引的工作流控制器。受控消费者通过三个 v-model 绑定持有当前步骤、逐步状态与完成标记。六种步骤状态为 `pending`、`active`、`validating`、`complete`、`error`、`skipped`；`error` 状态还可携带 `error` 消息。

```vue
<FyStepWizard
  v-model="activeStep"
  v-model:states="states"
  v-model:completed="completed"
  :steps="steps"
  :validate-step="validateStep"
  :resolve-next="resolveNext"
  @snapshot="saveProgress"
>
  <template #source>Source form</template>
  <template #complete>Completed result</template>
</FyStepWizard>
```

| Prop | 类型 | 说明 |
| --- | --- | --- |
| `steps` | `FyWizardStep[]` | 有序的 `{ value, title, description?, optional? }` 条目。 |
| `modelValue` | `string` | 当前步骤（`v-model`）；省略时默认为首个已声明步骤。 |
| `states` | `Record<string, FyWizardStepState>` | 受控状态映射（`v-model:states`）。 |
| `completed` | `boolean` | 受控工作流完成标记（`v-model:completed`）。 |
| `context` | `T` | 传给校验、路由、失效逻辑及步骤插槽的消费者数据。 |
| `snapshot` | `FyWizardSnapshot` | 待规范化并恢复的版本化快照。 |
| `validateStep` | `(step, context, signal) => boolean \| FyWizardValidationResult \| Promise<boolean \| FyWizardValidationResult>` | 前进前校验当前步骤。 |
| `resolveNext` | `(step, context) => string \| null` | 选择下一分支；`null` 表示完成工作流。 |
| `invalidateAfter` | `(changedStep, context) => string[]` | 返回由 `actions.invalidate` 重置为 `pending` 的步骤 ID。 |
| `backText` / `nextText` / `finishText` / `retryText` / `optionalText` | `string` | 可见文字，默认依次为 `Back`、`Next`、`Finish`、`Retry`、`optional`。 |
| `labels` | `FyWizardLabelsInput` | 部分覆盖状态、进度、步骤、错误历史、当前状态与已访问路径标签。 |

`labels` 会与导出的 `FY_WIZARD_DEFAULT_LABELS` 合并。其嵌套的 `status` 映射可以覆盖 `pending`、`active`、`validating`、`complete`、`error`、`skipped` 中任意状态；`progress`、`errorHistory`、`showVisitedPath` 与 `hideVisitedPath` 是字符串；`step(index, total)`、`compactProgress(index, total)`、`errorStep(title, status)` 与 `currentStatus(title, status)` 格式化其余可见或 ARIA 文本。配合上面的五个文字 prop，所有通用标签都有英文默认值且都可本地化。

事件为 `update:modelValue(value)`、`update:states(states)`、`update:completed(completed)`、`transition(from, to)`、`validation-error(step, message?)`、`restore-error(message)`、`snapshot(snapshot)` 与 `complete(snapshot)`。在受控 API 中，最终步骤校验通过且路由返回 `null` 后，`complete` 携带最终快照；下文已弃用的非受控最终步骤行为不携带快照。

| 插槽 | Props / 公共类型 |
| --- | --- |
| 步骤具名插槽（例如 `#source`） | 运行时为 `{ step, state, context, actions }`；导出 `FyWizardStepSlotProps<T>` 供可复用消费者使用，但 Vue 不会为任意插槽名逐个推断类型。 |
| `#step-label` | `{ step, index, state, statusLabel, active, context, actions }`（`FyWizardStepLabelSlotProps<T>`） |
| `#error` | `{ step, state, message, context, actions }`（`FyWizardErrorSlotProps<T>`） |
| `#actions` | `{ step?, state?, context, completed, busy, canBack, nextLabel, actions }`（`FyWizardActionsSlotProps<T>`） |
| `#complete` | `{ actions }` |

所有插槽都是可选的，未提供时保留默认 UI。错误包装器为自定义错误插槽提供 alert 语义。共享的 `actions` 对象提供 `next(): Promise<void>`、`back()`、`goTo(step)` 与 `invalidate(changedStep)`。后退和直接导航只允许在真实 `visitedPath` 内进行；未访问的未来步骤保持锁定。

### 校验、分支与失效

`validateStep` 会收到一个 `AbortSignal`。请监听它并传给可取消的 SDK/RPC 操作：失效、组件卸载或外部受控状态变化都可能中止转换，过期的异步完成结果会被忽略。同一时间只运行一次前进校验。校验必须成功后才会运行 `resolveNext`，因此路由能观察校验期间更新的 context。返回 `false` 或 `{ valid: false, message? }`，或抛出非中止错误，会让当前步骤进入 `error`；内联前进操作会变成 **Retry**，再次点击会重新校验。解析器抛错或返回未知目的步骤也按同样方式受控处理，且绝不会发布无效的当前步骤或快照。返回 `true` 或 `{ valid: true }` 会完成当前步骤并跟随已解析的路由。

当 `resolveNext` 跳过已声明步骤时，源步骤与目标步骤之间尚未访问的步骤会变成 `skipped`；快照记录真实访问路径，而不是伪造的线性路径。上游数据变化时调用 `actions.invalidate(changedStep)`。`invalidateAfter` 选择依赖步骤 ID；未提供时，向导使访问路径中 `changedStep` 后面的步骤失效。向导会从最早失效的已访问步骤处裁剪历史，把所有被裁剪的未来状态重置为 `pending`，并锁定对它们的直接导航。如果当前步骤也被裁剪，则回到最近的保留前置步骤（通常是 `changedStep`）；如果整条路径都被裁剪，则把第一个已声明步骤恢复为当前历史项。model、states、completion 与 snapshot 更新仍保持一致。失效还会取消校验并清除完成标记，但清理依赖的业务数据或结果仍由消费者负责。

快照是分离的纯 JSON 对象，字段为 `version`、`activeStep`、`visitedPath`、`states` 与 `completed`。恢复时会拒绝不支持的版本或空步骤列表，移除未知及重复的已访问 ID，修复无效的当前步骤，并把未完成工作流的当前步骤设为 `active`。重复的 `steps[].value` 定义会在状态创建/组件初始化时快速失败，并在快照规范化时返回受控错误，不会静默折叠。`FyStepWizard` 不包含任何存储 API：持久化、快照版本迁移、业务数据恢复与重新校验都属于消费插件的职责。

### 响应式与无障碍行为

桌面端渲染完整的横向路径。在 720px 及以下，紧凑视图聚焦当前步骤、保留所有错误步骤，并提供可展开且可本地化的已访问路径控件；Back/Next 操作保持粘性定位。每种状态除了颜色外还使用图标和文字标签。视觉隐藏的 `role="status"` 实时区域在包括桌面在内的所有宽度下持续挂载。向导会把受控或内部校验报告为忙碌，锁定未访问控件，把当前错误关联到步骤，并在转换或校验失败后把焦点移到新内容或错误；减少动态效果偏好会关闭状态动画。

### 已弃用的 1.x 兼容性

仅为 1.x 消费者保留已弃用的 `canContinue(from, to)` 与 `blocked(reason?)` 事件兼容行为。省略 `modelValue`、`states` 和 `completed` 会保留旧的非受控模式。只提供 `modelValue` 的旧消费者会把声明顺序中截至该步骤的前缀作为 Back 历史；提供 `states` 或 `snapshot` 的规范消费者只保留实际记录的路径。当存在 `canContinue`，且未提供有状态的 `states`、`completed`、`snapshot`、`validateStep` 与 `resolveNext` props 时，在最终已声明步骤按 Next 会立即且仅一次发出 `complete`；无论是否提供 `modelValue`，都**不会**调用 `canContinue(from, from)`。新插件应使用三个受控 v-model 绑定与 `validateStep`；兼容模式不提供完整的有状态工作流契约。

## 旧式静态插件

静态插件（纯 `ui/index.html` + `ui/app.js`、无构建步骤）**不**使用本包——它直接从 `./sdk.js` 导入 SDK。两种风格都被 `dev` 与 `build` 完整支持。把现有静态插件迁移到 Vue/Codex 套件是可选的；脚手架生成的布局见 [入门](/zh/plugins/getting-started)。

## 下一步

- [入门](/zh/plugins/getting-started)——create + dev + build 循环。
- [UI 微前端](/zh/plugins/ui-microfrontend)——选择器与外壳所封装的 `FengYuClient` API。
- [SDK 与 CLI](/zh/plugins/sdk-cli)——完整的 SDK + CLI 参考。
