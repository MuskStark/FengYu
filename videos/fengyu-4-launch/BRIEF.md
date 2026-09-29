---
workflow: product-launch-video
flow: automation
storyboard: no
message: "说出目标，蜂语替你跑完全程"
destination: web
aspect: 1920x1080
language: zh-CN
length: 60s
angle: agent-outcomes
---

## Intent

为 FengYu / Infinia（蜂语）4.0.0 GA 制作 60 秒产品宣发视频。受众：开发者、技术团队与效率型用户。
产品定位：AI 原生编排平台 —— Plan-and-Execute Agent 把一句自然语言目标拆解为多步骤业务流程，
通过三大扩展面执行：`.fyp` 插件（沙箱 iframe UI + Java/Python/Go JSON-RPC worker）、`.fys` 技能、
内置 AI 工具。4.0 的发布时刻：从 JavaFX 桌面单体重构为 headless Spring Boot 后端 + Vue 3.5 Web UI +
Electron 桌面壳（web + desktop 双形态）。语调：克制、专业、有高级感的开发者工具气质（Codex 式单色美学），
不夸张不喊口号，让产品的真实能力自己说话。

自主运行的 pitch 决策（内部五路采样）：选定「具体成果驱动的 Agent 叙事」—— 真实场景一句话→
Agent 拆步→插件/技能/工具执行→结果落地（Excel 拆分、邮件、浏览器、电脑操控），结尾落在 4.0
web+desktop 双形态与生态（插件市场/技能库）。落选：纯架构讲解片（典型但偏干）、抽象动态品牌片
（尾部分布，气质对但信息量低）。

## Assets

- 无用户提供的素材文件。产品资料来自仓库 README.md / CHANGELOG.md / docs/。
- 品牌 token 来自 frontend/src/plugins/md3-themes.ts（Codex 式单色调色板，见 capture/extracted/tokens.json）。

## Customizations

- 关键数字（1300+ 官方市场插件、25 个浏览器 AI 工具、4 种数据库等）可用 count-up 处理（视节奏取舍）。
- UI 画面以 HTML 精修重绘产品界面（聊天、Flow 画布、插件页），不要求真实截图 —— 产品未运行捕获。

## Notes

- 语言：中文旁白/文案；品牌名保留 Infinia / FengYu / 蜂语。
- 视觉基调：近黑画布 #0d0d0d、层叠深灰表面、米白文字 #ededed、薄荷绿强调 #8fd6bd；发丝级边框；
  禁止花哨渐变堆砌和 MD3 紫色旧基线。
- 安全姿态是卖点之一：敏感操作需人工批准（confirmation-first），loopback-only 后端，加密凭据。
- 官方插件实为四个：markdown / excel / email / offlinepython；浏览器自动化是宿主内置能力而非插件。
