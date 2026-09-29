# RESUME — 在另一台电脑继续这个视频项目

FengYu 4.0 宣发视频（HyperFrames / product-launch-video 工作流）。成品 `renders/video.mp4`
（1920×1080，60.5s）。当前版本：无字幕；**苹果发布会风格**（纯黑舞台 + 玻璃暗卡 + 苹果蓝，
渐变仅 F02 大字 / F10 扫带 / F11 流之所往，见 frame.md）；定位「通用 AI 工作平台」，Flow
三卖点（AI 编写/调用/定时执行）在 F03（SCHEDULED 徽章）；扩展面 .fyp 插件 / Skill / MCP
（F06）；slogan「蜂之所向，流之所往」（F11）；配音 edge-tts `zh-CN-YunjianNeural`（-4%，
首尾静音已修剪，支持按帧重生成：`python3 scripts-edge-tts.py 2 3 6`）；真品图标（`assets/infinia-logo.svg`）出现在 F02/F10/F11；
片尾 slogan 卡「说出目标，蜂语替你跑完全程」。

## 项目分层（从上到下读）

`BRIEF.md`（为什么做、给谁看）→ `STORYBOARD.md`（逐帧计划 + 修订记录）→ `frame.md`
（设计系统：商店配色 —— 暖米白 `#faf9f6` 地面 / `#ffffff` 卡片 / `#18181b` 文字 /
品牌金电压 `#eab04b`（填充）· `#885400`（细线/文字），终端窗用商店暗色 chrome
`#09090b/#141416/#1c1c20`，PingFang SC + JetBrains Mono）
→ `compositions/frames/*.html`（11 帧，每帧独立 GSAP 时间轴）→ `index.html`（组装总入口）。
`STORYBOARD.md` 的 `## Video direction` 区记录全片不变量；改任何帧前先读它。

## 新机器环境

必需：
- Node 22+（项目 `package.json` 已钉 `hyperframes@0.8.90`：`npm run dev/check/render`）
- FFmpeg（渲染与音频合成）
- 渲染浏览器：首次跑 `npx hyperframes browser ensure`
- HyperFrames 工作流技能（编排脚本所在）：`npx hyperframes skills update product-launch-video`

按需（只改音频时）：
- Python 3 + `pip install edge-tts`（中文旁白，`zh-CN-XiaoxiaoNeural`）
- Kokoro 本地引擎在本网络不可用（HF 下载过慢），不要走回头路

**字体注意**：显示字体 PingFang SC 是 macOS 系统字体。在 Linux/Windows 上渲染会回退 ——
要么接受回退（frame.md 建议 CJK 回退 Noto Serif/Sans SC），要么装 Noto 后把帧内
`local("PingFang SC")` 的栈补上 `"Noto Sans SC"`。追求与当前成品逐像素一致请用 macOS 渲染。

## 常用命令（项目根目录）

```bash
npm run dev                                  # 实时预览
npx hyperframes check                        # 布局/对比度校验
npx hyperframes lint                         # 结构 lint
npx hyperframes snapshot --at "8.3,66.2"     # 快照审查
npx hyperframes render --quality high --output renders/video.mp4   # 重渲染（约 1 分钟）
```

## 修改后的重组装链

帧 HTML 改动后（不影响时长/音频）：
```bash
node <skills>/product-launch-video/scripts/assemble-index.mjs --storyboard ./STORYBOARD.md --hyperframes .
npx hyperframes render --quality high --output renders/video.mp4
```

改旁白/文案（SCRIPT.md）后：
```bash
python3 scripts-edge-tts.py                  # 重新生成 10 条旁白 + 词级时间戳 → audio_meta.json
node <skills>/product-launch-video/scripts/audio.mjs sync-durations --audio-meta ./audio_meta.json --storyboard ./STORYBOARD.md
node <skills>/product-launch-video/scripts/assemble-index.mjs --storyboard ./STORYBOARD.md --hyperframes .
# 重渲染前记得 transitions inject（assemble 会重写 index.html）：
node <skills>/product-launch-video/scripts/transitions.mjs inject --storyboard ./STORYBOARD.md --hyperframes .
```

改 BGM/SFX：编辑 `make-audio.sh`（ffmpeg 纯合成，无版权）后 `zsh make-audio.sh`，
再把 `audio_meta.json` 的 `bgm.duration_s` 对齐，然后重组装。

`<skills>` = 本机 HyperFrames 技能安装目录（ZCode 环境为 `~/.zcode/skills`）。

## 历史决策（不要走回头路）

- 中文 TTS 用 edge-tts（HeyGen 未登录；Kokoro/HuggingFace 在原网络 13KB/s 不可行）。
- BGM/SFX 全部 ffmpeg 合成（FreePD 直链被封、MusicGen 权重同样在 HF）。
- 字幕已移除（归档在 `.hyperframes/removed-captions/`，恢复 captions.mjs build 流程即可加回）。
- frame.md 的品牌映射经手工暗色反转（混色脚本默认保留浅色极性）。
