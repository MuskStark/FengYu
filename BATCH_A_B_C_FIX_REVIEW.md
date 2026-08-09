# 批次 A / B / C 修复复审报告

> 本文保留 2026-08-09 修复前复审结论作为历史记录。自主修复后的状态与验证结果见
> [BATCH_A_B_C_FIX_IMPLEMENTATION_REPORT.md](BATCH_A_B_C_FIX_IMPLEMENTATION_REPORT.md)。

审查日期：2026-08-09  
审查基线：`f1016b0` + 当前未提交工作树（58 files changed, 2480 insertions, 232 deletions）  
审查范围：批次 A（P0-1/2/3/4/8）、批次 B（P0-5/6/7/9）、批次 C（P1-1～P1-9）  
约束：仅审查并在主项目根目录生成本报告；未修改业务代码。

## 1. 结论

**当前仍不建议进入 Beta 发布。**

- 18 个整改项中：**10 项完成、6 项部分完成、2 项未完成**。
- 唯一仍然明确阻断 Beta 的 P0 是 **P0-8 官方插件可信发布链未闭环**：CLI 已生成 `.fyp.sha256`，宿主也强制要求 sidecar，但发布工作流只复制、上传和装配 `*.fyp`，最终安装包缺少校验文件，官方插件会被宿主跳过。
- 批次 B 本轮已全部达到代码级闭环；上轮遗留的 P0-6 更新互斥 TOCTOU 和 P0-9 未签名自动更新问题均已补强。
- 批次 C 仍有数据库回收/原子性、日志游标、双向帧限制、Windows hook 清理、SSE 顺序等缺口。它们多数为 P1，但应在 Beta 前至少修掉会造成数据残留、协议内存放大和日志乱序的部分。

## 2. 最高优先级发现

### [P0] 官方插件 sidecar 未进入发布产物，可信安装链实际不可用（P0-8）

证据：

- `toolchain/cli/src/build.mjs` 在每个 `.fyp` 旁生成 `.fyp.sha256`。
- `OfficialPluginSeeder` 对缺失或不匹配的 sidecar fail-closed，拒绝安装官方插件。
- `.github/workflows/fengyu-release.yml:137-174` 只复制 `*.fyp` 到 staging，并只上传 `staging/plugins/*.fyp`。
- Web/desktop 后续装配同样只复制 `inputs/*.fyp`。
- `scripts/release-workflow.test.mjs` 当前 11 项测试全部通过，但没有断言 sidecar 被 staging、上传并打入 Web/desktop 包，因而未能发现该断链。

影响：发布包首次启动时，内置官方插件没有所需 sidecar，Seeder 会跳过它们；“官方身份 + 完整性校验”的修复在真实发布物中无法工作。

修复要求：发布工作流必须成对复制、上传、下载和装配 `.fyp` 与 `.fyp.sha256`，并新增 release contract test 验证 Web 与两种 desktop 产物均包含 sidecar。若目标是来源真实性而不仅是传输完整性，还需由受信任发布密钥签名或将预期 digest 固化在受信任清单中；可随包一起被替换的普通 sidecar 本身不是真实性信任根。

### [P1] SSE “原子快照”仍存在回放/实时乱序与数据竞争（P1-8）

证据：

- `PluginLogStore.subscribeWithSnapshot()` 在返回订阅对象前就启动虚拟线程 drainer（`PluginLogStore.java:132-149`）。
- Controller 在订阅返回后才写入普通 `long[] replayHighWater`，再同步回放快照（`PluginRuntimeController.java:104-118`）。
- drainer 可在 high-water 仍为 `-1` 时发送新日志，也可与快照回放并发调用 `SseEmitter.send()`；该数组没有 `volatile`/原子可见性保证。
- 回放期间发送失败时，`emitter.complete()` 发生在 completion/error callbacks 注册之前，订阅可能无法及时注销。

影响：高并发写日志时仍可能出现实时项先于历史项、重复/乱序或订阅泄漏。当前测试未覆盖此时序。

修复要求：在单一有序队列中先排入快照再开放 live delivery，或以明确的 replay barrier 阻塞 drainer；不要用跨线程普通数组做 high-water。终止回调应在任何 send 之前注册，并在回放失败路径直接执行 unsubscribe。

### [P1] 数据库回收记录仍可能被错误删除，且没有真正的重试器（P1-3）

证据：

- DDL 抛错时确实会保留记录并标为 `DELETE_PENDING`。
- 但当 datasource 配置缺失、类型不匹配或 admin 用户缺失时，代码不会执行 DDL，却仍在 `PluginDbProvisioner.java:121-136` 删除记录并报告成功，数据库用户/schema 可能因此永久失联。
- 注释声称可由 background sweep 重试，但代码库中没有 DELETE_PENDING sweep/scheduler；也没有向 UI 暴露该状态的查询路径。

影响：卸载时可能留下无法追踪的数据库账号或 schema，且用户会收到错误的“已清理”语义。

修复要求：任何不能确认 DDL 成功的路径都保留 `DELETE_PENDING`；实现可观测的重试器/手动重试 API，并让 UI 区分“包已卸载但数据待清理”。

### [P1] provision 仍不是原子/可恢复操作（P1-5）

证据：`PluginDbProvisioner.java:89-104` 先逐条执行自动提交 DDL，再写本地 store。中途某条 DDL 失败，或 DDL 全部成功后 `store.put()` 失败，都会留下没有记录的用户/schema。新增 `ALTER USER/ROLE` 只修复了“残留账号密码不匹配”，没有修复事务、补偿或状态机问题；也没有 MySQL/PostgreSQL 容器级恢复测试。

修复要求：引入 `CREATING/ACTIVE/DELETE_PENDING` 持久状态和幂等恢复；支持事务的数据库应显式事务化，不支持完整事务的 DDL 则需要补偿清理；增加 H2、MySQL、PostgreSQL 故障注入/容器测试。

### [P1] Jobs 日志上限实现存在游标与“字节”计算错误（P1-2）

证据：

- 异步任务顶层异常现已记录完整 stack trace，日志也有 5000 行/2 MiB 双上限，这是有效修复。
- 但 `Jobs.java:183/190` 使用 `String.length()` 作为 byte 数，对非 ASCII 文本并非 UTF-8 字节数。
- `snapshot()` 返回的是绝对 cursor（`dropped + size`），读取时却直接把请求 cursor 当当前队列下标（`Jobs.java:201-210`）。发生淘汰后，客户端会跳过仍未读取的新日志。

修复要求：以 UTF-8 实际字节计数；读取起点应按 `max(0, cursor - droppedLogs)` 换算，并增加多字节文本和溢出后连续轮询测试。

### [P1] JSON-RPC 仅接收侧有限制，未完成“输入输出双向”约束（P1-6）

宿主 stdout/stderr、SDK stdin、DevKit socket stdin 已改为 bounded line reader，能够阻止无换行输入无限增长。但宿主写入 worker 的请求、SDK/DevKit 写出的响应仍在完整序列化后直接 `println`，没有发送前的 frame size 校验；超大业务结果仍可先在 worker 内存中构造完整字符串。逐 UTF-16 `char` 编码计数也会错误计算 surrogate pair 的 UTF-8 长度。

修复要求：四个方向都在写入前按 UTF-8 byte length 拒绝超限帧；读取侧用 byte-oriented framing 或正确处理 code point/decoder；增加精确边界、emoji、无换行超限和超大响应测试。

### [P1] Windows Job hook 失败清理只修了插件路径（P1-7）

`PluginProcessManager` 现已在 `onStarted` 抛错时销毁刚启动的 worker 并关闭已创建的 Job handle；但 `CommandExecuteTool.java:97-105` 仍直接调用 hook，异常路径没有销毁刚启动的命令进程，也没有关闭可能已写入的 handle。

修复要求：命令执行路径采用与插件 worker 相同的 `try/catch/finally` 清理协议，并增加 hook 在“创建 handle 后抛错”的 Windows 单测。

## 3. 分批次状态

| 批次 | 编号 | 状态 | 复审结论 |
| --- | --- | --- | --- |
| A | P0-1 | 完成 | Worker 环境已改为正向 allowlist；`JAVA_TOOL_OPTIONS`、`JAVA_OPTS`、`XAUTHORITY` 不再继承。 |
| A | P0-2 | 完成 | 包目录只读、manifest 与整包 digest 启动前复验、缺记录 fail-closed；旧官方插件通过可信 archive 重建基线。发布侧仍受 P0-8 影响。 |
| A | P0-3 | 完成（代码级） | macOS 明示 reduced/advisory，Linux fail-closed，Windows Job Object 树终止逻辑存在；真实 Windows/Linux runner 验证仍需发布门禁覆盖。 |
| A | P0-4 | 完成 | AI `FULL_ACCESS` 不再隐式解除插件 OS 沙箱，仅显式 host-wide 开关可解除。 |
| A | P0-8 | **未完成 / 阻断** | namespace/official 标记已限制，整包 digest 已实现，但 sidecar 发布装配断链，且普通 sidecar 不提供独立真实性。 |
| B | P0-5 | 完成 | response 到达后原子 `pending.remove(id)`，超时/错误路径也回收；有回归测试。 |
| B | P0-6 | 完成 | per-plugin `ReentrantLock` 将 updating check、worker acquire 与 beginUpdate/stop 串行化，修掉上轮 TOCTOU。 |
| B | P0-7 | 完成 | Java 21 宿主测试 377 项通过（2 项跳过），相关 flaky timing 已调整。 |
| B | P0-9 | 完成 | 打包元数据 `fengyu.signedRelease` 默认 false；未签名包在 check 前关闭 autoDownload/autoInstallOnQuit，仅提示手动下载。当前发布工作流不设置 true。 |
| C | P1-1 | 完成 | 宿主和 SDK handler 入口只记录参数 key，不记录值；新增 SDK 回归测试。 |
| C | P1-2 | 部分完成 | 顶层异常 stack 与日志容量上限已加；UTF-8 byte 计数和淘汰后 cursor 仍错误。 |
| C | P1-3 | 部分完成 | DDL 异常会保留 DELETE_PENDING；缺配置/admin 路径仍删记录，且无后台重试/UI 状态。 |
| C | P1-4 | 部分完成 | uninstall 会 best-effort 删除 `plugin-data/<id>`；没有“保留/删除”选择，删除失败只写日志仍返回成功。 |
| C | P1-5 | 部分完成 | 残留账号密码会 rotate；DDL + store 仍非原子且无补偿/恢复状态机。 |
| C | P1-6 | 部分完成 | 接收侧有上限；发送侧、surrogate 精确计数和双向测试未闭环。 |
| C | P1-7 | 部分完成 | plugin worker hook 失败会清理；AI command 路径仍可能遗留进程/handle。 |
| C | P1-8 | **未完成** | snapshot 注册原子化，但 drainer 启动过早，仍有跨线程可见性和 replay/live 乱序。 |
| C | P1-9 | 完成（诚实模型） | 文档已明确 `network.email`/`database` 是宽泛网络放行，clipboard/notification 仅 advisory；不再宣称不存在的细粒度强制。 |

## 4. 验证结果

| 验证项 | 结果 |
| --- | --- |
| `JAVA_HOME=<Java 21> ./mvnw -f FengYu/pom.xml test` | 通过：377，失败 0，错误 0，跳过 2 |
| `JAVA_HOME=<Java 21> ./mvnw -f OfficialPlugins/pom.xml test` | 通过：35，失败 0，错误 0，跳过 0（远程 DB contract 内部另有 2 个条件跳过） |
| `JAVA_HOME=<Java 21> ../../mvnw test`（SDK） | 通过：25/25 |
| `JAVA_HOME=<Java 21> ../../mvnw test`（DevKit） | 通过：6/6 |
| `npm test`（toolchain/cli） | 通过：90/90 |
| `npm test && npm run build:ts`（desktop） | 通过：65/65，TypeScript 编译通过 |
| `npm test && npm run typecheck && npm run build`（frontend） | 通过：12/12，类型检查及生产构建通过；仅有 chunk size 警告 |
| `node --test scripts/release-workflow.test.mjs` | 通过：11/11；但缺少 sidecar 契约断言 |
| `git diff --check` | 通过 |

未执行：真实 Windows Job Object、Linux bubblewrap、macOS 打包签名/公证、MySQL/PostgreSQL 容器故障恢复、完整 Electron 打包及 `scripts/e2e-smoke.sh`。因此“跨平台真实发布物”仍需 CI/目标机验证，不能仅凭本机单测判定。

## 5. Beta 放行门槛

必须完成：

1. 修复 P0-8：sidecar 随 `.fyp` 贯穿 staging、artifact、Web、desktop，并添加产物级契约测试；明确真实性信任根。
2. 重新构建真实 Web/desktop 产物，启动宿主并确认 5 个官方插件均被 Seeder 接受和可调用。

强烈建议 Beta 前完成：

1. 修复 P1-3/P1-5 的数据库状态机、重试和故障恢复测试。
2. 修复 P1-6 双向 frame limit、P1-7 command hook 清理、P1-8 SSE replay barrier。
3. 修复 Jobs 的 UTF-8 容量与绝对 cursor 算法，并补溢出轮询测试。

完成上述 P0 后可再次做候选构建审查；在 P0-8 未闭环前，不应打 Beta 标签或对外发布。
