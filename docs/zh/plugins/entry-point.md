# Plugin Entry Point

## SwissKitJPlugin 接口

所有插件必须实现 `fan.summer.zhiflow.api.SwissKitJPlugin`：

```java
package {{base-package}};

import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.i18n.I18n;
import javafx.scene.Node;
import {{base-package}}.ui.{{Name}}PluginUi;

public class {{Name}}Plugin implements SwissKitJPlugin {

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
    public String getCategory() {
        return "OTHER";
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

## 接口方法说明

| 方法 | 必须 | 说明 |
|------|------|------|
| `getId()` | 是 | 反向域名唯一标识，如 `plugin.zhiflow.star` |
| `getName()` | 是 | 显示名称 |
| `getDescription()` | 是 | 短描述 |
| `getCategory()` | 是 | 分类：`text / image / net / dev / other` |
| `getVersion()` | 是 | 版本字符串 |
| `getMdiIcon()` | 是 | Material Design Icons 图标名，如 `file-excel` |
| `getIconStyle()` | 否 | `IconStyle` 枚举，默认 `IconStyle.BLUE` |
| `getType()` | 否 | 返回 `"builtin"` 表示内置工具 |
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