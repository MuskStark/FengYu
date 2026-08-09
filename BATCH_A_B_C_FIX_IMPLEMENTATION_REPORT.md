# 批次 A / B / C 自主修复与发布复验报告

日期：2026-08-09  
基线：`f1016b0` 后的当前未提交工作树  
范围：批次 A（P0-1/2/3/4/8）、批次 B（P0-5/6/7/9）、批次 C（P1-1～P1-9）  
说明：本报告位于主项目根目录；未提交、未推送、未打标签。

## 1. 结论

前次复审列出的代码缺口已完成修复，宿主、插件运行时、SDK/DevKit、五个官方插件、
Web 前端、Electron 外壳及发布契约的本机验证均已通过。当前未发现仍然明确阻断 Beta
候选构建的代码级 P0。

可以进入 **Beta 候选构建及目标平台 CI 验收**，但不能把本机 macOS 验证等同于所有平台
已经放行。正式对外发布前仍需在 Windows/Linux runner 验证真实沙箱与安装包，并对
MySQL/PostgreSQL 做容器级恢复验证。

另外，`.sha256` sidecar 已完整贯穿发布链，但它只证明归档与侧车一致，不能抵抗攻击者
同时替换二者。当前官方身份的信任边界是宿主控制的 bundled-resource 路径；若发布威胁
模型要求独立来源真实性，应在稳定版前增加公钥签名或宿主内置的受信任摘要清单。

## 2. 本轮修复结果

| 编号 | 结果 | 修复摘要 |
| --- | --- | --- |
| P0-8 | 完成（完整性链） | 五个官方插件的 `.fyp` 与 `.fyp.sha256` 成对贯穿 staging、共享 artifact、Web 和两种 desktop 装配；缺失、数量异常或摘要不符均 fail-closed；补齐真实 Web 包及端到端冒烟检查。 |
| P1-2 | 完成 | Jobs 按真实 UTF-8 bytes 控制容量；用 `cursor - droppedLogs` 换算绝对游标，淘汰后连续读取不再跳日志。 |
| P1-3 | 完成 | deprovision 在任何 DDL 前持久化 `DELETE_PENDING`；配置缺失、类型变化、admin 缺失、DDL/记录删除失败均保留恢复记录；定时协调器与手动 retry API 可重试。 |
| P1-4 | 完成 | 传统市场与统一商店卸载 API 均要求显式 `deleteData`；先停 Worker，按选择保留或删除文件/DB 数据，删除失败向调用方报告，日志在完成后清理。 |
| P1-5 | 完成 | provision 改为 `PROVISIONING → ACTIVE` 可恢复状态机；首个 DDL 前原子保存完整意图，激活落盘失败可幂等恢复；非 ACTIVE 凭据不会注入 Worker。 |
| P1-6 | 完成 | 宿主、Java SDK 与 DevKit 改为 byte-oriented bounded line；请求/响应写入前均校验 UTF-8 帧长度，覆盖 emoji、精确边界、无换行超限与超大响应。 |
| P1-7 | 完成 | command hook 在 `RuntimeException` 和 `Error` 路径都会终止子进程并关闭 Job handle；成功结束也关闭句柄；`ProcessSandbox` 提前发布可清理句柄。 |
| P1-8 | 完成 | SSE 改为 paused subscription → 历史回放 → activate；sequence 与 live enqueue 在同一 per-plugin 锁内严格排序，发送失败立即幂等退订。 |

前次已经通过的 P0-1/2/3/4、P0-5/6/7/9 与 P1-1/9 保持有效。统一商店路径另行补上
了更新互斥 gate，避免绕过 P0-6；其卸载路径也不再绕过 P1-4。

## 3. 集成复验中额外发现并修复

1. `e2e-smoke.sh` 原先只暂存 `.fyp`，新版 Seeder 会正确拒绝缺少 sidecar 的官方插件。
   冒烟脚本现成对暂存归档与摘要，并有发布契约测试防回归。
2. Email 冒烟原先在没有用户授权 DB 的情况下直接启动 Worker。新版 ACTIVE-only 注入正确
   暴露了此问题；冒烟现显式调用 `POST /api/plugin-db/provision/fan.summer.email`，再以真实
   DB-backed RPC 验证凭据注入。
3. 统一插件商店曾直接调用旧卸载重载、没有停止 Worker，也绕过更新 gate。本轮已把显式
   数据策略、停止/清日志和更新互斥接入统一路径，并增加服务层与前端 store 回归测试。
4. Windows command hook 对普通异常已有处理，但 `Error` 仍可能泄漏进程/句柄；现已补齐
   两类失败与成功关闭的测试。

## 4. 验证结果

| 验证项 | 结果 |
| --- | --- |
| 宿主完整测试（Java 21） | 393 tests：失败 0、错误 0、跳过 2 |
| OfficialPlugins reactor | BUILD SUCCESS；五个官方插件全部通过（Browser 35 tests） |
| Java Worker SDK | 30/30 |
| Java DevKit | 7/7 |
| Plugin CLI | 90/90 |
| Electron | 65/65；TypeScript build 通过 |
| Frontend | 主测试 12/12；统一商店定向 4/4；typecheck 与 production build 通过（仅 chunk-size warning） |
| Docs | VitePress EN/ZH build 通过 |
| 发布/版本契约 | 25/25 |
| Web 临时产物 | 五组 `.fyp` + `.sha256` 共 10 个插件条目；摘要替换后正确拒绝 |
| `scripts/e2e-smoke.sh` | 通过：五个官方插件安装、DB 授权、Worker RPC、文件桥与 token auth |
| `git diff --check` | 通过 |

## 5. 尚需目标环境验证的发布风险

- Windows：真实 Job Object/JNA 进程树终止、NSIS 与 portable ZIP 启动。
- Linux：真实 bubblewrap 可用与不可用两条路径、AppImage/deb 启动。
- macOS：签名/公证后的 DMG；当前未签名构建按设计禁用静默自动更新。
- 数据库：MySQL/PostgreSQL 容器内对 `PROVISIONING`、`DELETE_PENDING` 和故障注入做实跑；
  本轮真实数据库执行覆盖为 H2 TCP，MySQL/PG 为 DDL/单测覆盖。
- 供应链：若要求官方插件独立真实性，增加非对称签名；普通 SHA-256 sidecar 仅用于完整性。

## 6. Beta 放行建议

代码可进入候选构建。建议以 Windows、Linux、macOS release workflow 全绿，五个官方插件在
解包后的真实 Web/desktop 资源中均被 Seeder 接受，以及 MySQL/PostgreSQL 恢复测试通过，作为
Beta 对外发布的最终门禁。当前工作树包含用户既有改动与本轮修复，提交前应按批次审阅并拆分，
不要直接把整个脏工作树一次性提交。
