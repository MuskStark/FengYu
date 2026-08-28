---
title: 数据库
description: 通过首次启动设置向导选择并配置四种数据库后端之一，之后无需重装即可重新配置。
lang: zh-CN
---

# 数据库

Infinia 自带一个首次启动的**设置向导**，要求你选择一个数据库后端，并在应用完成初始化之前验证其可用。在数据源配置好之前，后端以 SETUP 模式运行，仅服务于向导；一旦初始化完成，它会重启进入 APP 模式并加载完整技术栈。

## 后端

支持四种数据库，由 `DbType` 枚举声明。其中两种是嵌入式（无需独立服务器），另外两种是外部数据库：

| `DbType` | 驱动族 | 模式 |
| --- | --- | --- |
| `H2` | H2 | 嵌入式 |
| `SQLITE` | SQLite | 嵌入式 |
| `MYSQL` | MySQL | 外部 |
| `POSTGRESQL` | PostgreSQL | 外部 |

嵌入式后端写入一个由应用管理的本地文件；外部后端连接到你提供的服务器。

## 设置端点

所有 `/api/setup/*` 端点都**绕过令牌过滤器**，以便向导在令牌尚未存在时运行。绕过列表参见[后端](/zh/architecture/backend)。

| 方法 + 路径 | 请求体 / 返回 | 用途 |
| --- | --- | --- |
| `GET /api/setup/status` | `{initialized, supportedTypes[], embeddedTypes[]}` | 应用是否已初始化，以及它支持哪些后端类型、其中哪些是嵌入式。 |
| `GET /api/setup/types` | 按类型的表单元数据 | 每个后端所需的字段（以便向导渲染正确的表单）。 |
| `POST /api/setup/test-connection` | `{type, params}` → 测试结果 | 探测连接，**不持久化**任何内容。 |
| `POST /api/setup/initialize` | `{type, params}` → 结果 | 重新测试连接，然后持久化配置并发出重启进入 APP 模式的信号。 |
| `DELETE /api/setup/config` | — | 备份当前配置并清空，然后重启进入 SETUP 模式。 |

### 推荐流程

1. `GET /api/setup/status`——确认 `initialized:false` 并查看有哪些可用后端。
2. `GET /api/setup/types`——为你选择的后端渲染表单。
3. `POST /api/setup/test-connection`——在提交之前验证参数。
4. `POST /api/setup/initialize`——持久化并让后端重启进入 APP 模式。

## 配置存放在哪里

持久化的数据源存放在：

```text
<运行目录>/.fengyu/config/datasource.properties
```

嵌入式 H2 和 SQLite 数据文件默认存放在 `<运行目录>/.fengyu/database/fengyu`。

其中的键：

| 键 | 含义 |
| --- | --- |
| `db.type` | `H2`、`SQLITE`、`MYSQL`、`POSTGRESQL` 之一。 |
| `db.url` | JDBC URL。 |
| `db.driver` | JDBC 驱动类。 |
| `db.dialect` | Hibernate 方言。 |
| `db.username` | 数据库用户名。 |
| `db.password` | 数据库密码，**AES/GCM 加密**（见下文）。 |
| `db.file.path` | 嵌入式后端的文件位置。 |

### 密码加密

密码通过 `CryptoUtil` 以 AES/GCM 加密。密钥是**绑定机器**的：

1. 启动器将一个每机 UUID 写入 `<运行目录>/.fengyu/config/.machineid`。
2. AES 密钥为 `SHA-256("FengYu-4.0-Phase4-SetupKey:" + <machine UUID>)`。
3. 加密后的值以 `ENC(...)` 的形式包裹存放在属性文件中。

由于密钥派生自本地机器 ID，复制到另一台主机上的 `datasource.properties` 将无法解密。

## 数据库不可达

如果 `datasource.properties` 存在，但所配置的数据库在启动时不可达，启动器会把配置备份为一个 `.bak` 兄弟文件，并回退到 SETUP 模式，以便向导收集更正后的参数。参见[后端 —— SETUP 与 APP 模式](/zh/architecture/backend#setup-vs-app-mode)。

## 虚拟用户

在首次 APP 模式启动时，应用会创建一个虚拟本地用户：

- **id：** `1`
- **name：** `Summer`
- **role：** admin / local

会话及其他记录都挂在这个身份之下。

## 重新配置

有两种等价的方式可重新进入向导：

- **手动**——删除 `<运行目录>/.fengyu/config/datasource.properties` 并重启后端；没有配置时它会以 SETUP 模式启动。
- **API**——`POST /api/settings/database/reset` 会备份当前配置、清空它，并重启进入 SETUP 模式。参见[配置](/zh/guide/configuration)。

两者产生相同的最终状态：一份已备份的配置，以及一个等待新参数的 SETUP 模式进程。

## 下一步

- [配置](/zh/guide/configuration)——用户设置、AI 配置以及重置端点。
- [后端](/zh/architecture/backend)——SETUP 与 APP 模式以及可达性探测。
- [快速开始](/zh/quickstart)——构建并启动后端。
