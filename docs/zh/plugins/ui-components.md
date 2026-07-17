---
title: UI 组件
description: "@infinia/plugin-ui 套件——面向 FengYu 插件的 Codex 风格 Vuetify 3 基础库。外壳、文件/目录选择器、步骤向导、任务表、通知中心，以及脚手架 main.ts 接线的主题/locale 绑定。"
lang: zh-CN
---

# UI 组件

`@infinia/plugin-ui` 是面向 FengYu 插件的官方 Vuetify 3（Material Design 3）组件库。`fengyu plugin create` 生成的项目依赖它，其 `src/main.ts` 已经接好 Vuetify 实例、主题/locale 绑定与 client 注入——你只需组合组件即可。从单一入口导入你用到的部分：

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
| `FyStepWizard` | 线性、按 value 索引的多步骤控制器，带 `canContinue` 门控。 |
| `FyTaskTable` | 只读任务列表（`tasks: FyTaskRow[]`），用 `v-data-table` 渲染；状态以图标 + 文字展示。 |
| `FyNotificationCenter` | 兜底 snackbar 堆栈。调用其 `notify(msg)`（经模板 ref）展示消息；转发给宿主，宿主拒绝时本地兜底。 |
| `FyConfirmDialog` | v-model 对话框，支持 `destructive` 样式；发出 `confirm` / `cancel`。 |
| `FyEmptyState` / `FyLoadingState` / `FyErrorState` / `FyPermissionNotice` | 标准化的空/加载中/错误/权限不足面板，含 `title`、`message`、`icon`。`FyErrorState` 发出 `retry`。 |

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

`FyStepWizard` 是一个线性、按 value 索引的多步骤控制器。`steps` 是有序的 `{ value, title, optional? }` 列表；当前步骤是以 `value` 为键的 v-model；每个步骤的内容来自具名插槽。`canContinue(from, to)` 可同步也可异步，门控每一次前进——返回 `false` 会发出 `blocked` 并停留在原地。在最后一步前进会发出 `complete`。

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { FyStepWizard } from '@infinia/plugin-ui'
import type { FyWizardStep } from '@infinia/plugin-ui'

const steps: FyWizardStep[] = [
  { value: 'source', title: 'Source' },
  { value: 'options', title: 'Options' },
  { value: 'review', title: 'Review' },
]
const current = ref(steps[0].value)

// 门控 Source → Options 的真实（异步）检查
async function canContinue(from: string, _to: string): Promise<boolean> {
  if (from === 'source') {
    // 例如：校验已选取文件后再放行
    return true
  }
  return true
}
</script>

<template>
  <FyStepWizard
    v-model="current"
    :steps="steps"
    :can-continue="canContinue"
    @complete="console.log('wizard complete')"
  >
    <template #source>
      <p class="pa-4">选取一个源文件。</p>
    </template>
    <template #options>
      <p class="pa-4">调整导入选项。</p>
    </template>
    <template #review>
      <p class="pa-4">复核并完成。</p>
    </template>
  </FyStepWizard>
</template>
```

| Prop | 类型 | 说明 |
| --- | --- | --- |
| `steps` | `FyWizardStep[]` | 有序的 `{ value, title, optional? }` 条目。 |
| `modelValue` | `string` | 当前步骤 value（v-model）。缺省为第一步。 |
| `canContinue` | `(from, to) => boolean \| Promise<boolean>` | 门控前进转换。 |

发出事件：`update:modelValue`、`blocked(reason?)`、`complete`。后退导航忽略 `canContinue`，且在第一步隐藏。

## 旧式静态插件

静态插件（纯 `ui/index.html` + `ui/app.js`、无构建步骤）**不**使用本包——它直接从 `./sdk.js` 导入 SDK。两种风格都被 `dev` 与 `build` 完整支持。把现有静态插件迁移到 Vue/Codex 套件是可选的；脚手架生成的布局见 [入门](/zh/plugins/getting-started)。

## 下一步

- [入门](/zh/plugins/getting-started)——create + dev + build 循环。
- [UI 微前端](/zh/plugins/ui-microfrontend)——选择器与外壳所封装的 `FengYuClient` API。
- [SDK 与 CLI](/zh/plugins/sdk-cli)——完整的 SDK + CLI 参考。
