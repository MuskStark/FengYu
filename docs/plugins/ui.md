# UI Development

## 基础结构

```java
package {{base-package}}.ui;

import fan.summer.fengyu.api.i18n.I18n;
import fan.summer.fengyu.api.theme.Themes;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

public class {{Name}}PluginUi {

    private GridPane rootPanel;
    private Label exampleLabel = new Label();
    private Button exampleButton = new Button();

    public {{Name}}PluginUi() {
        initComponents();
    }

    private void initComponents() {
        rootPanel = new GridPane();
        rootPanel.setHgap(10);
        rootPanel.setVgap(5);
        rootPanel.setPadding(new Insets(0));

        ColumnConstraints col0 = new ColumnConstraints();
        col0.setHgrow(Priority.NEVER);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        rootPanel.getColumnConstraints().addAll(col0, col1);

        rootPanel.add(exampleLabel, 0, 0);
        rootPanel.add(exampleButton, 1, 0);

        // 绑定 i18n key 到属性 — 语言切换时自动更新
        String p = "plugin.{{slug}}.";
        I18n.bind(exampleLabel.textProperty(), p + "exampleLabel");
        I18n.bind(exampleButton.textProperty(), p + "exampleButton");
    }

    public Node getView() {
        return rootPanel;
    }
}
```

---

## i18n 绑定模式

| 模式 | 使用场景 | 示例 |
|------|----------|------|
| `I18n.bind(property, key)` | 静态标签、按钮 — 语言切换自动更新 | `I18n.bind(label.textProperty(), "plugin.xxx.title")` |
| `I18n.get(key)` | 动态文本（状态、格式化消息） | `statusLabel.setText(I18n.get("plugin.xxx.idle"))` |
| `I18n.addListener(runnable)` | 自定义刷新逻辑 | `I18n.addListener(this::refreshStatus)` |

---

## Themes

### 嵌入宿主 Scene 的节点

自动继承 `fengyu-common.css` 中的样式，无需额外操作。

### 独立窗口（Alert、自定义 Stage）

独立窗口的 Scene **不** 继承宿主样式表，必须手动应用：

```java
private void showAlert(Alert.AlertType type, String message) {
    Alert alert = new Alert(type);
    alert.setHeaderText(null);
    alert.setContentText(message);
    // Dialog 创建自己的 Scene，通过 sceneProperty 监听器应用主题
    alert.getDialogPane().sceneProperty().addListener((obs, old, scene) -> {
        if (scene != null) Themes.applyTo(scene);
    });
    alert.showAndWait();
}
```

---

## JavaFX 布局陷阱

### ScrollPane 在 StackPane 中

设置 `maxWidth` 和 `maxHeight` 为 `Double.MAX_VALUE`，否则无法填满父容器：

```java
sp.setMaxWidth(Double.MAX_VALUE);
sp.setMaxHeight(Double.MAX_VALUE);
```

### 填满 HBox/VBox 剩余空间

```java
node.setMaxWidth(Double.MAX_VALUE);
HBox.setHgrow(node, Priority.ALWAYS);  // 或 VBox.setVgrow
```

**禁止** 使用 `setPrefWidth(Double.MAX_VALUE)`，这会导致整个页面布局崩溃。

### StackPane 切换页面

切换子节点时，同时切换 `setVisible` 和 `setManaged`：

```java
for (int j = 0; j < pages.length; j++) {
    pages[j].setVisible(j == idx);
    pages[j].setManaged(j == idx);
}
```

更多陷阱 → [Common Pitfalls](./pitfalls.md)

---

## StepWizard 多步骤向导

宿主提供了可复用的 `StepWizard` 组件：

```java
StepWizard wizard = new StepWizard();
wizard.addStep("Select file",  step1Node, () -> filePath != null);
wizard.addStep("Split mode",   step2Node, () -> modeSelected);
wizard.addStep("Output path", step3Node, () -> outputPath != null);
wizard.build();  // 所有 addStep 之后调用

wizard.setOnStepChanged((from, to, total) -> {
    if (from == 0 && to == 1) startAnalysis();
});

wizard.goTo(2);
boolean last = wizard.isLastStep();
```

详细用法 → `fan.summer.fengyu.api.component.StepWizard`