# UI 设计文档集 — 设计 Spec

- **日期**：2026-07-01
- **分支**：v3.2.0
- **状态**：已批准，待写实现计划
- **关联**：`2026-06-30-idea-new-ui-redesign-design.md`（权威 New UI 设计基准，本 spec 引用之）

## 1. 目标

为 SwissKitJ（JavaFX 21 桌面工具箱）编写一套 UI 设计系统文档，满足三个要求：

1. **设计 + 实现并重**：既讲设计原则/令牌/视觉规格，也讲 JavaFX 代码实现。
2. **能指导 AI 正确开发 UI**：文档要精确到可直接引用代码（令牌名、CSS class、JavaFX 类名/包路径），每个组件给可复用代码模板、强制检查清单、反模式，AI 按文档产出能与现有代码无缝集成。
3. **双语**：英文（`docs/ui-design/`）+ 中文镜像（`docs/zh/ui-design/`）。

## 2. 范围与排除

### 包含
基于 JetBrains IDEA 2025 New UI 设计语言，结合项目现有 UI 实现，编写 8 份文档（原目录 01-08，**跳过 09 OfflinePython**）。

### 明确排除
- **09 OfflinePython UI Specification**：代码库无任何 Python 模块（仅有的离线功能是 Qwen3-4B 本地 AI 大模型，与 Python 无关）。按用户指示"不要理会这个文档"。

## 3. 文档清单与组织

**方案 A（原始 8 份目录，用户最终选定）**。位置：独立 `ui-design/` 目录（英文）+ `zh/ui-design/`（中文镜像）。

```
docs/ui-design/                       docs/zh/ui-design/
├── README.md            (索引页)     ├── README.md
├── 01-design-system.md               ├── 01-design-system.md
├── 02-javafx-implementation.md       ├── 02-javafx-implementation.md
├── 03-component-library.md           ├── 03-component-library.md
├── 04-interaction-guidelines.md      ├── 04-interaction-guidelines.md
├── 05-theme-color-system.md          ├── 05-theme-color-system.md
├── 06-icon-system.md                 ├── 06-icon-system.md
├── 07-animation-guidelines.md        ├── 07-animation-guidelines.md
└── 08-accessibility-guide.md         ├── 08-accessibility-guide.md
```

粒度：**深度单文件**，每份 800–2500 行 Markdown，内容详尽（令牌表、代码模板、示意图、AI 清单齐全）。docsify 无 mermaid，图表统一用 **ASCII art** + **markdown 表格** + **fenced code blocks**（与现有 `architecture.md` 风格一致）。

## 4. 跨文档约定

### 4.1 "单一事实来源 + 交叉链接"（避免重复）

| 内容 | 唯一定义处 | 其他文档处理方式 |
|------|-----------|-----------------|
| 设计令牌（`-sk-*` 颜色精确值） | **05** | 01 给哲学；02/03 引用 class 名；精确值链接 05 |
| CSS class 命名规范 | **02** | 03 每个组件列自己用的 class，命名规则链接 02 |
| 组件视觉/交互 | **03** | 04 引用组件名 + 链接 03 |
| 动效时长/曲线 | **07** | 03/04 引用 token 名 + 链接 07 |
| 图标使用 | **06** | 03 组件的图标示例链接 06 |
| 对比度达标组合 | **05 + 08** | 05 给令牌对比矩阵，08 给可访问性要求 |

### 4.2 每份文档统一 7 段结构（保证 AI 可消费性）

每份文档遵循固定骨架，AI 可靠解析：

1. **概览** — 一句话定位 + 适用场景
2. **设计原则** — 3-5 条不可妥协的规则（该做/不该做）
3. **规范细则** — 令牌表 / class 表 / 尺寸表（精确值）
4. **JavaFX 实现模板** — 可直接复制的代码（标注类名、包路径）
5. **AI 开发清单** — "生成此组件时必须满足 N 条"清单
6. **反模式** — 常见错误 + 修正
7. **参考** — 对应源码文件路径、关联文档链接

## 5. 各文档内容大纲

### 01 UI Design System（全局设计语言）
- 设计哲学 4 原则：功能性优先 / 克制的 IDEA New UI 美学 / 深浅双主题一致 / 插件原生融合
- 设计语言参考系：JetBrains IDEA 2025 New UI 核心特征（扁平、不透明、中性灰主导、accent `#3574F0`、选中态=中性灰底+左侧 3px 强调色条而非蓝色填充）
- 全局布局架构（OS 原生标题栏 → 侧边栏+内容区 → 状态栏）+ ASCII 架构图
- 排版尺度（字体栈、13px 基准、字号/行高阶梯）
- 间距栅格（4px 基准、间距阶梯表）
- 圆角规范（capsule 胶囊形 vs 6-8px 标准圆角的应用场景）
- 信息层级与视觉权重（文本层级、克制使用阴影）
- 与 IDEA New UI 的差异点（SwissKit 特有的工具卡片网格、侧边栏分组）

### 02 JavaFX Implementation Guide（开发实现规范）
- 技术栈前提（JavaFX 21、纯代码构建 UI 无 FXML、CSS 主题化）
- `SwissKitJPlugin` 接口契约逐方法详解（`createView` 缓存、生命周期钩子、`aiTools`、`getMdiIcon`/`getIconStyle`/`getCategory`）
- 插件 UI 完整可运行模板（骨架代码，AI 直接套用）
- CSS 加载机制：`ThemeService.registerScene(scene)` 自动加载 `swisskit-common.css`，插件不重复加载
- CSS class 命名规范（`sk-` 前缀、`.glass-*`→`.sk-*` 废弃迁移映射、BEM-lite）
- 主题切换 API：`ThemeService.set(Theme)` / `onChange()` / WebView 同步（`MarkdownRenderer` 模式）
- **关键反模式**：`setStyle()` 内联颜色不响应主题 → 必须用 `.sk-t1/.sk-fill-2` 等 class
- 布局容器选型（GridPane/VBox/HBox/BorderPane/FlowPane 何时用，配示例）
- 内联尺寸 vs 令牌：尺寸/内边距可内联，颜色必须走 class/令牌
- 国际化（`I18n`、`messages.properties` 键命名）
- 打包与资源隔离要点

### 03 Component Library（组件库）
**基础组件**（来自 `swisskit-common.css`）：按钮（`.sk-btn-primary`/`.sk-btn`/`.sk-btn-danger`）、输入框（`.sk-text-field`/`.sk-text-area`，focus 用 `-sk-border-strong`）、下拉/选择、复选框/单选/开关、表格（`.sk-table`，表头/斑马纹/hover）、标签页（`.sk-tab`）、对话框（`.sk-dialog`，模态遮罩）、滚动条、通知/Toast（`.sk-notification`）、标签/徽章（`.sk-badge`，success/warning/danger）。

**外壳组件**（来自 `shell.css`，**按完整规范详写**）：侧边栏导航项 `NavItem`、搜索栏（capsule，`⌘K`）、工具卡片 `ToolCard`（图标+名称+描述+分类色）、详情面板 `DetailPanel`（右侧滑入）、状态栏 `StatusBar`（脉冲点+计数+时钟）。

每个组件给：用途 / 何时用 / 视觉规格表（含令牌引用）/ 交互态（default-hover-pressed-disabled-selected）/ JavaFX 代码模板 / AI 清单 / 反模式。

### 04 Interaction Guidelines（交互指南）
- 导航交互（侧边栏展开/收起、分类切换、收藏、主题切换入口）
- 工具发现流程（搜索→卡片 hover→详情面板→启动）
- 搜索行为（关键词过滤、空状态、清除）
- 启动与页面切换（`showPage` 交叉淡入、视图缓存）
- 插件生命周期交互（激活/停用/卸载的确认与反馈）
- 插件商店交互（安装、进度、本地已装切换）
- 表单交互（校验时机、错误提示位置、提交反馈）
- 键盘快捷键体系（`⌘K`、Esc、Tab 焦点顺序）
- 反馈与状态（loading/空/错误/成功四态呈现时机与组件选择）
- 防误操作（破坏性操作二次确认、不可逆操作文案）

### 05 Theme & Color System（主题与配色）— 令牌唯一权威定义处
- 主题架构（`ThemeService`、looked-up color 机制、`.theme-dark`/`.theme-light` 切换、无闪烁）
- **完整令牌表**（dark/light 双值，十六进制精确值）：`-sk-bg`/`-sk-bg-elevated`/`-sk-bg-hover`/`-sk-bg-selected`/`-sk-border`/`-sk-border-strong`/`-sk-text`/`-sk-text-secondary`/`-sk-text-disabled`/`-sk-accent`/`-sk-accent-soft`/`-sk-success`/`-sk-warning`/`-sk-danger`
- 语义色使用规则（accent 仅用于关键操作与选中指示；status 色严格语义化）
- 中性灰优先原则
- 颜色对比度矩阵（文本/背景组合 WCAG 达标，衔接 08）
- 主题持久化（`"theme"` DB key、启动读取）
- WebView 主题同步（`MarkdownRenderer` 的 dark/light CSS 与 JavaFX 令牌对应）
- 扩展指南（新增语义令牌、第三方插件引用令牌）

### 06 Icon System（图标系统）
- 图标库选型（Material Design Icons webfont、`mdi-codemap.properties`）
- 图标 API：`MdiIconUtil.createIcon(name, size, fillStyle)`
- 尺寸阶梯（16/20/24/32px 及场景）
- 颜色规则（默认跟随 `-sk-text`；强调用 `IconStyle` 7 色：BLUE/PURPLE/TEAL/AMBER/RED/PINK/GRAY → `.ic-*` class + DropShadow 辉光）
- 工具图标规范（`getMdiIcon()`/`getIconStyle()`、卡片图标渲染流程）
- 一致性规则（线/填充风格统一、视觉重量平衡）
- 命名查找（mdi-codemap 查图标名）
- 反模式（混用图标库、emoji 代替图标、图标当装饰）

### 07 Animation Guidelines（动效规范）
- 动效原则（克制、服务于反馈、不阻塞）
- 时长令牌（fast 150ms / normal 250ms / slow 400ms）
- 缓动曲线（`Interpolator` 选型、ease-out 主导）
- **现有动效清单**（基于真实代码）：主窗口入场 250ms 淡入、工具卡片交错入场、页面切换交叉淡入、详情面板滑入/滑出、侧边栏展开/收起、按钮 hover/pressed 过渡、状态栏脉冲点、主题切换（瞬时切 class，无渐变）
- **建议但尚未实现的标准动效**（作为未来规范）：列表项 reorder、对话框入场缩放淡入、Toast 滑入淡出、复选框勾选过渡、loading spinner、焦点环淡入等
- 何时不要动画（数据加载、文本输入、主题切换）
- 性能边界（动画对象限制、避免布局抖动）
- 每个动效给 JavaFX 代码模板（`Timeline`/`FadeTransition`/`TranslateTransition`）

### 08 Accessibility Guide（可访问性规范）
- 无障碍原则（感知/操作/理解/健壮）
- 颜色对比度要求（衔接 05：文本 ≥4.5:1，大文本 ≥3:1，达标组合清单）
- 不仅靠颜色传达信息（状态=图标+文字+颜色）
- 键盘可操作性（全键盘可达、可见焦点环、逻辑 Tab 顺序、`⌘K`/Esc）
- 焦点管理（对话框打开聚焦首个控件、关闭还焦、面板切换焦点）
- 文本可读性（字号、行高、避免全大写、文本层级）
- 动效与减弱动效（JavaFX 无媒体查询的降级策略）
- 屏幕阅读器（`AccessibleRole`/`accessibleText`）
- i18n 与可访问性（文案长度变化不破坏布局）
- AI 无障碍清单（生成组件强制检查项）

## 6. 与现有代码的锚点

每份文档锚定真实代码，确保 AI 按文档产出能与现有代码集成：

| 锚点 | 文件 |
|------|------|
| 令牌定义 | `SwissKitJ-Api/src/main/resources/css/swisskit-common.css`（第 17-48 行） |
| 主题 API | `SwissKitJ-Api/.../api/theme/ThemeService.java`、`Themes.java` |
| 插件契约 | `SwissKitJ-Api/.../api/SwissKitJPlugin.java` |
| 分类/图标样式 | `SwissKitJ-Api/.../api/ToolCategory.java`、`IconStyle.java`、`ToolType.java` |
| 外壳 CSS | `SwissKit/src/main/resources/css/shell.css` |
| 内置工具 CSS | `SwissKit/src/main/resources/css/builtin.css` |
| 预览窗口 CSS | `SwissKitJ-Api/src/main/resources/css/swisskit-preview.css` |
| 主窗口布局 | `SwissKit/.../app/SwissKitJApp.java`、`ui/MainWindow.java` |
| 侧边栏/内容 | `SwissKit/.../ui/sidebar/Sidebar.java`、`ui/content/ContentArea.java`、`ToolCard.java`、`DetailPanel.java` |
| 内置工具注册 | `SwissKit/.../registrar/BuiltinToolRegistrar.java`（11 个内置工具） |
| 图标工具 | `MdiIconUtil`、`SwissKit/src/main/resources/fonts/`、`mdi-codemap.properties` |
| WebView 主题 | `SwissKit/.../ai/util/MarkdownRenderer.java` |
| 设计基准 | `docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`（权威引用） |

## 7. README 索引页与导航

- `docs/ui-design/README.md` 与 `docs/zh/ui-design/README.md`：8 份文档的索引页（标题、一句话描述、链接表格）。
- 更新 `docs/_sidebar.md` 与 `docs/zh/_sidebar.md`：在现有导航中加入 "UI Design" 分组，链接到 `ui-design/`（英文）与 `ui-design/`（中文）。

## 8. 验收标准

每份文档需满足：
1. 含统一的 7 段结构。
2. 所有令牌/class/类名/包路径**与真实代码一致**（写完后需对照源码核验）。
3. 每个组件/规范有**可直接复制的 JavaFX 代码模板**。
4. 每个组件有**AI 开发清单**（N 条强制检查项）。
5. 含**反模式**（常见错误 + 修正）。
6. 双语两版内容对应（英文版先行，中文版镜像）。
7. ASCII 图表渲染正常，无 mermaid 依赖。

## 9. 实施顺序（供 writing-plans 拆解）

建议按依赖顺序写（令牌与基础设施先行）：
1. **05 Theme & Color System**（令牌权威定义，其他文档引用它）
2. **02 JavaFX Implementation Guide**（契约与命名规范）
3. **06 Icon System**（被 03 引用）
4. **03 Component Library**（依赖 02/05/06）
5. **01 UI Design System**（全局哲学，引用上述）
6. **07 Animation Guidelines**
7. **04 Interaction Guidelines**
8. **08 Accessibility Guide**
9. **README 索引页 + _sidebar 导航更新**

每份先写英文版，核验代码一致性后，再产出中文镜像版。
