---
format: 1920x1080
duration: 72.8s
message: "蜂之所向，流之所往"
arc: Rebuild Cold-open（SwissKit 谢幕爆炸 → 命名 → 对话 → Flow 创建/执行 → demo ×2 → 联动 → breadth → trust → CTA）
audience: 开发者、技术团队与效率型用户（中文）
mode: autonomous
music: minimal confident tech underscore, restrained, warm
---

## Video direction

- **revision (user, 2026-09-29g)**：**片头改为重构宣言**（用户点名 3.x SwissKit 遗产叙事 +
  苹果式爆炸场面）：新 F01「谢幕与重构」—— SwissKit 3.x 石碑颤动碎裂爆炸（15 碎片慢动作 +
  粒子迸发 + 白蓝闪光），Infinia 4.0.0 lockup 从辉光中显形；旧「一句话的委托」帧移除（委托
  气泡由 F03 聊天线程承接）；L1 重录（8.14s），sfx 换爆炸落点 + 品牌亮相铃。全片 13 帧 ~72.7s。

- **revision (user, 2026-09-29f)**：**新增三大产品镜头**（13 帧，~69.5s）：F03「像搭档一样对话」
  （聊天线程 ack / 追问 / 流式作答 / 结果卡）；F04 改造为「Flow 创建与执行」（DSL 代码卡逐字
  打出 → 四节点飞入 → 连线级联 → 蓝勾）；F07 新增「AI 与 Flow 联动」（聊天调用 Flow →
  Flow 内 ✱ AGENT 回调 → ⏰ DAILY 09:00 定时开跑）。旧 F03 的 SCHEDULED 徽章移入 F07。
  帧文件整体重编号（03-chat / 04-flow / 05-excel / 06-approval / 08-surfaces / 09-browser /
  10-platform / 11-numbers / 12-cta / 13-slogan），旁白重录 L3/L4/L7。

- **revision (user, 2026-09-29e)**：**产品定位修订**（蜂语是类 ChatGPT 的通用 AI 工作平台）：
  ① F02 命名副题改「通用 AI 工作平台」；② **Flow 升为头号卖点**——F03 升级为「AI 生成
  Flow」画布（meta「AI-GENERATED FLOW」），VO 直说“把目标写成 Flow”，状态栏新增
  「SCHEDULED · DAILY 09:00」定时徽章（AI 编写 / AI 调用 / 定时执行三卖点齐）；③ F06 扩展面
  改为 **.fyp 插件 / Skill 技能 / MCP 服务**（MCP 首次登场）；④ **新 slogan「蜂之所向，
  流之所往」**（F11 两拍揭示，「流之所往」承载品牌渐变，渐变 санкция 从两处扩为三处）。
  旁白 L2/L3/L6 重录（按帧重生成，其余帧音频与 cue 不动），F02/F03/F06 cue 按新词级
  时间戳重排；F04/F05/F07–F10 保持不变。

- **revision (user, 2026-09-29d)**：**采用苹果发布会风格**：纯黑舞台 `#000000`；玻璃感暗卡
  `#1d1d1f / #2d2d30` + 白 alpha 发丝线；文字 `#f5f5f7` 主 / `rgba(245,245,247,α)` 次；
  强调色苹果蓝 `#2997ff`（填充与细线同色，其上文字白）；英雄字重升 600；卡片圆角加大
  （12→18 / 8→12）；地面允许一处 ≤0.10 alpha 蓝色辉光替代纸面提亮；**仅两处品牌渐变**
  （Apple 四色 `#0090f7→#a259ff→#f2416b→#f55600`）：F02 品牌大字（background-clip 渐变文字）
  与 F10 扫带。终端成功绿 `#30d158`，表格数字橙 `#ff9f0a`。结构/动效/时长/配音全部不变。

- **revision (user, 2026-09-29c)**：反馈修订 —— ① **突出 Agent 与 Flow**：F03 重建为
  「Agent Flow 画布」（Agent 枢纽 + 蛇形 2×2 节点图 + 金色连线自画 + 执行光点沿线流动，
  节点逐个点亮成金勾徽章）；F02 副题改为「AI 原生的 Agent 编排平台」。② **配音整体重录**：
  edge-tts 换 `zh-CN-YunjianNeural`（rate -4%，沉稳男声、纪录片质感），逐条修剪首尾静音
  （头留 60ms / 尾留 140ms），旁白更连贯不生硬；全片 cue 按新词级时间戳重对齐 —— F01 打字、
  F02 唱名、F03 节点、F10 扫带/终端为词级对齐，F04–F09 按新旧语音时长比例整体缩放。

- **revision (user, 2026-09-29b)**：**全系换用商店配色**（`FengYu-Store/store-web` 默认浅色主题，
  Aceternity 中性面板 + 暖金品牌色）：地面（cream 角色）暖米白 `#faf9f6`；卡片表面 `#ffffff`、
  次级填充/胶囊 `#f3f1ec`（tile 角色）；文字（ink 角色）`#18181b` 主、`#62626c` 次；发丝线
  `#e5e2db` 或 ink@8–14%。电压色（coral 角色）由薄荷绿改为**品牌金**：填充/色带/徽章
  `#eab04b`（其上文字一律 `#18181b`），细规/描边/链接文字 `#885400` —— **每帧至多一次**。
  终端/浏览器/桌面窗保持暗色设备模型，但改用商店暗色 chrome `#09090b / #141416 / #1c1c20`
  （窗内文字 `#fafafa` / `rgba(250,250,250,α)`，成功绿 `#86d69b`）。固定语法色 teal
  `#5DB8A6` / amber `#E8A55A` 与窗口红绿灯只作装饰保留。

- **revision (user, 2026-09-29)**：移除字幕轨道（caption band 保留但不放字幕）；真实产品图标
  `assets/infinia-logo.svg`（取自 `frontend/public/infinia-logo.svg`）出现在 F02 命名卡与 F10
  lockup；新增 F11 slogan 收尾卡。

- **palette system**（frame.md 为唯一色彩事实）：地面（cream 角色）纯黑 `#000000` 舞台；内容表面玻璃暗卡 `#1d1d1f` → 次级面 `#2d2d30`（tile 角色）；文字（ink 角色）`#f5f5f7` 主、`rgba(245,245,247,α)` 次；电压色（coral 角色）苹果蓝 `#2997ff` —— 填充/色带/徽章/细规同色，其上文字白 —— **每帧至多一次**（确认卡、连接线、徽章或 CTA 胶囊其一）；终端/浏览器/桌面窗用 `#1d1d1f` 机身 + `#2d2d30` chrome + `#000` 内嵌（固定语法色 teal `#5DB8A6` / amber `#ff9f0a` 只出现在终端与表格里）。发丝线一律 white@8–16%。品牌渐变仅两处（F02 大字 / F10 扫带）；地面辉光 ≤0.10 alpha 蓝色、每帧至多一处。禁止薄荷绿/金色旧电压、内容上的重阴影。
- **typography**：PingFang SC 显示层 weight 500、句首大写、负字距；正文 400；JetBrains Mono 承担 kicker（全大写 0.16em、前缀 ✱）、mono 标签、数字单位与终端。所有大字落在 1.4cqw 可读底线之上。
- **motion grammar + reveal model**：全片 `power3` 长尾减速，平滑压倒弹性，无 overshoot；每帧只进 VO 当前说到的东西，后续元素等各自的口语 cue 在**后半段**依次揭示；入场一律 `fromTo`；动作全部走 GSAP 时间轴（禁 CSS transition/@keyframes）；帧内接缝用速度匹配剪（cut-the-catalog），帧间交给 harness 注入的 transition。
- **rhythm / held-frame allocation**：F2（命名卡）与 F5 尾（批准落定的静持）是刻意 breather；F10 以光标闪烁的静持收尾 —— 静持优于坏动作。其余帧按 VO 节奏揭示。
- **caption band**：底部 ~17% 为字幕保留带，所有内容排在上方 83%。
- **negative list**：不出现幻灯片式 front-load（前 25% 倾倒全部内容然后冻结）；不出现屏保式漂浮（多元素各自游动）；无 lazy breathing、无后半程慢推拉；无 `repeat`/`yoyo`、无 `Math.random`；除刻意的 UI 重构外不画导航栏/浏览器 chrome/滚动条；**一切数字必须可溯源**（README/docs）：1300+ 市场插件、25 个浏览器工具、3 种 Worker 语言、4 种数据库、24056 端口。

## Frame 1 — 谢幕与重构

- scene: 黑场冷开场 —— 暗色石碑「SwissKit 3.x · LEGACY」升起，到此为止@1.36 颤动+两道裂纹划过，内核@2.55 爆炸（白蓝闪光、15 碎片慢动作飞散、一次性粒子迸发），蓝色辉光中 Infinia 标志与「蜂语 Infinia 4.0.0」lockup 显形
- voiceover: "SwissKit 3.x，到此为止。内核、界面，全部推倒重写——蜂语 Infinia 4.0.0。"
- duration: 8.136s
- transition_in: cut
- status: animated
- src: compositions/frames/01-rebuild.html
- type: hook
- persuasion: 推倒重写的宣言 —— 苹果式爆炸冷开场立住 4.0 的分量
- beat: awe + resolution
- blueprint: kinetic-type-beats (Adapt)
- focal: 石碑爆炸 → Infinia lockup 显形
- sfx: submit-thunk (爆炸落点), brand-sting (lockup)

narrativeRole: 苹果式冷开场 —— 旧时代谢幕、新时代登场的宣言，为全片"通用 AI 工作平台"立起分量。
keyMessage: 3.x 到此为止，Infinia 4.0.0 从内核重写。

Scene 1 (0.0–1.2s): 黑场，暗色石碑（玻璃暗卡 720×300）升起，mono 刻字「SwissKit 3.x · LEGACY RUNTIME」。
Scene 2 (1.3–1.8s): VO「到此为止」—— 石碑衰减式颤动（显式 x 链，无 repeat），两道裂纹划过碑面。
Scene 3 (2.55–4.5s): VO「内核、界面，全部推倒重写」—— 白蓝闪光，石碑无缝换为 15 块碎片慢动作飞散（幂3长尾），一次粒子迸发（预设角度表，无随机），蓝色辉光自中心生长。
Scene 4 (5.7–8.6s): VO「蜂语 Infinia 4.0.0」—— 碎片散尽，Infinia 标志 scale 0.84→1 显形，「蜂语 Infinia」逐词落位（5.76/6.27），版本胶囊 4.0.0@6.82，mono 签「REBUILT FROM THE GROUND UP」@7.3；静持。

## Frame 2 — 这就是蜂语

- scene: 提交瞬间画面沉入品牌帧 —— ✱ kicker + 渐变 "蜂语 Infinia" 大字 + 「通用 AI 工作平台」副题
- voiceover: "这就是蜂语 Infinia——AI 原生编排平台。"
- duration: 3.72s
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
Scene 2 (1.5–3.9s): VO「通用 AI 工作平台」—— 副题三词逐词揭示于大字下方（通用@1.69 AI@2.19 工作平台@2.66）；kicker 下画一条 1px 蓝色短规（`svg-path-draw`）——本帧唯一电压。
Scene 3 (3.9–4.4s): 刻意静持的命名卡：整帧几乎不动（至多 subtle jitter）—— 全片最静的一帧，压住节奏。

## Frame 3 — 像搭档一样对话

- scene: 对话延续 F01 的委托 —— 聊天线程里 Agent 回话：ack 落定、用户追问打字上屏、Agent 流式作答、结果卡（3 份工作簿 + 已发送）落回聊天
- voiceover: "它像搭档一样对话——你追问，它回答；结果，直接回到聊天里。"
- duration: 5.664s
- transition_in: crossfade
- status: animated
- src: compositions/frames/03-chat.html
- type: feature_showcase
- persuasion: 类 ChatGPT 的对话体验 —— 会话即工作台
- beat: warmth + competence
- blueprint: prompt-type-submit-generate (Adapt)
- focal: 聊天线程里的流式回答与结果卡
- sfx: keyboard-soft, stat-land

narrativeRole: 通用 AI 工作平台的第一体验：对话不是玩具，是工作界面 —— 追问、补充、交付都发生在聊天里。
keyMessage: 会话即工作，结果回到聊天。

Scene 1 (0.0–0.9s): 聊天窗（玻璃暗卡，52% 宽，居中）随 VO 浮入；F01 的委托气泡已在线程里（static）。
Scene 2 (0.95–1.7s): VO「对话」—— Agent ack 气泡弹入「好的，已接手。我先拆步骤，有疑问随时打断我。」
Scene 3 (1.79–2.5s): VO「你追问」—— 用户追问气泡逐字打出「华南的加一列同比。」
Scene 4 (2.55–3.4s): VO「它回答」—— Agent 流式作答「已添加同比列 → 3 份工作簿已更新 ↓」。
Scene 5 (3.4–4.4s): VO「结果」—— 结果卡落定，三枚文件 chip（华东/华南/华北.xlsx）依次弹出。
Scene 6 (4.49–6.2s): VO「回到聊天里」—— 蓝点 + 「已发送 · 12 位收件人」点亮；静持。

## Frame 4 — Flow 创建与执行

- scene: 同一块 Flow 画布 —— 左上 DSL 代码卡逐字打出（AI 写 Flow），四节点从代码中飞入槽位，连线级联自画 + 光点流动，执行@4.5 全部翻蓝勾
- voiceover: "创建 Flow 不用画图——AI 把目标写成节点，连线即执行。"
- duration: 5.136s
- transition_in: crossfade
- status: animated
- src: compositions/frames/04-flow.html
- type: feature_showcase
- persuasion: Flow 创建零门槛 —— AI 代写，画布即结果
- beat: ease + power
- blueprint: agent-progress-theater (Adapt)
- focal: DSL 代码卡 → 四节点画布的交接瞬间
- sfx: keyboard-soft, step-tick ×4, status-hum

narrativeRole: Flow 是平台另一大卖点：创建（AI 写节点）与执行（连线即跑）一镜说完。
keyMessage: 创建 Flow 不用画图，AI 写好、连线就跑。

Adapt: 保留"节点点亮 + 完成回执"签名；新增 DSL 代码卡作为创建具象 —— 左侧 Agent 枢纽（✱ + AGENT · ORCHESTRATOR），meta「AI-GENERATED FLOW · 4 NODES」。
Scene 1 (0.0–1.7s): VO「创建 Flow 不用画图」—— 面板浮入，Agent 枢纽落定；DSL 代码卡滑入画布右上，三行流程逐字打出。
Scene 2 (1.76–3.5s): VO「AI 把目标写成节点」—— 代码卡上浮让位，四节点快速飞入槽位（2.0/2.35/2.7/3.05），计数连跳。
Scene 3 (3.79–4.5s): VO「连线」—— 四条蓝色连线级联自画，执行光点依次流过。
Scene 4 (4.5–5.6s): VO「即执行」—— 全部徽章翻蓝勾，PROGRESS 铺满、蓝胶囊「已完成」弹入；静持。

## Frame 5 — Excel 拆分跑起来

- scene: 官方 Excel 插件执行拆分 —— 行数据流动，按分公司切成多份工作簿，完成回执逐个打勾
- voiceover: "官方 Excel 插件接管拆分——三千行明细，几秒变成分公司报表。"
- duration: 5.736s
- transition_in: crossfade
- status: animated
- src: compositions/frames/05-excel.html
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

## Frame 6 — 发送之前，先问你

- scene: 邮件按分公司自动写好排队；一条"待批准"确认卡浮起，鼠标点下批准，发送状态点亮
- voiceover: "邮件写好、归档，发送之前——它先请你批准。"
- duration: 4.008s
- transition_in: crossfade
- status: animated
- src: compositions/frames/06-approval.html
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

## Frame 7 — AI 与 Flow 联动

- scene: 左聊天右 Flow 双卡 —— 聊天里一句话调用 Flow（蓝脉冲沿连线飞入，PLANNED→RUNNING）；Flow 中部节点亮起 ✱ AGENT 徽章（Flow 里调 AI）；底部 SCHEDULED · DAILY 09:00 徽章落定，开跑时再一道脉冲
- voiceover: "AI 能调用 Flow，Flow 里也能调 AI——每天早上九点，它自动开跑。"
- duration: 5.4s
- transition_in: crossfade
- status: animated
- src: compositions/frames/07-link.html
- type: feature_showcase
- persuasion: 双向联动 + 定时自治 —— Flow 不是死流程，是活管道
- beat: connection + autonomy
- blueprint: constellation-hub (Adapt)
- focal: 聊天→Flow 的调用脉冲与 ✱ AGENT 回调徽章
- sfx: submit-thunk, approval-chime, step-tick

narrativeRole: 平台的粘合剂：AI 与 Flow 互为手脚，再加定时执行 —— 这是"通用工作平台"成立的关键一环。
keyMessage: AI 调 Flow，Flow 调 AI，定时自动跑。

Scene 1 (0.0–1.0s): VO「AI 能调用 Flow」—— 左聊天卡浮入，输入「跑一下周报拆分」逐字打出，Flow@0.89 回车按下。
Scene 2 (1.0–2.0s): 蓝色连线自画 + 光点飞入右 Flow 卡，头部签 PLANNED→RUNNING（Flow 里@1.45）。
Scene 3 (2.25–2.9s): VO「Flow 里也能调 AI」—— 节点 02「生成同比摘要」亮起 ✱ AGENT 徽章，卡底色点亮。
Scene 4 (3.6–4.4s): VO「每天早上九点」—— 底部 ⏰ SCHEDULED · DAILY 09:00 胶囊弹入。
Scene 5 (4.83–5.9s): VO「它自动开跑」—— 再一道脉冲飞向 Flow 卡，卡片轻微提亮一次；静持。

## Frame 8 — 一个 Agent，三种扩展

- scene: 中央 Agent 核心，三张扩展面卡片依次飞入归位 —— .fyp 插件 / Skill 技能 / MCP 服务
- voiceover: "一个平台，三种扩展——.fyp 插件、Skill 技能、MCP 服务。"
- duration: 5.568s
- transition_in: zoom-through
- status: animated
- src: compositions/frames/08-surfaces.html
- type: feature_showcase
- persuasion: 结构即论据 —— 生态可扩展
- beat: awe + confidence
- blueprint: constellation-hub (Reproduce)
- focal: 中央枢纽 + 三条自画连接线的星座图（registry 块 `constellation-hub`）
- sfx: node-pop ×3, connector-draw

narrativeRole: 从"能干一件事"抬升到"平台"：三大扩展面是 4.0 架构的真实分层。
keyMessage: 插件、Skill、MCP——能力可以无限长出来。

Scene 1 (0.0–1.4s): VO「一个 Agent」—— 中央枢纽（✱ 核心标记 + mono 标签「AGENT」）从微缩平滑落定于画面中心（Centered，枢纽 ~18%）。
Scene 2 (1.4–2.4s): VO「三种扩展面」—— 三个空节点位以 120° 间隔绕枢纽亮起淡淡的 hairline 环（一次性描画）。
Scene 3 (2.4–3.4s): VO「.fyp 插件」—— 节点一（拼图图标 + 「.fyp 插件」+ mono 小注「JSON-RPC Worker · Java/Python/Go」）飞入归位，SVG 连接线自枢纽画出（`svg-path-draw`）。
Scene 4 (3.4–4.4s): VO「.fys 技能」—— 节点二（卷轴图标 + 「.fys 技能」+「渐进式披露」）同律入场 + 连线。
Scene 5 (4.4–5.4s): VO「内置工具」—— 节点三（螺栓图标 + 「内置 AI 工具」+「浏览器 / 电脑 / 检索」）归位 + 连线；三线在同一 mint 上（本帧唯一电压）。
Scene 6 (5.4–7.0s): 星座整体合拢成 lockup：枢纽轻微提亮，三节点停驻；静持。

## Frame 9 — 真浏览器，真桌面

- scene: 浏览器代理标签页在操作真实网页；画面切换到电脑操控 —— 光标移动、键入，每步旁标注"待批准"
- voiceover: "它还能开真的浏览器，操作真的桌面——每个动作都在你的批准之下。"
- duration: 5.736s
- transition_in: crossfade
- status: animated
- src: compositions/frames/09-browser.html
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

## Frame 10 — Web 与桌面，数据不出本机

- scene: 三层架构图 —— 无头 Spring Boot 后端 / Vue Web 界面 / Electron 桌面壳，127.0.0.1 环回标识点亮
- voiceover: "4.0 全线重构——无头后端、Web 界面、桌面壳。数据只留在你的机器上。"
- duration: 7.248s
- transition_in: zoom-through
- status: animated
- src: compositions/frames/10-platform.html
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

## Frame 11 — 生态的数字

- scene: 四组数字 count-up：1300+ 市场插件 / 25 个浏览器工具 / 3 种 Worker 语言 / 4 种数据库
- voiceover: "一千三百多个市场插件，二十五个浏览器工具——生态已经长起来了。"
- duration: 5.688s
- transition_in: crossfade
- status: animated
- src: compositions/frames/11-numbers.html
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

## Frame 12 — 现在就跑起来

- scene: 蜂语标识落定，版本号 4.0.0 亮起，GitHub 地址 + 终端安装胶囊打字出现
- voiceover: "蜂语 Infinia 4.0.0，正式发布。现在就去 GitHub，把它跑起来。"
- duration: 6.24s
- transition_in: zoom-through
- status: animated
- src: compositions/frames/12-cta.html
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

## Frame 13 — Slogan 收尾卡

- scene: 全彩产品图标落定于纯黑舞台中央，新 slogan「蜂之所向，流之所往」两拍揭示（「流之所往」承载品牌渐变），下方 mono 小字标注版本与地址
- voiceover: ""
- duration: 4.4s
- transition_in: crossfade
- status: animated
- src: compositions/frames/13-slogan.html
- type: branding
- persuasion: 品牌收束 —— slogan 作为最后一帧的落点
- beat: peace of mind + inevitability
- blueprint: titlecard-reveal (Reproduce)
- focal: slogan「说出目标，蜂语替你跑完全程」
- asset_candidates: assets/infinia-logo.svg — Infinia 官方无穷环标志（蓝紫渐变，取自 frontend/public）

narrativeRole: 全片的最后定格 —— 把 message 原句作为 slogan 刻在片尾；音乐在此收尾。
keyMessage: 蜂之所向，流之所往。

Scene 1 (0.0–1.2s): 近黑地面；全彩 Infinia 图标（~96px）以平滑 scale 0.9→1 落定画面中上（Centered，`spring-pop-entrance` 的平滑长尾变体，无 overshoot）。
Scene 2 (1.2–3.0s): VO 无 —— slogan「蜂之所向，流之所往」分两拍揭示（「蜂之所向」白字 @1.25 /「流之所往」@1.76，品牌四色渐变文字，PingFang SC 600，`dynamic-content-sequencing`）；「流之所往」落位时其下 1px 蓝色短规自画（`svg-path-draw`，本帧唯一电压）。
Scene 3 (3.0–4.4s): 底部 mono 小字一行淡入「Infinia 4.0.0 · github.com/MuskStark/FengYu」（1.35cqw），随后整帧静持至音乐淡出。
