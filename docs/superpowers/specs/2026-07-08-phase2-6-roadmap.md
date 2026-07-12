# 4.0.0 Phase 2–6 Roadmap

**Date:** 2026-07-08
**Context:** Phase 1 完成后的后续规划
**Prerequisite:** Phase 1 (Vue + Tauri 走路骨架) 已完成 — headless 后端、Markdown 插件、Vue 主壳、Tauri 桌面壳、JavaFX 已删除

---

## Overview

Phase 1 建立了基础架构并证明了端到端管道。Phase 2–6 在此基础上扩展功能和生态：

| Phase | 主题 | 周期估算 | 产出 |
|---|---|---|---|
| **2** | 工具插件化 | 3-4 周 | 所有内置工具提取为官方插件 |
| **3** | 运行时插件热更新 | 2-3 周 | 市场下载覆盖编译期捆绑版本 |
| **4** | 多数据源部署向导 | 1-2 周 | 首次启动选择 MySQL/H2/SQLite |
| **5** | 生产打包 + 发布自动化 | 2 周 | 签名安装包 + 每平台 JRE 捆绑 |
| **6** | 第三方插件 SDK + 市场后端（可选） | 4+ 周 | 插件生态对外开放 |

---

## Phase 2: 所有内置工具插件化

**Goal:** 将所有现有内置工具（Excel、PDF、邮件、浏览器自动化、dev 工具）提取为官方插件，沿用 Markdown 的模式（Maven 模块 + Spring bean + v2 合约 + Vue 微前端）。

### 范围

提取以下工具为独立 Maven 模块，每个实现 `FengYuPluginV2`：

1. **Excel 分割器** (`plugin-excel-splitter/`)
   - 后端：`invoke("analyze", {file})` → 返回表名+列头映射；`invoke("split", {mode, config})` → 执行分割，返回进度/结果
   - 前端：StepWizard 风格 UI（文件选择 → 模式选择 → 配置 → 执行 → 结果）
   - 依赖：Fesod、POI (已有)

2. **PDF 工具** (`plugin-pdf/`)
   - 三个子功能：split、merge、convert (PDF↔DOCX)
   - 后端：`invoke("split", {file, ranges})`、`invoke("merge", {files})`、`invoke("convert", {file, format})`
   - 前端：Tab 切换三个子工具，每个独立的输入/输出区
   - 依赖：PDFBox (已有)

3. **邮件** (`plugin-email/`)
   - 后端：`invoke("send", {to, subject, body, attachments})` — 调用现有 `EmailSendService`
   - 前端：富文本编辑器 (Quill/TipTap) + 附件列表 + 地址簿集成
   - 依赖：simple-java-mail (已有)

4. **邮件归档查询** (`plugin-email-archive/`)
   - 后端：`invoke("query", {keyword, dateRange})`、`invoke("export", {ids})`
   - 前端：搜索表单 + 表格结果 + 详情抽屉
   - 依赖：现有 `EmailArchiveService`

5. **浏览器自动化** (`plugin-browser-automation/`)
   - 后端：`invoke("execute", {script})` — 执行 Playwright 脚本并返回截图/结果
   - 前端：脚本编辑器 (Monaco/CodeMirror) + 结果预览
   - 依赖：Playwright (已有)
   - **注意**：AI 工具 (`BrowserAutomateTool`) 保持独立注册，不与插件耦合

6. **Dev 工具集** (`plugin-dev-utils/`)
   - 合并简单 dev 工具为一个插件，Tab 切换：Base64 编解码、Hash 计算 (MD5/SHA256)、JSON 格式化、颜色转换
   - 后端：`invoke("base64-encode", {text})`、`invoke("hash", {text, algo})`、`invoke("json-format", {json})`、`invoke("color-convert", {value, from, to})`
   - 前端：每个子工具一个 Tab，输入/输出简单布局

### 架构约束

- **后端逻辑复用**：每个插件 `@Component`，依赖注入现有 service 类 (`EmailSendService`, `EmailArchiveService`, Fesod/POI/PDFBox 工具类)。尽量复用现有业务逻辑，只重构 UI 层。
- **前端组件库**：Phase 1 的 Vue shell 会建立通用组件库 (`src/components/`)，包括 StepWizard、FileUpload、ProgressBar、DataTable、CodeEditor 等。Phase 2 插件复用这些。
- **编译期捆绑**：所有官方插件仍作为 reactor modules 编译期捆绑进主程序，与 Markdown 一致。运行时覆盖留到 Phase 3。
- **AI 工具分离**：插件的 `aiTools()` 继续自动注册到 `AiServiceProvider`，与 v1 一致。浏览器自动化、Excel、PDF 的 AI 工具保持不变。

### 测试

- 每个插件独立的单元测试 (`plugin-xxx/src/test/`)：后端 `invoke` 逻辑、前端微前端 mount/unmount
- 集成测试：启动 headless 后端，curl 调用每个插件的 `invoke` 端点，验证返回
- E2E：Playwright 驱动 Vue 前端，依次打开每个插件，执行核心流程（上传文件 → 执行 → 查看结果）

### Definition of Done

- 所有 7 个内置工具（Excel、PDF、邮件、邮件归档、浏览器、dev 工具 6 合 1）提取为 Maven 模块
- `GET /api/plugins` 返回 8 个插件（Markdown + 7 个新插件）
- Vue 前端 ToolGrid 显示 8 个卡片，点击每个都能加载微前端并正常工作
- 所有插件的核心功能通过 E2E 测试
- 更新 `README.md` + `CHANGELOG.md`，记录插件化完成

---

## Phase 3: 运行时插件热更新（市场覆盖机制）

**Goal:** 实现"编译期捆绑 + 运行时市场覆盖"混合分发模式。用户可以在主程序不更新的情况下，从官方市场下载插件新版本，覆盖内置版本。

### 架构设计

#### 插件加载优先级

```
for each plugin id:
   if  marketplace-downloaded version exists in  ~/.fengyu/plugins/<id>/plugin.jar   → load that
   else                                                                                → load bundled Maven module
```

下载的插件 JAR 优先级高于内置版本。

#### 动态类加载方案

**挑战：** Spring Boot 应用上下文在启动时固定，后续动态加载 JAR 并注册为 Spring bean 需要特殊处理。

**方案 A（推荐）：** 使用 `PluginClassLoader` (URLClassLoader) 加载下载的 JAR，但不注册为 Spring bean。插件实例通过反射创建，手动调用 `invoke` / `aiTools()`。`PluginRegistryService` 维护两份映射：`bundledPlugins: Map<String, FengYuPluginV2>` (Spring beans) 和 `overridePlugins: Map<String, FengYuPluginV2>` (手动实例化)。

**方案 B（复杂）：** 运行时刷新 Spring context 子上下文 — 为每个下载的插件创建独立的 `AnnotationConfigApplicationContext` 作为主上下文的子上下文，加载插件 JAR 的 `@Component`。需要仔细管理父子上下文的 bean 可见性和生命周期。

Phase 3 采用**方案 A**，方案 B 留作后续优化（如果需要插件使用 Spring DI）。

#### 版本比较与下载

- `PluginDescriptor` 增加 `version` 字段（semver）
- 市场 API（暂定本地 mock）：`GET /marketplace/plugins` 返回可用插件列表及最新版本；`GET /marketplace/plugins/{id}/download?version=x.y.z` 下载 JAR
- `PluginUpdateService` 定期检查更新（启动时 + 用户手动触发）：对比内置版本与市场版本，如果市场更新则下载到 `~/.fengyu/plugins/<id>/`
- 下载后，提示用户重启应用（或实现热重载：卸载旧实例、加载新 JAR、重新注册）

#### UI 界面

- 前端增加 `/settings/plugins` 页面：列出所有插件，显示当前版本（bundled / overridden）、市场最新版本、更新按钮
- 更新流程：点击更新 → 后端下载 JAR → 提示重启 → 重启后加载新版本

### 测试

- 单元测试：`PluginClassLoader` 加载测试 JAR，反射创建插件实例，调用 `invoke`
- 集成测试：模拟市场 API，下载一个新版本插件（修改返回值），重启后端，验证新版本生效
- 回退测试：删除 `~/.fengyu/plugins/<id>/plugin.jar`，重启，验证回退到内置版本

### Definition of Done

- `PluginRegistryService` 实现加载优先级逻辑（市场 JAR > 内置模块）
- 前端 `/settings/plugins` 页面可查看所有插件版本并触发更新
- 手动模拟：编译一个插件新版本（修改返回值），放到 `~/.fengyu/plugins/markdown/plugin.jar`，重启，验证新版本被加载
- 文档更新：`docs/plugins/marketplace-override.md` 记录机制和测试步骤

---

## Phase 4: 多数据源首次部署向导

**Goal:** 首次启动时，通过 Web UI 向导选择数据库类型（MySQL / PostgreSQL / SQLite / H2），配置连接信息，自动建表，持久化配置。支持 Web 部署（指向远程 MySQL）和桌面部署（默认本地 H2）。

### 用户流程

1. **首次启动检测**：后端检测 `~/.fengyu/config/datasource.properties` 不存在 → 进入"首次部署向导"模式，不初始化数据库，只启动 Web 服务器
2. **前端向导**：Vue 前端检测后端返回 `{"initialized": false}` → 显示全屏向导（非主 shell），引导用户完成配置
3. **选择数据库类型**：
   - **本地嵌入式** (默认)：H2 或 SQLite，无需额外配置，数据存储在 `~/.fengyu/data/`
   - **远程服务器**：MySQL / PostgreSQL，填写 host、port、database、username、password
4. **连接测试**：前端调用 `POST /api/setup/test-connection`，后端尝试连接并返回成功/失败
5. **建表**：连接成功后，前端调用 `POST /api/setup/initialize`，后端运行 DDL（`init.sql` 通用 + 数据库特定调整），创建表
6. **保存配置**：后端将数据源配置写入 `~/.fengyu/config/datasource.properties`（加密敏感字段如密码）
7. **重启提示**：前端提示"配置完成，正在重启..."，后端优雅关闭并重启（Tauri sidecar 自动重启；Web 部署需要手动重启或用 Spring Boot Actuator `/restart`）

### 后端实现

- `src/main/java/fan/summer/fengyu/setup/SetupController.java` — 向导专用控制器，不受 token auth 保护（或用临时 setup token）
- `src/main/java/fan/summer/fengyu/setup/DataSourceConfigService.java` — 读写 `datasource.properties`，支持加密
- `src/main/java/fan/summer/fengyu/setup/SchemaInitializer.java` — 根据数据库类型加载并执行 DDL
- 修改 `DatabaseInit.init()` — 如果检测到未初始化，跳过建表，只返回 `{"initialized": false}` 给前端

### DDL 适配

- 当前 `init.sql` 是 H2 语法。Phase 4 需要准备多套 DDL：
  - `init-h2.sql` (已有)
  - `init-mysql.sql` (兼容 MySQL 8+)
  - `init-postgres.sql`
  - `init-sqlite.sql`
- 或使用 Flyway / Liquibase 管理 schema migration（推荐，便于后续版本升级）

### 测试

- 集成测试：模拟首次启动，调用向导 API，传入不同数据源配置，验证建表成功
- E2E：启动应用（无 `datasource.properties`），在浏览器中完成向导，重启，验证主 shell 正常显示并能访问数据库

### Definition of Done

- 首次启动自动进入向导模式（前后端协同）
- 支持 H2、MySQL、PostgreSQL、SQLite 四种数据源
- 配置持久化到 `~/.fengyu/config/datasource.properties`（密码加密）
- 向导完成后重启，主应用正常加载所选数据库
- 文档更新：`docs/deployment/setup-wizard.md`

---

## Phase 5: 生产打包 + 发布自动化

**Goal:** 通过 GitHub Actions 自动构建多平台签名安装包（macOS `.dmg`、Windows `.exe`、Linux `.AppImage`），每个平台捆绑适配的 JRE，一键安装无需用户配置 Java 环境。

### 平台目标

- **macOS**: `.dmg` 或 `.app` bundle，签名 + 公证 (notarization)
- **Windows**: `.exe` 安装包 (NSIS / Inno Setup)，代码签名
- **Linux**: `.AppImage` 或 `.deb` / `.rpm`

### JRE 捆绑

使用 **jlink** 或预构建的最小化 JRE (Azul Zulu / Adoptium)：

- 每个平台在 CI 中下载对应架构的 JRE (x64 / arm64)
- Tauri build 时将 JRE 和 `FengYu.jar` 一起打包到 `binaries/`
- `main.rs` 启动 sidecar 时用 `./jre/bin/java -jar FengYu.jar` 而不是系统 `java`

### 签名

- **macOS**: 需要 Apple Developer 证书 + `codesign` + `xcrun notarytool`
  - GitHub secret 存储证书 (.p12) 和密码
  - CI workflow: `codesign --sign "Developer ID Application: ..." --options runtime FengYu.app`
  - 上传到 Apple 公证服务：`xcrun notarytool submit FengYu.dmg --wait`
  - Staple 公证票据：`xcrun stapler staple FengYu.dmg`
- **Windows**: 需要 code signing 证书 (EV 或 OV)
  - 使用 `signtool.exe` 或 `osslsigncode` (开源跨平台签名工具)
  - 证书存储在 GitHub secret
- **Linux**: AppImage 可选签名 (GPG)，大多数用户不强制要求

### GitHub Actions Workflow

```yaml
name: Release
on:
  push:
    tags: ['v*']
jobs:
  build-backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21 }
      - run: mvn clean package -DskipTests
      - uses: actions/upload-artifact@v4
        with: { name: backend-jar, path: FengYu/target/FengYu-*.jar }

  build-frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20 }
      - run: cd frontend && npm ci && npm run build
      - uses: actions/upload-artifact@v4
        with: { name: frontend-dist, path: frontend/dist }

  build-desktop:
    strategy:
      matrix:
        platform: [macos-latest, windows-latest, ubuntu-latest]
    runs-on: ${{ matrix.platform }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/download-artifact@v4
        with: { name: backend-jar, path: desktop/binaries }
      - uses: actions/download-artifact@v4
        with: { name: frontend-dist, path: desktop/dist }
      # Download JRE for platform
      - run: ./scripts/download-jre-${{ runner.os }}.sh
      # Build Tauri
      - uses: tauri-apps/tauri-action@v0
        with:
          projectPath: desktop
          tauriScript: cargo tauri
        env:
          TAURI_PRIVATE_KEY: ${{ secrets.TAURI_PRIVATE_KEY }}
          TAURI_KEY_PASSWORD: ${{ secrets.TAURI_KEY_PASSWORD }}
          # macOS signing
          APPLE_CERTIFICATE: ${{ secrets.APPLE_CERTIFICATE }}
          APPLE_CERTIFICATE_PASSWORD: ${{ secrets.APPLE_CERTIFICATE_PASSWORD }}
          APPLE_ID: ${{ secrets.APPLE_ID }}
          APPLE_PASSWORD: ${{ secrets.APPLE_PASSWORD }}
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
          # Windows signing
          WINDOWS_CERTIFICATE: ${{ secrets.WINDOWS_CERTIFICATE }}
          WINDOWS_CERTIFICATE_PASSWORD: ${{ secrets.WINDOWS_CERTIFICATE_PASSWORD }}
      - uses: actions/upload-artifact@v4
        with: { name: installer-${{ runner.os }}, path: desktop/src-tauri/target/release/bundle/** }

  create-release:
    needs: [build-desktop]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4
      - uses: softprops/action-gh-release@v1
        with:
          files: |
            installer-macOS/**
            installer-Windows/**
            installer-Linux/**
```

### 测试

- 本地模拟：在各平台手动执行打包流程，验证安装包可安装、可运行
- CI dry-run：不推送到 release，只构建和上传到 artifacts
- 实际发布：打 tag (`v4.0.0`) → CI 自动构建 → 发布到 GitHub Releases

### Definition of Done

- GitHub Actions workflow 配置完成，支持 macOS / Windows / Linux 三平台
- 每个平台安装包捆绑 JRE，用户无需预装 Java
- macOS 签名 + 公证通过，Windows 签名通过
- 推送 `v4.0.0` tag → CI 自动构建 → 发布到 GitHub Releases
- `README.md` 增加"下载"部分，链接到 Releases 页面

---

## Phase 6: 第三方插件 SDK + 市场后端（可选）

**Goal:** 开放插件生态给第三方开发者：提供插件开发 SDK、脚手架、文档、市场后端（插件上传、审核、分发），使 FengYu 成为可扩展平台。

**注意：** 此 phase 工作量大，可能拆分为多个子阶段，或根据社区需求决定是否实施。以下为高层次规划。

### SDK 组件

1. **Plugin Archetype (Maven / Gradle)**
   - `fengyu-plugin-archetype` — Maven archetype 快速生成插件骨架
   - 生成的项目包含：
     - `pom.xml` (声明 `FengYu-Api` 为 `provided`)
     - 示例 `MyPlugin.java` 实现 `FengYuPluginV2`
     - 示例 Vue 微前端 `ui-src/`
     - `README.md` 开发指南
   - 用法：`mvn archetype:generate -DarchetypeGroupId=fan.summer.fengyu -DarchetypeArtifactId=fengyu-plugin-archetype`

2. **插件开发文档**
   - `docs/plugin-dev/` 目录：
     - `01-getting-started.md` — 环境搭建、archetype 使用、本地调试
     - `02-backend-api.md` — `invoke` 合约、`AiTool` 集成、依赖注入限制
     - `03-frontend-microfrontend.md` — Vue MF 规范、`mount` 合约、`PluginUiContext` API、设计 token 复用
     - `04-testing.md` — 单元测试、集成测试、E2E 测试
     - `05-packaging.md` — 打包为 JAR、manifest 要求、版本管理
     - `06-publishing.md` — 提交到市场、审核流程
   - API reference (Javadoc) 发布到 GitHub Pages

3. **本地调试支持**
   - 后端增加 `--dev-plugin` 启动参数，指定本地插件 JAR 路径，动态加载（绕过市场）
   - 前端增加开发者模式，允许加载本地 `http://localhost:3001` 的插件 MF（插件前端独立 Vite dev server）

### 市场后端

**架构：** 独立的 Spring Boot 应用 (`fengyu-marketplace-backend`)，与主应用解耦，部署在云端。

**功能：**

1. **插件上传**
   - 开发者通过 Web 界面或 CLI 上传插件 JAR
   - 后端验证 JAR：检查 manifest、`FengYuPluginV2` 实现、版本号格式
   - 存储到对象存储 (S3 / OSS) 或文件系统

2. **审核流程**
   - 上传后进入"待审核"状态
   - 管理员审核：运行自动化扫描（安全漏洞、恶意代码检测），手动测试，批准/拒绝
   - 批准后进入"已发布"状态，用户可见

3. **插件列表 API**
   - `GET /api/marketplace/plugins` — 返回所有已发布插件列表（分页、搜索、分类筛选）
   - `GET /api/marketplace/plugins/{id}` — 插件详情（版本历史、下载量、评分、截图）
   - `GET /api/marketplace/plugins/{id}/download?version=x.y.z` — 下载 JAR

4. **版本管理**
   - 支持多版本共存，用户可选择下载特定版本
   - 自动检测更新：主应用调用 `GET /api/marketplace/plugins/{id}/latest` 获取最新版本

5. **用户评价 + 统计**
   - 用户可对插件评分、评论
   - 统计下载量、活跃安装数

**技术栈：**
- 后端：Spring Boot + PostgreSQL + S3
- 前端：独立的 Vue 管理后台（插件开发者门户 + 管理员审核界面）
- 认证：OAuth2 / GitHub 登录

### 社区生态

- 建立 GitHub Discussion / Discord 社区，插件开发者交流
- 示例插件仓库：`fengyu-plugin-examples`（天气查询、RSS 阅读器、番茄钟等简单插件作为学习模板）
- 每月插件开发比赛 / Hackathon（可选）

### 测试

- SDK 测试：用 archetype 生成示例插件，本地开发 → 打包 → 上传市场 → 下载安装，全流程验证
- 市场后端 API 测试：上传、审核、下载、版本管理的集成测试
- 安全测试：上传恶意 JAR（模拟），验证扫描器能检测并拒绝

### Definition of Done

- `fengyu-plugin-archetype` 发布到 Maven Central 或 GitHub Packages
- 插件开发文档完整，Javadoc API reference 在线可访问
- 市场后端部署上线，至少 5 个示例插件已发布
- 主应用集成市场 API，用户可在 `/settings/plugins` 浏览并安装第三方插件
- 社区渠道建立（GitHub Discussion / Discord）

---

## 总结

| Phase | 关键里程碑 | 对用户的价值 |
|---|---|---|
| **1** | Vue + Tauri 走路骨架 | 现代化 UI，Web + 桌面双端支持，架构基础 |
| **2** | 所有工具插件化 | 功能完整性恢复，模块化架构 |
| **3** | 运行时插件热更新 | 官方插件可独立更新，无需重装主程序 |
| **4** | 多数据源部署向导 | 支持企业部署（MySQL），降低部署门槛 |
| **5** | 生产打包 + 签名 | 用户一键安装，无需配置 Java 环境，跨平台发行 |
| **6** | 第三方插件生态（可选） | 社区扩展，长尾功能由第三方提供 |

**预计总周期：** Phase 1–5 累计约 **10-14 周**（假设单人全职或小团队）。Phase 6 是长期投入（4+ 周起步），可根据产品方向决定优先级。

---

**下一步：** Phase 1 spec + plan 已完成并提交。开始执行 Phase 1 实施。
