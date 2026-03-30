package plugin.swisskit.hpl.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;


/**
 * Period data for required learning units (group and self learning).
 *
 * @since 2026-03-19
 */
@Data
public class PeriodDataRU {
    @JSONField(name = "requiredvalue1")
    private Float groupLearningGoal;
    @JSONField(name = "totalnum1")
    private Float groupLearningTotal;
    @JSONField(name = "ispass1")
    private String groupLearningPassed;
    @JSONField(name = "requiredvalue2")
    private Float selfLearningGoal;
    @JSONField(name = "totalnum2")
    private Float selfLearningTotal;
    @JSONField(name = "ispass2")
    private String selfLearningPassed;


}
