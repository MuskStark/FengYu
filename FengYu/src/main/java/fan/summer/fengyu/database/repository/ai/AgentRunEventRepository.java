package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.AgentRunEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunEventRepository extends JpaRepository<AgentRunEventEntity, Long> {
    List<AgentRunEventEntity> findByRunIdOrderBySeqAsc(String runId);
    Optional<AgentRunEventEntity> findTopByRunIdOrderBySeqDesc(String runId);
}
