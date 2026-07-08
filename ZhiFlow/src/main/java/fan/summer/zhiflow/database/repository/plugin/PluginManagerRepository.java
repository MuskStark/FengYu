package fan.summer.zhiflow.database.repository.plugin;

import fan.summer.zhiflow.database.entity.plugin.PluginManagerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PluginManagerRepository extends JpaRepository<PluginManagerEntity, Integer> {
    Optional<PluginManagerEntity> findByJarName(String jarName);
}
