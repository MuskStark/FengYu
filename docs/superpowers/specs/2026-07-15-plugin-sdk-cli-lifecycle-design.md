# FengYu 插件 SDK 与 CLI 全生命周期设计

## 背景

FengYu 4.0.0 插件由 Vue 微前端、可选的 Java JSON-RPC worker 和根目录
`manifest.json` 组成，最终以 `.fyp` zip 安装包交付。仓库已经提供 TypeScript 浏览器
SDK、Vue UI Kit、Java worker SDK 和 `@fengyu/plugin-cli`，但当前工具链尚未形成从初始化
到安装包的完整闭环：

- `fengyu plugin create` 默认只生成 Vue 项目，没有 Java worker、Maven Wrapper 或真实 RPC 示例。
- `fengyu plugin dev` 的 `rpc.invoke` 返回固定 mock，不会启动或调用 Java worker。
- `fengyu plugin build` 只构建根目录 Vite 项目，不会测试、构建或收集 worker JAR。
- CLI 的 manifest 权限集合与宿主、文档不一致，例如不接受官方 Email 插件使用的
  `database` 和 `network.email`。
- 三个官方插件依赖 `OfficialPlugins/build-packages.sh` 手工组装、复制和压缩，未使用官方
  CLI，因此 CLI 的缺口不会被官方构建持续发现。
- npm 工具包和 Java SDK 没有完整发布流水线，第三方项目不能被证明可以只依赖已发布产物
  完成构建。

本设计让 CLI 成为插件开发和打包的唯一标准入口，并让官方插件成为该入口的持续验收样本。

## 目标

1. 默认脚手架生成一个独立、可构建的 Vue + Java 插件项目。
2. CLI 覆盖初始化、依赖准备、本地开发、测试、校验、构建、打包和安装。
3. 保持现有纯前端 Vite/static 插件的零配置兼容。
4. 三个官方插件分别直接通过 `fengyu plugin build` 生成 `.fyp`，删除官方专用打包实现。
5. CLI、SDK、宿主与文档对 manifest、权限和协议的理解保持一致。
6. npm 和 GitHub Packages 发布的工具链能够在全新目录中支持完整插件构建。

## 非目标

- 不改变 `.fyp` 的运行时布局或 JSON-RPC 2.0 worker 协议。
- 不把 Maven 多模块、父 POM 或仓库内部构建结构暴露为插件开发契约。
- 不要求现有纯前端插件立即增加 `fengyu.plugin.json`。
- 不在本次工作中设计插件签名、市场审核或远程发布到插件市场的流程。

## 项目模型

### 运行时清单

`manifest.json` 继续是唯一的运行时契约，只描述插件身份、UI 入口、worker 启动方式、权限和
AI tools。构建命令、源码目录和测试命令不得写入 manifest。

### 开发期配置

新增可选的 `fengyu.plugin.json`。它只用于 CLI 编排，`schemaVersion` 初始为 `1`：

```json
{
  "schemaVersion": 1,
  "ui": {
    "root": "ui-src",
    "output": "dist",
    "install": ["npm", "ci"],
    "test": ["npm", "test"],
    "build": ["npm", "run", "build"]
  },
  "worker": {
    "root": "worker",
    "test": ["maven", "test"],
    "build": ["maven", "package", "-DskipTests"],
    "artifact": "target/my-plugin-worker.jar",
    "mainClass": "com.example.plugin.WorkerMain"
  },
  "package": {
    "outputDirectory": "dist-package"
  }
}
```

约束如下：

- 所有目录与产物路径相对插件根目录或对应阶段的 `root` 解析。
- 解析后的路径必须留在插件根目录内；不允许 `..`、符号链接或绝对路径逃逸。
- 命令必须是非空字符串数组，CLI 直接传给 `spawn`，不经过 shell 拼接。
- `maven` 是逻辑工具名。CLI 在当前目录及其父目录查找 `mvnw`/`mvnw.cmd`，并按平台执行；
  找不到 wrapper 时失败，不静默依赖系统 Maven。
- UI、worker 和 package 区块分别负责源码构建、worker 构建和最终布局，避免一个通用脚本接管
  全部打包语义。
- 官方仓库可以覆盖具体命令和参数以适应其内部父 POM，但这种覆盖不改变公共项目模型。

没有 `fengyu.plugin.json` 时，CLI 保留当前行为：Vite 项目执行根目录 `npm run build`，static
项目直接校验并打包。

## 默认脚手架

`fengyu plugin create <directory> --id <id>` 默认生成完整项目：

```text
manifest.json
fengyu.plugin.json
ui-src/
worker/
mvnw
mvnw.cmd
.mvn/wrapper/
.mvn/settings.xml
.mvn/maven.config
```

UI 使用 Vue 3、TypeScript、Vuetify、`@fengyu/plugin-sdk` 和 `@fengyu/plugin-ui`。worker 使用
JDK 21、`fengyu-plugin-sdk` 和 Maven Shade，生成带正确 `Main-Class` 的 fat JAR。示例 UI
必须调用一个真实示例 RPC；worker 必须提供对应 handler，并为两端生成最小测试。

`--ui-only` 生成现有轻量 Vue 模板，不包含 worker 或 Maven 文件。`--no-install` 继续跳过
依赖安装，但生成的项目结构必须完整。

Java SDK 发布到 GitHub Packages。脚手架的 `.mvn/settings.xml` 只引用
`${env.FENGYU_GITHUB_TOKEN}` 或 `${env.GITHUB_TOKEN}`，不得写入真实令牌；
`.mvn/maven.config` 让 wrapper 自动使用该 settings。CLI 在需要解析 Java SDK 前执行凭据
预检，并说明令牌需要 `read:packages` 权限。

## CLI 生命周期

### `plugin create`

1. 校验插件 ID 和目标目录。
2. 生成完整或 `--ui-only` 模板。
3. 默认安装前端依赖。
4. 验证生成后的配置和 manifest 能被 CLI 读取。
5. 安装失败时保留脚手架，并输出可复制的恢复命令。

### `plugin dev`

对于完整项目：

1. 准备 UI 依赖。
2. 构建 Java worker，并从配置的 JAR 启动进程。
3. 启动 Vite 和现有宿主模拟器。
4. 将浏览器 SDK 的 `rpc.invoke` 请求转换为逐行 JSON-RPC 2.0，并转发给 worker stdin；将
   worker stdout 响应映射回 iframe。
5. worker 日志只能写 stderr；协议 stdout 中出现非 JSON 内容时立即显示协议错误。
6. Java 源码或 POM 变化后重新构建并原子替换 worker 进程；构建失败时保留 UI 和错误信息，
   不继续向旧 worker 隐式发送请求。
7. CLI 退出、Vite 退出或 worker 异常退出时清理所有子进程和监听器。

模拟器继续支持主题、语言、权限拒绝、通知以及文件/目录 capability mock。纯前端和 static
项目保持当前 dev 路径。

### `plugin validate`

校验分为源项目和组装产物两层。

源项目校验包括：

- `fengyu.plugin.json` schema、命令数组和路径边界。
- manifest 必填字段、语义化版本、category、permissions、backend protocol 和 AI tool 唯一性。
- AI tool `inputSchema` 必须是 JSON object schema，`method` 必填且唯一。
- manifest 声明 backend 时，配置必须能产生 worker artifact；未声明 backend 时不允许意外打包
  `backend/worker.jar`。

产物校验包括：

- `.fyp` 根目录只包含允许的运行时文件，不包含源码、令牌、`.git`、`node_modules`、测试缓存
  或旧构建输出。
- `ui.entry` 是归档内普通文件且不能逃逸。
- backend command 引用的 JAR 存在，protocol 为 `json-rpc-2.0`。
- JAR manifest 的 `Main-Class` 与配置一致，并能加载该 class。
- 归档大小、展开大小和条目路径满足宿主安装限制。
- permissions 至少覆盖文档和宿主支持的
  `files.read`、`files.write`、`network`、`clipboard.read`、`clipboard.write`、
  `notifications`、`database`、`network.email`。

CLI 和宿主使用同一套 manifest fixtures 做契约测试，防止两套手写规则再次漂移。

### `plugin build`

声明式项目的固定流水线为：

```text
读取并预校验配置
→ 必要时准备依赖
→ 默认运行 UI 和 worker 测试
→ 构建 UI
→ 构建 worker
→ 创建临时 staging
→ 复制 manifest、UI、worker 和显式资源
→ 对 staging 做产物校验
→ 原子生成 .fyp
→ 清理 staging
```

依赖准备规则：存在锁文件时优先执行确定性安装；已存在且与锁文件匹配的依赖可跳过重复安装。
`--skip-tests` 只跳过测试阶段，不跳过 TypeScript 类型检查、UI build 或 Java package。

输出文件默认是
`<package.outputDirectory>/<manifest.id>-<manifest.version>.fyp`。压缩先写入同目录临时文件，
校验和写入全部成功后 rename；任何失败均不得留下 `.fyp`、staging 或临时文件。

### `plugin install`

安装前先对本地 `.fyp` 执行离线产物校验，再上传到 `/api/plugin-market/upload`。HTTP 错误必须
保留状态码和宿主响应正文。认证继续使用 `--token` 或 `FENGYU_TOKEN`。

## 官方插件迁移

`fan.summer.markdown`、`fan.summer.excel` 和 `fan.summer.email` 分别成为 CLI 可识别的插件根：

- 把对应 `manifest.json` 移到 `OfficialPlugins/plugin-<name>/`。
- 在每个插件根增加 `fengyu.plugin.json`，声明 `ui-src`、worker JAR 和内部 Maven 命令。
- 删除 `OfficialPlugins/build-packages.sh`。
- 删除 `OfficialPlugins/packages/*` 中用于手工组装的 manifest 和 UI 文件。
- CI 使用 matrix 分别执行三个 `fengyu plugin build OfficialPlugins/plugin-<name>`。
- `scripts/e2e-smoke.sh` 从各插件 `dist-package/` 或 CI 汇总目录读取 CLI 产出的 `.fyp`，安装后
  实际调用 worker RPC。

官方插件不得保留另一条可生成发布 `.fyp` 的脚本路径。它们可以继续使用仓库内部父 POM；
该事实不进入 CLI 文档和第三方模板。

## SDK 与工具发布

四个开发工具采用同一个插件工具链版本：

- npm：`@fengyu/plugin-cli`、`@fengyu/plugin-sdk`、`@fengyu/plugin-ui`
- GitHub Packages Maven：`fan.summer.fengyu.sdk:fengyu-plugin-sdk`

工具链版本独立于宿主 4.x 版本，但遵循语义化版本。SDK 主版本用于运行时兼容协商；模板生成时
固定引用与当前 CLI 同一兼容版本，不使用仓库内 `file:` 路径。

发布流水线必须：

1. 运行 Java SDK、TypeScript SDK、Vue UI Kit 和 CLI 单元测试。
2. 从模板创建完整插件并运行真实 dev RPC 测试。
3. 通过 CLI 构建三个官方插件并运行宿主 e2e smoke。
4. 发布 Java SDK 到 GitHub Packages。
5. 发布 TypeScript SDK、UI Kit 和 CLI 到 npm。
6. 在全新临时目录中只使用已发布版本重新 create/build。
7. 全部验证成功后创建或推进 release 标记；中途失败不得宣告完整工具链版本可用。

npm 发布使用 registry 的自动化凭据或 trusted publishing。GitHub Packages 使用 GitHub Actions
提供的发布凭据。日志必须屏蔽 npm token、GitHub token 和 Maven settings 中的敏感值。

## 错误处理

- 配置错误必须指出 JSON 字段和解析后的路径。
- 子命令失败必须保留实际退出码，并标明失败阶段和工作目录。
- worker 协议错误必须显示请求 ID、方法名和安全截断后的 stderr，不输出文件内容或凭据。
- 多个校验问题应一次性汇总；构建进程失败则立即停止后续阶段。
- cleanup 失败作为附加警告报告，不能覆盖原始构建错误。

## 测试与验收

### CLI 单元与集成测试

- 参数解析不会把 `--out`、`--port` 等选项值误认为项目路径。
- 完整模板、`--ui-only`、`--no-install` 和目标目录冲突。
- wrapper 的 macOS/Linux/Windows 选择与令牌预检。
- 测试、UI build、worker build、staging、validate、zip 的严格顺序。
- 任一阶段失败时不存在半成品 `.fyp` 或残留进程。
- dev 中真实 JSON-RPC 成功、worker error、非法 stdout、重启和退出清理。
- 旧 Vite/static fixtures 继续通过。

### 契约测试

- CLI 与宿主共同验证合法/非法 manifest fixtures。
- 覆盖全部权限、官方 ID、AI tool schema、路径逃逸、缺失 UI、缺失 JAR、错误主类、zip slip、
  package 大小和展开大小限制。
- Java `FileRef` 与 TypeScript `FileRef` 字段保持一致。
- SDK major version 不兼容时 dev 与生产宿主给出一致错误。

### 端到端验收

1. 在空目录运行默认 `create`。
2. 使用真实 worker 完成一次 dev RPC。
3. 默认 `build` 运行前后端测试并生成 `.fyp`。
4. CLI 离线校验并安装该包。
5. 宿主加载 UI、启动 worker 并完成 RPC。
6. 对三个官方插件重复 build/install/RPC 流程。
7. 在没有仓库源码和本地 `file:` 依赖的临时环境中，使用 npm 与 GitHub Packages 已发布版本
   重复完整流程。

满足以上条件后，才能认为 SDK 与 CLI 支持了从初始化到打包、安装和运行验证的完整插件开发
生命周期。
