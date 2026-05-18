package fan.summer.database.entity.excel;

import lombok.Data;

/**
 * Excel complex split configuration entity.
 * Stores configuration for splitting Excel files by specific columns.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/3
 */
@Data
public class ComplexSplitConfigEntity {
    /** Unique identifier for the configuration */
    private Long id;

    /** Task ID to group related configurations */
    private String taskId;

    /** Excel file name being configured */
    private String fieldName;

    /** Sheet name within the Excel file */
    private String sheetName;

    /** Row index where headers are located (0-based) */
    private Integer headerIndex;

    /** Column index to split by (0-based) */
    private Integer columnIndex;
}
