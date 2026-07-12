package fan.summer.fengyu.database.entity.excel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entity representing a complex Excel split configuration. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "complex_split_config")
@Data
public class ComplexSplitConfigEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false) private String taskId;
    @Column(name = "field_name", nullable = false) private String fieldName;
    @Column(name = "sheet_name", nullable = false) private String sheetName;
    @Column(name = "header_index", nullable = false) private Integer headerIndex;
    @Column(name = "column_index", nullable = false) private Integer columnIndex;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
