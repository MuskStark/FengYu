---
title: 配置
description: 管理用户设置，通过热切换更换 AI 后端与 API 密钥，探测连接，以及重新配置数据库。
lang: zh-CN
---

# 配置

Infinia 中有两个配置界面：**用户设置**（主题、语言、侧边栏、日志）与 **AI 配置**（当前后端、API 密钥、模型）。两者都通过 REST 读写，并且 AI 配置可在运行时热切换而无需重启。数据库重新配置也在此处作为一个重置端点暴露。

所有端点都要求带 `X-FengYu-Token` 头。参见[后端](/zh/architecture/backend)。

## 用户设置

```text
GET /api/settings
  ◄── 200 { "theme": "dark", "language": "en", "sidebarCollapsed": false,
             "logLevel": "INFO", "updateApiBase": "", "computerUseEnabled": true,
             "computerUse": { "available": true, "reason": null } }
```

`PUT /api/settings` 接受一个**部分请求体**——只有你包含的键会被持久化，其余保持不变。

```text
PUT /api/settings
  Content-Type: application/json
  { "sidebarCollapsed": true }

  ◄── 200 { "theme": "dark", "language": "en", "sidebarCollapsed": true, "logLevel": "INFO" }
```

| 键 | 类型 | 含义 |
| --- | --- | --- |
| `theme` | string | `"light"` 或 `"dark"`。参见[设计系统](/zh/design-system)。 |
| `language` | string | UI 区域设置（例如 `en`、`zh-CN`）。 |
| `sidebarCollapsed` | boolean | 侧边栏是否初始处于折叠状态。 |
| `logLevel` | string | `TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR` 或 `OFF`。立即应用到主程序和所有 Java 插件 Worker。 |
| `updateApiBase` | string | 设置中的**升级渠道**：生产环境 Infinia 商店部署的绝对 HTTP(S) 基础地址（插件安装/更新、云账号登录、主程序更新都经由它通信）。留空回退到启动时的商店基址（`FENGYU_STORE_API_BASE`，本地开发为 `http://localhost:8080`），更新走 GitHub。内网商店地址需以 `-Dfengyu.store.allow-private-network=true` 启动。 |
| `computerUseEnabled` | boolean | 桌面端 `computer_*` 屏幕控制工具族的总开关（默认 `true`）。置为 `false` 后，下一轮对话即从 AI 目录中移除这些工具；输入动作始终保留每轮审批门。 |
| `computerUse` | object | 只读能力探测：`{available, reason}`。仅桌面模式返回；纯 Web 模式为 `null`。 |

修改 `logLevel` 不会重启 Worker。宿主会更新自身的 Logback 命名空间，并向每个运行中的
Worker 发送内置 JSON-RPC 通知；之后新启动的 Worker 则通过 `FENGYU_LOG_LEVEL` 继承同一值。

`updateApiBase` 是通往独立部署的生产商店的唯一运行时渠道：插件安装/更新、云账号登录与
用户中心代理都逐请求经它解析（`StoreEndpointProvider`），`/api/store/status` 返回生效地址。
它也会在桌面窗口创建前加载，因此启动自动探测与**关于 → 检查更新**手动操作使用同一通道。
更新方面，商店的兼容镜像（APP listing 的 stable 发布）提供
`Infinia-<版本>-win32-x64-portable.zip` 产物并附带强制 SHA-256；lite deb feed 在商店提供
electron-updater feed 之前仍沿用 FY-Proxy 旧契约，NSIS、AppImage、macOS、JRE 与便携 Web/JAR
构建仍使用公共 GitHub 通道，指向商店渠道时会被拒绝。

## AI 配置

`GET /api/ai/config` 返回一份**脱敏**快照，以便你在不暴露原始密钥的情况下渲染表单：

- API 密钥被脱敏——只显示开头和结尾的几个字符，例如 `sk-1***wXYZ`。
- 该快照还会报告 `activeMode`（当前后端）和 `ready`（当前后端是否可用）。

```text
GET /api/ai/config
  ◄── 200 {
        "activeMode": "openai",
        "ready": true,
        "contextWindowTokens": 32768,
        "openai":  { "apiKey": "sk-1***wXYZ", "model": "gpt-4o", "baseUrl": "..." },
        ...
      }
```

`contextWindowTokens` 控制长对话压缩。FengYu 会在用量达到该值的 60% 时开始总结旧轮次；
默认值为 `32768`，`0` 表示禁用自动压缩。应把它设为所选模型真实的上下文窗口，而不是

### 动态工具加载

每个挂载的工具定义都要在每一轮模型请求中重复付费,MCP 较多的部署可能积累几十 KB 的
schema。`toolLoadingMode` 控制按需工具加载(pi 的 `setActiveTools` 模式):

- `auto`(默认)——仅当可见工具数量超过 `toolLoadingThreshold`(默认 25)时启用动态加载;
  不超过时与之前完全一样全量发送。
- `always` / `off`——无视数量强制开启或关闭。

启用后,每轮只挂载一个小型低成本核心加上本对话的激活集;其余工具在系统提示里按名字
列出("Available tools (on-demand activation)"),并通过内置 `search_tools` 工具按需激活——
只增不减、每对话上限 40 个,后续用户回合会从镜像结果重建激活集。调用尚未激活的工具会
得到可行动的指引而不是整轮失败。规划执行型 Agent 使用同一开关:超过阈值时先在无 schema
的目录上选择工具,再只针对所选工具的 schema 编写计划。
模型的输出 token 上限。

### 更新 AI 配置

`PUT /api/ai/config` 接受一个**部分**请求体。关于来回往返有一个关键点：因为 `GET` 会脱敏 API 密钥，把那个脱敏值原样发回会覆盖掉真实密钥。为避免这种情况，**任何包含 `***` 的 API 密钥字符串都被视为「未更改」**而不予持久化——因此脱敏值可以直接原样回传，不会丢失真实密钥。

```text
PUT /api/ai/config
  Content-Type: application/json
  { "activeMode": "anthropic", "anthropic": { "apiKey": "sk-a***9zzz", "model": "..." } }
```

持久化之后，后端通过 `BackendReactivator.reactivate()` **热切换**当前后端——无需重启。四种受支持的模式为 `local`（Ollama）、`openai`、`anthropic` 与 `deepseek`（OpenAI 兼容）。每种模式的作用参见 [AI 对话](/zh/guide/ai-chat)。

### 测试连接

在提交一个新模式之前，可以不保存地探测它：

```text
POST /api/ai/config/test
  Content-Type: application/json
  { "mode": "deepseek", "endpoint": "...", "apiKey": "...", "model": "...", "baseUrl": "..." }

  ◄── 200 { "success": true, ... }
```

用此端点可提前验证凭据与端点可达性。

## MCP 客户端

FengYu 支持在 **设置 → MCP** 中动态添加、测试、启停和删除 MCP 服务，也支持对应的 REST
API。STDIO、SSE 与 Streamable HTTP 均可在保存后立即连接，发现的工具会实时加入对话和 Agent
工具目录，不需要重启。

若要接入 [mcp-chrome](https://github.com/hangwin/mcp-chrome)：

1. 按其说明安装 Chrome 扩展和 `mcp-chrome-bridge`，并在扩展中点击 Connect。
2. 在 FengYu 的 **设置 → MCP** 点击 **添加 Chrome MCP**。
3. 保存预填配置：Streamable HTTP，地址 `http://127.0.0.1:12306`，端点 `/mcp`。

也可以手动创建同样的配置。mcp-chrome 官方推荐的连接地址是
`http://127.0.0.1:12306/mcp`；FengYu 的地址字段既可填写主机地址并单独填写端点，
也可直接填写完整地址。

如果使用 Codex 风格的 STDIO 服务器文件，仍可通过 Spring AI 的启动配置加载：

```bash
java -jar FengYu-*.jar \
  --spring.ai.mcp.client.stdio.servers-configuration=file:/absolute/path/mcp-servers.json
```

工具按服务器命名空间化为 `<服务器>__<工具>`（详情页会显示确切前缀），因此 `Mcp(...)`
权限规则可以精确到单个服务器，两个服务器也可以暴露同名工具。每个服务器可单独为 AI
工具目录禁用某些工具——支持裸工具名、线上名称或 `前缀*` / `*` 通配符——并可各自设置
请求与初始化超时（5–600 秒，默认 30 秒）。STDIO 配置中的解释器注入类环境变量
（`NODE_OPTIONS`、`LD_PRELOAD`、`LD_LIBRARY_PATH`、`DYLD_*` 及 JVM 变体）会被强制剥离。

从 Claude、Codex、Grok 市场安装的插件若声明了 `mcpServers`，会以“已禁用”状态出现在
**设置 → MCP** 中并标注来源插件。可随时测试连通性；启用即采纳进用户管理的注册表
（被采纳的服务器在插件卸载后仍保留）。

也可通过 `GET /api/mcp/status` 和 `/api/mcp/servers` 检查连接。配置外部 STDIO 命令即表示
明确授权启动该命令，因此只应使用可信服务器定义，并将凭据放在受保护的本机配置中。

## `datasource.properties` 布局

数据库连接与 AI 配置分开持久化，存放在
`<运行目录>/.fengyu/config/datasource.properties`，其键为：

| 键 | 含义 |
| --- | --- |
| `db.type` | `H2`、`SQLITE`、`MYSQL`、`POSTGRESQL` 之一。 |
| `db.url` | JDBC URL。 |
| `db.driver` | JDBC 驱动类。 |
| `db.dialect` | Hibernate 方言。 |
| `db.username` | 数据库用户名。 |
| `db.password` | 数据库密码，AES/GCM 加密（参见[数据库](/zh/guide/database)）。 |
| `db.file.path` | 嵌入式后端的文件位置。 |

`db.password` 使用由本地 `.machineid` 派生、绑定机器的 AES/GCM 密钥加密，并以 `ENC(...)` 包裹后存储。参见[数据库 —— 密码加密](/zh/guide/database#password-encryption)。

## 重新配置数据库

```text
POST /api/settings/database/reset
  X-FengYu-Token: <token>
```

这会备份当前的 `datasource.properties`、清空它，并重启后端进入 SETUP 模式，以便首次启动向导收集新参数。功能上等价于删除 `datasource.properties` 并手动重启——参见[数据库 —— 重新配置](/zh/guide/database#reconfigure)。

## 静态秘密的存储

本地敏感信息（数据源口令、AI 供应商 API Key、MCP 服务器凭据）写入磁盘前都会用机器绑定密钥加密。默认该密钥是 `.fengyu/config/.machineid` 中的随机值——它把密文绑定到本机（被窃走的配置文件在其他机器上无法解密），但无法防御同一操作系统用户下的读取。

使用操作系统凭据库保管秘密的部署，可以注入该密钥而不使用文件。在启动后端前设置 `FENGYU_MACHINE_KEY`（系统属性或环境变量，至少 16 个字符，重启间保持不变）——例如从 macOS 钥匙串读取：

```bash
export FENGYU_MACHINE_KEY="$(security find-generic-password -s FengYu -a machine-key -w)"
java -jar Infinia.jar --token=...
```

等价命令：Linux 上 `secret-tool lookup fengyu machine-key`，Windows 在启动脚本中读取凭据管理器。所有密文都与注入的密钥绑定——切换或丢失该密钥将导致已存密文无法解密（可通过设置界面重录）。设置 `FENGYU_MACHINE_KEY` 后，`.machineid` 文件不再使用也不会被创建。

## 下一步


- [数据库](/zh/guide/database)——首次启动向导与四种后端。
- [AI 对话](/zh/guide/ai-chat)——使用你刚配置好的后端。
- [REST API](/zh/reference/rest-api)——完整的端点参考。
