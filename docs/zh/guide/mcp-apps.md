# MCP Apps（MCP 服务器工具的渲染界面）

FengYu 实现了 [MCP Apps](https://github.com/modelcontextprotocol/ext-apps) 开放标准
（`io.modelcontextprotocol/ui` 扩展）的宿主侧：MCP 服务器的工具可以声明一个 `ui://`
HTML 资源作为它的渲染界面。当模型调用这样的工具时，聊天时间线里的工具卡片可以直接
承载这个页面——无需插件、无需安装任何额外组件，任何符合规范的 MCP 服务器
（以及兼容 OpenAI Apps SDK 别名的现成 widget）都能直接获得可渲染界面。

## 服务器侧怎么声明

工具在 `tools/list` 的 `_meta.ui` 里给出资源 URI，可选地给出展示与安全声明：

```json
{
  "name": "paint",
  "inputSchema": { "type": "object" },
  "_meta": {
    "ui": {
      "resourceUri": "ui://paint/main.html",
      "visibility": ["model", "app"],
      "csp": { "connectDomains": ["wss://example.com"] },
      "prefersBorder": true
    }
  }
}
```

- **resourceUri**：指向该服务器自身的一个资源，内容为 `text/html;profile=mcp-app`
  （或普通 `text/html`）的单文件 HTML，上限 16 MiB。兼容已弃用的扁平键
  `_meta["ui/resourceUri"]` 与 OpenAI 的 `openai/outputTemplate`。
- **visibility**：`model`（进模型工具表）与 `app`（仅供界面页调用）的组合，缺省两者皆可。
  一个 **app-only** 工具只有在该服务器存在已打开的界面页时才会进入模型工具表（认领语义）。
- **csp**：四类域白名单（`connectDomains` / `resourceDomains` / `frameDomains` /
  `baseUriDomains`）。资源 `resources/list` 条目自己的 `_meta.ui.csp` 优先于工具级声明。
  只接受 `https:`/`wss:` 任意主机与 `http://127.0.0.1` 回环。
- **尺寸与权限**：资源级 `_meta` 还可声明 `heightHint` / `minFrameHeight`（卡片初始与最小
  高度）、`showInline`（结果直接展开）以及四个浏览器权限键（camera / microphone /
  geolocation / clipboardWrite，未声明一律拒绝）。

## 界面如何被渲染

准备阶段（浏览器卡片展开时）由后端经该服务器的 MCP 连接读取 HTML，登记到一个随机的
128 位沙箱 id 下，并通过**专用沙箱端口**（应用端口 +1 的 loopback 端口，独立浏览器
origin）提供服务。页面带严格 CSP 响应头（`default-src 'none'` + 声明域），iframe 附加
`sandbox` 属性（不含 `allow-same-origin`），因此页面运行在不透明 origin 上——与宿主
DOM、存储、登录令牌完全隔离。`/sandbox/*` 在应用端口上始终是 404。

页面与宿主之间使用官方 `@modelcontextprotocol/ext-apps` 桥协议。页面可以：

- 读取自身服务器的资源（每次 8 MiB、MIME 白名单）与列出资源/模板；
- 调用自身服务器的工具——**每次调用都要在卡片上显式确认**；
- 请求打开外链（仅 http/https，宿主校验）、请求调整卡片高度、请求关闭自己；
- 下载文件内容；
- 订阅自身服务器的资源：页面发送标准的 `resources/subscribe`，宿主代为登记（按
  服务器+URI 引用计数），服务器推送的更新以标准
  `notifications/resources/updated` 通知回到页面（页面的 `ui://` 界面资源本身自动订阅）；
- 提交界面状态（widget state）：页面通过 `ui/set-widget-state` 把状态交给宿主内存保存
  （按 服务器::作用域 隔离，会话内同逻辑页面重挂载时经
  `ui/notifications/widget-state` 通知取回，应用重启即空）。这是宿主扩展，能力键
  `experimental["fengyu/widgetState"]`，页面按其探测；
- 接收规范数据通知：握手完成后宿主先送 `ui/notifications/tool-input`（工具入参）再送
  `ui/notifications/tool-result`（捕获的结果）；所属工具调用被中断时送
  `ui/notifications/tool-cancelled`；
- 向对话注入用户消息（`ui/message`）与为模型下一轮暂存上下文
  （`ui/update-model-context`）——仅对承载于对话内的卡片宣告这两个能力，面板/预览类
  挂载面不宣告，页面按规范降级；
- 切换展示模式（`ui/request-display-mode`）：inline 与 fullscreen 以原位覆盖层生效
  （切换过程页面不中断），`pip` 应答为 inline；
- 感知宿主变化：主题与语言切换会以 `ui/notifications/host-context-changed` 送达在线
  页面；初始 host context 携带主题、语言、时区、平台、容器尺寸、可用展示模式，以及
  一组由 FengYu 主题令牌换算的样式变量，供跟随宿主风格的页面使用；
- 在设置开启采样后，请求宿主模型补全（默认关闭：`fengyu.mcp.ui.sampling=true` 或环境
  变量 `FENGYU_MCP_UI_SAMPLING=1`，有消息数/单条长度/最大 token 三重硬上限）。支持
  `sampling.tools`：页面可声明自己的工具，采样可以以 `stopReason:"toolUse"` +
  `tool_use` 块收尾，页面本地执行后携带追加的 `tool_use`/`tool_result` 历史再次采样
  ——由页面驱动的应用内 agent 回环。

## 已知限制

- 沙箱页面运行在不透明 origin 上：没有 localStorage/IndexedDB 等持久存储（依赖存储的
  页面会退化；需要跨重挂载保留的界面状态请使用 widget state）。
- 远程（非 loopback）部署没有沙箱端口，界面回退为纯文本结果卡片。
- Web/移动端与桌面端能力一致；`pip` 展示模式应答为内联（fullscreen 以原位覆盖层生效）。
- 宿主样式变量只覆盖能解析为字面量值的主题令牌子集；间接令牌（color-mix 等）会被
  省略——规范中每个键都是可选的。
