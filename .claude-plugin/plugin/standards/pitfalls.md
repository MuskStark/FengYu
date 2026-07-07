# Common Pitfalls

## 1. SPI 文件路径错误

**问题**：`ServiceLoader` 找不到插件

**原因**：文件放在 `services/` 而非 `META-INF/services/`

**解决**：确认路径是 `src/main/resources/META-INF/services/fan.summer.api.SwissKitJPlugin`

---

## 2. Mapper XML namespace 不匹配

**问题**：`org.apache.ibatis.binding.BindingException`

**原因**：XML `<mapper namespace="...">` 与 Java 接口的完全限定名不一致

**解决**：确保两边完全一致，如 `fan.swisskitj.plugin.star.database.mapper.StarMapper`

---

## 3. SPI 文件内容错误

**问题**：部署后插件加载失败

**原因**：SPI 文件内容是旧的类名或部分类名

**解决**：确保内容是插件入口类的完全限定名，如 `fan.swisskitj.plugin.star.StarPlugin`

---

## 4. DevLauncher 包含 JavaFX 导入

**问题**：运行时报错 `java.lang.NoClassDefFoundError: javafx/application/Application`

**原因**：`DevLauncher` 导入了 JavaFX 类型，触发了模块系统检查

**解决**：`DevLauncher` 必须零 JavaFX 导入，所有 JavaFX 代码放在 `{{Name}}DevApp` 中

---

## 5. Scene 构造类型错误

**问题**：`{{Name}}PluginUi.getView()` 返回 `Node`，但 `Scene` 构造函数需要 `Parent`

**原因**：JavaFX `Scene` 构造函数签名 `Scene(Parent, ...)` 但 `getView()` 返回 `Node`

**解决**：用 `new Group(node)` 包装以满足 `Parent` 类型要求

---

## 6. Shade 插件未配置 ServicesResourceTransformer

**问题**：插件 JAR 中 SPI 文件被覆盖，其他依赖的 SPI 服务丢失

**解决**：配置 shade 插件的 `ServicesResourceTransformer`

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

---

## 7. H2 数据库路径格式错误

**问题**：Windows 上数据库无法创建或连接

**原因**：路径使用反斜杠 `\` 或 `user.home` 未正确解析

**解决**：始终用 `toString().replace("\\", "/")` 并使用正斜杠，路径基于 `user.dir` 而非 `user.home`

---

## 8. Dev profile mainClass 配置错误

**问题**：`mvn javafx:run -Pdev` 启动失败

**原因**：`javafx-maven-plugin` 的 `mainClass` 配置指向旧的类名

**解决**：确认配置为 `{{base-package}}.DevLauncher`

---

## 9. i18n Bundle 未注册

**问题**：`I18n.get()` 和 `I18n.bind()` 返回原始 key 而非翻译文本

**原因**：`I18n.registerPluginBundle()` 未在 `createView()` 中调用，或使用了系统 ClassLoader

**解决**：
```java
@Override
public Node createView() {
    I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());
    return new {{Name}}PluginUi().getView();
}
```

> **v3.2.0+:** 推荐改用 `host.i18n().registerBundle("i18n.messages")`(`PluginHost`
> 经 `init()` 注入)——它自动使用插件自己的 ClassLoader,从根上避免本坑。

---

## 10. Alert 对话框样式不匹配

**问题**：独立 Alert 窗口没有宿主的主题样式

**原因**：Alert 创建自己的 Scene，不继承宿主样式表

**解决**：通过 `sceneProperty` 监听器应用主题

```java
alert.getDialogPane().sceneProperty().addListener((obs, old, scene) -> {
    if (scene != null) Themes.applyTo(scene);
});
```

---

## 11. ScrollPane 无法填满父容器

**问题**：ScrollPane 只显示最小尺寸

**原因**：`Control` 子类默认 `maxWidth = USE_COMPUTED_SIZE`

**解决**：
```java
sp.setMaxWidth(Double.MAX_VALUE);
sp.setMaxHeight(Double.MAX_VALUE);
```

---

## 12. StackPane 切换页面后布局异常

**问题**：隐藏的页面仍然影响布局

**原因**：只设置了 `setVisible(false)`，但 `managed` 仍为 true

**解决**：同时切换可见性和 managed：
```java
pages[j].setVisible(j == idx);
pages[j].setManaged(j == idx);
```