# Windows 无沙箱插件运行开关 — 设计文档

**日期**: 2026-08-03
**状态**: 已设计，待评审
**作者**: MuskStark (经 brainstorming 协作)

## 1. 问题与动机

### 现象
在 Windows（无原生进程沙箱）平台上，直接运行插件工具后端会返回 **HTTP 500**，前端提示"没有沙箱环境"。

### 根本原因（已通过 systematic-debugging 确认）
失败链：

1. `ProcessSandbox.detect()` (`FengYu/.../security/ProcessSandbox.java:175-182`) 在 Windows 上返回 `Backend.NONE` —— 它只认 Linux+bwrap 和 macOS+sandbox-exec。
2. `ProcessSandbox.plugin()` (`:100-101`) 在 `backend == NONE` 时抛 `IllegalStateException("Plugin workers require a supported native process sandbox")`。
3. `PluginProcessManager.start()` (`:201-203`) 调用 `sandbox.plugin(...)` → 抛异常。
4. `GlobalExceptionHandler.handlePluginFailure` (`:25-31`) 把 `IllegalStateException` 映射成 **HTTP 500**。

这是**故意的 fail-closed 设计**，不是 bug：
- `ProcessSandboxTest.pluginWorkerFailsClosedWithoutNativeSandbox()` 显式断言 NONE 下 `plugin()` 必须抛异常，测试名就叫 `FailsClosed`。
- `.github/workflows/fengyu-release.yml:102` 注释："deliberate fail-closed IllegalStateException (no sandbox)"。
- 理由：插件 worker 是进程外的第三方不受信代码，原生沙箱是它唯一的隔离屏障（网络隔离 + 文件写限制）。没有沙箱就放它跑 = 任意代码执行。

### 关键不对称
`PluginProcessManager:105`：`fullAccess = (current turn mode == FULL_ACCESS)`。
- **AI 路径**：用户在对话里选 FULL_ACCESS 模式 → 走 `unrestricted()` → Windows 上也能跑（有逃生口）。
- **REST 直连路径**（插件的 iframe UI 调自己的 worker，`POST /api/plugin-runtime/{id}/invoke`）：没有任何地方设置 `AiPermissionContext` ThreadLocal → 默认 `ASK_FOR_APPROVAL` → `fullAccess=false` → **永远走 `sandbox.plugin()` → 永远 500**。

本设计为 Windows 用户提供一条**显式、知情、可审计**的逃生通道，而非继续硬 500。

## 2. 决策记录

经 brainstorming 确认的关键决策：

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 解锁范围 | **只解锁插件 worker**，AI 命令审批门不动 | 最小修复；AI 命令已有兼容模式（不抛异常，approval-gated），不混入 |
| 与 FULL_ACCESS 关系 | **开关是独立总闸**：`effectiveUnrestricted = fullAccess \|\| unsandboxedPluginsEnabled` | 最简语义；开关是平台级，FULL_ACCESS 是每轮 AI 级，两者正交 |
| 可见性 | **仅 NONE 平台可见可设**；Linux/macOS 上 PUT 该字段返 400 | 防止用户在有沙箱的平台上误关已有保护 |
| 授权摩擦 | **开关 + 确认对话框**（on→on 需确认，on→off 直接生效） | 重大安全降级需防误触；关闭保护不需确认 |
| 后端接入方案 | **方案 A**：开关持久化在 `app_setting`，由 `PluginProcessManager` 消费；`ProcessSandbox` 不改 | 保留安全原语的纯净契约，fail-closed 测试不变 |
| 前端控件 | **现有 `.cx-segment` 按钮组风格**，不引入 v-switch | 与 Settings.vue 现有 theme/language 控件视觉一致 |
| 前端测试 | **不引入新测试框架**（项目前端无 vitest） | 靠手动验证；后端单测覆盖核心逻辑 |

## 3. 架构

### 不变性
- `ProcessSandbox.plugin()` 在 `backend == NONE` 时**仍然抛异常**。安全原语的契约不变，现有 `pluginWorkerFailsClosedWithoutNativeSandbox()` 测试继续通过、不改。
- 开关的语义是"消费者选择 `unrestricted()` 通道"，而非"沙箱原语放宽"。`PluginProcessManager` 已经因为 `FULL_ACCESS` 而拥有走 `unrestricted` 的能力，开关只是第二个触发该通道的条件。

### 数据流

```
[用户在 Settings 页确认]
        │ (autosave partial PUT)
        ▼
SettingsController.put()  ──写──▶  app_setting 表 (key="plugin.unsandboxed", value="true"/"false")
        │                                   ▲
        │ 平台闸门:                          │
        │   ProcessSandbox.isNativeSandboxAvailable()==true  → 抛 IllegalArgumentException → 400
        ▼                                   │ 读取
AiConfigServiceHeadless.setUnsandboxedPluginsEnabled()
                                          (静态 facade, 对齐 sidebar.collapsed)
        ▼
PluginProcessManager.start() 读 AiConfigServiceHeadless.isUnsandboxedPluginsEnabled()
        │
        ▼
effectiveUnrestricted = fullAccess || unsandboxedPluginsEnabled
        │
        ├─ true  → sandbox.unrestricted(command)   [Windows 上也能跑，backend=NONE，无异常]
        └─ false → sandbox.plugin(...)             [Windows 上抛 IllegalStateException → 500，原行为]
```

### 组件职责（边界）

| 组件 | 职责 | 本设计是否改 |
|------|------|-------------|
| `ProcessSandbox` | 构建 OS 沙箱命令；NONE 下 `plugin()` fail-closed | **不改** |
| `AiConfigServiceHeadless` | 持久化开关（读写 `app_setting`） | 改：加 key + 静态 getter/setter |
| `SettingsController` | REST 读写设置 + 平台闸门（有沙箱平台 400） | 改：get/put 加字段 + 闸门 + 审计日志 |
| `PluginProcessManager` | 消费开关，决定走 unrestricted/plugin | 改：`:105` 一行 + 审计日志 |
| 前端 Settings.vue | 仅 NONE 平台显示开关 + 确认对话框 | 改：加一行 + store + i18n |

## 4. 后端改动（精确落点）

### 4.1 持久化开关 — `AiConfigServiceHeadless.java`

新增 key 常量（对齐 `sidebar.collapsed` 的 dotted-lowercase 命名）：
```java
private static final String PLUGIN_UNSANDBOXED_KEY = "plugin.unsandboxed";
```

新增静态方法，完全照抄 `getSidebarCollapsed`/`setSidebarCollapsed`（`AiConfigServiceHeadless.java:82-89`）模式：
```java
public static boolean isUnsandboxedPluginsEnabled() {
    return Boolean.parseBoolean(INSTANCE.readSetting(PLUGIN_UNSANDBOXED_KEY, "false"));
}

public static void setUnsandboxedPluginsEnabled(boolean enabled) {
    INSTANCE.writeSetting(PLUGIN_UNSANDBOXED_KEY, String.valueOf(enabled));
}
```

默认值 `"false"`（fail-closed：新用户、无设置行、读取出错时都返回 false）。

### 4.2 设置读写端点 — `SettingsController.java`

**构造函数**：**无需新增注入**。平台闸门用静态方法 `ProcessSandbox.isNativeSandboxAvailable()`（对齐 `ChatToolApprovalGate:113` 的调用形式），不需要 `ProcessSandbox` bean 实例。

**`get()`**（`:81-89`）：加一行：
```java
out.put("unsandboxedPlugins", config.isUnsandboxedPluginsEnabled());
```

**`put()`**（`:91-110`）：加分支（照抄 `sidebarCollapsed` 的 Boolean/String 双处理）+ 平台闸门：
```java
Object unsandboxed = body.get("unsandboxedPlugins");
if (unsandboxed instanceof Boolean b) {
    applyUnsandboxedPlugins(b);
} else if (unsandboxed instanceof String s) {
    applyUnsandboxedPlugins(Boolean.parseBoolean(s));
}
```

私有 helper 封装闸门 + 审计：
```java
private void applyUnsandboxedPlugins(boolean enabled) {
    // 平台闸门：有原生沙箱的平台不允许开启此开关。
    // 用 IllegalArgumentException → 由 GlobalExceptionHandler 映射为 HTTP 400。
    // isNativeSandboxAvailable() 是静态方法，统一用静态调用形式（对齐 ChatToolApprovalGate:113）。
    if (enabled && ProcessSandbox.isNativeSandboxAvailable()) {
        throw new IllegalArgumentException(
            "Unsandboxed plugin mode is only available on platforms without a native process sandbox");
    }
    config.setUnsandboxedPluginsEnabled(enabled);
    log.info("Plugin unsandboxed mode {} (platform: {})",
        enabled ? "ENABLED" : "disabled",
        ProcessSandbox.isNativeSandboxAvailable() ? "native" : "none");
}
```

> 注：关闭（`enabled=false`）在所有平台都接受——关闭保护总是安全的。只有"在有沙箱平台上**开启**"才被 400 拒绝。闸门用 `sandbox.isNativeSandboxAvailable()`（实例方法转发静态 `detect()`）。

### 4.3 消费开关 — `PluginProcessManager.java:105`

把：
```java
boolean fullAccess = AiPermissionContext.current() == AiPermissionMode.FULL_ACCESS;
```
改为：
```java
boolean fullAccess = AiPermissionContext.current() == AiPermissionMode.FULL_ACCESS
        || AiConfigServiceHeadless.isUnsandboxedPluginsEnabled();
```

下游 `start()` 里 `:201-203` 的 `fullAccess ? sandbox.unrestricted(command) : sandbox.plugin(...)` **一行都不用改**——开关 ON 时 `fullAccess=true` 自动走 `unrestricted`，Windows 上不再抛异常。

### 4.4 审计日志 — `PluginProcessManager.java:238-242`

现有的 `isolation` 日志行已经会反映 `sandbox=none`。为让开关状态在日志里明确，把 `isolation` 字符串追加开关标记（仅当开关生效时有意义）：
```java
String isolation = "sandbox=" + launch.backend().id()
        + ", network=" + (fullAccess || allowNetwork ? "allowed" : "isolated")
        + ", broadFileWrite=" + broadFileWrite
        + (AiConfigServiceHeadless.isUnsandboxedPluginsEnabled() ? ", unsandboxedOverride=true" : "");
```

### 4.5 `ProcessSandbox` 不改
`plugin()` 仍 fail-closed。安全契约测试 `pluginWorkerFailsClosedWithoutNativeSandbox()` 继续通过。

### 4.6 不需要的
- 无 DB 迁移（复用 `app_setting` 表新行；`ddl-auto: update` 无需任何 schema 变更）。
- 无新 entity。
- 无新 bean。
- 无 `@ConditionalOnXxx`。

## 5. 前端改动（精确落点）

### 5.1 类型与 API 层
- `frontend/src/api/types.ts` — `AppSettings` 接口加 `unsandboxedPlugins: boolean`。
- `frontend/src/api/client.ts` — 无需改（`getSettings`/`putSettings(partial)` 已是泛型 partial PUT）。
- `ProcessIsolationStatus` 已有 `compatibilityMode: boolean`，用作可见性闸门。

### 5.2 Pinia store — `frontend/src/stores/settings.ts`
照抄 `setSidebarCollapsed`（`:65-68`）：加 `setUnsandboxedPlugins(b)`，本地 ref 变更 + `update({ unsandboxedPlugins: b })`。

### 5.3 Settings.vue — "Runtime & security" 区块（`:187-216`）

在现有 "Process isolation" chip 行下方、MCP 行之前，插入新一行（仅 NONE 平台渲染）：
```html
<div v-if="isolationStatus?.compatibilityMode" class="cx-setting-row">
  <div class="cx-setting-row__label">
    <i class="mdi mdi-shield-alert-outline" />
    <span>{{ $t('settings.unsandboxedPluginsTitle') }}</span>
  </div>
  <div class="cx-segment">
    <button
      :class="{ active: !settings.unsandboxedPlugins }"
      @click="settings.setUnsandboxedPlugins(false)"
    >{{ $t('settings.unsandboxedOff') }}</button>
    <button
      :class="{ active: settings.unsandboxedPlugins }"
      @click="confirmEnableUnsandboxed()"
    >{{ $t('settings.unsandboxedOn') }}</button>
  </div>
</div>
<div v-if="isolationStatus?.compatibilityMode" class="cx-muted" style="color: var(--md-sys-color-error); font-size: 12px">
  {{ $t('settings.unsandboxedPluginsWarn') }}
</div>
```

确认逻辑（on→on 需确认）：
```ts
const showUnsandboxedConfirm = ref(false)
function confirmEnableUnsandboxed() {
  if (settings.unsandboxedPlugins) return
  showUnsandboxedConfirm.value = true
}
async function doEnableUnsandboxed() {
  showUnsandboxedConfirm.value = false
  await settings.setUnsandboxedPlugins(true)
}
```

确认对话框用 `v-dialog`（Vuetify 已是依赖）。on→off 直接调 `settings.setUnsandboxedPlugins(false)`，无确认（关闭保护不需确认）。

> 可见性闸门 `v-if="isolationStatus?.compatibilityMode"` 对应后端 400 闸门：Linux/macOS 上 `compatibilityMode=false`，该行不渲染，前端不会发送该字段。

### 5.4 i18n — `frontend/src/i18n/en.json` & `zh.json`

`settings` 节点下新增（EN / ZH 结构对齐）：
- `unsandboxedPluginsTitle`: "Unsandboxed plugins" / "无沙箱运行插件"
- `unsandboxedOff`: "Off" / "关闭"
- `unsandboxedOn`: "Allow" / "允许"
- `unsandboxedPluginsWarn`: "Plugins will run without process isolation. Only enable if you trust all installed plugins." / "插件将以无进程隔离方式运行。仅在你信任所有已安装插件时启用。"
- `unsandboxedPluginsConfirm`: "Disable plugin process isolation? Plugin workers will run with the same privileges as the app, with no sandbox boundary." / "禁用插件进程隔离？插件 worker 将以与应用相同的权限运行，无沙箱边界保护。"
- 确认/取消按钮复用现有 `common.confirm` / `common.cancel`（若不存在则新增）。

### 5.5 与现有兼容模式 chip 的关系
现有 "Process isolation" chip 行（`:194-202`）**保留不动**。它回答"平台有没有沙箱"（`sandboxActive` / `compatibilityApproval`）。新开关回答"用户要不要在没沙箱时也跑插件"。两者正交：开启开关后 chip 仍显示"兼容模式"，这是对的。

## 6. 测试设计

### 6.1 后端单元测试（JUnit，对齐现有 `ProcessSandboxTest`/`PluginProcessManagerTest` 风格）

1. **`AiConfigServiceHeadless` 开关默认 false、可读写**
   - mock `AppSettingRepository`。
   - `isUnsandboxedPluginsEnabled()` 默认 false（无行）。
   - `setUnsandboxedPluginsEnabled(true)` 写入 key `plugin.unsandboxed` 值 `"true"`。
   - 读回 true。

2. **`SettingsController` NONE 平台接受、有沙箱平台 400**
   - 闸门用静态 `ProcessSandbox.isNativeSandboxAvailable()`，测试无法直接注入伪造。
   - **策略**：在 CI（Linux/macOS，`isNativeSandboxAvailable()==true`）上，PUT `{unsandboxedPlugins:true}` 必须抛 `IllegalArgumentException`（→ 400）；PUT `{unsandboxedPlugins:false}` 必须成功（关闭保护总是安全）。
   - NONE 平台的接受路径由手动验证清单覆盖（section 6.4），因为单测环境无法把 detect() 强制为 NONE。
   - 替代方案（如需单测覆盖开启路径）：把 `applyUnsandboxedPlugins` 的闸门判断抽成 package-private 可覆写方法（`boolean nativeSandboxAvailable()`），测试子类覆写。**默认不抽**（YAGNI），只在手动验证不足时考虑。

3. **`PluginProcessManager` 开关 ON 走 unrestricted**
   - 用 NONE 后端的 `ProcessSandbox`。
   - 开关 ON：`start()` 不再抛异常，`launch.backend()==NONE`（验证走 unrestricted 路径）。
   - 开关 OFF：维持抛 `IllegalStateException`（fail-closed 不变）。
   - **可测试性**：测试通过 `AiConfigServiceHeadless` 的 `@PostConstruct init()` 机制塞入测试 INSTANCE（它有 `static volatile INSTANCE` 字段），或直接构造实例并调用 `init()` 让静态读生效。不为此在生产代码加可注入 hook（YAGNI）。

4. **`ProcessSandboxTest.pluginWorkerFailsClosedWithoutNativeSandbox()` 不动**
   - 这是安全契约测试，必须继续通过，证明沙箱原语没被放宽。

### 6.2 前端测试
项目 `frontend/` 当前无测试框架（仅 vite，无 vitest 配置）。**不引入新框架**。靠手动验证。

### 6.3 `scripts/e2e-smoke.sh`
不改。它跑在有沙箱的 CI 上，新开关不在那里生效。Windows/无 bwrap 环境靠本地手测。

### 6.4 手动验证清单
- [ ] Windows 上启动后端，`GET /api/security/process-isolation` 返回 `compatibilityMode:true`。
- [ ] Settings 页"Runtime & security"区块出现新开关行（仅 NONE 平台）。
- [ ] 点"允许"弹出确认对话框；确认后插件能调用（`POST /api/plugin-runtime/{id}/invoke` 不再 500）。
- [ ] 后端日志出现 `Plugin unsandboxed mode ENABLED (platform: none)`。
- [ ] 重启后端，开关状态保持（持久化生效）。
- [ ] macOS/Linux 上该行不渲染；直接 PUT `{unsandboxedPlugins:true}` 返 400。

## 7. 安全考量

- **默认 fail-closed**：开关默认 OFF，Windows 用户初始仍会 500。这是有意的——必须用户主动、知情地开启。
- **仅 NONE 平台可见可设**：Linux/macOS 有原生沙箱，用户没理由关它；后端硬 400 拦截，前端不渲染。
- **确认对话框防误触**：开启需二次确认；关闭不需（关闭保护无害）。
- **审计日志**：开关变更走 `SettingsController` 的 SLF4J `log.info`；每次插件 worker 启动走 `PluginProcessManager` 现有 `isolation` 日志行（追加 `unsandboxedOverride` 标记）。两条都落 `~/.fengyu/logs/fengyu.log`（logback 滚动文件）。
- **不影响 AI 命令审批门**：`ChatToolApprovalGate` 的 `isNativeSandboxAvailable()` 逻辑不动。AI 跑命令在 Windows 上仍维持兼容模式（approval-gated），不因本开关放宽。
- **不放宽安全原语**：`ProcessSandbox.plugin()` 的 fail-closed 抛异常保留。开关是"消费者选 unrestricted 通道"，不是"沙箱放宽"。

## 8. 影响范围（受影响文件清单）

| 文件 | 改动类型 |
|------|---------|
| `FengYu/.../ai/service/AiConfigServiceHeadless.java` | 加 key 常量 + 2 个静态方法 |
| `FengYu/.../web/controller/SettingsController.java` | get/put 字段 + 闸门 helper（静态调 ProcessSandbox）+ 审计 |
| `FengYu/.../plugin/runtime/PluginProcessManager.java` | `:105` 一行改 + `:238-242` 日志追加 |
| `FengYu/.../security/ProcessSandbox.java` | **不改** |
| `frontend/src/api/types.ts` | `AppSettings` 加字段 |
| `frontend/src/stores/settings.ts` | 加 setter |
| `frontend/src/views/Settings.vue` | 加开关行 + 确认对话框 |
| `frontend/src/i18n/en.json` | 加 6 个 key |
| `frontend/src/i18n/zh.json` | 加 6 个 key（结构对齐） |
| 后端测试（3 个测试类/方法） | 新增 |

无 DB 迁移、无新 entity、无新 bean、无 CI 工作流改动、无 docs 自动同步需求（非发布改动）。

## 9. 非目标 (YAGNI)

- ❌ 粒度到单插件的授权（保持平台级总闸）。
- ❌ Windows 原生沙箱后端实现（Job Objects / AppContainer）——工作量过大，不在本设计范围。
- ❌ AI 命令审批门的放宽。
- ❌ 新建独立 audit 事件表（用现有 SLF4J 滚动日志）。
- ❌ 前端测试框架引入。
- ❌ 可注入的可测试性 hook（用现有 INSTANCE 静态注入）。
