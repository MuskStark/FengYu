# SwissKitJ → JetBrains IDEA 2025 New UI 改造设计

- **日期**: 2026-06-30
- **状态**: 已确认,待编写实施计划
- **目标版本**: `3.2.0`(minor bump)
- **分支**: `v3.2.0`(从 `main` @ `33ffc14` 分出)

> **⚠️ 版本号备注**:CSS 类名重命名(`.glass-*` → `.sk-*`)对外部插件为破坏性变更,
> 按 SemVer 惯例应 major bump。本仓选择 `3.2.0`(minor),理由:SwissKitJ-Api 的
> Java 插件接口未变,仅 CSS 类(软契约)变更,且官方插件仓库由本仓同步维护。
> 迁移表见 §7,务必在 CHANGELOG 顶部显式标注为破坏性变更。

## 1. 目标

将 SwissKitJ 主线 UI 从当前的「毛玻璃深色主题(glassmorphism dark)」改造为
**JetBrains IDEA 2025 New UI** 设计语言,并支持深/浅色主题切换。

### 范围内
- 完整 New UI 视觉规范:扁平、不透明、中性灰色板、`#3574F0` 强调色
- 深色 / 浅色双主题,可切换、持久化
- 侧栏可折叠(标签列表 ⇄ 图标条)
- 原生窗口装饰(弃用自绘窗口按钮)
- 所有共性组件(`.glass-*`)与外壳组件统一重制
- AI Chat 的 WebView 出浅色版本

### 非目标(YAGNI)
- 跟随系统主题(JavaFX 无可靠系统外观探测 API,v1 仅手动切换)
- Compact / Default 密度切换(v1 只做 Default)
- 自绘 IDEA 风格窗口按钮(已明确弃用)
- 插件市场 / Settings / About 之外的新功能

## 2. 已确认决策

| # | 决策 | 选择 |
|---|---|---|
| 1 | 视觉方向 | 完整 IDEA New UI(弃毛玻璃) |
| 2 | 主题 | 深色 + 浅色,可切换,**默认深色** |
| 3 | 侧栏 | 可折叠(标签 ⇄ 图标条) |
| 4 | 窗口按钮 | 原生装饰(`StageStyle.DECORATED`),不自实现 |
| 5 | 共性类名 | **重命名** `.glass-*` → `.sk-*`(破坏性) |
| 6 | 密度 | 仅 Default |
| 7 | 实施节奏 | 一次性完成全部步骤 |

## 3. 主题系统(技术核心)

### 3.1 机制:Looked-up Color

所有颜色定义为 JavaFX 查找色(looked-up color),命名空间统一加 `-sk-` 前缀避免与
`-fx-*` 及第三方插件变量冲突。值在场景根节点上声明;`.theme-dark` / `.theme-light`
两个 style class 各定义一套。切换主题 = 替换根节点上的 class,JavaFX 自动重算所有
引用,无需重载样式表、无闪烁。

### 3.2 `ThemeService`(API 模块)

扩展现有 `fan.summer.zhiflow.api.theme.Themes`。API 模块**不直接依赖数据库**;持久化由宿主
负责,`ThemeService` 只持有内存态 + 监听器:

```java
public final class ThemeService {
    public enum Theme { DARK, LIGHT }

    static Theme current = Theme.DARK;                 // 默认深色
    static final List<Consumer<Theme>> listeners = new CopyOnWriteArrayList<>();
    static final List<Scene> scenes = new CopyOnWriteArrayList<>();

    public static Theme current() { return current; }
    public static void set(Theme t);                   // 切换:更新所有已注册 Scene 的 class + 通知监听器
    public static void registerScene(Scene s);         // 把当前主题 class 盖到 s 上(替代旧 Themes.applyTo)
    public static void onChange(Consumer<Theme> c);    // WebView / 插件独立 Stage 据此重渲染
}
```

`Themes.applyTo(scene)` 改为委托 `ThemeService.registerScene(scene)`(保留旧静态方法
签名,内部转发,避免破坏现有插件调用)。

### 3.3 持久化(宿主侧)

复用现有语言持久化模式(`AppSettingMapper.selectByKey / upsert`):
- key = `"theme"`,value = `"dark"` / `"light"`
- `SwissKitJApp.start()` 在加载语言之后读取 theme,调用 `ThemeService.set(...)`
- 主题切换 UI 触发时由宿主写回 DB

## 4. 配色 Token 表

| Token | 深色 `.theme-dark` | 浅色 `.theme-light` | 用途 |
|---|---|---|---|
| `-sk-bg` | `#1E1E1E` | `#FFFFFF` | 主背景 |
| `-sk-bg-elevated` | `#2B2B2B` | `#F7F8FA` | 面板 / 侧栏 / 卡片 |
| `-sk-bg-hover` | `#363636` | `#EBECEF` | 悬停 |
| `-sk-bg-selected` | `#393B40` | `#DFE1E5` | 选中项中性灰高亮 |
| `-sk-border` | `#3C3F41` | `#DADCE0` | 普通边框 |
| `-sk-border-strong` | `#555555` | `#C9CDD3` | 聚焦 / 强调边框 |
| `-sk-text` | `#D0D0D0` | `#1E1E1E` | 主文字 |
| `-sk-text-secondary` | `#9AA0A6` | `#5A5D60` | 次文字 |
| `-sk-text-disabled` | `#6B6F73` | `#A0A4A8` | 禁用 |
| `-sk-accent` | `#3574F0` | `#3574F0` | 强调色(替换 `#5b8cf7`) |
| `-sk-accent-soft` | `rgba(53,116,240,.18)` | `rgba(53,116,240,.14)` | 选中底色着色 |
| `-sk-success` | `#5BB065` | `#3C914A` | 成功 |
| `-sk-warning` | `#F0A732` | `#C2751C` | 警告 |
| `-sk-danger` | `#F75464` | `#E53935` | 危险 |

**关键视觉变化**:选中项用 `-sk-bg-selected`(中性灰)+ 左侧 `-sk-accent` 细条指示,
不再用蓝色填充。这是 New UI 与现状最大的外观差异。

## 5. 窗口架构变更

| 文件 / 位置 | 现状 | 改造 |
|---|---|---|
| `SwissKitJApp.java:124` | `stage.initStyle(StageStyle.TRANSPARENT)` | `StageStyle.DECORATED` |
| `SwissKitJApp.java:110` | `scene.setFill(Color.TRANSPARENT)` | 删除(不透明) |
| `SwissKitJApp.java:133` | `WindowResizeHelper.attach(stage)` | 删除(原生接管 resize/drag) |
| `ui/util/WindowResizeHelper.java` | 自绘边/角拖拽 | **删除整个文件** |
| `ui/titlebar/TitleBar.java` | 红绿灯 + 标题 + 设置入口 | **删除**(原生标题栏替代);设置/关于/主题切换移入侧栏底部 |
| `MainWindow.buildScene()` | orbLayer + 圆角裁剪 + 顶高光 | 删除 orb 层、`setClip` 圆角、`topHighlight`;面板改不透明 |

**收益**:顺带消除 CLAUDE.md 中记录的 macOS `StageStyle.TRANSPARENT` + `isMaximized()`
撒谎的坑(JavaFX 布局陷阱 #6)。

**代价**:窗口失去 20px 圆角与半透明,变成标准直角矩形 —— 符合 IDEA 外观预期。

## 6. 布局与组件重制

```
┌─ OS 原生标题栏(关/最大/最小 + "SwissKitJ")──────────────────┐
├──────┬──────────────────────────────────────────────────────┤
│ 侧栏  │  内容区                                                │
│ 可折叠│  ┌ 搜索框(IDEA Search Everywhere 胶囊形)──────────┐ │
│ 标签⇄│  │                                                    │ │
│ 图标  │  │   [工具卡片网格 — 扁平、克制 hover]                │ │
│ ─────│  └────────────────────────────────────────────────────┘ │
│ 设置  │                                                        │
│ 关于  │                                                        │
│ ☀ / ☾ │                                                        │
├──────┴────────────────────────────────────────────────────────┤
│ 状态栏(精简,中性灰)                                          │
└────────────────────────────────────────────────────────────────┘
```

### 6.1 侧栏(`Sidebar.java` + `shell.css .sidebar/.nav-item`)
- 默认标签列表模式(~200px,图标 + 文字,保留分区标题),扁平项,选中项中性灰高亮
  + 左侧 3px `-sk-accent` 细条
- 顶部 « 按钮折叠为 46px 图标条模式(纯图标,选中项同样灰底 + 细条);AI 项用醒目方块
- 折叠状态持久化(DB key `sidebar.collapsed`)
- 底部固定:设置 / 关于 / **主题切换 ☀☾**

### 6.2 搜索框(已在 `ContentArea.java:277`,改 `.search-bar` 样式)
- 改为 IDEA Search Everywhere 胶囊形,聚焦时 `-sk-accent` 细边

### 6.3 工具卡片(`shell.css .tool-card`)
- 扁平,`-sk-bg-elevated` + `-sk-border` 细边
- hover 仅轻微背景变化,**移除现有 `-fx-translate-y: -2px` 上浮与大阴影**

### 6.4 其他外壳组件
- `.detail-panel`:扁平面板,中性边框
- `.statusbar`:精简,`-sk-text-secondary`
- `.store-*`:全部改用 token

### 6.5 动画
- 删除 `MainWindow.buildOrbLayer()`(光球)与 `playEntryAnimation()` 的缩放/位移动画
- 仅保留极轻微淡入(可选)

## 7. 共性类重命名 `.glass-*` → `.sk-*`(破坏性)

### 映射
| 旧 | 新 |
|---|---|
| `.glass-dialog` | `.sk-dialog` |
| `.glass-field` / `.glass-field-label` | `.sk-field` / `.sk-field-label` |
| `.glass-tab-pane` | `.sk-tab-pane` |
| `.glass-combo` | `.sk-combo` |
| `.glass-table` | `.sk-table` |
| `.glass-checkbox` | `.sk-checkbox` |
| `.glass-btn-primary` / `.glass-btn-secondary` | `.sk-btn-primary` / `.sk-btn-secondary` |
| `.glass-notif-*` | `.sk-notif-*` |

### 影响范围(需同步改 `getStyleClass().add("glass-...")`)
- `SwissKitJ-Api`:`StepWizard` 及 API 模块内其余引用(实施时全量 grep)
- `SwissKit`:`EmailPlugin`、`PdfToolPlugin`、`OnlineStorePane`、`AboutDialog`、
  `SwissKitJSettingUi`(重度使用)、`builtin.css`
- **外部插件仓库** `MuskStark/SwissKiJ-Plugin`:所有官方插件需同步重命名,随 v3.2.0
  一起发布;在 CHANGELOG / 迁移指南中显式声明此破坏性变更

> 类的样式定义本身也全部改用 token,扁平 IDEA 风格(详见第 4 节)。

## 8. WebView 主题(AI Chat)

- 现有 AI Markdown 渲染为 WebView 深色 `#1e1e2e`;新增浅色 CSS 变体
- `ThemeService.onChange(theme -> aiChatView 重新生成 HTML 并套用对应 CSS)`
- `<html>` 根注入 `class="dark"` / `class="light"`,WebView 内 CSS 据此切换

## 9. 默认值汇总

| 项 | 值 |
|---|---|
| 强调色 | `#3574F0`(全局替换 `#5b8cf7`,含 Java 代码中 `Color.web("#5b8cf7")` / `setStyle` 内联) |
| 默认主题 | 深色,记忆上次选择 |
| 密度 | IDEA Default |
| 动画 | 极简淡入 |

> 注意:`#5b8cf7` / `rgba(91,140,247,*)` 不仅在 CSS 中,也散落在 `MainWindow`、
> `Sidebar.NavItem.setActive()` 等 Java 内联 style 里,需一并清理。

## 10. 实施顺序

1. **主题基建**:`ThemeService` + token 定义(`.theme-dark` / `.theme-light` on `.root`)。
   先让深色与现状视觉一致,证明切换机制不破坏现有外观。
2. **`zhiflow-common.css` 全量换 token + 重命名 `.glass-*` → `.sk-*`**(含扁平化重制)。
   同步改 API 模块 Java 引用。
3. **`shell.css` 换 token + New UI 组件外观**:侧栏折叠、卡片、搜索框、状态栏、detail-panel、store。
   同步改 `SwissKit` 模块 Java 引用 + 内联 `#5b8cf7` 清理。
4. **窗口架构**:`StageStyle.DECORATED`、删 `WindowResizeHelper`、删 `TitleBar`、删 orb 层与圆角裁剪。
5. **主题切换 UI**:侧栏底部 ☀☾ 按钮 + Settings 页入口;宿主读写 DB 持久化;
   `ThemeService.registerScene` 在启动与新建窗口时调用。
6. **WebView 浅色主题** + `onChange` 接线。
7. **内置页面收尾**:Plugin Store / Settings / About 套用 token,整体回归。

## 11. 验证

- 深 / 浅主题切换:所有组件(含弹窗、下拉、表格、复选框、通知)两套下均无残留硬编码色
- 侧栏折叠 ↔ 展开:状态持久化,选中项指示正确
- 原生窗口:关/最大/最小、resize、拖拽、最大化还原均正常(macOS + Windows)
- 全文搜索 `#5b8cf7`、`91,140,247`、`glass-` 在两模块中无残留(external plugin repo 单独验证)
- AI Chat 在两主题下 Markdown 渲染正确
- 现有插件(官方 repo 更新后)加载后样式正常

## 12. 风险

| 风险 | 缓解 |
|---|---|
| 外部插件使用 `.glass-*`,升级 v3.2.0 后样式失效 | 随主版本发布更新官方插件;CHANGELOG 显式标注破坏性变更与迁移表 |
| token 替换遗漏导致单主题下残色 | 实施步骤 1 先证深色与现状一致;切换后全文 grep 校验 |
| 原生标题栏在 macOS 与深色内容视觉断层 | 接受(System 原生外观,符合 IDEA 行为);必要时 `stage` 用户偏好 |
| 删除 `WindowResizeHelper` 后窗口最小尺寸行为变化 | 保留 `setMinWidth/MinHeight`,原生尊重之 |
