# 邮件账号设置：SMTP/IMAP 分区 + IMAP 测试

- **日期**：2026-07-31
- **范围**：`OfficialPlugins/plugin-email/`（UI、worker、i18n、测试）
- **背景**：账号设置界面把 6 个 SMTP/IMAP 字段塞进同一个 2 列 `.form-grid`，导致 SMTP 的「安全方式」下拉紧挨着 IMAP 的「服务器」输入框，两组配置无任何标题或分隔，难以区分。且只有「测试 SMTP」，缺 IMAP 连接验证。

## 目标 / 非目标

**目标**
1. 账号设置表单中，SMTP 与 IMAP 各自独立成卡片、带标题、上下堆叠。
2. 新增「测试 IMAP 连接」能力（后端 + UI 按钮），与现有「测试 SMTP」对称。
3. i18n 双语同步。

**非目标（本次不做，避免范围蔓延）**
- 不改数据模型：`Account`/`EmailAccount` 字段本就是平铺同级（`smtpHost`、`imapHost` 等），无需嵌套 `smtp:{}` / `imap:{}`。
- 不支持「测试未保存的草稿」：测试仅对已保存账号（凭 `accountId`），与 SMTP 测试现状一致。（`AccountRpc.ConnectionTestRequest.unsavedAccount` 已预留该字段，但属另一坨工作。）
- IMAP 测试只验证连接（`store.connect`），不打开 INBOX —— 与 SMTP 的 `Mailer.testConnection()` 对等。

## 现状（不改的部分）

- 数据模型：`Account` / `AccountDraft`（`stores/accounts.ts`）、`EmailAccount` record、`AccountService.AccountView` —— SMTP/IMAP 字段均为平铺同级，本次不动。
- 顶部三字段（显示名称 / 邮箱 / 密码）、默认账户勾选框、底部操作行（删除 / 设为默认 / 保存）—— 布局与行为不变。
- 现有「测试 SMTP」链路完全保留：RPC 方法名 `email_account_test`、`EmailSendService.testSmtp`、Simple Java Mail `Mailer.testConnection()` 均不动。

## 设计

### A. UI 布局 — `ui-src/src/components/AccountSettingsView.vue`

把当前塞了 6 个字段的单一 `.form-grid`（模板第 50–51 行）拆成**两张上下堆叠的 `v-card`**，作为表单列的直接子元素：

```
显示名称  [__________________]
邮箱      [__________________]
密码      [__________________]

┌─ 发件 · SMTP ──────────────────────┐
│ 服务器 [__________________]        │   ← host 占满整行（.full-row）
│ 端口 [____]   安全方式 [▼ STARTTLS]│   ← port + security 同行
│ [测试 SMTP]                         │
└─────────────────────────────────────┘

┌─ 收件 · IMAP ──────────────────────┐
│ 服务器 [__________________]        │
│ 端口 [____]   安全方式 [▼ SSL]     │
│ [测试 IMAP]                         │
└─────────────────────────────────────┘

□ 设为默认账户
[删除] [设为默认]              [保存]
```

**要点**
- 每张卡片用 `v-card` 包裹，`v-card-title` 显示分区标题（`accounts.smtpSection` / `accounts.imapSection`），`v-card-text` 内放各自的 `.form-grid`。
- 卡片内的 `.form-grid` 沿用现有 2 列网格；host 字段加 `.full-row { grid-column: 1 / -1; }` 占满整行。
- 兼容性选择器 `.account-layout > :last-child > * + *` 天然继续生效——两张卡片是表单列的直接子元素，自动得到 14px 纵向间距。`shellCompliance.test.ts` 无需改动。
- 窄屏响应式沿用现有断点（`.form-grid` 在 `max-width: 720px` 折成单列，见 `styles.css:135`）。

### B. 样式 — `ui-src/src/styles.css`

新增一条规则（放在 `.form-grid` 规则附近）：

```css
.full-row { grid-column: 1 / -1; }
```

不新增 `.account-layout` 相关规则——卡片的内边距、阴影由 Vuetify `v-card` 自带。

### C. 后端 — IMAP 连接测试

| 文件 | 改动 |
|---|---|
| `service/EmailArchiveService.java` | 新增 `testImap(long accountId) -> SendResult`，形态完全对称 `EmailSendService.testSmtp`：取账号 → `accountService.decryptPassword(accountId)` → 校验 IMAP 三件套（host/port/security）非空（与 `collect()` 第 60–62 行同款守卫）→ `Session.getInstance(imapProperties(security))` → `session.getStore(protocol(security))` → `store.connect(imapHost, imapPort, email, password)`。**不抛异常**：try-with-resources 包裹 `Store`，catch 全部异常，失败用现成的 `safeMessage(e, password)` 脱敏后返回 `SendResult.failure(...)`；成功返回 `SendResult.success(null)`。复用本类已有的 `imapProperties`、`protocol`、`safeMessage` 三个静态 helper。 |
| `rpc/EmailRpcHandlers.java` | 新增 `testImapAccount(Map<String,Object> params)`，返回 `{ success, summary }`，与现有 `testAccount`（第 86–93 行）完全对称：取 `accountId` → `archive.testImap(accountId)` → 据结果 `ok("IMAP connection succeeded")` 或 `failure(errorMessage)`。 |
| `EmailWorkerMain.java` | 注册新方法 **`email_account_test_imap`**（参数 `{ accountId: long }`），紧挨现有 `email_account_test` 注册处（第 43 行）。**保留 `email_account_test` 不动**（仍只测 SMTP）→ 零破坏现有调用与测试。 |
| `manifest.json` | 给 `email_account_test_imap` 声明工具条目；**顺手回填** `email_account_test`（目前漏声明，host 工具列表里看不到它）。 |

**返回形状**（与 SMTP 测试一致）：`{ success: boolean, summary: string }`，不新增 duration 等字段。

### D. i18n — `ui-src/src/i18n/en.ts` + `zh-CN.ts`

新增 4 个 key（`accounts.*` 命名空间，两边同步）：

| key | zh-CN | en |
|---|---|---|
| `accounts.smtpSection` | 发件 · SMTP | Outgoing · SMTP |
| `accounts.imapSection` | 收件 · IMAP | Incoming · IMAP |
| `accounts.testSmtp` | 测试 SMTP | Test SMTP |
| `accounts.testImap` | 测试 IMAP | Test IMAP |

把现有按钮用的 `accounts.test`（值=「测试 SMTP」）替换为 `accounts.testSmtp`，使两个按钮文案对称。`accounts.testAction`（进行中提示）/ `testSuccess`（成功提示）通用，继续复用；不新增 IMAP 专用的进行中/成功文案。

### E. UI 调用 — `AccountSettingsView.vue`

- 现有 `testAccount()`（第 18–20 行，调 `email_account_test`）保留为 SMTP 测试，按钮 `data-testid="smtp-test"` 不变，改用文案 key `accounts.testSmtp`。
- 新增 `testImapAccount()`，调 `invoke('email_account_test_imap', { accountId: accounts.draft.id })`，成功后 `notice.value = t('accounts.testSuccess')`；按钮 `data-testid="imap-test"`，文案 `accounts.testImap`，放进 IMAP 卡片。

## 测试

### 后端
- `EmailArchiveServiceTest`：用 GreenMail `PROTOCOL_IMAP` 起服务、存账号、调 `archive.testImap(accountId)`，断言 `result.success()` 为真；再加一个错密码 → `result.success()` 为假、`errorMessage` 非空且不含密码的负例。复用该测试类现成的 IMAP harness（起 GreenMail IMAP + 存账号）。
- `EmailWorkerMainTest`：断言 `email_account_test_imap` 出现在已注册方法列表（紧挨现有 `email_account_test` 断言，第 90 行附近）。

### 前端
- `ManagementViews.test.ts`：现有用 `data-testid="smtp-test"` 跑 save→load 刷新流，保留；新增对 `data-testid="imap-test"` 按钮存在的断言（render 后能找到该按钮）。
- `shellCompliance.test.ts`：选择器 `.account-layout > :last-child > * + *` 不变，应继续通过——无需改动，但实现后须跑一遍确认。

## 涉及文件清单（全部在 `OfficialPlugins/plugin-email/` 内）

| 文件 | 类型 |
|---|---|
| `ui-src/src/components/AccountSettingsView.vue` | UI 布局 + 新测试按钮 + invoke |
| `ui-src/src/styles.css` | 新增 `.full-row` |
| `ui-src/src/i18n/en.ts` | 4 个新 key + 替换 `accounts.test` |
| `ui-src/src/i18n/zh-CN.ts` | 同上 |
| `src/main/java/.../service/EmailArchiveService.java` | 新增 `testImap` |
| `src/main/java/.../rpc/EmailRpcHandlers.java` | 新增 `testImapAccount` |
| `src/main/java/.../EmailWorkerMain.java` | 注册 `email_account_test_imap` |
| `manifest.json` | 声明 `email_account_test_imap` + 回填 `email_account_test` |
| `src/test/java/.../service/EmailArchiveServiceTest.java` | IMAP 测试正负例 |
| `src/test/java/.../EmailWorkerMainTest.java` | 注册断言 |
| `ui-src/src/components/ManagementViews.test.ts` | imap-test 按钮断言 |

## 验证

- 后端：`./mvnw -pl OfficialPlugins/plugin-email test`（聚焦该插件测试）。
- 前端：`cd OfficialPlugins/plugin-email/ui-src && npm test`。
- 回归：`shellCompliance.test.ts` 通过。
- 视觉：`npm run dev` 起前端，进账号设置，确认两张卡片上下堆叠、标题清晰、两测试按钮各在其卡片内、窄屏折成单列。
