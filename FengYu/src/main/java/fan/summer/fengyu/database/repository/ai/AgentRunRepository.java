package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.AgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {
    List<AgentRunEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<AgentRunEntity> findByIdAndUserId(String id, Long userId);
    List<AgentRunEntity> findByStatusIn(Collection<String> statuses);
}
