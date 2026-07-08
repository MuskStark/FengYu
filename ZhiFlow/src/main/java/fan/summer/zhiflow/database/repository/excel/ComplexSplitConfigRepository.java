package fan.summer.zhiflow.database.repository.excel;

import fan.summer.zhiflow.database.entity.excel.ComplexSplitConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplexSplitConfigRepository extends JpaRepository<ComplexSplitConfigEntity, Long> {
    List<ComplexSplitConfigEntity> findAllByUserIdAndTaskId(Long userId, String taskId);
    void deleteByUserIdAndTaskId(Long userId, String taskId);
}
