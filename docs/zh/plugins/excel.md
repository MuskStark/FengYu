# Excel I/O

## 技术栈

使用 `org.apache.fesod:fesod-sheet` 库进行高性能 Excel 读写。

---

## DTO 定义

用 `@ExcelProperty` 注解标记字段对应的列索引：

```java
package {{base-package}}.excel.dto;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

@Data
public class {{Name}}Dto {
    @ExcelProperty(index = 0)
    private String field1;
    @ExcelProperty(index = 1)
    private String field2;
}
```

---

## ReadListener 实现

实现 `ReadListener<{{Name}}Dto>` 接口，批量处理数据：

```java
package {{base-package}}.excel.listener;

import {{base-package}}.database.DatabaseInit;
import {{base-package}}.database.entity.{{Name}}Entity;
import {{base-package}}.database.mapper.{{Name}}Mapper;
import {{base-package}}.excel.dto.{{Name}}Dto;
import org.apache.fesod.common.util.ListUtils;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.apache.ibatis.session.SqlSession;

import java.util.ArrayList;
import java.util.List;

public class {{Name}}Listener implements ReadListener<{{Name}}Dto> {

    private static final int BATCH_COUNT = 1000;
    private List<{{Name}}Dto> batch = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);

    @Override
    public void invoke({{Name}}Dto data, AnalysisContext context) {
        batch.add(data);
        if (batch.size() >= BATCH_COUNT) {
            saveData();
            batch = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        saveData();
    }

    private void saveData() {
        if (batch.isEmpty()) return;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            {{Name}}Mapper mapper = session.getMapper({{Name}}Mapper.class);
            List<{{Name}}Entity> entities = new ArrayList<>();
            batch.forEach(dto -> {
                {{Name}}Entity entity = new {{Name}}Entity();
                // Map dto fields to entity
                entities.add(entity);
            });
            mapper.batchInsert(entities);
            session.commit();
        }
    }
}
```

---

## 读取文件

```java
// 指定编码（GBK 编码文件）
FesodSheet.read(filePath, {{Name}}Dto.class, new {{Name}}Listener())
    .charset(Charset.forName("GBK"))
    .sheet().doRead();

// 默认 UTF-8
FesodSheet.read(filePath, {{Name}}Dto.class, new {{Name}}Listener())
    .sheet().doRead();

// 从 InputStream 读取（如从 ZIP 解压）
FesodSheet.read(inputStream, {{Name}}Dto.class, new {{Name}}Listener())
    .charset(Charset.forName("GBK"))
    .sheet().doRead();
```

---

## 写入文件

```java
// 构建表头：List<List<String>>
List<List<String>> head = List.of(
    List.of("Field1", "Field2")
);

// 构建数据行：List<List<Object>>
List<List<Object>> rows = new ArrayList<>();
for ({{Name}}Entity entity : data) {
    rows.add(List.of(entity.getField1(), entity.getField2()));
}

// 写入
FesodSheet.write(outputFile)
    .sheet(sheetName)
    .head(head)
    .doWrite(rows);
```

---

## 与 Background Task 结合

通常将读取操作放在 `Task` 中异步执行：

```java
{{Name}}UploadWorker worker = new {{Name}}UploadWorker(filePath);
worker.setOnSucceeded(ev -> { /* show success alert */ });
worker.setOnFailed(ev -> { /* show error with worker.getException() */ });
new Thread(worker).start();
```

详见 [Background Tasks](./background-tasks.md)