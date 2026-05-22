# Background Tasks

## JavaFX Task

继承 ` javafx.concurrent.Task<Void>` 实现异步任务：

```java
package {{base-package}}.worker;

import {{base-package}}.database.DatabaseInit;
import {{base-package}}.excel.dto.{{Name}}Dto;
import {{base-package}}.excel.listener.{{Name}}Listener;
import javafx.concurrent.Task;
import org.apache.fesod.sheet.FesodSheet;

public class {{Name}}UploadWorker extends Task<Void> {

    private final String filePath;

    public {{Name}}UploadWorker(String filePath) {
        this.filePath = filePath;
    }

    @Override
    protected Void call() throws Exception {
        DatabaseInit.init();
        FesodSheet.read(filePath, {{Name}}Dto.class, new {{Name}}Listener())
                .sheet().doRead();
        return null;
    }
}
```

## 在 UI 中使用

```java
{{Name}}UploadWorker worker = new {{Name}}UploadWorker(filePath);

worker.setOnSucceeded(ev -> {
    showAlert(Alert.AlertType.INFORMATION, I18n.get("plugin.xxx.success"));
});

worker.setOnFailed(ev -> {
    Throwable ex = worker.getException();
    showAlert(Alert.AlertType.ERROR, I18n.get("plugin.xxx.error") + ": " + ex.getMessage());
});

new Thread(worker).start();
```

## 进度更新

如果需要进度条：

```java
public class {{Name}}UploadWorker extends Task<Void> {

    @Override
    protected Void call() throws Exception {
        updateProgress(0, 100);
        // ... work ...
        updateProgress(50, 100);
        // ... more work ...
        updateProgress(100, 100);
        return null;
    }
}
```

UI 端绑定进度：

```java
progressBar.progressProperty().bind(worker.progressProperty());
```

## 取消操作

```java
Button cancelBtn = new Button("Cancel");
cancelBtn.setOnAction(ev -> worker.cancel());
```