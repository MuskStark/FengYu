package fan.summer.zhiflow.plugin;

import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.database.entity.PluginFavoriteEntity;
import fan.summer.zhiflow.database.mapper.PluginFavoriteMapper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Manages the plugin favorites (bookmarks) lifecycle.
 *
 * <p>Maintains an in-memory {@link ObservableSet} of favorited plugin IDs,
 * loaded from the {@code plugin_favorites} H2 table at startup. All mutations
 * are persisted immediately to the database. UI components can observe the set
 * or register listeners via {@link #setOnFavoritesChanged} for reactive updates.</p>
 *
 * @since 3.0.0
 */
public class FavoriteService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteService.class);

    private static volatile FavoriteService INSTANCE;

    private final ObservableSet<String> favorites = FXCollections.observableSet();
    private volatile Consumer<String> onFavoritesChanged;

    /**
     * Constructs a FavoriteService and loads all existing favorites from the database.
     */
    public FavoriteService() {
        loadFromDb();
        INSTANCE = this;
        log.info("FavoriteService initialized with {} favorite(s)", favorites.size());
    }

    /**
     * Returns the singleton instance.
     *
     * @return the FavoriteService instance, or {@code null} if not yet created
     */
    public static FavoriteService getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the observable set of favorited plugin IDs.
     *
     * @return the live set; never {@code null}
     */
    public ObservableSet<String> getFavorites() {
        return favorites;
    }

    /**
     * Sets the callback invoked when favorites are added or removed.
     * The callback receives the affected plugin ID.
     *
     * @param handler a consumer that receives the changed plugin ID; may be {@code null}
     */
    public void setOnFavoritesChanged(Consumer<String> handler) {
        this.onFavoritesChanged = handler;
    }

    /**
     * Returns whether the given plugin is favorited.
     *
     * @param pluginId the plugin ID to check
     * @return {@code true} if the plugin is in the favorites set
     */
    public boolean isFavorite(String pluginId) {
        return favorites.contains(pluginId);
    }

    /**
     * Returns the total number of favorited plugins.
     *
     * @return the count of favorites
     */
    public int count() {
        return favorites.size();
    }

    /**
     * Toggles the favorite state for a plugin.
     * If currently favorited, it is removed; otherwise it is added.
     *
     * @param pluginId the plugin ID to toggle
     * @return {@code true} if the plugin is now favorited, {@code false} if unfavorited
     */
    public boolean toggle(String pluginId) {
        if (favorites.contains(pluginId)) {
            remove(pluginId);
            return false;
        } else {
            add(pluginId);
            return true;
        }
    }

    /**
     * Adds a plugin to favorites and persists the change to the database.
     * This method is synchronized to prevent concurrent duplicate inserts.
     *
     * @param pluginId the plugin ID to favorite
     */
    public synchronized void add(String pluginId) {
        if (favorites.contains(pluginId)) return;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginFavoriteMapper mapper = session.getMapper(PluginFavoriteMapper.class);
            PluginFavoriteEntity entity = new PluginFavoriteEntity();
            entity.setPluginId(pluginId);
            mapper.insert(entity);
            session.commit();
            favorites.add(pluginId);
            log.info("Added favorite: pluginId={}", pluginId);
            fireChanged(pluginId);
        } catch (Exception e) {
            log.error("Failed to add favorite: pluginId={}", pluginId, e);
        }
    }

    /**
     * Removes a plugin from favorites and persists the change to the database.
     * This method is synchronized to prevent concurrent modification.
     *
     * @param pluginId the plugin ID to unfavorite
     */
    public synchronized void remove(String pluginId) {
        if (!favorites.contains(pluginId)) return;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginFavoriteMapper mapper = session.getMapper(PluginFavoriteMapper.class);
            mapper.deleteByPluginId(pluginId);
            session.commit();
            favorites.remove(pluginId);
            log.info("Removed favorite: pluginId={}", pluginId);
            fireChanged(pluginId);
        } catch (Exception e) {
            log.error("Failed to remove favorite: pluginId={}", pluginId, e);
        }
    }

    // ── Internal ──────────────────────────────────────────────

    private void loadFromDb() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginFavoriteMapper mapper = session.getMapper(PluginFavoriteMapper.class);
            List<PluginFavoriteEntity> rows = mapper.selectAll();
            for (PluginFavoriteEntity e : rows) {
                favorites.add(e.getPluginId());
            }
        } catch (Exception e) {
            log.error("Failed to load favorites from database", e);
        }
    }

    private void fireChanged(String pluginId) {
        if (onFavoritesChanged != null) {
            Platform.runLater(() -> onFavoritesChanged.accept(pluginId));
        }
    }
}
