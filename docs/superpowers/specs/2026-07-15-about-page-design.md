# 关于(About)页面设计

- **日期**:2026-07-15
- **状态**:已设计,待实施
- **分支**:4.0.0-FengYu
- **关联**:取代已废弃的 `2026-06-22-about-button-design.md`(那是 v3.1.0 JavaFX 弹窗方案,JavaFX 已在 4.0.0 删除)。本 spec 面向 4.0.0 headless Vue 前端。

## 1. 目标与约束

### 目标
在主项目(4.0.0 Vue 前端)新增一个 **About(关于)**页面,集中展示:软件名称、作者、开源地址、编译时间,以及所使用的开源项目(完整依赖清单)。

### 已确认决策(与用户确认)
1. **展示形式**:整页视图(`/about` 路由 + 侧边栏导航项),复用现有 `cx-*` 页面骨架,与 Settings / PluginMarket 一致。不是弹窗。
2. **编译时间来源**:**前端构建时间**——在 `vite.config.ts` 的 `define` 注入 `__APP_BUILD_TIME__`(构建/dev 启动时取 `new Date().toISOString()`)。零后端改动,dev 模式即时可用。
3. **致谢范围**:**完整依赖清单**——列出 `pom.xml`(后端)与 `package.json`(前端)中的主要依赖,按后端/前端分组,每项标注名称 + 用途(+ 可选项目主页链接)。
4. **作者显示**:**MuskStark**(仓库 git owner,与仓库地址一致),可点击跳转 GitHub 主页。

### 硬约束
- 完全遵循现有前端模式:`cx-*` CSS 工具类、`router/index.ts` 路由、`Sidebar.bottomNav` 数组、`i18n` en/zh JSON(键形一致)。
- **不引入新依赖**。About 页是纯展示视图,静态数据 + 全局常量。
- 不改动后端(无新端点)。版本号复用已有 `__APP_VERSION__`;编译时间用新 `__APP_BUILD_TIME__`。
- 所有静态文案走 i18n 键(en + zh 双语)。依赖清单的「用途」描述也做双语。

### 非目标(YAGNI)
- 不做「检查更新」/在线版本比对。
- 不做依赖项的自动抓取(从 pom/package.json 解析)——依赖清单作为静态数组维护,随版本演进手工同步。
- 不修改后端 `build-info.properties` 机制(那是另一回事;本页编译时间取前端构建时间)。
- 不修复 README/LICENSE 描述遗留(范围外)。

## 2. 背景与现状(已核对)

- **前端无任何 About 页面**:`src/views/` 下无 About,路由无 `/about`,侧边栏无 About 项,i18n 无 about 键。(旧 JavaFX About 弹窗随 4.0.0 删除。)
- **品牌名**:`Infinia`(英文,`desktop/tauri.conf.json` `productName`、`app.properties` `app.name`)/「蜂语」(中文,i18n `brand` zh 值 = "蜂语FengYu",en = "Infinia")。显示「Infinia(蜂语)」。
- **版本**:`frontend/package.json` `version` = `4.0.0`;`vite.config.ts` 已注入全局 `__APP_VERSION__`,被 `StatusBar.vue` 消费。直接复用。
- **编译时间**:当前前端无任何构建时间注入(`vite.config.ts` `define` 只有 `__APP_VERSION__`)。需新增。
- **作者 / 仓库**:git owner `MuskStark`;仓库 `https://github.com/MuskStark/FengYu`(README clone、launch4j supportUrl);文档站 `https://muskstark.github.io/FengYu/`。无真实姓名,无 `<developer>` POM 标签。
- **协议**:`pom.xml` `<licenses>` + `LICENSE` 文件 = **GNU GPL v3.0**。
- **Logo**:`public/infinia-logo.svg`(侧边栏已在用)。
- **页面骨架参考**:`Settings.vue`——`<div flex 可滚动>` → `.cx-page` → `.cx-page-title` → `.cx-card` / `.cx-section-title`。
- **侧边栏参考**:`Sidebar.vue` `bottomNav` 数组(图标 + labelKey + to),`route.path === item.to` 判激活。

## 3. 架构与组件清单

| 文件 | 动作 | 职责 |
|---|---|---|
| `frontend/src/views/About.vue` | **建** | About 页视图:页头卡片(logo + 名称 + 简介)、信息卡片(版本/作者/协议/编译时间/仓库链接)、致谢后端卡片、致谢前端卡片。版本读 `__APP_VERSION__`、编译时间读 `__APP_BUILD_TIME__`(按当前 locale 本地化展示)。 |
| `frontend/vite.config.ts` | **改** | `define` 块新增 `__APP_BUILD_TIME__: JSON.stringify(new Date().toISOString())`。 |
| `frontend/src/env.d.ts` | **改** | 在 `__APP_VERSION__` 旁声明 `declare const __APP_BUILD_TIME__: string`。 |
| `frontend/src/router/index.ts` | **改** | `routes` 数组加 `{ path: '/about', name: 'about', component: () => import('@/views/About.vue') }`。 |
| `frontend/src/shell/Sidebar.vue` | **改** | `bottomNav` 加 `{ key: 'about', to: '/about', labelKey: 'sidebar.about', icon: 'mdi-information-outline' }`。 |
| `frontend/src/i18n/en.json` + `zh.json` | **改** | 加 `sidebar.about`;加 `about.*` 命名空间(title / subtitle / version / author / buildTime / license / repository / docs / backendTitle / frontendTitle / 以及每个依赖的用途描述键)。 |

数据流:`About.vue` 直接读两个构建期全局常量(`__APP_VERSION__`、`__APP_BUILD_TIME__`)与静态依赖数组;无 store、无 API、无 props。

## 4. 页面布局

复用 Settings.vue 的外壳:

```
[可滚动容器]
  .cx-page(居中, max-width 720)
    h1.cx-page-title           = $t('about.title')  // "关于" / "About"

    // 1) 页头卡片
    .cx-card
      [logo + 名称 "Infinia(蜂语)" + 一句话简介]
      [版本 chip: v __APP_VERSION__]

    // 2) 信息卡片(label : value 行)
    .cx-section-title          = $t('about.infoTitle')
    .cx-card
      Version    : __APP_VERSION__
      Author     : MuskStark          (链接 → github.com/MuskStark)
      License    : GNU GPL v3.0       (链接 → LICENSE / 选择性)
      Build Time : __APP_BUILD_TIME__ (Intl.DateTimeFormat 按 locale)
      Repository : github.com/MuskStark/FengYu  (外链)
      Docs       : muskstark.github.io/FengYu   (外链)

    // 3) 致谢 — 后端
    .cx-section-title          = $t('about.backendTitle')
    .cx-card
      for dep in backendDeps:  [名称 → 链接]  [用途 $t(...)]

    // 4) 致谢 — 前端
    .cx-section-title          = $t('about.frontendTitle')
    .cx-card
      for dep in frontendDeps: [名称 → 链接]  [用途 $t(...)]
```

外链用 `<a target="_blank" rel="noopener noreferrer">`;在 Tauri 桌面下由默认浏览器打开(现有项目其他外链同此处理)。

## 5. 依赖清单(静态数据)

在 `About.vue` 内以两个静态数组维护(完整清单,每项 `{ name, url, descKey }`),用途文案走 i18n 键。

### 后端(取自 `FengYu/pom.xml`,仅主项目核心依赖)
> **排除插件专属依赖**:Apache POI / Apache Fesod(Excel 插件)、Commonmark(Markdown 插件)、Simple Java Mail / Jsoup(邮件插件)——这些库声明在各插件模块的 pom 中,不在主项目致谢范围。

Spring Boot 4.1.0(Web/SSE 后端)、Spring AI 2.0.0(多后端 AI + 工具调用)、H2 Database 2.4.240(嵌入式数据库)、MySQL / PostgreSQL / SQLite JDBC(多数据源驱动)、Apache PDFBox 3.0.4(PDF 处理)、Gson 2.13.1(JSON)、Lombok 1.18.42(样板消除)、Logback 1.5.34 + SLF4J 2.0.13(日志)、Playwright 1.49.0(浏览器自动化)。

### 前端(取自 `frontend/package.json`)
Vue 3.5.39(SPA 框架)、Vuetify 3.12(MD3 组件库)、Pinia 2.3(状态管理)、Vue Router 4.5(路由)、Vue I18n 10(国际化)、Axios 1.7(HTTP 客户端)、Marked 14.1(Markdown 渲染)、@mdi/font 7.4(图标)、Vite 6.0(构建工具)、TypeScript 5.7(语言)、Vitest 3.2(单测)、Sass 1.100(样式)、Tauri 2.0(桌面壳)。

> 依赖随版本演进手工同步;不在构建期自动解析(见非目标)。

## 6. 编译时间机制

- **注入**:`vite.config.ts` `define` 加 `__APP_BUILD_TIME__: JSON.stringify(new Date().toISOString())`。每次 `vite build` / `vite`(dev)启动时取当下时间。dev 下即每次 `npm run dev` 启动时间,可即时看到。
- **声明**:`env.d.ts` 加 `declare const __APP_BUILD_TIME__: string`。
- **展示**:`About.vue` 用 `Intl.DateTimeFormat(locale, {dateStyle:'full', timeStyle:'short'})` 按当前语言格式化;locale 取 `useI18n().locale`。ISO 字符串 `new Date(iso)` 可解析。

## 7. i18n 键(en + zh,键形一致)

新增 `sidebar.about`(zh="关于", en="About")。
新增 `about.*`:
- `title` / `subtitle`(一句话简介)
- `infoTitle`(信息区标题)、`version` / `author` / `buildTime` / `license` / `repository` / `docs`(字段标签)
- `backendTitle` / `frontendTitle`(致谢分组标题)
- 每个依赖用途:`about.dep.<key>`(如 `springBoot`、`vue` 等)。

## 8. 错误处理与边界

| 场景 | 处理 |
|---|---|
| `__APP_BUILD_TIME__` 为未来/异常 | 由 ISO 解析;`Intl` 对无效日期输出 `Invalid Date`——可接受(dev 下时间恒为当下,不会异常)。 |
| Tauri 桌面下点外链 | `<a target="_blank">` 由 Tauri 转交系统浏览器;与项目现有外链处理一致。 |
| 侧边栏 rail 折叠态 | `bottomNav` 已支持 rail(仅图标 + title tooltip),新项自动适配,无需额外处理。 |
| 依赖数组与实际 pom/package.json 漂移 | 静态维护,漂移为已知(见非目标);后续可另开「自动同步」任务。 |

## 9. 验证

- `npm run dev`:侧边栏出现 About 项 → 进入 `/about` → 名称/作者/仓库/协议/编译时间正确,编译时间为本次 dev 启动时间;中英切换文案正确;外链可点。
- `npm run build` + 类型检查:`vue-tsc` 通过(新全局声明、无类型错误)。
- rail 折叠态:About 项仅显示图标 + tooltip。
- 通过标准:以上 4 项全过。

## 10. 实施顺序(给 writing-plans 的输入)

1. **构建期常量**:`vite.config.ts` 加 `__APP_BUILD_TIME__` define + `env.d.ts` 声明。
2. **i18n 键**:en.json + zh.json 加 `sidebar.about` 与 `about.*`(含全部依赖用途)。
3. **About 视图**:`About.vue`(页头/信息/后端致谢/前端致谢四块)。
4. **接线**:路由加 `/about`;侧边栏 `bottomNav` 加项。
5. **验证**:dev 运行 + 类型检查 + rail 态。

每步独立可编译可检。
