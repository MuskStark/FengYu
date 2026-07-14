---
title: 官方插件：邮件中心
description: 多账户确认式发送、地址簿、手动收取、归档与七个 AI 工具。
lang: zh-CN
---

# 官方插件：邮件中心

邮件中心（`fan.summer.email`）是官方沙箱 `.fyp`，由 Vue/Vuetify/TipTap iframe 与隔离 Java Worker 提供五项工作流：

| 标签 | 能力 |
| --- | --- |
| 写邮件 | To/CC/BCC、富文本与纯文本、附件、安全摘要和显式确认。 |
| 群发 | 标签或文件名后缀模式、精确确认摘要、执行计数和重试准备。 |
| 地址簿 | 联系人/标签 CRUD、搜索、批量分配标签和收件人解析。 |
| 收取邮件 | 按账户、文件夹、日期范围手动收取到授权目录。 |
| 记录与账户 | 分页归档、发送状态/重试和多套 SMTP/IMAP 配置。 |

收取功能**仅手动执行**，不会后台轮询。RFC-822 `.eml` 写入授权目录，元数据写入插件独立数据库；账户/文件夹命名空间与文件夹范围 UID 去重避免冲突。

插件权限恰好是 `database`、`network.email`、`files.read`、`files.write`。SMTP/IMAP 密码经 AES-GCM 加密，账户 RPC 永不返回密码。

## AI 确认

七个工具覆盖账户、联系人、单封/群发准备、发送状态、归档收取和查询。准备阶段保存不可变快照并返回 `confirmation_required`；只有 `confirm_send` 才发送，`reject_send` 用于拒绝，重复确认是幂等的。归档查询只返回元数据和有长度上限的预览，不返回原始 `.eml`。

另见[插件数据库规范](/zh/plugins/database)、[AI 工具](/zh/plugins/ai-tools)和[文件 I/O](/zh/plugins/file-io)。
