package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.entity.AppSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppSettingRepository extends JpaRepository<AppSettingEntity, Integer> {
    Optional<AppSettingEntity> findByUserIdAndSettingKey(Long userId, String key);
    List<AppSettingEntity> findAllByUserId(Long userId);
}
