package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.ConnectionProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectionProfileRepository extends JpaRepository<ConnectionProfileEntity, String> {
    List<ConnectionProfileEntity> findByUserIdOrderByNameAsc(Long userId);

    Optional<ConnectionProfileEntity> findByIdAndUserId(String id, Long userId);

    List<ConnectionProfileEntity> findByUserIdAndTypeOrderByNameAsc(Long userId, String type);

    Optional<ConnectionProfileEntity> findByUserIdAndName(Long userId, String name);
}
