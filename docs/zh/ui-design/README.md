# ZhiFlow UI 设计系统

ZhiFlow 的用户界面是对 **JetBrains IntelliJ IDEA 2025 "New UI"** 视觉语言的忠实实现——中性
灰的表面、克制的单一强调色、扁平的形状，以及服务于反馈而非炫技的动效。本套文档是该系统的
完整、与代码精确对应的参考，写作目的就是让 AI（或从未见过代码库的人）也能生成与 v3.2.0 外壳
一致的 UI。

它是**双语的**（本中文树与 [`/ui-design/`](/ui-design/) 结构完全对应），且**以代码为锚**——
文中引用的每一个类名、token 值、JavaFX 签名，都能在真实的 ZhiFlow 源码中核实。

## 8 篇文档

| # | 文档 | 用途 | 适合谁读 |
|---|---|---|---|
| **01** | [UI 设计系统](01-design-system.md) | 哲学、布局原语、字号/间距/圆角刻度，以及 ZhiFlow 如何扩展 IDEA New UI。 | 想了解"为什么"——从这里开始。 |
| **02** | [JavaFX 实现](02-javafx-implementation.md) | 开发手册：`SwissKitJPlugin` 契约、CSS 命名约定、插件骨架。 | 准备写插件 UI。 |
| **03** | [组件库](03-component-library.md) | 每个 `.sk-*` 基础类、每个外壳组件（nav-item、tool-card…）的逐组件规格。 | 需要某个具体控件。 |
| **04** | [交互指南](04-interaction-guidelines.md) | 导航、发现/启动、插件生命周期、四态反馈、破坏性确认流程。 | 在接线用户操作。 |
| **05** | [主题与色彩系统](05-theme-color-system.md) | 全部 `-sk-*` 颜色 token 的唯一事实源 + WCAG 对比度矩阵。 | 需要精确颜色取值。 |
| **06** | [图标系统](06-icon-system.md) | MDI 图标库、`MdiIconUtil` API、尺寸刻度、`IconStyle` 强调色。 | 在放置图标。 |
| **07** | [动效指南](07-animation-guidelines.md) | 每一个出厂动画的时长/缓动 + `file:line` + 可复制模板。 | 在添加动效。 |
| **08** | [无障碍指南](08-accessibility-guide.md) | 对比度规则、"不仅靠颜色"、键盘可操作性、减弱动效。 | 必须验证 UI 对所有人都可用。 |

## 如何使用这些文档

**按目标：**

- **"我想理解设计哲学"** → 读 [01](01-design-system.md)。
- **"我在写插件 UI"** → [02](02-javafx-implementation.md)（手册）+ [05](05-theme-color-system.md)（颜色）。
- **"我需要某个具体组件"** → 在 [03](03-component-library.md) 里查。
- **"`-sk-bg-elevated` 的精确值是多少？"** → [05](05-theme-color-system.md#token-reference-table)。
- **"这个动画该多长？"** → [07](07-animation-guidelines.md#时长刻度)。
- **"这组颜色过 WCAG AA 吗？"** → [05 对比度矩阵](05-theme-color-system.md#contrast-matrix-wcag-aa) + [08](08-accessibility-guide.md)。

### 单一事实源约定

每一类事实只住在一篇文档里，其它地方都*链接*过去——这套文档从不在两处复述同一个值：

| 事实 | 住在 |
|---|---|
| Token 十六进制值与对比度比 | [05](05-theme-color-system.md#token-reference-table) |
| CSS 类名与命名约定 | [02](02-javafx-implementation.md#css-naming) / [03](03-component-library.md) |
| 图标名、尺寸、`IconStyle` 颜色 | [06](06-icon-system.md#icon-reference) |
| 动画时长与缓动 | [07](07-animation-guidelines.md) |
| 组件 CSS 与状态 | [03](03-component-library.md) |

如果你发现文档与源码不一致，**以源码为准**——提 issue 或开 PR。
