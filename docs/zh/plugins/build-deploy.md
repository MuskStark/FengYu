---
title: 构建与部署
description: 产出一个 .fyp 包——单插件 CLI 流程（fengyu plugin validate 然后 build）、官方多插件 build-packages.sh 脚本、.fyp 布局，以及安装产物。
lang: zh-CN
---

# 构建与部署

一个 `.fyp` 只是一个具有固定布局的 zip 归档。有两种构建流程：用于第三方插件的**单插件 CLI 流程**，以及组装并签名两个随产品发布插件的**官方多插件脚本**。两者最终都产出一个你通过[插件市场](/zh/plugins/marketplace)安装的 `.fyp`。

## `.fyp` 布局

每个 `.fyp` 都是一个 zip，恰好包含以下条目：

```
my-plugin-1.0.0.fyp
├── manifest.json          # 元数据、权限、aiTools
├── ui/
│   ├── index.html         # 入口 HTML（+ 与之并列的任何 CSS/JS 资源）
│   └── sdk.js             # UI 所 import 的 @fengyu/plugin-sdk bundle
└── backend/
    └── worker.jar         # shaded 的 JSON-RPC worker 可执行文件
```

`manifest.json` 声明 `ui.entry`（通常是 `ui/index.html`）和 `backend.command`（通常是 `java -jar backend/worker.jar`）。宿主把 `ui/**` 通过 `/plugin-runtime/{id}/**` 提供，并从 `backend.command` 启动 worker。参见 [清单](/zh/plugins/manifest) 与 [插件概述](/zh/plugins/overview)。

## 构建 worker jar

worker 是由 `maven-shade-plugin` 产出的 shaded fat JAR。把 `finalName` 和 `mainClass` 设为你的 `*WorkerMain`：

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <configuration>
    <finalName>my-worker</finalName>     <!-- → my-worker.jar -->
  </configuration>
  <executions>
    <execution>
      <phase>package</phase><goals><goal>shade</goal></goals>
      <configuration>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>com.example.myplugin.MyWorkerMain</mainClass>
          </transformer>
        </transformers>
      </configuration>
    </execution>
  </executions>
</plugin>
```

官方插件用的是同一套配方：`markdown-worker` / `excel-worker` 作为 `finalName`，主类为 `MarkdownWorkerMain` / `ExcelWorkerMain`。编写 worker 本身见 [Worker（JSON-RPC）](/zh/plugins/worker)。

## 单插件构建（CLI）

对于第三方插件，使用 `fengyu plugin` CLI。先校验，再构建：

```bash
# 1. 校验项目（清单 + 结构）
fengyu plugin validate

# 2. 构建 .fyp
fengyu plugin build --out dist-package/<id>-<version>.fyp
```

`build` 内部会运行 `validate` 并把项目打成 `--out`（默认 `dist-package/<id>-<version>.fyp`）处的 `.fyp`。完整命令参考见 [SDK 与 CLI](/zh/plugins/sdk-cli)。

## 官方多插件构建

两个随产品发布的插件由 `OfficialPlugins/build-packages.sh` 组装。它依次执行：

1. **构建 TypeScript SDK**（`plugin-sdk/typescript`）并把 bundle 复制进每个插件的 `ui/sdk.js`。
2. **运行 Excel UI 测试**并**校验 worker 所依赖的 POI 服务**。
3. 为每个插件**组装** `packages/{markdown,excel}/`，布局为：
   - `manifest.json`
   - `ui/`（入口 HTML + 资源，包括第 1 步得到的 `ui/sdk.js`）
   - `backend/worker.jar`（来自对应 `-worker` 模块的 shaded jar）
4. 把每个组装好的目录树**打 zip** 成 `target/packages/fan.summer.{markdown,excel}-4.0.0.fyp`。

产出的 `.fyp` 文件就是 `OfficialPluginSeeder` 安装进全新宿主的内容。它们的清单带有 `"official": true`，因此描述符的 `source` 为 `OFFICIAL`。

## 安装产物

用 CLI（它封装了 `POST /api/plugin-market/upload`）把构建好的 `.fyp` 推进一个运行中的宿主：

```bash
fengyu plugin install ./dist-package/com.example.my-plugin-1.0.0.fyp \
  --host http://127.0.0.1:24056 --token "$FENGYU_TOKEN"
```

或者直接通过插件市场 UI 上传文件。无论哪种方式，安装、更新、启用/禁用与卸载的 endpoint 都见 [插件市场](/zh/plugins/marketplace)。

## 下一步

- [SDK 与 CLI](/zh/plugins/sdk-cli)——`validate`、`build` 与 `install` 参考。
- [Worker（JSON-RPC）](/zh/plugins/worker)——`worker.jar` 里装了什么。
- [插件市场](/zh/plugins/marketplace)——安装你构建的 `.fyp`。
