package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.AiMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Per-user cross-session memory entries. */
public interface AiMemoryRepository extends JpaRepository<AiMemoryEntity, String> {
    List<AiMemoryEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<AiMemoryEntity> findByIdAndUserId(String id, Long userId);
}
