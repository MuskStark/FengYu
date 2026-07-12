# 侧边栏「关于」按钮与对话框设计

- **日期**:2026-06-22
- **状态**:已设计,待实施
- **分支**:v3.1.0
- **关联**:无前置 spec;独立的小型 UI 功能。

## 1. 目标与约束

### 目标
在侧边栏 **Settings** 项下方新增一个 **About**(关于)按钮,点击后弹出一个模态对话框,集中展示应用元信息:版本号、编译时间、作者、仓库地址、文档地址、开源协议。

### 硬约束(已与用户确认)
1. **展示形式**:模态对话框(用户选 A),不是整页、不是滑入面板。
2. **协议取值**:以仓库根目录 `LICENSE` 文件为准 → **GNU GPL v3**。(`README.md` 的 MIT badge 与此冲突,属文档陈旧问题,**本功能范围外**,不在此次修正。)
3. **构建信息必须真实**:版本号、编译时间从构建产物中读取,不能硬编码。

### 非目标(YAGNI)
- 不做 About 内容的设置项开关。
- 不修复 README/LICENSE 协议描述不一致(单独的文档问题)。
- 不做"检查更新"或在线版本比对。
- 不为 About 单独做主题切换;复用现有 glassmorphism 样式。

## 2. 背景与现状

- **侧边栏**(`fan.summer.fengyu.ui.sidebar.Sidebar`):底部已有 `Settings` 项,通过 `addSettingsItem(...)` 加入,点击触发 `onSettingsSelect`(`Runnable`)回调。`NavItem` = 图标(MDI)+ 文本(可 i18n 绑定)+ 可选 badge。
- **Settings 的展示方式**(`MainWindow:369`):`contentArea.showPage(SwissKitJSettingUi.build(), ...)`——整页模式。About 不沿用此模式,改用模态对话框。
- **构建产物**:pom 用 `maven-shade-plugin` 打 fat JAR,`finalName=SwissKitJ-${project.version}`;`<resource>` 段存在但 **未开启 filtering**;`manifestEntries` 块为空。当前没有任何机制把版本号/编译时间注入运行时。
- **仓库元数据**(已核对):
  - 仓库地址:`https://github.com/MuskStark/SwissKitJ`
  - 文档地址:`https://muskstark.github.io/SwissKitJ/`
  - 作者:`MuskStark`(git user + 组织名)
  - 协议:`LICENSE` 文件 = GNU GPL v3
  - 版本:`3.1.0`(`SwissKit/pom.xml`)

## 3. 架构与组件清单

| 组件 | 动作 | 职责 |
|---|---|---|
| `Sidebar` | **改** | 新增 `onAboutSelect`(`Runnable`)回调 + `setOnAboutSelect` setter;在 Settings 项之后 `addAboutItem(...)` 加入一个 `NavItem`(图标 `information-outline`,i18n key `sidebar.label.about`)。点击触发 `onAboutSelect`。 |
| `MainWindow` | **改** | 在 `wireEvents` 里把 `sidebar.setOnAboutSelect(...)` 接到打开 `AboutDialog` 的逻辑(传入 owner stage)。 |
| `AboutDialog`(新) | **建** | 模态 `Stage`(`Modality.APPLICATION_MODAL`,`StageStyle.TRANSPARENT`,owner=主 stage)。Stage 的 `Scene` 为**全屏透明背景**(捕获鼠标),`.glass-dialog` 玻璃卡片在 `StackPane` 中居中——点击卡片**外**的透明区域即关闭(等效遮罩点击)。卡片内:顶部应用名 `SwissKitJ` + 版本行;中部 `Label : Value` 行列表(Repository / Documentation 两行为可点击超链接,`Desktop.browse`);右上角 `×` 关闭按钮。关闭手势:`×` / Esc / 点击卡片外透明区。静态字段(author/repo/docs/license)为类常量;版本与编译时间从 `BuildInfo` 读取。 |
| `BuildInfo`(新) | **建** | 读取 classpath 上的 `build-info.properties`,暴露 `getVersion()` / `getBuildTime()`。检测 dev-run(文件缺失或含字面量 `${`)→ 返回 `(dev)` / `(dev build)`。 |
| `build-info.properties`(新模板) | **建** | `src/main/resources/build-info.properties`,含 `app.version=${project.version}`、`build.time=${maven.build.timestamp}`,由 Maven filtering 在 `process-resources` 阶段替换。 |
| `SwissKit/pom.xml` | **改** | `<resources>` 开启 `<filtering>true</filtering>`;设置 `<maven.build.timestamp.format>yyyy-MM-dd HH:mm z</maven.build.timestamp.format>`。 |
| i18n(`messages.properties` + `messages_zh.properties`) | **改** | 新增 `sidebar.label.about` 及 6 个字段标签键(`about.field.version` / `.buildTime` / `.author` / `.repository` / `.documentation` / `.license`)。注意:本仓库无 `messages_en.properties`,英文落 `messages.properties`、中文落 `messages_zh.properties`。 |

## 4. 交互流程

```
用户点击侧边栏 About 项
  → Sidebar 触发 onAboutSelect
  → MainWindow 回调:new AboutDialog(ownerStage).show()
  → AboutDialog 加载 BuildInfo(版本/编译时间)+ 静态常量(作者/仓库/文档/协议)
  → 渲染模态对话框(玻璃态卡片,居中)
  → 用户:点击 Repository/Documentation 超链接 → Desktop.browse(url);
         点击 × / 按 Esc / 点击遮罩 → 关闭
```

对话框字段(中英对照,值见 §2):

| 标签键(英文默认) | 标签键(中文) | 值 |
|---|---|---|
| Version | 版本 | `3.1.0`(构建注入,dev 模式 `(dev)`) |
| Build Time | 编译时间 | `2026-06-22 16:50 CST`(构建注入,dev 模式 `(dev build)`) |
| Author | 作者 | MuskStark |
| Repository | 仓库地址 | https://github.com/MuskStark/SwissKitJ (超链接) |
| Documentation | 文档地址 | https://muskstark.github.io/SwissKitJ/ (超链接) |
| Open Source License | 开源协议 | GNU GPL v3 |

## 5. 构建信息机制 — BuildInfo + resource filtering

**生成**:Maven `process-resources` 阶段对 `build-info.properties` 做 filtering,把 `${project.version}` 和 `${maven.build.timestamp}` 替换为真实值。`maven.build.timestamp` 是 Maven 内置属性,格式由 `maven.build.timestamp.format` 控制。

**读取**:`BuildInfo` 用 `getClass().getResourceAsStream("/build-info.properties")` 载入 `Properties`。

**dev-run 兜底**(关键):从 IDEA 直接运行(不走 Maven filtering)时,该文件可能缺失,或 `${project.version}` 未被替换仍为字面量。`BuildInfo` 检测两种情况——
- 文件不存在 / IO 失败 → 版本=`(dev)`,编译时间=`(dev build)`。
- 读到的值含字面量 `${` → 同上视为 dev。
- 否则返回真实值。

这样保证对话框在 IDE 运行时永不崩溃、不显示丑陋的 `${...}` 占位符。

## 6. 错误处理与边界

| 场景 | 处理 |
|---|---|
| IDEA 直跑(filtering 未执行) | `BuildInfo` 返回 `(dev)` / `(dev build)`,不崩。 |
| `build-info.properties` 缺失 | 同上(视为 dev)。 |
| 用户点超链接但 `Desktop.browse` 抛异常(如无默认浏览器) | try/catch,日志记录,对话框内不弹错(静默失败可接受)。 |
| 主 stage 为 null(理论上不会) | `AboutDialog` 构造要求传入 owner,调用方保证非 null。 |
| 多次点击 About | APPLICATION_MODAL 模态天然阻塞二次点击(已打开则不重复弹;若需要,后续可单例化)。 |
| macOS/Linux/Windows 模态居中 | 用 `stage.centerOnScreen()`,跨平台一致。 |

## 7. 测试与验证

### 单元测试(确定性)`BuildInfoTest`(新建)
- filtering 已生效的 properties(写一份测试资源 `build-info.properties` 放 `src/test/resources`)→ `getVersion()` / `getBuildTime()` 返回真实值。
- properties 缺失(读不到资源)→ 返回 `(dev)` / `(dev build)`。
- 值含字面量 `${project.version}`(未 filtering)→ 返回 `(dev)` 兜底。

### 手动验证(IDEA + 打包 JAR 两轮)
1. IDEA 直跑 → 点 About → 对话框弹出,版本/编译时间显示 `(dev)` 兜底,其余字段正常,超链接可点。
2. `mvn package` 打 fat JAR → `java -jar` 运行 → 点 About → 版本=`3.1.0`、编译时间为真实构建时间。
3. 点 Repository / Documentation → 浏览器打开正确 URL。
4. × / Esc / 点遮罩三种方式都能关闭。

**通过标准**:4 项全过。

## 8. 实施顺序(给 writing-plans 的输入)

1. **构建信息管线**:`pom.xml` filtering + 时间戳格式 + `build-info.properties` 模板 + `BuildInfo` 类。第 7 节单元测试先行。
2. **对话框**:`AboutDialog`(布局 + 超链接 + 关闭手势)。复用 `.glass-dialog`。
3. **i18n 键**:两个 properties 文件。
4. **接线**:`Sidebar` 加 About 项 + 回调;`MainWindow` 接到 `AboutDialog.show()`。
5. **手动验证**:IDEA 直跑 + 打包 JAR 两轮。

每步独立可编译可测。

## 9. 已知遗留(范围外)

- `README.md` 的 MIT badge / "## License" 段与 `LICENSE`(GPL v3)不一致——单独的文档修复,不属本功能。
- `README.md` 的 Java badge 写 "Java-17",实际为 Java 21——同上,文档遗留。
