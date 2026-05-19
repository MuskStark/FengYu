package fan.summer.database.mapper.excel;

import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import java.util.List;

public interface ComplexSplitConfigMapper {
    void insert(ComplexSplitConfigEntity entity);
    void update(ComplexSplitConfigEntity entity);
    void deleteAllByTaskId(String taskId);
    List<ComplexSplitConfigEntity> selectAllByTaskId(String taskId);
}
