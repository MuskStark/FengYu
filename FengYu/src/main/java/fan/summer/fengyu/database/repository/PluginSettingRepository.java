package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.database.entity.PluginSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginSettingRepository extends JpaRepository<PluginSettingEntity, Integer> {
    Optional<PluginSettingEntity> findByUserIdAndPluginIdAndSettingKey(Long userId, String pluginId, String key);
    List<PluginSettingEntity> findAllByUserIdAndPluginId(Long userId, String pluginId);
}
