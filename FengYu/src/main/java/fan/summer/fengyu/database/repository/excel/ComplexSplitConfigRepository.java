package fan.summer.fengyu.database.repository.excel;

import fan.summer.fengyu.database.entity.excel.ComplexSplitConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplexSplitConfigRepository extends JpaRepository<ComplexSplitConfigEntity, Long> {
    List<ComplexSplitConfigEntity> findAllByUserIdAndTaskId(Long userId, String taskId);
    void deleteByUserIdAndTaskId(Long userId, String taskId);
}
