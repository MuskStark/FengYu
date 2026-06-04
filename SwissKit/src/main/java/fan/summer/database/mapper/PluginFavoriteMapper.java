package fan.summer.database.mapper;

import fan.summer.database.entity.PluginFavoriteEntity;

import java.util.List;

/**
 * MyBatis mapper for {@link PluginFavoriteEntity} persistence operations.
 *
 * <p>Provides operations for managing tool favorites (bookmarks): adding,
 * removing, querying, and counting favorites by plugin ID.</p>
 *
 * @since 3.0.0
 * @see PluginFavoriteEntity
 */
public interface PluginFavoriteMapper {
    /**
     * Returns all favorited plugins, ordered by creation time.
     *
     * @return the list of favorite entities; may be empty
     */
    List<PluginFavoriteEntity> selectAll();

    /**
     * Finds a favorite entry by its plugin ID.
     *
     * @param pluginId the plugin ID to look up
     * @return the favorite entity, or {@code null} if not found
     */
    PluginFavoriteEntity selectByPluginId(String pluginId);

    /**
     * Inserts a new favorite entry.
     *
     * @param entity the favorite entity to insert
     */
    void insert(PluginFavoriteEntity entity);

    /**
     * Removes a favorite entry by its plugin ID.
     *
     * @param pluginId the plugin ID whose favorite to remove
     */
    void deleteByPluginId(String pluginId);

    /**
     * Returns the total number of favorited plugins.
     *
     * @return the count of favorites
     */
    int count();
}
