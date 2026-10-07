---
title: UI 组件
description: "@infinia/plugin-ui 套件——面向 FengYu 插件的 Infinia React 19 模板套件，包含聚焦工作台外壳、页面、状态组件、选择器、Select/Combobox、步骤向导、通知、i18n 与实时主题/locale 绑定。"
lang: zh-CN
---

# UI 组件

`@infinia/plugin-ui`（2.1）是面向 FengYu 插件 UI 的官方 React 19 模板套件。`fengyu init` 生成的项目依赖它，其 `src/main.tsx` 已把完整的启动与销毁生命周期交给 `mountFengYuApp`——你只需组合组件即可。从单一入口导入你用到的部分：

```tsx
import {
  FilePicker,
  Page,
  PageHeader,
  PluginBar,
  PluginShell,
  StepWizard,
  useFengYuClient,
  mountFengYuApp,
} from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
```

一条样式导入覆盖全部样式：套件以库模式 Tailwind（`source(none)` 只扫描自身源码）构建，产出一整份编译好的 `dist/plugin-ui.css`——插件项目自身永远不需要运行 Tailwind。

套件**有意不内置任何 Aceternity UI 组件**（其许可禁止再分发源码文件）。这类组件按项目用 `fengyu add` 拉取——见 [Aceternity UI 组件](/zh/plugins/aceternity-ui)。

## 设计令牌：Infinia 设计语言

套件与主程序、商店共享同一设计语言（取值逐字镜像自主程序 `zai.css`）：暖白画布、白面板、`#e5e2db` 发丝线，以及作为唯一高饱和交互色的金 `#eab04b`——金色填充永远搭配深墨文字。所有令牌都是 `:root` 上的 `--c-*` CSS 变量，暗色一套位于 `<html>` 的 `.dark` 类下（由 `bindFengYuEnvironment` 切换）：

| 令牌 | 亮色 | 暗色 | 用途 |
| --- | --- | --- | --- |
| `--c-canvas` | `#faf9f6` | `#09090b` | 应用画布 |
| `--c-panel` | `#ffffff` | `#141416` | 面板、栏条、输入框 |
| `--c-raised` | `#ffffff` | `#1c1c20` | 抬升表面（菜单、弹层） |
| `--c-muted-surface` | `#f3f1ec` | `#101013` | 弱化填充、骨架行 |
| `--c-ink` / `--c-ink-2` / `--c-ink-3` | 墨色三级 | 墨色三级 | 主/次/弱文字 |
| `--c-line` / `--c-line-strong` | `#e5e2db` / `#d8d3c8` | `#29292d` / `#38383e` | 发丝线边框 |
| `--c-hover` | 墨 4.5% | 白 5% | 悬停底色 |
| `--c-gold` / `--c-gold-hover` | `#eab04b` / `#d99e33` | `#f6bd60` / `#ffd28a` | 唯一高饱和交互色 |
| `--c-gold-ink` | `#18181b` | `#18181b` | 金底上的文字（金永远配深墨） |
| `--c-accent` | `#885400` | `#f6bd60` | 焦点描边（2px `:focus-visible`） |
| `--c-tag` | `#e9e5dc` | `#26262c` | 中性标签/胶囊底色 |
| `--c-success(-bg)` / `--c-warning(-bg)` / `--c-danger(-bg)` | 语义成对 | 语义成对 | 状态色及其浅底 |
| `--c-input` / `--c-input-border` | 输入底/边框 | 输入底/边框 | 表单控件（聚焦时 `--c-input-border-focused`） |

这些令牌同时映射为套件内部的 Tailwind 主题工具类（`bg-canvas`、`bg-panel`、`bg-muted-surface`、`text-ink`、`text-ink-2`、`border-line`、`bg-gold`、`text-gold-ink`、`bg-hover`……），组件自身正是用它们排版的。样式表还带两条签名规则：`.infinia-hex`（`HexMark` 背后的六边形裁剪）与 2px accent 色 `:focus-visible` 描边。导出的 `cn(...inputs)` 工具（clsx + tailwind-merge）用于按套件同样的方式组合类名；常用 SDK 类型（`FengYuClient`、`Environment`、`Theme`、`FileRef`、`FileFilter`）也被再导出，插件代码由此获得稳定导入。所有组件都携带稳定的 `data-*` 属性（`data-plugin-shell`、`data-wizard-step`、`data-action`……），便于端到端测试。

## 引导：`mountFengYuApp` 与 client hook

脚手架生成的 `src/main.tsx` 就是全部引导（下面是 `react-java` 模板原文）：

```tsx
import { fengyu } from '@infinia/plugin-sdk'
import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './App'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
const client = fengyu
await mountFengYuApp({ root: App, client })
```

`mountFengYuApp` 持有完整的 iframe UI 生命周期：绑定环境、把你的根组件包进 `FengYuClientProvider` + `NotifyProvider`（传入 `messages` 时再加 `FengYuI18nProvider`）、创建 React root，并在 `pagehide` 时卸载、取消订阅、销毁一切。它返回一个幂等的 disposer。选项（`MountFengYuAppOptions`）：

| 选项 | 类型 | 说明 |
| --- | --- | --- |
| `root` | `ComponentType \| ReactNode` | 你的根组件（或现成的元素）。 |
| `client` | `FengYuClient` | SDK client（iframe 中的 `fengyu` 单例）。 |
| `target` | `string \| Element` | 挂载目标；默认 `#app`。 |
| `messages` | `FengYuMessageTables` | 扁平键 i18n 表（`{ en: {...}, zh: {...} }`）；自动接线宿主 locale。 |
| `wrap` | `(tree) => ReactNode` | 插件专用 Provider 的逃生口；收到默认树。 |
| `onEnvironment` | `(environment) => void` | 每次宿主环境更新后触发（主题/locale 已先应用）。 |
| `onReadyError` | `(error) => void` | 宿主 ready 握手失败或超时（UI 仍以默认值渲染）。 |

在组件树内用 `useFengYuClient()` 获取 client——作用域内没有 client 时它会立即抛错，而不是等到第一次宿主往返才失败。自定义引导时可用的底层导出是 `FengYuClientProvider` / `FengYuClientContext` 与：

| 导出 | 用途 |
| --- | --- |
| `bindFengYuEnvironment(client, options?)` | 先应用一次当前宿主环境，再响应 `environment` 事件。在 `<html>` 上切换 `.dark` 类，并把 locale 推入 i18n 运行时。在 ready 握手**之前**订阅，3s 超时后回退默认值（无宿主的独立 `vite dev` 也能渲染）。返回取消订阅函数。 |
| `themeClass(value?)` | 把 `Environment.theme` 映射为 `'light' \| 'dark'`（设在 `<html>` 上的类）。 |
| `localeName(value?)` | 把 BCP-47 风格的 `Environment.locale` 映射为 `'en' \| 'zh'`。 |

## 模板外壳

2.x 的外壳是**无侧边栏的聚焦工作台**：宿主面板已提供返回与插件身份，插件只保留任务级 chrome——全幅内容区、可选的顶部聚焦条、可选的底部状态条。

| 组件 | 用途 |
| --- | --- |
| `PluginShell` | 工作台骨架：全高 `h-dvh` 纵向列（画布底色），逐层叠放 `PluginBar` / 页头 / 内容 / `StatusBar`。 |
| `PluginBar` | 顶部聚焦条。`tabs?: PluginBarTab[]` 把视图切换渲染为 Infinia 胶囊（白胶囊 + 金色活动丸）；用 `active` + `onNavigate` 受控，或以 `defaultActive` 自持。可选 `title`（左侧）与 `right` 槽（上下文动作/状态）。纯动作条可整个省略 `tabs`；单用途插件可省略整条。 |
| `PluginHeader` | 内容区页头行：`HexMark` 图标 + `name` + `category` 胶囊 + 等宽 `version` + `right` 动作。 |
| `StatusBar` | 底部状态条：发丝线上沿、11px 等宽字、`left` 与 `right` 槽——与主程序状态条同构。 |
| `HexMark` | 六边形插件印记（金底 + 深墨图标）：`children` 图标、`size`（默认 26）。 |
| `Chip` | 中性标签胶囊（tag 底色），用于分类/版本/次级信息。 |
| `StatusChip` | 语义状态胶囊，带真实状态圆点：`tone: 'success' \| 'warning' \| 'danger' \| 'idle'`。 |
| `GoldButton` | 主按钮：金底深墨字、按压反馈、禁用态。透传原生 button 属性。 |
| `GhostButton` | 次按钮：面板底 + 发丝线。透传原生 button 属性。 |

`PluginBarTab` 为 `{ value, title, icon? }`。脚手架应用的完整外壳如下：

```tsx
import {
  GoldButton, Page, PageHeader, PluginBar, PluginShell, StatusBar, useFengYuClient,
} from '@infinia/plugin-ui'

export default function App() {
  const client = useFengYuClient()
  return (
    <PluginShell>
      <PluginBar
        active={view}
        onNavigate={setView}
        tabs={[{ value: 'home', title: '主页' }]}
      />
      <Page>
        <PageHeader
          title="Hello, worker"
          description="UI → 宿主 RPC → worker 的最小端到端链路。"
          right={<GoldButton onClick={sayHello}>调用 hello</GoldButton>}
        />
      </Page>
      <StatusBar left={<span>worker 127.0.0.1:24057</span>} right={<span>{pluginId}</span>} />
    </PluginShell>
  )
}
```

## 页面与页头

| 组件 | 用途 |
| --- | --- |
| `Page` | 内容容器：居中列，`maxWidth` 默认 980px。编辑器/画布用 `fluid` 去掉上限；`fullHeight` 让这类页面填满视口（flex 列，滚动发生在内部）。 |
| `PageHeader` | 分区标题行：`title`、可选 `description`、尾部 `right` 动作槽。 |

## 状态组件

| 组件 | 用途 |
| --- | --- |
| `EmptyState` | 组合式空状态——`icon`、`title`、`message`、`action`：一句话与一条出路。 |
| `LoadingState` | 与内容形状一致的骨架行：`label`、`rows`（默认 3）。带实时区域语义。 |
| `ErrorState` | 内联错误面板（danger 浅底）：`title`、`message`、`onRetry` + `retryLabel`，`role="alert"`。 |
| `PermissionNotice` | 权限不足提示（warning 浅底）——选择器用它替代报错。 |
| `Progress` | 发丝线进度条：`value` 为 0–1 的比例，`status: 'determinate' \| 'indeterminate'`，可选 `label`（确定模式下未给 label 时内联显示百分比）。 |

## 选择器

| 组件 | 用途 |
| --- | --- |
| `FilePicker` | 封装 `client.files.open` 的文件选择器。`value: FileRef \| null`、`onChange`、`extensions`、`filters`、`label`、`onCancel`、`onError`。 |
| `DirectoryPicker` | 封装 `client.files.inputDirectory` 的目录选择器。`value: string \| null`（路径）、`onChange`、`label`、`onCancel`、`onError`。 |
| `isPermissionError(error)` | 从选择失败中识别权限/访问拒绝。 |

两个选择器保留 Vue 2.x 套件的行为契约：一次选择进行中会守住并发点击；宿主返回 `null` 是**正常取消**（`onChange(null)` + `onCancel`，不弹任何错误）；权限拒绝渲染 `PermissionNotice`；其他错误渲染 `ErrorState`，重试会重新发起选择。本版默认文案为简体中文——其他语言的 UI 请传入 `label`（及你自己的周边文案）。

```tsx
import { useState } from 'react'
import { FilePicker, useFengYuNotify } from '@infinia/plugin-ui'
import type { FileRef } from '@infinia/plugin-sdk'

function SourcePicker() {
  const { notify } = useFengYuNotify()
  const [file, setFile] = useState<FileRef | null>(null)
  return (
    <FilePicker
      label="选择表格"
      extensions={['xlsx', 'csv']}
      value={file}
      onChange={async (next) => {
        setFile(next)
        if (next) await notify(`已选择 ${next.name}`, { tone: 'success' })
      }}
    />
  )
}
```

## Select 与 Combobox

原生 `<select>` / `<datalist>` 的弹层无法换肤，因此套件提供自绘等价物（抬升表面上的发丝线圆角菜单、选中项金色对勾、完整键盘循环 ↑↓ / Enter / Esc、点击外部与 Escape 关闭）：

| 组件 | 用途 |
| --- | --- |
| `Select` | 受控下拉，`<select>` 的等价物：`value: string`、`options: SelectOption[]`、`onChange`、`placeholder`、`size: 'sm' \| 'md'`、`disabled`。 |
| `Combobox` | 自由输入 + 建议弹层，`<datalist>` 的等价物：输入即时过滤并立即提交（允许列表外的值）；点选建议与自由输入统一走 `onCommit(value)`。`options`/`size`/`disabled` 形状相同。 |

`SelectOption` 为 `{ value: string, label: ReactNode, disabled?: boolean }`。

## ConfirmDialog

确认优先的对话框，只有两个动词：`open`、`title`、`message`、`confirmLabel` / `cancelLabel`（默认 确认 / 取消）、`destructive`（确认钮 danger 色）、`busy`、`onConfirm`、`onCancel`，以及消息与按钮之间可选的 `children` 槽。焦点初始落在确认按钮上，Escape 取消，点击遮罩取消。

## 步骤向导

`StepWizard` 在框架无关的状态机（`src/wizard.ts`）之上渲染 Infinia 步骤轨。**快照格式与 Vue 2.x 套件完全一致**（`FY_WIZARD_SNAPSHOT_VERSION = 1`），Vue 时代插件持久化的向导进度可以原样渡过 React 迁移。

| Prop | 类型 | 说明 |
| --- | --- | --- |
| `steps` | `StepWizardStep<T>[]` | 有序的 `{ value, title, description?, optional?, validate?, render }` 条目。`validate(context, step)` 在前进前运行（同步或异步，返回 `{ valid, message? }`；抛错按 `{ valid: false, message }` 处理）；`render({ step, state, context, actions })` 返回步骤内容。 |
| `context` | `T` | 调用者持有的向导上下文（表单状态）；传给每个 `validate`/`render`。向导绝不修改它。 |
| `completed` | `boolean` | 外部完成标记（结果页）；冻结导航。 |
| `snapshot` | `FyWizardSnapshot` | 恢复种子——先在上游用 `normalizeWizardSnapshot` 规范化（比如来自 localStorage）。 |
| `onSnapshot` | `(snapshot) => void` | 每次转换后携带新快照触发；在此持久化。 |
| `labels` | `StepWizardLabels` | `next` / `back` / `finish` 按钮文字，加上 `status` 映射与格式化器覆盖（默认值见 `FY_WIZARD_DEFAULT_LABELS`）；所有可见文字都可替换。 |
| `footer` | `(props) => ReactNode` | 可选自定义页脚；收到 `busy`、`canBack`、`nextLabel`、`actions`、`completed`。默认为 上一步/下一步 + 紧凑进度。 |

六种步骤状态为 `pending`、`active`、`validating`、`complete`、`error`、`skipped`；`error` 状态可携带 `error` 消息（渲染为内联 `ErrorState`）。共享的 `actions` 对象提供 `next(): Promise<void>`、`back()`、`goTo(step)` 与 `invalidate(changedStep)`——失效会把声明顺序中 `changedStep` **之后**的所有步骤重置为 `pending`。

```tsx
import { useState } from 'react'
import { EmptyState, GoldButton, Progress, StepWizard, type StepWizardStep } from '@infinia/plugin-ui'

interface Ctx { sourceFile?: string }

export function SplitWizard() {
  const [context, setContext] = useState<Ctx>({})
  const steps: StepWizardStep<Ctx>[] = [
    {
      value: 'source',
      title: '源文件',
      validate: async () => context.sourceFile
        ? { valid: true }
        : { valid: false, message: '请先选择工作簿' },
      render: ({ actions }) => (
        <div className="grid gap-3">
          <GoldButton onClick={() => { setContext((c) => ({ ...c, sourceFile: 'ledger.xlsx' })); actions.invalidate('source') }}>
            选择工作簿
          </GoldButton>
          {context.sourceFile
            ? <Progress value={1} label="就绪" />
            : <EmptyState title="还没有工作簿" message="选择一个 .xlsx 文件继续" />}
        </div>
      ),
    },
    { value: 'mode', title: '导入模式', optional: true, render: () => <p>按工作表、列值或复杂规则……</p> },
  ]
  return (
    <StepWizard
      steps={steps}
      context={context}
      onSnapshot={(s) => localStorage.setItem('wizard', JSON.stringify(s))}
    />
  )
}
```

状态机本身也直接导出：`createWizardStates`、`invalidateWizardStates`、`buildWizardSnapshot`、`normalizeWizardSnapshot`（拒绝不支持的版本或非法步骤列表，移除未知/重复的已访问 id，修复无效的当前步骤，并把未完成工作流的当前步骤设为 `active`）、`guardWizardStepDefinitions`（重复或空白的 `steps[].value` 定义快速失败），以及各 `FyWizard*` 类型与 `FY_WIZARD_DEFAULT_LABELS`。`StepWizard` 不含任何存储 API——持久化与业务数据恢复属于消费插件。

## 通知

`mountFengYuApp` 已替你挂好 `NotifyProvider`。`useFengYuNotify()` 返回 `{ notify, dismiss, messages }`：`notify(message, { tone, timeout })` 先投递到宿主的统一通知面（应用内 toast + 原生 OS 通知 + 通知中心；无需 manifest 权限）；宿主拒绝或抛错时，消息镜像进本地兜底队列，由 `NotifyHost` 渲染在右下角。`tone` 为 `info`（默认）/ `success` / `warning` / `error`；`timeout` 默认 5s，`-1` 表示常驻直到手动关闭。绑定同一 client 的所有通知方共享一个队列，因此树中任何位置发出的通知都会到达这唯一的宿主。`sendFengYuNotification(client, message, options?)` 是非组件场景的等价物。

## i18n

把扁平键消息表传给 `mountFengYuApp`，再通过 hook 读取——一切由宿主 locale 驱动，插件永远不带语言切换器：

```tsx
import { mountFengYuApp, useFengYuI18n } from '@infinia/plugin-ui'

await mountFengYuApp({
  root: App,
  client,
  messages: {
    en: { title: 'Split complete', pick: 'Choose a file' },
    zh: { title: '拆分完成', pick: '选择文件' },
  },
})

function Header() {
  const { t, locale } = useFengYuI18n()
  return <h2>{t('title')}</h2>   // 位置参数 {0} 占位符会插入 t(key, ...args)
}
```

`createFengYuI18n(tables, fallback = 'en')` 构建运行时（目录形状与 `t()` 语义与 Vue 2.x 套件一致），`FengYuI18nProvider` 安装它，`useFengYuI18n()` 订阅并在 locale 变化时重渲染，`normalizeFengYuLocale` 把宿主 locale 映射到你的表上。详见[国际化](/zh/plugins/i18n)。

## 旧式静态插件

静态插件（纯 `ui/index.html` + `ui/app.js`、无构建步骤）**不**使用本包——它直接从 `./sdk.js` 导入 SDK。`check` 与 `build` 接受两种风格（`ui/` 目录原样打包）；`dev` 模拟器需要 Vite 的 `ui-src` 目录。把现有静态插件迁移到 React 套件是可选的；脚手架生成的布局见 [入门](/zh/plugins/getting-started)。

## 下一步

- [入门](/zh/plugins/getting-started)——create + dev + build 循环。
- [UI 微前端](/zh/plugins/ui-microfrontend)——选择器与外壳所封装的 `FengYuClient` API。
- [SDK 与 CLI](/zh/plugins/sdk-cli)——完整的 SDK + CLI 参考。
- [Aceternity UI 组件](/zh/plugins/aceternity-ui)——用 `fengyu add` 拉取带许可约束的 Aceternity 组件。
