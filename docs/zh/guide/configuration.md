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
  ◄── 200 { "theme": "dark", "language": "en", "sidebarCollapsed": false, "logLevel": "INFO" }
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

修改 `logLevel` 不会重启 Worker。宿主会更新自身的 Logback 命名空间，并向每个运行中的
Worker 发送内置 JSON-RPC 通知；之后新启动的 Worker 则通过 `FENGYU_LOG_LEVEL` 继承同一值。

## AI 配置

`GET /api/ai/config` 返回一份**脱敏**快照，以便你在不暴露原始密钥的情况下渲染表单：

- API 密钥被脱敏——只显示开头和结尾的几个字符，例如 `sk-1***wXYZ`。
- 该快照还会报告 `activeMode`（当前后端）和 `ready`（当前后端是否可用）。

```text
GET /api/ai/config
  ◄── 200 {
        "activeMode": "openai",
        "ready": true,
        "openai":  { "apiKey": "sk-1***wXYZ", "model": "gpt-4o", "baseUrl": "..." },
        ...
      }
```

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

FengYu 在启动时读取 Spring AI MCP 客户端配置。STDIO、SSE 与 Streamable HTTP 分别通过
`spring.ai.mcp.client.stdio.*`、`spring.ai.mcp.client.sse.*` 和
`spring.ai.mcp.client.streamable-http.*` 配置。若使用 Codex 风格的 STDIO
服务器文件，可这样启动：

```bash
java -jar FengYu-*.jar \
  --spring.ai.mcp.client.stdio.servers-configuration=file:/absolute/path/mcp-servers.json
```

MCP 工具会同时加入对话与 Agent 的工具目录。可通过 `GET /api/mcp/status` 检查已初始化
连接。MCP 配置仅在启动时读取，修改后需要重启。配置外部 STDIO 命令即表示明确授权启动
该命令，因此只应使用可信服务器定义，并将凭据放在启动环境或受保护的外部文件中。

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

## 下一步

- [数据库](/zh/guide/database)——首次启动向导与四种后端。
- [AI 对话](/zh/guide/ai-chat)——使用你刚配置好的后端。
- [REST API](/zh/reference/rest-api)——完整的端点参考。
