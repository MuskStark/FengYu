# 插件标准 UI 模板 · 差异化效果图（Aceternity 版）

FengYu 4.1 插件标准 UI 模板重设计：**四类差异化布局原型**，Infinia 设计语言
（暖白 #faf9f6 画布 / 白面板 / #e5e2db 发丝线 / 金 #eab04b 唯一高饱和交互色 /
蜂巢六边形印记），交互组件全部来自 **ui.aceternity.com 官方源码**
（`https://ui.aceternity.com/registry/<name>.json` 直取，零手写组件）。

## 效果图（shots/）

| 文件 | 内容 |
|---|---|
| `00-overview.png` | 模板体系总览：四类原型 + 设计令牌 + 类目映射 |
| `01/02-t1-markdown-*.png` | T1 文档编辑类（text · Markdown 编辑器）明 / 暗 |
| `03/04-t2-excel-*.png` | T2 文件处理类（file · Excel 拆分器）明 / 暗 |
| `05/06-t3-email-*.png` | T3 沟通中心类（network · 邮件中心）明 / 暗 |
| `07/08-t4-python-*.png` | T4 构建台类（dev · 离线 Python 构建器）明 / 暗 |

## 四类模板

| 类目 (manifest.category) | 原型 | 骨架 | Aceternity 组件 |
|---|---|---|---|
| text | T1 写作台 | 窄轨 + 文档栏 + 编辑/预览双栏 | Sidebar · PlaceholdersAndVanishInput · CardSpotlight · TextGenerateEffect · FloatingDock |
| file | T2 流水线 | 导入区 + 流水线 + 任务表 | Sidebar · FileUpload · MultiStepLoader · GlowingEffect · Meteors |
| network | T3 中心台 | 动效页签 + 列表⇄阅读 + 队列 | Sidebar · Tabs · PlaceholdersAndVanishInput · CardSpotlight · StatefulButton · MovingBorder · CardStack |
| dev | T4 构建台 | 配置列 + 状态卡 + 控制台 | Sidebar · Terminal · GlowingEffect · Meteors |

未识别类目回退 T2；模板在 `fyp init` 脚手架阶段按 category 选择。

## 设计令牌（= frontend/src/styles/zai.css，值逐字对齐）

- 画布 / 弱面 `#faf9f6 / #f3f1ec`，面板白；暗色 `#09090b / #141416 / #1c1c20`
- 发丝线 `#e5e2db`（暗 `#29292d`），分层靠线不靠投影
- 金 `#eab04b`（暗 `#f6bd60`）：唯一高饱和交互色，金底永远配深墨 `#18181b`
- 焦点/强调 `#885400`（暗金），选中态 = 中性胶囊 + 2px 金色内嵌条
- 蜂巢印记：六边形（金底墨标）用于插件标识与联系人头像
- 圆角：卡片 12 / 控件 8 / 标签全圆；正文 14 / 辅助 12 / mono 11.5

## 运行

```bash
npm install
npm run dev        # http://localhost:5194/?page=t1&theme=dark  (page: overview|t1-t4, theme: light|dark)
npm run build && npm run preview
node shot.mjs      # 重拍 shots/（Playwright, 1440×900 @2x, 每页等 4.2s 让动效进入中段）
```

## 对主程序工具链的含义（后续落地时）

现有 `toolchain/ui`（@infinia/plugin-ui）是 Vue + Vuetify 模板；本方案的 Aceternity
组件均为 React + Tailwind + motion。落地即推出 React 版插件 UI 模板层（新包或
plugin-ui 2.0），iframe 沙箱与 postMessage 桥不受影响。
