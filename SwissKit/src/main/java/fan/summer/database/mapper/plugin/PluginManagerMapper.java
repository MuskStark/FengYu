package fan.summer.database.mapper.plugin;

import fan.summer.database.entity.plugin.PluginManagerEntity;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface PluginManagerMapper {

    List<PluginManagerEntity> selectAll();

    List<PluginManagerEntity> selectEnabled();

    List<PluginManagerEntity> selectDisabled();

    PluginManagerEntity selectByJarName(@Param("jarName") String jarName);

    PluginManagerEntity selectByPluginName(@Param("pluginName") String pluginName);

    void insert(PluginManagerEntity entity);

    void updateDisabled(@Param("jarName") String jarName, @Param("isDisabled") Integer isDisabled);

    void updateVersion(@Param("jarName") String jarName, @Param("version") String version);

    void updateLastCheck(@Param("jarName") String jarName);

    void updateUpdateUrl(@Param("jarName") String jarName, @Param("updateUrl") String updateUrl);

    void deleteByJarName(@Param("jarName") String jarName);

    void deleteByPluginName(@Param("pluginName") String pluginName);
}