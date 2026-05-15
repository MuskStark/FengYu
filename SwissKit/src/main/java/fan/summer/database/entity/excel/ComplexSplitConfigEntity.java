package fan.summer.database.entity.excel;

import lombok.Data;

@Data
public class ComplexSplitConfigEntity {
    private Long id;
    private String taskId;
    private String fieldName;
    private String sheetName;
    private Integer headerIndex;
    private Integer columnIndex;
}
