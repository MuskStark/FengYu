# 线上插件商店 UI 重构 — 设计文档

**日期**: 2026-06-09
**范围**: `OnlineStorePane` 主体重构 + 搜索/分类筛选 + 安装状态 + 样式归位 CSS。
**约束**: 不修改远程清单 JSON 格式;不修改本地安装(`LocalInstallPane`)与下载核心逻辑;仅使用现有字段 `id / name / description / version / jarUrl / iconStyle / category`。

---

## 1. 目标与动机

当前 `OnlineStorePane` 是纯文字的竖向单列卡片列表,存在四个痛点:

1. **找不到想要的插件** — 没有搜索框,没有分类筛选,插件多了只能从头滚到尾。
2. **卡片信息太单薄** — 只有文字,没有图标,难以快速辨识。
3. **布局不美观 / 拥挤** — 单列列表空间利用率低,视觉层次弱。
4. **看不出安装状态** — 不知道哪些已安装、哪些有更新、哪些是新插件。

重构目标:在**不改远程清单格式**的前提下,做出一个便于浏览的卡片网格商店,带搜索、分类筛选和安装状态。

---

## 2. 整体布局

`OnlineStorePane`(仍继承 `VBox`)重构为三层结构:

```
┌──────────────────────────────────────────────────┐
│ 标题「插件商店」 + 副标题                            │
├──────────────────────────────────────────────────┤
│ [🔍 搜索框 .............................] [分类▾] [↻]│  ← 工具栏(一行 HBox)
├──────────────────────────────────────────────────┤
│ ScrollPane (fitToWidth)                             │
│   FlowPane(自适应换行,卡片固定宽 ~260px)            │
│     ▢ ▢ ▢                                           │
│     ▢ ▢ ▢                                           │
├──────────────────────────────────────────────────┤
│ 状态栏: 共 N 个插件 · 已安装 M / 加载中 / 出错 / 空    │
└──────────────────────────────────────────────────┘
```

- **工具栏**(单行 `HBox`,`alignment = CENTER_LEFT`,spacing ~10):
  - 搜索框 `TextField`:`setMaxWidth(Double.MAX_VALUE)` + `HBox.setHgrow(field, ALWAYS)`,占满剩余宽度。
  - 分类下拉 `ComboBox<ToolCategory>`(右侧):选项为「全部 + 五个分类」。
  - 刷新按钮 `Button`(最右),保留现有刷新逻辑。
- **主体**:`FlowPane` 包在 `ScrollPane` 里。
  - `ScrollPane.setFitToWidth(true)`;`setMaxWidth(Double.MAX_VALUE)` + `setMaxHeight(Double.MAX_VALUE)`(遵守 CLAUDE.md StackPane/ScrollPane 陷阱)。
  - `FlowPane` 设置 `hgap/vgap`(~12),卡片宽度固定,窗口越宽每行卡片越多(自适应 2–3 列)。
  - **禁止** 对任何控件使用 `setPrefWidth(Double.MAX_VALUE)`。

### 布局陷阱检查(CLAUDE.md)
- [x] `ScrollPane` 在容器内 → `setMaxWidth/Height(Double.MAX_VALUE)`。
- [x] 搜索框「填满剩余」→ `setMaxWidth(MAX_VALUE)` + `HBox.setHgrow(ALWAYS)`,不用 `prefWidth=MAX_VALUE`。
- [x] 描述 `Label` 换行 → `setWrapText(true)` + `setMaxWidth(MAX_VALUE)`,不绑定 `maxWidthProperty` 到自身 `widthProperty`。

---

## 3. 卡片设计

每张卡片为一个 `VBox`,固定宽度 ~260px:

```
┌──────────────────────────┐
│ ⬛E  Excel 拆分器          │   ← 色块(首字母) + 名称
│      v2.1.0 · [开发]      │   ← 版本徽章 + 分类徽章
│  按工作表或列值快速拆分…   │   ← 描述(换行,最多 2 行)
│  ┌────────────────────┐  │
│  │      安装           │  │   ← 状态按钮(占满卡片宽度)
│  └────────────────────┘  │
└──────────────────────────┘
```

组成:
1. **图标色块**(`Label` 或 `StackPane`,~38×38,圆角):
   - 内容 = `name` 的第一个字符(`name.substring(0,1)`,空名回退到 `?`)。
   - 背景渐变色按 `iconStyle` 注入(与现有 `IconStyle` 一致,Java 中设置)。
2. **名称** `Label`(加粗)。
3. **版本徽章** `v{version}` + **分类徽章** `category` 中文名(小号、半透明小标签)。
4. **描述** `Label`:`setWrapText(true)`,限制约 2 行(`setMaxHeight` 或省略)。
5. **状态按钮**(见 §4):`setMaxWidth(Double.MAX_VALUE)`,占满卡片宽度。

整张卡片有圆角边框 + 半透明背景,hover 时高亮边框。

---

## 4. 安装状态(本地计算)

新增:把**已安装插件信息**传入商店,用于判断每个商店插件的安装态。

### 数据来源与传递
- `MainWindow.wireEvents()` 中已持有 `registry`。改为:
  ```java
  contentArea.showPage(
      fan.summer.fengyu.ui.store.PluginStoreUi.build(registry.getPlugins()),
      I18n.get("store.online.title"));
  ```
- `PluginStoreUi.build(ObservableList<SwissKitJPlugin> installed)`:把已安装插件归约为 `Map<String,String>`(id → version),传给 `new OnlineStorePane(null, installedVersions)`。
- `OnlineStorePane` 新增构造参数 `Map<String,String> installedVersions`(可为空 → 视为「无已安装信息」,所有按钮显示「安装」)。

### 状态判定(逐卡片)
| 条件 | 按钮文案 | 颜色 | 可点击 |
|---|---|---|---|
| `id` 不在已安装 Map 中 | `安装` | 蓝 `#5b8cf7` | 是 → 触发现有下载流程 |
| `id` 已安装,且版本相同 | `✓ 已安装` | 绿 `#4cd97b` | 否(禁用) |
| `id` 已安装,但商店版本 > 已装版本 | `↑ 更新` | 琥珀 `#f0a93a` | 是 → 触发现有下载流程 |

### 版本比较
新增小工具方法 `compareVersion(String a, String b)`:按 `.` 分段,逐段尝试数字比较(`Integer.parseInt`),解析失败的段回退到字符串比较;段数不等时缺失段视为 0。返回 `>0 / 0 / <0`。仅用于判断「商店版本是否更高」。

### 安装成功后
保留现有下载逻辑(`.part` 临时文件 → 原子 `move`)。安装成功后,把该 `id` 的安装版本更新到内存 Map 并刷新该卡片状态(从「安装/更新」→「✓ 已安装」)。`onInstallComplete` 回调行为不变。

---

## 5. 搜索 + 筛选

- 在内存中维护一份完整的 `List<StorePlugin> allPlugins`(fetch 结果)。
- **搜索**:`TextField` 监听 `textProperty()`,对 `name + description + id` 做不区分大小写子串匹配,输入即时过滤。
- **分类下拉**:选中某 `ToolCategory` 只显示该分类;「全部」显示所有。
- **叠加**:搜索词与分类条件同时生效(逻辑与)。
- 过滤只重建 `FlowPane` 的卡片,不重新发起网络请求。
- 过滤后无结果 → 显示「未找到匹配的插件」提示(`store.online.noMatch`)。

提取一个私有方法 `applyFilters()`:读取当前搜索词 + 分类 → 过滤 `allPlugins` → 重建卡片 → 更新状态栏计数。搜索框 / 下拉 / 刷新完成后都调用它。

---

## 6. 样式归位(CSS)

当前样式全是 Java 内联。本次把可复用的卡片/工具栏样式提取为 CSS 类,放进 **`SwissKit/src/main/resources/css/shell.css`**(app-shell 范围):

| 类名 | 用途 |
|---|---|
| `.store-toolbar` | 工具栏行 |
| `.store-search` | 搜索 `TextField` |
| `.store-card` | 卡片容器(背景/边框/圆角 + hover) |
| `.store-card-name` | 卡片名称 |
| `.store-badge` | 版本/分类徽章 |
| `.store-install-btn` / `.store-install-btn.installed` / `.store-install-btn.update` | 三态按钮 |

- 图标色块的**渐变色**仍在 Java 中按 `iconStyle` 注入(沿用现有 `IconStyle` 机制,不进 CSS)。
- 三态按钮的语义色(蓝/绿/琥珀)用 CSS 修饰类切换,Java 仅切换 styleClass。

---

## 7. i18n

复用现有 key,新增以下。注意 i18n 文件实际为 **`messages.properties`(英文,默认)** 和 **`messages_zh.properties`(中文)**:

| Key | 中文 | English |
|---|---|---|
| `store.online.search` | 搜索插件… | Search plugins… |
| `store.online.category.all` | 全部 | All |
| `store.online.category.dev` | 开发 | Dev |
| `store.online.category.text` | 文本 | Text |
| `store.online.category.image` | 图像 | Image |
| `store.online.category.net` | 网络 | Network |
| `store.online.category.other` | 其他 | Other |
| `store.online.btn.install` | 安装 | Install |
| `store.online.btn.installed` | ✓ 已安装 | ✓ Installed |
| `store.online.btn.update` | ↑ 更新 | ↑ Update |
| `store.online.noMatch` | 未找到匹配的插件 | No matching plugins |
| `store.online.countWithInstalled` | 共 {0} 个插件 · 已安装 {1} | {0} plugins · {1} installed |

> 「中文」列写入 `messages_zh.properties`,「English」列写入 `messages.properties`。
> 现有 `store.online.pluginCount` / `foundPlugins` / `noPlugins` 等保留,用于无已安装信息的回退场景。

---

## 8. 受影响文件

| 文件 | 改动 |
|---|---|
| `SwissKit/.../ui/store/OnlineStorePane.java` | 主体重构:工具栏 + FlowPane 网格 + 卡片 + 搜索/筛选 + 三态按钮 + 版本比较 + `installedVersions` 构造参数 |
| `SwissKit/.../ui/store/PluginStoreUi.java` | `build()` 改为 `build(ObservableList<SwissKitJPlugin> installed)`,归约为 id→version Map 传入 |
| `SwissKit/.../ui/MainWindow.java` | 调用处改为 `PluginStoreUi.build(registry.getPlugins())` |
| `SwissKit/src/main/resources/css/shell.css` | 新增 `.store-*` 类 |
| `SwissKit/src/main/resources/i18n/messages.properties` | 新增上表 key(英文,默认) |
| `SwissKit/src/main/resources/i18n/messages_zh.properties` | 新增上表 key(中文) |
| `SwissKit/.../ui/store/StorePlugin.java` | 由 `OnlineStorePane` 内部类抽取为顶层类(字段不变),便于纯逻辑可测 |
| `SwissKit/.../ui/store/StorePluginLogic.java` | 新增:版本比较 + 安装态判定 + 过滤匹配(纯静态方法) |
| `SwissKit/pom.xml` | 新增 JUnit 5 测试依赖 + surefire 插件 |

**不改动**:远程清单 JSON 格式、`StorePlugin` 字段、`LocalInstallPane`、下载/原子移动核心逻辑、`IconStyle` / `ToolCategory` 枚举。

---

## 9. 非目标 (YAGNI)

- 不新增作者、下载量、截图、标签等需要扩展清单的字段。
- 不做插件详情弹窗 / 截图画廊。
- 不做网络结果缓存(仍每次刷新重新请求)。
- 不做排序选项(保持 fetch 顺序;过滤后顺序不变)。
- 不改本地安装 (`LocalInstallPane`) 的交互。
