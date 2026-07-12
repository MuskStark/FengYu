# Plugin Entry Point

## FengYuPlugin 接口

所有插件必须实现 `fan.summer.fengyu.api.FengYuPlugin`：

```java
package {{base-package}};

import fan.summer.fengyu.api.IconStyle;
import fan.summer.fengyu.api.FengYuPlugin;
import fan.summer.fengyu.api.ToolCategory;
import fan.summer.fengyu.api.i18n.I18n;
import javafx.scene.Node;
import {{base-package}}.ui.{{Name}}PluginUi;

public class {{Name}}Plugin implements FengYuPlugin {

    @Override
    public String getId() {
        return "{{plugin-id}}";
    }

    @Override
    public String getName() {
        return "{{display-name}}";
    }

    @Override
    public String getDescription() {
        return "{{description}}";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.OTHER;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getMdiIcon() {
        return "{{icon-name}}";
    }

    @Override
    public IconStyle getIconStyle() {
        return IconStyle.BLUE;
    }

    @Override
    public Node createView() {
        // 注册 i18n bundle，必须在 UI 创建前调用，使用插件自己的 ClassLoader
        I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());
        return new {{Name}}PluginUi().getView();
    }
}
```

## init(PluginHost)（v3.2.0+）

宿主在插件实例化后、进入注册表可见列表前，会在 JavaFX Application Thread 上
恰好调用一次 `init(PluginHost)`，注入宿主门面。保存引用即可在整个插件生命周期
内访问 `settings()` / `tasks()` / `i18n()` / `theme()` / `notifications()`：

```java
import fan.summer.fengyu.api.host.PluginHost;

private PluginHost host;

@Override
public void init(PluginHost host) {
    this.host = host;
}

@Override
public Node createView() {
    // 推荐：用 host.i18n().registerBundle(...)，无需再传 ClassLoader
    host.i18n().registerBundle("i18n.messages");
    return new {{Name}}PluginUi().getView();
}
```

`init()` 是可选的 `default` 方法，不实现也完全兼容；旧插件继续使用
`I18n.registerPluginBundle(...)` 静态入口即可。详见 [PluginHost](./plugin-host.md)。

## 接口方法说明

| 方法 | 必须 | 说明 |
|------|------|------|
| `getId()` | 是 | 反向域名唯一标识，如 `plugin.fengyu.star` |
| `getName()` | 是 | 显示名称 |
| `getDescription()` | 是 | 短描述 |
| `getCategory()` | 是 | 返回 `ToolCategory` 枚举：`DEV / TEXT / IMAGE / NET / OTHER` |
| `getVersion()` | 是 | 版本字符串 |
| `getMdiIcon()` | 是 | Material Design Icons 图标名，如 `file-excel` |
| `getIconStyle()` | 否 | `IconStyle` 枚举，默认 `IconStyle.BLUE` |
| `getType()` | 否 | 返回 `ToolType` 枚举：`PLUGIN`（默认，第三方插件）或 `BUILTIN`（内置工具） |
| `init(PluginHost)` | 否 | v3.2.0+；宿主注入 `PluginHost` 门面，见下节 |
| `createView()` | 是 | 返回 JavaFX `Node`，仅调用一次，结果会被缓存复用 |
| `onActivate()` | 否 | 插件被激活时调用 |
| `onDeactivate()` | 否 | 插件被停用时调用 |
| `onUnload()` | 否 | 插件卸载时调用 |

## IconStyle 可选值

`BLUE / PURPLE / TEAL / AMBER / RED / PINK / GRAY`（分别对应 CSS 类 `ic-blue` … `ic-gray`）

---

## i18n Bundle 注册

`createView()` 中 **必须** 调用 `I18n.registerPluginBundle()`：

```java
I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());
```

- 路径 `i18n.messages` 对应 `src/main/resources/i18n/messages.properties`
- 必须使用插件自己的 ClassLoader，不能用系统 ClassLoader
- 必须在 UI 创建之前调用

详细说明 → [i18n](./i18n.md)