---
format: 1920x1080
duration: 68s
message: "说出目标，蜂语替你跑完全程"
arc: Demo Loop（question → product intro → demo cycle 1 → demo cycle 2 → breadth → trust → CTA）
audience: 开发者、技术团队与效率型用户（中文）
mode: autonomous
music: minimal confident tech underscore, restrained, warm
---

## Video direction

- **revision (user, 2026-09-29)**：移除字幕轨道（caption band 保留但不放字幕）；真实产品图标
  `assets/infinia-logo.svg`（取自 `frontend/public/infinia-logo.svg`）出现在 F02 命名卡与 F10
  lockup；新增 F11 slogan 收尾卡。

- **palette system**（frame.md 为唯一色彩事实）：地面（cream 角色）`#0d0d0d` 近黑；内容表面半步亮起 `#161616` → `#232323`（tile 角色）；文字（ink 角色）`#ededed` 主、`#cccccc` 次；电压色（coral 角色）薄荷绿 `#8fd6bd` —— **每帧至多一次**（确认卡、连接线、徽章或 CTA 胶囊其一）；代码/终端/聊天表面用 navy 家族 `#111413 / #161a18 / #1c201e`（固定语法色 teal `#5DB8A6` / amber `#E8A55A` 只出现在终端里）。发丝线一律 cream@12–14%。禁止紫色旧 MD3 基线、冷灰、纯黑纯白、内容上的重阴影/辉光/渐变。
- **typography**：PingFang SC 显示层 weight 500、句首大写、负字距；正文 400；JetBrains Mono 承担 kicker（全大写 0.16em、前缀 ✱）、mono 标签、数字单位与终端。所有大字落在 1.4cqw 可读底线之上。
- **motion grammar + reveal model**：全片 `power3` 长尾减速，平滑压倒弹性，无 overshoot；每帧只进 VO 当前说到的东西，后续元素等各自的口语 cue 在**后半段**依次揭示；入场一律 `fromTo`；动作全部走 GSAP 时间轴（禁 CSS transition/@keyframes）；帧内接缝用速度匹配剪（cut-the-catalog），帧间交给 harness 注入的 transition。
- **rhythm / held-frame allocation**：F2（命名卡）与 F5 尾（批准落定的静持）是刻意 breather；F10 以光标闪烁的静持收尾 —— 静持优于坏动作。其余帧按 VO 节奏揭示。
- **caption band**：底部 ~17% 为字幕保留带，所有内容排在上方 83%。
- **negative list**：不出现幻灯片式 front-load（前 25% 倾倒全部内容然后冻结）；不出现屏保式漂浮（多元素各自游动）；无 lazy breathing、无后半程慢推拉；无 `repeat`/`yoyo`、无 `Math.random`；除刻意的 UI 重构外不画导航栏/浏览器 chrome/滚动条；**一切数字必须可溯源**（README/docs）：1300+ 市场插件、25 个浏览器工具、3 种 Worker 语言、4 种数据库、24056 端口。

## Frame 1 — 一句话的委托

- scene: 聊天输入框里，一句真实的业务委托被打字上屏并回车提交
- voiceover: "把这份明细按分公司拆开，汇总好，发给每个负责人。"
- duration: 5.784s
- transition_in: cut
- status: animated
- src: compositions/frames/01-brief.html
- type: hook
- persuasion: 直接展示 —— 观众自己的委托原声开场
- beat: curiosity + intrigue
- blueprint: prompt-type-submit-generate (Reproduce)
- focal: 打字中的聊天输入框（registry 块 `typed-prompt`）
- sfx: keyboard-soft, submit-thunk

narrativeRole: 冷开场 —— 不解释产品，先让观众听见"一句委托"。这就是全片的提问，后面 55 秒都是回答。
keyMessage: 一个目标，一句话，就够了。

Adapt: 保留"prompt types → 剪在提交"的签名；输入框落在 tile 表面上，上下留出呼吸。
Scene 1 (0.0–2.4s): 近黑地面上，居中偏下 ~55% 宽的聊天输入卡（tile `#161616`、hairline、8px 圆角）以 `fromTo` 微升入场；`typed-prompt` 以人类节奏逐字打出「把这份明细按分公司拆开」—— VO 说到哪，字跟到哪（Centered, 1 depth layer + 地面）。
Scene 2 (2.4–3.2s): VO「汇总好」—— 输入卡上方弹出一枚附件胶囊「汇总报告.xlsx」（spring-pop 平滑落位），输入继续打出剩余文字。
Scene 3 (3.2–5.0s): VO「发给每个负责人」—— 回车提交：输入卡内侧一条 mint 状态线亮起「已提交 · Agent 已接手」，卡片轻微压缩反弹为已发送态（`press-release-spring`，无 overshoot）。
Scene 4 (5.0–6.0s): 静持：提交态 + 光标低幅闪烁（subtle jitter），无相机移动。

## Frame 2 — 这就是蜂语

- scene: 提交瞬间画面沉入品牌帧 —— ✱ kicker + "蜂语 Infinia" 大字 + AI 原生编排平台副题
- voiceover: "这就是蜂语 Infinia——AI 原生编排平台。"
- duration: 4.536s
- transition_in: zoom-through
- status: animated
- src: compositions/frames/02-intro.html
- type: product_intro
- persuasion: 命名时刻 —— 悬念即刻兑现
- beat: clarity
- blueprint: kinetic-type-beats (Reproduce)
- focal: 「蜂语 Infinia」display-cover 大字
- sfx: brand-sting

narrativeRole: 在承诺落地前给出名字与定位；承上（提交的委托）启下（它怎么跑）。
keyMessage: 产品名 + 品类定位一句话说清。

Scene 1 (0.0–2.6s): 近黑地面；JetBrains Mono kicker「✱ INFINIA 4.0」先亮，随后「蜂语 Infinia」以 display-cover（PingFang SC 500，负字距）per-word 逐词落位（`dynamic-content-sequencing`，power3 长尾）—— VO 唱名时字齐（Centered，主视觉 ~60%）。
Scene 2 (2.6–4.2s): VO「AI 原生编排平台」—— 副题一行 per-word 揭示于大字下方；kicker 下画一条 1px mint 短规（`svg-path-draw`）——本帧唯一电压。
Scene 3 (4.2–5.0s): 刻意静持的命名卡：整帧几乎不动（至多 subtle jitter）—— 全片最静的一帧，压住节奏。

## Frame 3 — 目标拆成步骤

- scene: Agent 计划面板逐条亮起编号步骤（读表 → 按规则拆分 → 生成汇总 → 逐个发送），执行标记点亮
- voiceover: "Agent 自己拆步骤——读表、拆分、汇总、发送，每一步都看得见。"
- duration: 6.816s
- transition_in: crossfade
- status: animated
- src: compositions/frames/03-plan.html
- type: feature_showcase
- persuasion: 机制可视化 —— plan-and-execute 主轴
- beat: clarity + control
- blueprint: agent-progress-theater (Reproduce)
- focal: 计划面板的步骤清单
- sfx: step-tick ×4, status-hum

narrativeRole: 产品的脊柱（plan-and-execute Agent）第一次显形；"看得见"为后面的信任做铺垫。
keyMessage: 它不是聊天，它规划并执行。

Scene 1 (0.0–2.0s): 计划面板（tile 表面，~46% 宽，右置 60/40 不对称）随 VO「Agent 自己拆步骤」浮入；面板头「执行计划」+ mono 状态「PLANNING → RUNNING」打字切换（`discrete-text-sequence`）。
Scene 2 (2.0–2.7s): VO「读表」—— 步骤行 ① 滑入，行首圆点 mint 描边自画（`svg-path-draw`）。
Scene 3 (2.7–3.4s): VO「拆分」—— 行 ② 滑入 + 圆点自画；行 ① 保持已勾状态。
Scene 4 (3.4–4.1s): VO「汇总」—— 行 ③ 同律入场。
Scene 5 (4.1–4.8s): VO「发送」—— 行 ④ 入场；四行以 ~0.7s/条 的节奏各占一个口语 cue，无提前倾倒。
Scene 6 (4.8–6.5s): VO「每一步都看得见」—— 面板底部状态条落定「4/4 已完成」，完成的清单整体轻微提亮（一次性 emphasis，非辉光循环），随后静持。

## Frame 4 — Excel 拆分跑起来

- scene: 官方 Excel 插件执行拆分 —— 行数据流动，按分公司切成多份工作簿，完成回执逐个打勾
- voiceover: "官方 Excel 插件接管拆分——三千行明细，几秒变成分公司报表。"
- duration: 6.792s
- transition_in: crossfade
- status: animated
- src: compositions/frames/04-excel.html
- type: feature_showcase
- persuasion: Show-don't-tell proof —— 真实工具真的在跑
- beat: power + ease
- blueprint: agent-progress-theater (Adapt)
- focal: 拆分工作台（左：明细表；右：产出回执）
- sfx: data-flow, receipt-check ×3

narrativeRole: 演示循环 1：具体业务能力落地（.fyp 官方插件），证明"跑完全程"不是口号。
keyMessage: 重活插件干，几秒出结果。

Adapt: 保留"working theater → 回执级联打勾"的签名；表面从检查清单换成"左表右据"的双栏工作台。
Scene 1 (0.0–3.0s): VO「官方 Excel 插件接管拆分」—— 左侧明细表（navy 表面、mono 数字、hairline 行线）入场，行以流水节奏向上滚动三拍（有限 tween，非循环）；左上插件徽章「官方 Excel 插件 · .fyp」落位（asymmetric 60/40，3 depth layers）。
Scene 2 (3.0–4.0s): VO「三千行明细」—— 表头下方的行计数器以 `counting-dynamic-scale` 数至 3,000，字号随数值微涨。
Scene 3 (4.0–6.0s): VO「几秒变成分公司报表」—— 右栏三张工作簿卡（华东 / 华南 / 华北）依次弹出，每张卡上的完成回执逐个打勾（badge 翻转 + mono 行数「1,024 rows」），卡与左表之间一条 SVG 连接线自画（`svg-path-draw`）。
Scene 4 (6.0–7.0s): 静持：三卡齐整，回执全绿点（mint 每卡一枚，属于同一次电压时刻），无继续运动。

## Frame 5 — 发送之前，先问你

- scene: 邮件按分公司自动写好排队；一条"待批准"确认卡浮起，鼠标点下批准，发送状态点亮
- voiceover: "邮件写好、归档，发送之前——它先请你批准。"
- duration: 4.992s
- transition_in: crossfade
- status: animated
- src: compositions/frames/05-approval.html
- type: benefit_highlight
- persuasion: 风险逆转 —— 敏感操作 confirmation-first
- beat: trust + relief
- blueprint: cursor-ui-demo (Reproduce)
- focal: 待批准确认卡 + 落下的光标点击
- sfx: draft-whoosh, approval-click

narrativeRole: 演示循环 2 收尾 + 信任转折：能力越大，闸门在人手里。这是产品的安全立场第一次亮出。
keyMessage: 自动执行，但批准权在你。

Scene 1 (0.0–2.4s): VO「邮件写好、归档」—— 右置邮件队列（三行收件组，tile 卡、hairline 分隔）逐行滑入，每行尾部状态签从「起草中」打字切到「已归档」（`discrete-text-sequence`）；左上 kicker「✱ EMAIL CENTER」（Centered-right 55/45）。
Scene 2 (2.4–3.4s): VO「发送之前」—— 队列上方浮起确认卡（tile-strong `#232323`，mint 左缘 1px）「确认发送 · 12 位收件人」—— 本帧唯一的 mint 时刻在此卡上。
Scene 3 (3.4–5.2s): VO「它先请你批准」—— 自绘光标移向「批准」按钮，压下（`cursor-click-ripple` 一圈涟漪），按钮翻转为 mint 实底、深色字；三行状态签同步翻转「发送中」。
Scene 4 (5.2–6.5s): 静持：点击落定的画面停住（breather 尾），光标退场，无相机运动。

## Frame 6 — 一个 Agent，三种扩展

- scene: 中央 Agent 核心，三张扩展面卡片依次飞入归位 —— .fyp 插件 / .fys 技能 / 内置 AI 工具
- voiceover: "一个 Agent，三种扩展面——.fyp 插件、.fys 技能、内置工具。"
- duration: 6.624s
- transition_in: zoom-through
- status: animated
- src: compositions/frames/06-surfaces.html
- type: feature_showcase
- persuasion: 结构即论据 —— 生态可扩展
- beat: awe + confidence
- blueprint: constellation-hub (Reproduce)
- focal: 中央枢纽 + 三条自画连接线的星座图（registry 块 `constellation-hub`）
- sfx: node-pop ×3, connector-draw

narrativeRole: 从"能干一件事"抬升到"平台"：三大扩展面是 4.0 架构的真实分层。
keyMessage: 能力可以无限长出来。

Scene 1 (0.0–1.4s): VO「一个 Agent」—— 中央枢纽（✱ 核心标记 + mono 标签「AGENT」）从微缩平滑落定于画面中心（Centered，枢纽 ~18%）。
Scene 2 (1.4–2.4s): VO「三种扩展面」—— 三个空节点位以 120° 间隔绕枢纽亮起淡淡的 hairline 环（一次性描画）。
Scene 3 (2.4–3.4s): VO「.fyp 插件」—— 节点一（拼图图标 + 「.fyp 插件」+ mono 小注「JSON-RPC Worker · Java/Python/Go」）飞入归位，SVG 连接线自枢纽画出（`svg-path-draw`）。
Scene 4 (3.4–4.4s): VO「.fys 技能」—— 节点二（卷轴图标 + 「.fys 技能」+「渐进式披露」）同律入场 + 连线。
Scene 5 (4.4–5.4s): VO「内置工具」—— 节点三（螺栓图标 + 「内置 AI 工具」+「浏览器 / 电脑 / 检索」）归位 + 连线；三线在同一 mint 上（本帧唯一电压）。
Scene 6 (5.4–7.0s): 星座整体合拢成 lockup：枢纽轻微提亮，三节点停驻；静持。

## Frame 7 — 真浏览器，真桌面

- scene: 浏览器代理标签页在操作真实网页；画面切换到电脑操控 —— 光标移动、键入，每步旁标注"待批准"
- voiceover: "它还能开真的浏览器，操作真的桌面——每个动作都在你的批准之下。"
- duration: 6.552s
- transition_in: crossfade
- status: animated
- src: compositions/frames/07-browser.html
- type: feature_showcase
- persuasion: 能力边界突破 + 重复安全母题
- beat: awe + reassurance
- blueprint: device-surface-showcase (Adapt)
- focal: 两个依次登场的窗口（浏览器 → 桌面）
- sfx: tab-open, keyboard-taps, approval-chime

narrativeRole: 能力上限展示（浏览器代理 25 个 AI 工具、电脑操控），同时复述信任母题加固立场。
keyMessage: 从对话框到你的整台电脑。

Adapt: 保留"held window hero + 界面步进"的签名；一帧内两个窗口以速度匹配剪（cut-the-curve）接力。
Scene 1 (0.0–2.4s): VO「它还能开真的浏览器」—— 浮动浏览器窗（`browser-device-stage` device chrome，~58% 宽，rule-of-thirds 右置）升起，地址栏输入、标签页切换一步，页内一处元素被 mint 圈注（`css-marker-patterns` 手绘圈——唯一电压），角落 mono 徽章「BROWSER TOOL · 25 个 AI 工具」。
Scene 2 (2.4–4.4s): VO「操作真的桌面」—— 速度匹配剪切到桌面操控窗：光标平滑移动、点开应用、键入两行（`cursor-click-ripple` + 键入节奏），步骤计数「3/7」随动作跳动。
Scene 3 (4.4–6.5s): VO「每个动作都在你的批准之下」—— 两窗并置（前窗缩小让位），各自角落亮起「待批准」mint 签同拍闪烁一次；随后静持，安全母题落定。

## Frame 8 — Web 与桌面，数据不出本机

- scene: 三层架构图 —— 无头 Spring Boot 后端 / Vue Web 界面 / Electron 桌面壳，127.0.0.1 环回标识点亮
- voiceover: "4.0 全线重构——无头后端、Web 界面、桌面壳。数据只留在你的机器上。"
- duration: 8.208s
- transition_in: zoom-through
- status: animated
- src: compositions/frames/08-platform.html
- type: benefit_highlight
- persuasion: 架构背书 —— 4.0 发布时刻 + 本地隐私
- beat: confidence + peace of mind
- blueprint: kinetic-type-beats (Adapt)
- focal: 「4.0 全线重构」大字 + 三层堆叠卡
- sfx: layer-stack ×3, loopback-glow

narrativeRole: 4.0 版本叙事的落点：web + desktop 双形态与 loopback-only 隐私立场。
keyMessage: 4.0 是一次重构，隐私是默认。

Adapt: 保留"statement beats 逐拍落位"的签名；拍子从纯文字换为文字 + 架构层卡。
Scene 1 (0.0–1.8s): VO「4.0 全线重构」—— display 大字「4.0 全线重构」居中偏上落位（Centered，字占 ~45% 宽）。
Scene 2 (1.8–2.8s): VO「无头后端」—— 第一层卡滑入大字下方（mono 标签「HEADLESS BACKEND · Spring Boot · 127.0.0.1:24056」）。
Scene 3 (2.8–3.8s): VO「Web 界面」—— 第二层卡（「WEB UI · Vue 3.5 + Vuetify 3」）叠落；层与层之间 hairline 分隔。
Scene 4 (3.8–4.8s): VO「桌面壳」—— 第三层卡（「DESKTOP · Electron」）叠落，三层成塔（layered-depth，2 层景深）。
Scene 5 (4.8–6.5s): VO「数据只留在你的机器上」—— 塔底一枚环回徽章「loopback only · 数据不出本机」以 mint 描边点亮（唯一电压），随后整帧静持。

## Frame 9 — 生态的数字

- scene: 四组数字 count-up：1300+ 市场插件 / 25 个浏览器工具 / 3 种 Worker 语言 / 4 种数据库
- voiceover: "一千三百多个市场插件，二十五个浏览器工具——生态已经长起来了。"
- duration: 6.456s
- transition_in: crossfade
- status: animated
- src: compositions/frames/09-numbers.html
- type: social_proof
- persuasion: Statistical proof —— 数字作证
- beat: inevitability
- blueprint: dataviz-countup (Reproduce)
- focal: 1300+ 主数字（registry 块 `count-up`）
- sfx: count-ticks, stat-land

narrativeRole: 用可验证的数字收束广度，为 CTA 蓄力；全部数字可溯源到仓库与文档。
keyMessage: 不是承诺，是已经长成的生态。

Scene 1 (0.0–2.6s): VO「一千三百多个市场插件」—— 主数字卡（左置 60% 位）`count-up` 数至 **1,300+**，PingFang SC 大数 + JetBrains Mono 单位「官方市场插件」，数字下方 1px 规自画（asymmetric 60/40）。
Scene 2 (2.6–4.6s): VO「二十五个浏览器工具」—— 右侧副数字卡「25 · 浏览器 AI 工具」跟进 count-up，字号明显小于主数字（层级 3:1）。
Scene 3 (4.6–6.0s): VO「生态已经长起来了」—— 底部两枚小卡淡入（「3 · Worker 语言」「4 · 数据库」），收束行「生态已经长起来了」per-word 揭示并落定；静持。

## Frame 10 — 现在就跑起来

- scene: 蜂语标识落定，版本号 4.0.0 亮起，GitHub 地址 + 终端安装胶囊打字出现
- voiceover: "蜂语 Infinia 4.0.0，正式发布。现在就去 GitHub，把它跑起来。"
- duration: 7.416s
- transition_in: zoom-through
- status: animated
- src: compositions/frames/10-cta.html
- type: cta
- persuasion: 行动指令 + 渠道给足
- beat: urgency-to-act
- blueprint: prompt-type-submit-generate (Adapt)
- focal: 终端安装胶囊（registry 块 `code-terminal-run`）
- sfx: brand-sting, keyboard-final, caret-loop-off

narrativeRole: 收束到行动：版本宣告 + 唯一渠道（GitHub），终端胶囊呼应开发者受众。
keyMessage: v4.0.0 已发布，跑起来只要一条命令。

Adapt: 保留"typed command holds with blinking caret"的 CTA 签名；头部先落品牌 lockup，与 F1 打字开场形成首尾呼应。
Scene 1 (0.0–2.2s): VO「蜂语 Infinia 4.0.0」—— ✱ 标记自画（`svg-path-draw`）+ 「蜂语 Infinia」per-word 落位，右侧版本签「v4.0.0」翻转亮相（`discrete-text-sequence`）（Centered lockup，~55%）。
Scene 2 (2.2–3.2s): VO「正式发布」—— 一条 mint 全宽细带自左向右扫过字底（`svg-path-draw`，本帧唯一电压），随扫随停。
Scene 3 (3.2–5.4s): VO「现在就去 GitHub，把它跑起来」—— 下方终端胶囊（navy 表面、mono）弹入，逐字打出「github.com/MuskStark/FengYu · Releases」，尾行输出「✓ v4.0.0 released — Ready to run」。
Scene 4 (5.4–6.0s): 静持：只留光标低幅闪烁（subtle jitter）—— 全片收在等待下一次输入的姿态上。

## Frame 11 — Slogan 收尾卡

- scene: 全彩产品图标落定于近黑画布中央，slogan 大字逐词揭示，下方 mono 小字标注版本与地址
- voiceover: ""
- duration: 4.4s
- transition_in: crossfade
- status: animated
- src: compositions/frames/11-slogan.html
- type: branding
- persuasion: 品牌收束 —— slogan 作为最后一帧的落点
- beat: peace of mind + inevitability
- blueprint: titlecard-reveal (Reproduce)
- focal: slogan「说出目标，蜂语替你跑完全程」
- asset_candidates: assets/infinia-logo.svg — Infinia 官方无穷环标志（蓝紫渐变，取自 frontend/public）

narrativeRole: 全片的最后定格 —— 把 message 原句作为 slogan 刻在片尾；音乐在此收尾。
keyMessage: 说出目标，蜂语替你跑完全程。

Scene 1 (0.0–1.2s): 近黑地面；全彩 Infinia 图标（~96px）以平滑 scale 0.9→1 落定画面中上（Centered，`spring-pop-entrance` 的平滑长尾变体，无 overshoot）。
Scene 2 (1.2–3.0s): VO 无 —— slogan「说出目标，蜂语替你跑完全程」按 VO 呼吸节奏分三拍 per-word 揭示（「说出目标」/「蜂语替你」/「跑完全程」，display 档 PingFang SC 500，`dynamic-content-sequencing`）；「跑完全程」落位时其下 1px mint 短规自画（`svg-path-draw`，本帧唯一电压）。
Scene 3 (3.0–4.4s): 底部 mono 小字一行淡入「Infinia 4.0.0 · github.com/MuskStark/FengYu」（1.35cqw），随后整帧静持至音乐淡出。
