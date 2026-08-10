# 应用更新 — 手动测试清单

针对"检测更新 → 用户同意 → 自行下载/安装/重启"流程的端到端验证，覆盖全部三种部署模式。每节独立可用：在目标平台按先决条件准备好后执行。

> 单元测试（`UpdateCheckServiceTest`、`portable-updater.test.ts`、`auto-updater.test.ts`）已覆盖版本比较与检测逻辑。本清单验证的是**真实**的"下载 → 校验 → 替换 → 重启"流水线——这部分只能在目标 OS 上对着真实的 GitHub release 才能跑通。

---

## 通用先决条件（所有模式）

1. **GitHub 上必须存在两个 release**，这样"旧版"才能检测到"新版"：
   - 一个**较旧**的 tagged release，你要安装/运行它（如 `v4.0.0-beta.1`）。
   - 一个**较新**的 tagged release，已发布在 Releases 页（如 `v4.0.0-beta.2`）。
   - 较新的 release 必须是在本次改动合入**之后**由 `fengyu-release.yml` 构建的（这样 `latest*.yml`、`*.blockmap`、`checksums.txt` 才会发布——electron-updater 和便携自更新都需要）。
2. 确认较新 release 包含你所在模式需要的产物（见下）。
3. 先把旧版装好/解压好并跑起来，再触发检查。

### 如何确认较新 release 有必需的 feed 文件

打开 `https://github.com/MuskStark/FengYu/releases/tag/v<新版本>`，确认下列产物存在（任一缺失都说明 CI 改动未生效，自动更新无法工作）：

| 产物 | 谁需要 |
|---|---|
| `latest.yml`（Windows）/ `latest-mac.yml`（macOS） | electron-updater（NSIS 安装版 + macOS） |
| `*.blockmap` | electron-updater 增量下载 |
| `Infinia-<版本>-win-x64-portable.zip` | Windows 便携自更新 |
| `Infinia.jar` + `checksums.txt` | 便携 Web（java -jar）自更新 |

---

## 模式 1：Windows NSIS 安装版（`*-setup.exe`）

**平台：** Windows 10/11 · **更新机制：** electron-updater

### 准备
1. 下载并安装**旧版** `*-win-x64-setup.exe`。
2. 从开始菜单/桌面快捷方式启动 Infinia。

### 步骤
1. 打开**关于**（侧边栏）。几秒后"更新"行应显示
   `Version <新版本> is available`（StatusBar 也会出现徽标）。
2. 点击**立即更新**。弹出未签名风险确认框。
3. 点击**继续**。
4. 下载进度条填充（`update:progress` IPC 事件在流动）。
5. 完成后应用**退出，NSIS 安装器静默运行**，然后 Infinia 重新启动。
   - Windows SmartScreen **可能**弹警告（未签名构建）——点"更多信息"→"仍要运行"。未签名构建出现这个是预期行为。
6. 重启后再次打开**关于**——版本应已变为 `<新版本>`，"更新"行应显示"已是最新版本"。

### 通过标准
- [ ] 旧版能检测到更新
- [ ] 能看到下载进度
- [ ] 应用退出、安装器运行、应用重启
- [ ] 重启后关于页确认是新版本
- [ ] 更新后 StatusBar 徽标消失

### 常见失败
| 症状 | 可能原因 |
|---|---|
| 关于页显示"检查更新失败" | release 上没有发布 `latest.yml`（CI glob 漏了） |
| 下载一直不开始 | `signedRelease` 门控——同意 IPC 路径应该绕过它；检查 `update:download-install` 是否被调到（DevTools → Network/Console） |
| 应用退出但不重启 | NSIS `--updated` 流程失败；查 `%TEMP%` 里的安装器日志 |

---

## 模式 2：Windows 便携版（`*-portable.zip`）

**平台：** Windows 10 1803+ · **更新机制：** 自定义流水线（`portable-updater.ts`）

> electron-updater **不能**更新便携 zip——此模式用的是自定义的"下载 → tar 解压 → robocopy 替换 → 重启"流水线。这是最容易出现文件锁或单实例问题的模式，务必仔细测试。

### 准备
1. 下载并解压**旧版** `*-win-x64-portable.zip` 到某个文件夹，如
   `C:\Users\<你>\Infinia\`。
2. 运行 `C:\Users\<你>\Infinia\Infinia.exe`。

### 步骤
1. 打开**关于** → "更新"行检测到 `<新版本>`（走 GitHub API，不走 latest.yml）。
2. 点击**立即更新** → **继续**（未签名警告）。
3. 下载进度填充（便携 zip 较大，留意百分比）。
4. 应用**退出**。一个 detached 的 `.bat`（在 `%TEMP%`）接管：
   - 等待旧 `Infinia.exe` 的 PID + backend JVM 进程树退出（tasklist 轮询）。
   - 用 `robocopy` 把解压出的新目录树覆盖到安装目录。
   - 重启 `Infinia.exe`。
5. Infinia 自动重新启动。

### 通过标准
- [ ] 便携检测生效（关于页显示更新，不是静默失败）
- [ ] 能看到下载进度
- [ ] 旧进程完全退出后才替换文件（无"文件被占用"错误）
- [ ] `Infinia.exe` + `resources\binaries\FengYu.jar` + `resources\app.asar` 全部被替换
- [ ] 应用以新版本重启（关于页显示 `<新版本>`）
- [ ] 重启时不报第二实例错误（单实例锁已干净释放）

### 失败时如何排查
- detached 脚本日志：`%TEMP%\fengyu-portable-update-<pid>.log`
- 脚本本身（自删前）：`%TEMP%\fengyu-portable-update-<pid>.bat`
- 暂存目录：`%TEMP%\fengyu-portable-update-*\`（解压出的新目录树）
- 从 cmd 窗口运行以看控制台输出：`Infinia.exe` 日志在
  `<runtime>\.fengyu\logs\`。

### 常见失败
| 症状 | 可能原因 |
|---|---|
| `tar extraction failed` | Windows 版本低于 10 1803（无 bsdtar）；或 zip 产物名不含 `-portable.zip` |
| robocopy 报"文件被占用" | backend JVM 还没完全退出就开始替换——bat 里的 PID 等待循环需要更长的宽限期 |
| 应用重启后立即退出 | 单实例锁未释放（旧进程还活着）；或新 exe 路径不一致 |
| 关于页一直不显示更新 | `isWindowsPortable()` 返回了 false——确认便携解压目录里 `resources\app-update.yml` **不存在** |

---

## 模式 3：便携 Web（`java -jar`，run.sh / run.bat）

**平台：** 任意（macOS/Linux/Windows） · **更新机制：** 后端 `SelfUpdateService`
（JAR 下载 → SHA256 校验 → detached 重启脚本 → JVM 退出 → 替换 → 重启）

> 这是唯一能在 macOS/Linux 上端到端测试的模式。它只替换 JAR（启动脚本和插件保持原位）。

### 准备
1. 下载并解压**旧版** `Infinia-<旧版本>-web.zip`（或 `.tar.gz`）。
2. 启动：
   - macOS/Linux：`./run.sh`
   - Windows：`run.bat`
3. 记下输出到 stderr 的生成 token（`Generated per-launch token ...: zf-...`）。
4. 在浏览器打开打印出的 URL（或用带 token 的 curl）。

### 步骤
1. 打开**关于** → "更新"行显示 `<新版本>` 可用。
   - 该 release **必须**有 `Infinia.jar` 产物**和** `checksums.txt` 产物（都由 release 工作流发布）。没有 `checksums.txt`，SHA256 校验步骤会失败。
2. 点击**立即更新** → **继续**。
3. 后端：
   - 从 release 下载 `Infinia.jar` 到暂存文件。
   - 下载 `checksums.txt`，解析 `Infinia.jar` 那一行，校验 SHA256。
   - 在 `<runtime>\runtime-files\` 下生成 `self-update.sh`（POSIX）或 `self-update.bat`（Windows）。
   - detached 派生脚本，然后退出 JVM（`System.exit`，延迟 1 秒让响应刷新）。
4. detached 脚本：
   - 等待旧 JVM PID 退出（POSIX 用 `tail --pid=`，Windows 用 `tasklist`）。
   - 备份旧 JAR 到 `Infinia.jar.bak`。
   - 把下载的 JAR 移到位。
   - 用 `java -jar Infinia.jar <原始参数>` 重新启动。
5. 新 backend 起来；浏览器重连（StatusBar 圆点变回绿色）。

### 通过标准
- [ ] 检测到更新（关于页显示 `<新版本>`）
- [ ] 下载 + SHA256 校验成功（查 backend 日志里的 `[self-update] checksum verified`）
- [ ] 旧 JVM 干净退出（backend 日志显示 context close + shutdown hook）
- [ ] JAR 已替换：`Infinia.jar` 是新版本，`Infinia.jar.bak` 是旧版本
- [ ] 新 JVM 以相同端口/token 重启（UI 无需手动操作即重连）
- [ ] 关于页现在显示 `<新版本>` 且"已是最新版本"

### 失败时如何排查
- backend 日志：`<解压目录>\data\logs\`（即 `-Dfengyu.runtime.dir=$ROOT/data` 指向的位置）。
- 重启脚本：`<解压目录>\data\runtime-files\self-update.sh`（或 `.bat`）。
- 重启脚本日志：`<解压目录>\data\runtime-files\self-update-*.log`。
- 暂存下载文件：`<解压目录>\data\runtime-files\update-staging-*.jar`。

### 常见失败
| 症状 | 可能原因 |
|---|---|
| `SHA-256 mismatch for Infinia.jar` | release 上的 `checksums.txt` 过期或与发布的 JAR 不匹配 |
| `checksums.txt has no entry for Infinia.jar` | 该 release 是在 `checksums.txt` 加入 CI 收集步骤之前构建的 |
| 旧 JAR 没被替换（重启后还是旧版本） | detached 脚本并未真正脱离（随 JVM 一起死了）；或 `java.class.path` 没解析到 JAR 路径 |
| JVM 退出但没重启 | 脚本的重启命令有误——检查 `self-update.sh`，看 `exec java ...` 那行是否带了正确的 `-D` 标志和 `--token` |
| 重启了但端口变了 | 重启没把 `--port=<n>` 传过去；`SelfUpdateService.buildRelaunchCommand` 对 `sun.java.command` 的解析漏掉了 |

---

## 模式 4：macOS 桌面（`*-mac-arm64.dmg`）

**平台：** macOS（Apple Silicon） · **更新机制：** 手动下载（未签名 Gatekeeper 降级路径）

> macOS 未签名构建在 `quitAndInstall` 替换后**无法**重启（Gatekeeper 会拦截被替换的 bundle）。应用降级为"下载 + 打开发布页"。在实现代码签名 + 公证之前，这是有意为之。

### 准备
1. 下载并安装**旧版** `*-mac-arm64.dmg`（把 Infinia 拖进应用程序）。
2. 首次启动时右键 → 打开（绕过 Gatekeeper 对未签名旧构建的拦截）。
3. 运行 Infinia。

### 步骤
1. 打开**关于** → "更新"行检测到 `<新版本>`。
   - 注意：关于页会检测到 macOS，显示的是**"打开页面"**按钮，而不是"立即更新"（因为外壳的 `update:download-install` IPC 在 darwin 上返回 `{action:'manual'}`）。
2. 点击**打开页面** → 浏览器打开 GitHub releases 页。
3. 手动下载**较新**的 `*-mac-arm64.dmg`。
4. 替换 /Applications 里的旧 Infinia.app（把新的拖进去，同意替换）。
5. 右键 → 打开新的 Infinia（又是 Gatekeeper 警告——未签名）。

### 通过标准
- [ ] 检测到更新
- [ ] 显示"打开页面"按钮（**不是**"立即更新"——确认走了 darwin 分支）
- [ ] 打开正确的 releases URL
- [ ] 手动替换后关于页确认是新版本

### 未来：代码签名落地后
在签名 + 公证的构建之后重新跑模式 4：`ipc/update.ts` 的 darwin 分支应改为调 `downloadUpdate()` + `quitAndInstall()`（同 Windows NSIS），关于页按钮应切换为"立即更新"。到那时完整的自动流程才适用。

---

## 跨模式通用检查（任意模式成功更新后快速过一遍）

无论哪种模式，成功更新后都要验证应用是**功能完整**的，而不只是"版本号变了"：

- [ ] **插件仍能加载**：打开工具页——5 个官方插件（markdown/excel/email/offlinepython/browser）都出现。更新后的 JAR/asar 必须带了配套的插件包。
- [ ] **AI 对话可用**：在对话页发条消息——有响应流回。
- [ ] **数据库完好**：打开设置——DB 配置在重启后还在（`data/` 运行目录没被更新清掉）。
- [ ] **再次检查显示"已是最新"**：关于 → "重新检查" → 现在显示"已是最新版本"（新构建版本号等于最新 release tag）。
