# FengYu 插件市场服务 — 计划 4：前端（支柱 4）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已落地的后端（计划 1-3，main @ `eb662d3`，148/148 测试）上实现**自带前端**：Vue 3.5 + TypeScript + Vuetify 3 / MD3 SPA，覆盖 8 个路由（访客浏览、登录/注册、账户、作者上传/提交、管理员审核/插件/用户），silent-refresh API 客户端，EN+ZH i18n，构建产物托管进 Spring Boot `static/` + SPA fallback。

**Architecture:** 在 marketplace 仓库的 `frontend/` 子目录建独立 Vue 项目（与后端同仓、独立打包）。`src/api/`（axios + silent-refresh 拦截器 + 类型）、`src/stores/`（Pinia：auth/account、catalog、submissions、reviews）、`src/router/`（vue-router + 角色守卫）、`src/views/`（8 页）、`src/i18n/`（EN+ZH）、`src/layouts/`（主壳）。构建期 `frontend-maven-plugin` 把 `dist/` 拷进后端 `resources/static/`；后端加 SPA fallback（未匹配 GET → index.html，排除 `/api/**`/`/marketplaces/**`/`/actuator/**`）。

**Tech Stack:** Vue 3.5、TypeScript、Vite、Vuetify 3、Pinia、vue-router、vue-i18n、axios。**不**共享 FengYu 主程序前端代码（独立仓库、独立打包）。

## ⚠️ 计划 4 执行前须知

- **JDK 21**（后端 Maven 用）：`JAVA_HOME=/Users/phoebej/Library/Java/JavaVirtualMachines/azul-21.0.12/Contents/Home`。
- **Node/npm**：本机需有 Node（Vite 5+ 需 Node 18+）。子 agent 先 `node -v` 确认。
- **仓库**：`fengyu-marketplace-server`（**非** FengYu）。SDD 子 agent 必须 `cd /Users/phoebej/Develop/Java/fengyu-marketplace-server`（计划 2/3 教训）。
- **后端已就绪**：计划 1-3 的端点都在（`/api/auth/*`、`/api/catalog/*`、`/api/submissions/*`、`/api/admin/*`、`/marketplaces/*`）。§3.2 错误契约（`code` 字段）+ `TOKEN_EXPIRED` 是 silent-refresh 的触发器。
- **refresh token 存储**：§8.2 说「httpOnly cookie（refresh）」——但后端 `/api/auth/login` 当前返回 `{accessToken, refreshToken, ...}`（refreshToken 在响应体里，非 cookie）。**v1 简化**：refreshToken 存内存（Pinia store，刷新页面丢——可接受，用户重登）；**或** 存 localStorage（持久但 XSS 风险）。**决策**：v1 存内存（store），刷新页面则重登；不引入 cookie 复杂度（后端没发 cookie）。文档里记这个简化。
- **测试**：前端用 Vitest（单测 stores/逻辑）+ 组件测试（@vue/test-utils）；端到端 Playwright 可选（v1 可只做构建冒烟 `npm run build` + 手测）。**v1 最小**：Vitest 覆盖 API 客户端的 silent-refresh 去重逻辑 + 路由守卫；页面用 `npm run build`（vue-tsc 类型检查）作门禁。

## 全局约束

- **EN+ZH i18n 键集镜像**（与主程序纪律一致；每加一个 key，两份文件都加）。
- **路由守卫**：`meta.roles` 声明；store `user.roles` 不匹配 → 跳 `/login`；访客可访问 `/` 与 `/login`/`/register`。
- **API base**：同源（`VITE_API_BASE=""`）；dev 期 Vite proxy `/api`→`:24057`。
- **conventional commits + emoji**。
- **不删/不改本任务之外的文件**。

## 文件结构（本计划产出）

```
fengyu-marketplace-server/
├── pom.xml                                       # Task 9（加 frontend-maven-plugin）
├── frontend/                                     # Task 1
│   ├── package.json
│   ├── vite.config.ts                            # Task 1（proxy + build outDir）
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── main.ts                               # Task 1（挂载 Vue + Vuetify + pinia + router + i18n）
│       ├── App.vue
│       ├── api/
│       │   ├── client.ts                         # Task 2（axios + silent-refresh 拦截器）
│       │   ├── auth.ts                           # Task 2（register/login/refresh/me）
│       │   ├── catalog.ts                        # Task 2（catalog/manifests/download）
│       │   ├── submissions.ts                    # Task 2（作者端点）
│       │   ├── reviews.ts                        # Task 2（管理员端点）
│       │   └── types.ts                          # Task 2（与后端 DTO 对齐）
│       ├── stores/
│       │   ├── auth.ts                           # Task 3（user/roles/tokens + login/logout/refresh）
│       │   ├── catalog.ts                        # Task 4（catalog 列表 + 过滤）
│       │   ├── submissions.ts                    # Task 6（作者提交）
│       │   └── reviews.ts                        # Task 7（管理员审核队列）
│       ├── router/
│       │   └── index.ts                          # Task 3（8 路由 + 角色守卫）
│       ├── layouts/
│       │   └── DefaultLayout.vue                 # Task 3（顶栏 + 侧栏 + 角色菜单）
│       ├── views/
│       │   ├── LoginView.vue / RegisterView.vue  # Task 4
│       │   ├── BrowseView.vue                    # Task 4（catalog 浏览 + 详情抽屉）
│       │   ├── AccountView.vue                   # Task 5
│       │   ├── AuthorSubmissionsView.vue         # Task 6
│       │   ├── AuthorUploadView.vue              # Task 6
│       │   ├── AdminReviewsView.vue              # Task 7
│       │   ├── AdminPluginsView.vue              # Task 8（概要）
│       │   └── AdminUsersView.vue                # Task 8（概要）
│       └── i18n/
│           ├── index.ts
│           ├── en.json
│           └── zh.json
└── src/main/java/fan/summer/marketplace/config/
    └── SpaFallbackConfig.java                    # Task 9（SPA fallback + 静态资源）
```

---

### Task 1: Vue 项目脚手架（package.json/vite/tsconfig/main.ts/Vuetify/Pinia/i18n/router 空壳）

**Files:**
- Create: `frontend/package.json`、`frontend/vite.config.ts`、`frontend/tsconfig.json`、`frontend/index.html`、`frontend/src/main.ts`、`frontend/src/App.vue`

**Interfaces:**
- Produces: 可 `npm install` + `npm run build` 的 Vue 项目；`main.ts` 挂载 Vuetify + Pinia + router（空壳）+ i18n（EN+ZH 空壳）。vite proxy `/api`→`:24057`。build 输出 `frontend/dist/`。

- [ ] **Step 1: 确认 Node 可用**

```bash
node -v   # 需 ≥18；否则子 agent 报 BLOCKED
```

- [ ] **Step 2: 写 `frontend/package.json`**（Vue 3.5 + Vite 5 + Vuetify 3 + Pinia + vue-router + vue-i18n + axios + TypeScript + Vitest）

```json
{
  "name": "fengyu-marketplace-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "preview": "vite preview",
    "test": "vitest run"
  },
  "dependencies": {
    "vue": "^3.5.0",
    "vuetify": "^3.7.0",
    "@mdi/font": "^7.4.0",
    "pinia": "^2.2.0",
    "vue-router": "^4.4.0",
    "vue-i18n": "^9.14.0",
    "axios": "^1.7.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.1.0",
    "vite": "^5.4.0",
    "vite-plugin-vuetify": "^2.0.0",
    "typescript": "^5.5.0",
    "vue-tsc": "^2.1.0",
    "@vue/test-utils": "^2.4.0",
    "vitest": "^2.1.0",
    "jsdom": "^25.0.0"
  }
}
```

- [ ] **Step 3: 写 `vite.config.ts`**（proxy + build outDir）

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'

export default defineConfig({
  plugins: [vue(), vuetify({ autoImport: true })],
  server: {
    proxy: {
      '/api': { target: 'http://127.0.0.1:24057', changeOrigin: true },
      '/marketplaces': { target: 'http://127.0.0.1:24057', changeOrigin: true }
    }
  },
  build: { outDir: 'dist' }
})
```

- [ ] **Step 4: 写 `tsconfig.json`**（strict + `@` 别名 → src）+ `index.html`（挂 `#app`）

- [ ] **Step 5: 写 `main.ts`**（挂 Vuetify + Pinia + router + i18n 空壳）+ `App.vue`（`<router-view />`）

- [ ] **Step 6: `npm install` + `npm run build`**（验证脚手架能起；空壳 build 通过）

- [ ] **Step 7: 提交**

```bash
git add frontend
git commit -m "✨ feat(frontend): Vue 3 + Vuetify 3 + Pinia + router + i18n scaffold"
```

---

### Task 2: API 客户端层（axios + silent-refresh + 类型 + 各域 API）

**Files:**
- Create: `frontend/src/api/{client,auth,catalog,submissions,reviews,types}.ts`
- Test: `frontend/src/api/client.test.ts`（silent-refresh 去重逻辑）

**Interfaces:**
- `client.ts`：axios 实例 + 请求拦截（附 Bearer）+ 响应拦截（401 + `code=TOKEN_EXPIRED` → 单次 refresh 去重 → 重放；refresh 失败 → 清登录态 + 跳 `/login`）。
- `types.ts`：与后端 DTO 对齐（`AccountUser`、`AuthTokens`、`UnifiedCatalogEntry`、`Submission`、`ReviewRecord`、`Plugin`、`PluginVersion`、`ApiError`、`ErrorCode`）。
- `auth.ts`：`register/login/refresh/logout/logoutAll/me/updateMe/changePassword/devices`。
- `catalog.ts`：`listCatalog(query)/getVersions/downloadUrl/manifestUrl`。
- `submissions.ts`：`upload/submit/withdraw/listMine/get`。
- `reviews.ts`：`listPending/get/approve/reject/listPlugins/unpublish/listUsers/...`。

- [ ] **Step 1-4: client.ts（silent-refresh 是核心——单例 Promise 去重）+ 类型 + 各域 API + Vitest 测 silent-refresh**

```bash
git commit -m "✨ feat(frontend): API client layer (axios + silent-refresh + typed domains)"
```

> **silent-refresh 关键**：模块级 `let refreshPromise: Promise<string> | null`；401 时若已有 refreshPromise 则 `.then` 它，否则新建；refresh 成功存新 token + 重放原请求；失败清登录态。Vitest 测：并发 N 个 401 → 只发 1 次 refresh。

---

### Task 3: auth store + 路由 + 主布局（DefaultLayout）

**Files:**
- Create: `frontend/src/stores/auth.ts`、`frontend/src/router/index.ts`、`frontend/src/layouts/DefaultLayout.vue`

**Interfaces:**
- `auth.ts`（Pinia）：state（user/roles/accessToken/refreshToken — **内存**），actions（login/register/logout/refresh/me），getters（isAuthenticated、hasRole）。
- `router/index.ts`：8 路由 + `meta.roles` + 全局 `beforeEach` 守卫（未登录访问受保护 → `/login`；角色不匹配 → `/login` 或 403）。
- `DefaultLayout.vue`：Vuetify 顶栏（品牌 + 账户菜单 + 语言切换）+ 侧栏（按角色显示菜单项：Browse/Account/Author/Admin）+ `<router-view />`。

- [ ] **Step 1-3: store + router（8 路由占位 view 组件）+ 布局 + Vitest 测守卫**

```bash
git commit -m "✨ feat(frontend): auth store + router (8 routes + role guard) + DefaultLayout"
```

---

### Task 4: 认证页 + 浏览页（LoginView/RegisterView/BrowseView）

**Files:**
- Create: `views/LoginView.vue`、`RegisterView.vue`、`BrowseView.vue`；`stores/catalog.ts`；`i18n/{en,zh}.json`（auth + browse 命名空间）

- [ ] **Step 1-3: Login/Register（表单 + 调 auth store）+ Browse（catalog store + Vuetify 卡片网格 + sourceType 徽章 + 详情抽屉）+ i18n**

```bash
git commit -m "✨ feat(frontend): Login/Register/Browse views + catalog store + i18n"
```

---

### Task 5: AccountView（个人资料 + 改密码 + 设备列表）

**Files:**
- Create: `views/AccountView.vue`；i18n account 命名空间

- [ ] **Step 1-2: Account（资料编辑 + 改密码 + 设备列表 + 登出全部）+ i18n**

```bash
git commit -m "✨ feat(frontend): Account view (profile/password/devices/logout-all)"
```

---

### Task 6: 作者页（AuthorSubmissionsView + AuthorUploadView）

**Files:**
- Create: `views/AuthorSubmissionsView.vue`、`AuthorUploadView.vue`；`stores/submissions.ts`；i18n author 命名空间

- [ ] **Step 1-2: 提交列表（状态过滤）+ 上传（拖拽 + 校验报告 ✓/✗ + 提交审核）+ i18n**

```bash
git commit -m "✨ feat(frontend): Author submissions + upload views (drag-drop + validation report)"
```

---

### Task 7: AdminReviewsView（审核队列）

**Files:**
- Create: `views/AdminReviewsView.vue`；`stores/reviews.ts`；i18n admin 命名空间

- [ ] **Step 1-2: 待审列表 + 详情（manifest + 校验报告 + 作者）+ 批准/拒绝 + i18n**

```bash
git commit -m "✨ feat(frontend): Admin reviews view (queue + detail + approve/reject)"
```

---

### Task 8: AdminPluginsView + AdminUsersView（概要）

**Files:**
- Create: `views/AdminPluginsView.vue`、`AdminUsersView.vue`；i18n admin 扩展

- [ ] **Step 1-2: 插件管理（已发布 + 下架）+ 用户管理（列表 + 角色 + 禁用）+ i18n**

> v1 概要实现（后端用户管理端点尚未建——见计划 1；如缺端点则前端先做列表展示，写操作标「待后端支持」）。

```bash
git commit -m "✨ feat(frontend): Admin plugins + users views (list + manage)"
```

---

### Task 9: 后端集成（SpaFallbackConfig + frontend-maven-plugin + 构建）

**Files:**
- Modify: `pom.xml`（加 `frontend-maven-plugin`：构建期 `npm install` + `npm run build`，输出到 `resources/static/`）
- Create: `src/main/java/fan/summer/marketplace/config/SpaFallbackConfig.java`（SPA fallback：未匹配 GET → `index.html`，排除 `/api/**`/`/marketplaces/**`/`/actuator/**`）

- [ ] **Step 1-3: pom 加 frontend-maven-plugin + SpaFallbackConfig（`WebMvcConfigurer` + view forward）+ 验证 `./mvnw package` 产出含前端的 jar + 跑后端 `./mvnw test`（含前端构建，148/148 不回归）**

> **SpaFallbackConfig 关键**：用一个 `WebMvcConfigurer` 的 `addResourceHandlers` 把 `/` 指向 `classpath:/static/`，再加一个控制器/view 把未匹配的 GET（非 `/api`/`/marketplaces`/`/actuator`）forward 到 `/index.html`。Spring Boot 4.1 的 SPA fallback 用法。

```bash
git commit -m "✨ feat(frontend): backend integration (SpaFallbackConfig + frontend-maven-plugin build)"
```

---

## 完成判据

- `cd frontend && npm run build` 通过（vue-tsc 类型检查 + Vite build）。
- `cd frontend && npm run test`（Vitest）通过（silent-refresh 去重 + 路由守卫）。
- `./mvnw package`（JDK 21）产出含前端 `static/` 的 jar，`./mvnw test` 148/148 不回归（+ 前端构建无失败）。
- 起 jar，浏览器访问 `/` → SPA 加载 → 浏览 catalog（需后端有已发布插件）→ `/login` 登录 → `/author/upload` 上传 → `/admin/reviews` 审核。
- EN+ZH i18n 键集镜像。
- `git log` 清晰 conventional commits。

## 下一步

前端落地后，市场服务的 4 个支柱全部完成（认证 + 发布 + 聚合 + 前端）。后续可进入：跨仓库后续工单（主程序 `PluginMarketplaceService` sha256 兼容、login 枚举硬化、unpublish 端点、Codex mcp 字符串 resolver），或正式 release。
