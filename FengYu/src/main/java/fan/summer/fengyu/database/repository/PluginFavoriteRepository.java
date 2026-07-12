package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.database.entity.PluginFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginFavoriteRepository extends JpaRepository<PluginFavoriteEntity, Integer> {
    Optional<PluginFavoriteEntity> findByUserIdAndPluginId(Long userId, String pluginId);
    List<PluginFavoriteEntity> findAllByUserId(Long userId);
    void deleteByUserIdAndPluginId(Long userId, String pluginId);
}
