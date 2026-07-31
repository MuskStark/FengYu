# 邮件通讯录界面重构

- **日期**：2026-07-31
- **范围**：`OfficialPlugins/plugin-email/`（前端 + 后端 Contact 数据模型 + i18n + 测试）
- **背景**：通讯录界面（`AddressBookTab.vue`）当前问题：①联系人列表项信息密度高、缺层次（勾选框+姓名+邮箱+删除全挤一行）；②标签不可见（列表里看不到联系人有哪些标签）；③搜索/筛选/批量打标三行工具条跟列表混在一起；④右侧表单只有 3 个字段（邮箱/姓名/标签）大片留白；⑤标签管理是弹窗，入口藏在联系人表单 actions 里，与联系人编辑混在一起。

## 目标 / 非目标

**目标**
1. 列表项改为「C 详情行」样式：头像 + 姓名 + 邮箱 + **标签胶囊** + 编辑按钮，信息分层清晰。
2. 整体保持左列表 + 右栏，但右栏改为**上下两栈**：上=联系人表单，下=**独立的标签管理卡**（不再用弹窗，不再藏在表单里）。
3. 联系人表单增加「备注」字段（需后端 Contact 模型加 `notes` 列，全栈贯通）。
4. 标签管理卡：标签多时用**可滚动列表 + 搜索框**（S2）应对溢出。
5. 列表项里联系人标签**折叠**：只显前 2 个 + 「+N」（行高齐整）。
6. i18n 双语同步，新增所需 key。

**非目标**
- 不改 RPC 方法名集合（`email_contact_save` 等沿用，只是 `email_contact_save` 的参数多一个 `notes`）。
- 不改导航结构（通讯录仍是 Email Center 下的一个 workspace）。
- 不改其他 workspace（Compose/Batch/Archive/Records/Accounts）。
- 列表项头像用**姓名首字母**生成（不引入图片上传/真实头像）。

## 决策记录（已通过浏览器逐项与用户确认）

| # | 决策点 | 选择 |
|---|---|---|
| 1 | 列表项样式 | C · 详情行（两行：姓名/邮箱 + 标签胶囊，删除/编辑靠右） |
| 2 | 整体布局 | 左列表 + 右上下两栈（保留两栏骨架，右栏分区） |
| 3 | 表单与标签管理 | **分区**：标签管理独立成常驻卡片，不再用弹窗 |
| 4 | 标签管理卡溢出 | S2 · 可滚动列表（每行 标签名+删除）+ **搜索框** |
| 5 | 列表项标签溢出 | 折叠：只显前 2 个 + 「+N」 |
| 6 | 备注字段 | 加（改后端 Contact 模型） |

## 设计

### A. 列表项 — C 详情行

`AddressBookTab.vue` 联系人列表从 `v-list-item`（单行 title+subtitle）改为自定义行结构：

```
[勾选框] [头像]  Alice Wang                    [编辑]
                  alice@example.com
                  [客户] [VIP] [+3]
```

- **头像**：取 `nickname` 或 `email` 首字母（大写），圆形渐变背景。无头像图片。
- **行结构**：`display:flex`，左侧勾选框 + 头像 + 中间文本块（姓名加粗 / 邮箱灰色 / 标签胶囊行）+ 右侧操作。
- **标签折叠**：联系人标签胶囊只渲染前 2 个；超出 2 个时追加一个「+N」胶囊（点击可 tooltip/展开看全部，最小实现：`v-tooltip` 或直接 title 属性）。
- 点击行（非按钮区域）= `edit(item)`（沿用现有行为）；「编辑」按钮显式触发 `edit`。
- 删除按钮保留（靠右，`variant="text" color="error"`）。

### B. 整体布局 — 左列表 + 右上下两栈

根容器仍是 `.panel-grid`（两列），但右列从「单卡」改为**纵向堆叠的两个卡**：

```
┌─ 联系人列表（左，1.5fr）─┐  ┌─ 联系人表单（右上）────┐
│ [搜索][标签筛选][搜索]   │  │ 邮箱  [________]        │
│ ── 列表（C 详情行）──    │  │ 姓名  [________]        │
│  Alice  ... [编辑]       │  │ 标签  [芯片][+ 添加]    │
│  Bob    ... [编辑]       │  │ 备注  [________多行]    │
│  Carol  ... [编辑]       │  │ [保存] [新建]           │
│ ┄┄ 批量打标（虚线分隔）┄┄ │  └─────────────────────────┘
│ [选标签▾]      [打标]    │  ┌─ 标签管理（右下）──────┐
└──────────────────────────┘  │ [搜索标签…]             │
                              │ ┌ 客户        删除 ┐    │
                              │ │ VIP         删除 │(滚动)│
                              │ └ 供应商      删除 ┘    │
                              │ [新标签名]    [添加]    │
                              └─────────────────────────┘
```

- **左卡**：顶部工具条（搜索/筛选/搜索按钮）→ C 详情行列表 → 底部批量打标条（用虚线/分隔与列表区分）。
- **右上卡**：联系人表单（邮箱/姓名/标签/备注）。标题随 `contactId` 切换「编辑联系人/新建联系人」。
- **右下卡**：标签管理（独立）。

右列两卡之间的间距沿用现有全局规则（`.account-layout` 同款 `> * + *` 纵向间距，或显式 `ga-` / margin）。

### C. 标签管理卡（独立，替代原弹窗）

- 删除原 `v-dialog` 标签管理弹窗。
- 标签以**可滚动列表**呈现：每行 = 标签名 + 删除按钮（`color="error" variant="text"`）。固定 `max-height`，超出滚动。
- 顶部一个**搜索框**：输入即过滤列表中显示的标签（前端本地过滤 `store.tags`，按名称包含匹配）。
- 底部一行：新建标签输入 + 「添加」按钮。
- 删除标签仍走 `pendingDelete`（kind='tag'）+ 共享 `ActionDialog` 确认（沿用现有机制）。

### D. 备注（notes）字段 — 后端全栈贯通

当前后端 Contact 模型只有 `email` + `nickname`，无 `notes`。需逐层加：

插件数据库用**自带的版本化迁移框架**（`database/SchemaMigrator.java`），非 Flyway：每个方言（h2/postgresql/sqlite/mysql）在 `src/main/resources/db/<dialect>/` 下有 `V<n>__email_schema.sql`，按版本号顺序执行；`SchemaMigrator.LATEST_VERSION`（当前=4）控制上界。V1 是完整建表脚本（含 `FENGYU_PL_Email_Contact`，**当前无 notes 列**）；V2/V3/V4 仅记录版本号（实际改动在 Java 里做）。

加 `notes` 采用**新增 V5 迁移**（不动 V1），让全新安装和升级库走同一路径（V1 建表无 notes → V5 ALTER 加列），统一、无需方言特定的 IF NOT EXISTS 守卫：

| 层 | 文件 | 改动 |
|---|---|---|
| DB 迁移 | `src/main/resources/db/{h2,postgresql,sqlite,mysql}/V5__add_contact_notes.sql`（**新建 4 个**） | 每个文件：`ALTER TABLE FENGYU_PL_Email_Contact ADD COLUMN notes <类型>;` + `INSERT INTO FENGYU_PL_Email_Schema_History(version) VALUES (5);`。类型按方言：h2/postgres/mysql 用 `TEXT`（或 `VARCHAR(2000)`），sqlite 用 `TEXT`。nullable（不加 NOT NULL）。 |
| 迁移器 | `database/SchemaMigrator.java` | `LATEST_VERSION` 从 `4` 改为 `5`。 |
| Repository | `repository/AddressBookRepository.java` | `ContactRow` 加 `notes` 字段 + 构造/访问；`ContactInput` 加 `notes`；`saveContact` 的 INSERT（及 UPDATE 分支）加 `notes`；`findContact`/`searchContacts` 的 SELECT 列加 `notes`。 |
| Service | `service/AddressBookService.java` | `ContactInput` record 加 `notes`；`saveContact` 透传 `notes`（`trimToNull`）。 |
| Model | `model/Contact.java` | record 加 `notes`（`String`，nullable）。 |
| RPC | `rpc/AddressBookRpc.java` | `ContactRequest`（保存 DTO）加 `notes`。 |
| 前端 store | `ui-src/src/stores/contacts.ts` | `Contact` interface 加 `notes?: string`。 |
| 前端表单 | `AddressBookTab.vue` | 加 `notes` ref + `v-textarea`（备注，多行）字段；`edit`/`reset` 处理 notes；`saveContact` 传 notes。 |

`notes` 为可选字段（空字符串/null 允许），不参与搜索匹配（搜索只匹配 email/nickname，沿用现有）。

### E. i18n — `en.ts` + `zh-CN.ts`

新增/复用 key（`contacts.*` 命名空间）：

| key | zh-CN | en |
|---|---|---|
| `contacts.edit` | 编辑 | Edit |
| `contacts.tagSearch` | 搜索标签… | Search tags… |
| `contacts.tagsMore` | +{count} | +{count} |
| `contacts.notes`（已有，启用） | 备注 | Notes |

`contacts.notes` 已存在于两个 i18n 文件但未使用，本次启用。其余需要的 key（title/search/filter/assign/manageTags/newTag 等）均已存在。

### F. 样式 — `styles.css`

新增少量全局类（与现有 `.panel-grid`/`.inline-fields`/`.surface` 风格一致）：

```css
.contact-row { display: flex; gap: 12px; align-items: flex-start; padding: 10px 4px; border-bottom: 1px solid var(--email-border); }
.contact-row__main { flex: 1; min-width: 0; }
.contact-avatar { width: 36px; height: 36px; border-radius: 50%; flex: 0 0 36px; display: flex; align-items: center; justify-content: center; font-weight: 700; color: #fff; }
.tag-overflow { display: inline-flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.tag-manager-list { max-height: 220px; overflow-y: auto; }
```

头像渐变背景可用一个固定渐变或按姓名 hash 取色（简单起见用单一品牌渐变 `linear-gradient(135deg, var(--email-accent), …)`）。

## 数据 / RPC 不变项

- RPC 方法名不变：`email_contacts_query`、`email_contact_save`、`email_contact_delete`、`email_tags_list`、`email_tag_save`、`email_tag_delete`、`email_tags_assign`。
- `email_contact_save` 参数仅新增可选 `notes`（后端 `trimToNull`，空值存 null）。
- 标签搜索是**前端本地过滤**（过滤已加载的 `store.tags`），不新增 RPC。

## 测试

### 前端（Vitest）
- `ManagementViews.test.ts`：现有用例断言 `data-testid="contact-bulk-tags"`、`tag-manager-open`、`contact-save`、`contact-tags`、`contact-email`。
  - **`tag-manager-open` 行为变化**：原是「打开弹窗」的按钮；现标签管理是常驻卡片，无弹窗开关。需调整该用例：改为断言标签管理卡片渲染（如 `data-testid="tag-manager-card"` 存在）且不再有弹窗触发。
  - 新增：列表项标签折叠（联系人有 3+ 标签时显示「+N」）；标签搜索框过滤标签列表。
- 新增备注字段交互测试（可选，轻量）。

### 后端（JUnit）
- `AddressBookServiceTest`（若存在）/ repository 测试：保存带 `notes` 的联系人 → 查询返回 `notes`；保存空 notes → 返回 null。
- 现有 `EmailManifestTest` 等 manifest 测试不受影响（不改 aiTools）。

### 视觉验证
- `npm run dev` 起前端，进通讯录，确认：C 详情行、标签胶囊可见、列表项标签折叠、右下标签管理卡（带搜索的列表）、备注字段。

## 涉及文件清单（全部在 `OfficialPlugins/plugin-email/` 内）

| 文件 | 类型 |
|---|---|
| `ui-src/src/components/AddressBookTab.vue` | 重构模板 + 脚本（列表项/布局/标签管理卡/备注字段） |
| `ui-src/src/styles.css` | 新增 contact-row / avatar / tag-overflow / tag-manager-list 类 |
| `ui-src/src/stores/contacts.ts` | `Contact` interface 加 `notes` |
| `ui-src/src/i18n/en.ts` | 新增 edit/tagSearch/tagsMore，启用 notes |
| `ui-src/src/i18n/zh-CN.ts` | 同上 |
| `ui-src/src/components/ManagementViews.test.ts` | 调整 tag-manager 断言 + 新增折叠/搜索测试 |
| `src/main/resources/db/h2/V5__add_contact_notes.sql` | 新建：ALTER 加 notes 列 |
| `src/main/resources/db/postgresql/V5__add_contact_notes.sql` | 新建：同上 |
| `src/main/resources/db/sqlite/V5__add_contact_notes.sql` | 新建：同上 |
| `src/main/resources/db/mysql/V5__add_contact_notes.sql` | 新建：同上 |
| `database/SchemaMigrator.java` | `LATEST_VERSION` 4 → 5 |
| `repository/AddressBookRepository.java` | ContactRow/ContactInput/SQL 加 notes |
| `service/AddressBookService.java` | ContactInput 加 notes，透传 |
| `model/Contact.java` | record 加 notes |
| `rpc/AddressBookRpc.java` | ContactRequest 加 notes |

## 验证

- 后端：`./mvnw -pl OfficialPlugins/plugin-email test`
- 前端：`cd OfficialPlugins/plugin-email/ui-src && npm test`
- 构建：`npm run build`（TS 类型检查，确认 notes 字段贯通）
- 视觉：浏览器进通讯录逐项核对 5 项决策。
- 端到端（可选）：重新打包 `.fyp` + `upload-native` 安装后，在 host 里操作通讯录确认。
