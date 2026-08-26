# FengYu 全量代码审查修复方案

> 生成日期：2026-08-25  
> 适用范围：当前工作区（包含未提交的 Flow AI authoring 变更）  
> 审查结论：2 个 P1、3 个 P2；修复完成前不建议合入或发布

## 1. 目标

本方案修复全量代码审查中确认的五项问题，并统一文件授权的生命周期语义：

1. Flow 运行对话框遗留文件与目录授权。
2. AI 输出暂存引用被错误地作为跨轮次附件返回。
3. AI 流在启动前失败时不回收本轮资源。
4. AI Flow 提案可能生成重复节点 ID。
5. 插件文件上传失败时遗留无主目录。

修复必须满足以下原则：

- 一个授权在任何时刻只能有一个明确的所有者。
- 所有权转移必须发生在一个可判断成功或失败的边界上。
- 客户端持有的长期引用、服务端持有的本轮引用、仅用于暂存的引用必须分开建模。
- 每个创建磁盘资源或授权的路径都必须有对称的成功交接和失败回收。
- 前后端都要拒绝重复 Flow 节点 ID，不能只展示诊断后继续应用。

## 2. 优先级与实施顺序

| 阶段 | 优先级 | 工作项 | 合入条件 |
|---|---:|---|---|
| A | P1 | Flow 运行文件授权所有权转移 | 对话框退出、失败和运行终态均无授权泄漏 |
| B | P1 | AI 聊天持久引用与暂存引用分离 | 输出目录场景的下一轮聊天正常 |
| C | P2 | PendingTurn 统一清理 | 所有流启动失败分支均回收本轮资源 |
| D | P2 | Flow 节点 ID 全局唯一 | 后端拒绝、前端阻止应用重复 ID 图 |
| E | P2 | 上传事务化清理 | 所有上传失败分支均不遗留目录 |
| F | 验证 | 全量回归与桌面 E2E | 下文验收矩阵全部通过 |

阶段 A、B、C 都涉及文件授权生命周期，建议在同一分支中连续完成，但拆成独立提交，避免把协议变化与 UI 改动混在一起。

## 3. 修复设计

### 3.1 P1：Flow 运行文件授权所有权转移

#### 问题位置

- `frontend/src/components/agent/FlowRunDialog.vue`
- `FengYu/src/main/java/fan/summer/fengyu/web/controller/AgentController.java`
- `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginFileGrantService.java`

#### 所有权约定

采用“提交前由对话框持有，提交成功后由运行持有”的规则：

```text
picker / upload
    │
    ▼
FlowRunDialog owns refs
    ├── 替换、清除、关闭、提交失败 ──► 前端撤销
    └── 创建运行成功 ───────────────► 所有权转移给 AgentController
                                           │
                                           └── 运行终态/启动失败 ──► 后端撤销
```

#### 后端修改

1. `AgentController.resolveRunFiles` 将通过 `refs` 传入并校验成功的 picker/upload 引用加入运行持有的授权集合。
2. 将 `issuedGrants` 重命名为表达所有权的名称，例如 `ownedGrants`，避免继续暗示只包含后端新建授权。
3. `createManual` 成功注册运行后，由 `issuedRunFileGrants` 接管全部运行期引用。
4. `createManual`、`start` 或参数解析失败时，撤销本次请求已经接管或新建的授权。
5. 终态清理继续通过 `revokeRunFileGrants` 统一回收；重复撤销必须保持幂等。
6. 明确 API 约定：一旦运行创建成功，客户端不得再撤销已提交引用。

#### 前端修改

1. 在 `FlowRunDialog.vue` 增加集中式帮助函数：
   - `revokeEntries(entries)`：逐项 best-effort 调用 `api.revokeAiFile`。
   - `replaceRunFile(name, refs, displayName)`：新引用创建成功后，撤销被替换的旧引用。
   - `releaseDialogRefs()`：释放当前仍由对话框持有的全部引用。
2. 以下路径调用 `releaseDialogRefs` 或对应的单项撤销：
   - 用户清除某个输入。
   - 用户重新选择文件或目录。
   - 对话框关闭。
   - 组件卸载。
   - 提交 API 失败。
3. 运行创建成功后只清空本地引用，不调用撤销接口，表示所有权已经转移给后端。
4. 使用一次性状态防止关闭 watcher、提交完成和组件卸载发生竞态时重复处理所有权。
5. 默认原生路径异步授权返回时，若对话框已关闭，应立即撤销刚创建的引用，不能重新写回已销毁的表单状态。

#### 测试

- 扩展 `AgentControllerRunFileGrantTest`：
  - picker 引用在运行终态被撤销。
  - 启动失败时 picker、native、shared 引用均被撤销。
  - 同一引用重复清理不报错。
- 新增 Flow 运行对话框测试：
  - 替换、清除、关闭分别触发正确的撤销次数。
  - 成功提交后前端不撤销，后端在终态撤销。
  - 授权请求晚于对话框关闭完成时立即回收。

### 3.2 P1：区分持久引用和本轮暂存引用

#### 问题位置

- `FengYu/src/main/java/fan/summer/fengyu/web/controller/AiController.java`
- `FengYu/src/main/java/fan/summer/fengyu/ai/ChatFileGrantService.java`
- `frontend/src/stores/aiSession.ts`
- `frontend/src/components/agent/FlowChatPanel.vue`
- `frontend/src/api/types.ts`

#### 数据模型

不要再用一个 `refs` 列表表示三种生命周期。建议引入内部结构：

```java
record PreparedChatFiles(
    List<ActiveFileRef> clientOwnedRefs,
    List<ActiveFileRef> newlyIssuedPersistentRefs,
    List<ActiveFileRef> turnScopedRefs,
    List<StagedOutput> stagedOutputs
) {}
```

语义如下：

| 类型 | 示例 | 是否返回前端 | 成功终态 | 失败/过期 |
|---|---|---:|---|---|
| `clientOwnedRefs` | 请求原有附件 | 否，可不重复回显 | 不撤销 | 不撤销 |
| `newlyIssuedPersistentRefs` | 用户消息中显式路径产生的授权 | 是 | 转移给前端 | 若尚未交接则撤销 |
| `turnScopedRefs` | 插件输出 staging 根 | 否 | 导出后撤销 | 丢弃并撤销 |
| `stagedOutputs` | 暂存目录与真实输出目标关系 | 否 | 导出 | 删除 |

#### 后端修改

1. `POST /api/ai/chat` 的 `activeFileRefs` 只返回新创建且允许跨轮次使用的持久引用。
2. staging refs 只进入本轮 `ChatFileContext`，不得进入响应。
3. 不再把请求中已有的客户端引用重新回显，避免前端通过名称去重时产生非预期替换。
4. 为响应交接增加明确时点：`POST /chat` 成功返回后，`newlyIssuedPersistentRefs` 归客户端所有。
5. 如果 `POST /chat` 在返回前失败，只撤销本次新建引用，不能撤销请求原有引用。
6. 检查 pending 过期清理：当前实现会遍历并撤销全部 `activeFileRefs`，必须改为只清理服务端仍持有的本轮资源，不能撤销客户端传入的附件。

#### 前端修改

1. `aiSession.ts` 继续保存后端返回的持久引用，但不再收到 staging refs。
2. `FlowChatPanel.vue` 增加 Flow 聊天自己的活动引用集合：
   - 将后端返回的持久引用保存并用于下一轮。
   - 工作流切换、面板卸载或用户显式清除时撤销。
   - 每次请求把当前活动引用传给 `api.aiChat`。
3. 如果产品不希望 Flow Chat 支持路径跨轮次访问，则采用更简单的策略：后端不为 Flow Chat 返回持久引用，并在该轮终态自动回收；二者必须二选一，不能继续忽略响应。

#### 测试

- 普通聊天：“输出到指定目录”完成后发送“继续”，第二轮不得携带已经撤销的 staging ref。
- 响应的 `activeFileRefs` 中不包含任何 staging ref。
- 请求原有附件在 pending 过期后仍然有效。
- Flow Chat 中自动路径授权可以用于下一轮，并在面板销毁时撤销。
- 多插件同时获得输出 staging 权限时，每个插件的临时引用都只回收一次。

### 3.3 P2：PendingTurn 统一终止与清理

#### 问题位置

- `FengYu/src/main/java/fan/summer/fengyu/web/controller/AiController.java`

#### 修改方案

1. 为 `PendingTurn` 增加显式资源所有权字段，不再依赖 `activeFileRefs` 推断哪些引用应当撤销。
2. 在从 `pending` 移除 turn 后立即创建统一的终止处理器，例如 `PendingTurnLease`：
   - `complete()`：导出 staging，回收本轮临时引用。
   - `abort()`：丢弃 staging，回收本轮临时引用。
   - `transferPersistentRefs()`：标记持久引用已经交接前端。
   - 所有操作幂等。
3. 以下分支全部调用 `abort()`：
   - AI backend 未配置。
   - Ollama 模型加载失败。
   - backend 未就绪。
   - 已存在活动生成。
   - SSE 首次发送失败。
   - `chat()` 同步抛错。
   - 传输断开或取消。
4. pending 超时清理复用同一终止处理器，不再自行遍历所有 refs。
5. 保持 `activeStreamId` 和 `activeBackend` 的释放与资源清理解耦，但二者都必须在终止路径执行。

#### 测试

扩展 `AiControllerChatGrantLeakTest` 和 `AiControllerSseCallbackTest`，逐一覆盖上述失败分支，并断言：

- staging 目录不存在。
- staging grant 不存在。
- 客户端原有附件仍然有效。
- `activeStreamId` 可以被下一次请求占用。
- 重复 completion/error/disconnect 回调不会重复导出或抛错。

### 3.4 P2：Flow 节点 ID 全局唯一

#### 问题位置

- `FengYu/src/main/java/fan/summer/fengyu/ai/workflow/FlowAuthoringToolFactory.java`
- `frontend/src/components/agent/FlowChatPanel.vue`
- `frontend/src/components/agent/workflow.ts`
- `frontend/src/views/FlowBuilder.vue`

#### 后端修改

1. 构建提案前先收集要保留的 note ID，并预留结构节点 ID `start`。
2. 模型节点 ID 与以下任一集合冲突时直接拒绝：
   - `start`。
   - 保留便签 ID。
   - 其他模型节点 ID。
3. `preserveNotes` 也必须验证便签自身 ID 非空且唯一；脏画布中的重复便签不能被无条件复制到提案。
4. 在返回 `flow_proposal` 前对最终 `graph.nodes` 再执行一次全局唯一性校验，作为最后防线。
5. `diagnostics` 中存在 `severity=error` 时，可以返回诊断结果，但必须增加机器可判定的 `applicable=false`，或直接拒绝生成可应用提案。

#### 前端修改

1. `FlowChatPanel` 在提案含 error 诊断时禁用“应用”，warning 仍允许用户决定。
2. `applyAiFlowProposal` 在修改历史栈和画布前执行：
   - 节点 ID 唯一性校验。
   - 边端点存在性校验。
   - Start 节点数量校验。
3. `rehydrateFlowGraph` 遇到重复 ID 时返回结构化错误或 `null`，不能静默生成重复 Vue Flow 节点。
4. `ensureWorkflowStartNode` 继续负责结构节点迁移，但不能被当成通用 ID 去重器。

#### 测试

- 模型节点使用 `start` 时后端拒绝。
- 模型节点与保留 note 同 ID 时后端拒绝。
- 当前画布存在重复 note ID 时提案不可应用。
- error 诊断禁用应用按钮，warning 不禁用。
- 直接构造重复 ID 提案调用 `applyAiFlowProposal` 时，画布和历史栈保持不变。

### 3.5 P2：插件文件上传事务化清理

#### 问题位置

- `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginFileGrantService.java`

#### 修改方案

1. 为 host-owned 目录创建统一帮助方法，确保只有 `register` 成功后才解除失败清理责任。
2. `upload`、`uploadDirectory`、`outputDirectory` 使用相同模式：

```java
Path ownedRoot = createOwnedRoot(pluginId);
boolean registered = false;
try {
    // validate/copy
    FileRef ref = register(...);
    registered = true;
    return ref;
} finally {
    if (!registered) deleteTree(ownedRoot);
}
```

3. `uploadDirectory` 尽可能在创建目录前完成纯数据校验，包括空文件、相对路径、路径逃逸和重复目标路径。
4. 对流读取异常、复制异常和 `MAX_ACTIVE_GRANTS` 注册失败同样执行清理。
5. `deleteTree` 失败应记录包含目录和插件 ID 的警告，但不能覆盖原始异常。
6. 不在本次修复中盲目删除既有 runtime-files 内容；若要清理历史遗留目录，应单独实现带年龄阈值和活动引用排除的启动期清理器。

#### 测试

扩展 `PluginFileGrantServiceTest`：

- 第二个目录条目为空时，第一个已经复制的文件被清理。
- 中途输入流抛出 `IOException` 时目录被清理。
- 相对路径逃逸时不创建无主目录。
- 达到活动授权上限导致 `register` 失败时目录被清理。
- 成功上传后的目录在 revoke 时仍按原逻辑删除。

## 4. 建议提交拆分

按仓库 conventional commit + emoji 约定拆分：

1. `🐛 fix: transfer Flow run file grant ownership`
2. `🐛 fix: separate persistent and staged AI file refs`
3. `🐛 fix: clean pending AI turn resources on every terminal path`
4. `🐛 fix: reject duplicate Flow proposal node ids`
5. `🐛 fix: clean failed plugin file uploads transactionally`
测试应和对应修复一起提交。不要在本任务未获授权时自动 commit 或 push。

## 5. 验证矩阵

### 5.1 聚焦测试

```bash
./mvnw -f FengYu/pom.xml \
  -Dtest=AgentControllerRunFileGrantTest,AiControllerChatGrantLeakTest,AiControllerSseCallbackTest,PluginFileGrantServiceTest,FlowAuthoringToolFactoryTest \
  test

cd frontend
corepack yarn typecheck
corepack yarn test
corepack yarn test:unit
```

### 5.2 模块回归

```bash
./mvnw -f FengYu/pom.xml test
./mvnw -f OfficialPlugins/pom.xml test

cd frontend
corepack yarn typecheck
corepack yarn test
corepack yarn test:unit

cd ../desktop/electron
corepack yarn build:ts
corepack yarn test
```

### 5.3 全链路验证

```bash
scripts/e2e-smoke.sh

# 先生成最新 shaded JAR，再运行稳定桌面启动 E2E。
./mvnw -f FengYu/pom.xml package -DskipTests
FENGYU_E2E_JAR="$(find "$(pwd)/FengYu/target" -maxdepth 1 \
  -name 'FengYu-*.jar' ! -name '*.original' -print -quit)"
cd desktop/electron
FENGYU_JAR="$FENGYU_E2E_JAR" corepack yarn test:e2e

# browser bridge 用例保持显式 opt-in。
FENGYU_E2E_BROWSER_BRIDGE=1 \
FENGYU_JAR="$FENGYU_E2E_JAR" \
  corepack yarn test:e2e test/e2e/browser-bridge.spec.ts

cd ../..
node --test scripts/*.test.mjs
git diff --check
```

执行时从构建产物解析实际 JAR 文件名，不在脚本中复制版本字面量。

## 6. 验收标准

全部满足后方可认为修复完成：

- [ ] Flow 对话框替换、清除、关闭和异步竞态不遗留授权。
- [ ] Flow 运行成功后，授权由后端持有并在运行终态回收。
- [ ] 输出目录场景完成后，下一轮聊天不会发送失效 staging ref。
- [ ] pending 过期或流启动失败不会撤销客户端原有附件。
- [ ] 每个 AI 流终止路径都回收 staging 目录和本轮授权。
- [ ] 后端和前端都拒绝重复节点 ID 的 Flow 提案。
- [ ] 所有上传异常和授权上限异常均不留下无主目录。
- [ ] 后端、前端、桌面、官方插件和发布契约测试通过。
- [ ] 使用实际 shaded JAR 执行的 Electron `launch.spec.ts` 通过，而不是被跳过。
- [ ] `scripts/e2e-smoke.sh` 通过且后端关闭后无孤儿 plugin-worker 进程。
- [ ] `git diff --check` 无错误，未包含无关文件改写。

## 7. 风险与回滚

- **API 兼容风险**：若改变 `activeFileRefs` 响应语义，应保持字段存在并只收窄内容；如果新增字段，前端需兼容旧后端的缺省值。
- **双重撤销风险**：所有权转移期间最容易出现前后端同时撤销。撤销本身应保持幂等，但不能依赖幂等掩盖生命周期不清晰。
- **运行期竞态**：Flow 提交成功和对话框关闭可能同时发生，必须通过显式状态决定由哪一侧清理。
- **超时清理风险**：pending 清理不得撤销客户端传入的长期引用，否则会破坏其他会话或下一轮消息。
- **磁盘清理风险**：只删除当前操作创建且尚未交接的目录；不要对 runtime-files 根目录执行宽泛递归删除。
- **回滚策略**：五个工作项保持独立提交。若出现回归，可按工作项回滚，不回滚已有安全沙箱、授权上限或运行终态清理机制。
