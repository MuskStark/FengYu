package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.database.entity.store.StoreSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreSourceRepository extends JpaRepository<StoreSourceEntity, Integer> {
    Optional<StoreSourceEntity> findByOrigin(String origin);
    List<StoreSourceEntity> findAllByUserId(Long userId);
    boolean existsByOrigin(String origin);
    void deleteByOrigin(String origin);
}
