package fan.summer.scaner;


import fan.summer.annoattion.SwissKitPage;
import fan.summer.api.KitPage;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.MenuOrderEntity;
import fan.summer.database.mapper.MenuOrderMapper;
import fan.summer.plugin.PluginLoader;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * KitPage scanner that uses Java SPI to discover and register all KitPage implementations
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/2
 */
public class SwissKitPageScaner {
    private static final Logger logger = LoggerFactory.getLogger(SwissKitPageScaner.class);

    /** Cached saved menu orders: className -> menuOrder */
    private static final Map<String, Integer> savedMenuOrders = new ConcurrentHashMap<>();

    public static List<KitPage> scan() {
        List<KitPage> pages = new ArrayList<>();
        pages.addAll(scanBuiltinPages());
        pages.addAll(PluginLoader.loadFromPluginDir());

        // Load saved menu orders from DB
        loadSavedMenuOrders();

        // Sort: first by saved user order (ascending), then by annotation order, then by class name (stable)
        pages.sort(Comparator
                .comparingInt((KitPage p) -> {
                    Integer saved = savedMenuOrders.get(p.getClass().getName());
                    return saved != null ? saved : Integer.MAX_VALUE;
                })
                .thenComparingInt(p -> p.getClass().getAnnotation(SwissKitPage.class).order())
                .thenComparing(p -> p.getClass().getName())
        );

        logger.info("Total KitPage(s) loaded: {}", pages.size());
        return pages;
    }

    private static void loadSavedMenuOrders() {
        savedMenuOrders.clear();
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            MenuOrderMapper mapper = session.getMapper(MenuOrderMapper.class);
            List<MenuOrderEntity> orders = mapper.selectAll();
            for (MenuOrderEntity entity : orders) {
                savedMenuOrders.put(entity.getPageClass(), entity.getMenuOrder());
            }
            logger.info("Loaded {} saved menu order(s)", orders.size());
        } catch (Exception e) {
            logger.warn("Failed to load saved menu orders, using defaults: {}", e.getMessage());
        }
    }

    /**
     * Saves the current page order to the database.
     *
     * @param pages ordered list of pages
     */
    public static void saveMenuOrder(List<KitPage> pages) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            MenuOrderMapper mapper = session.getMapper(MenuOrderMapper.class);
            mapper.deleteAll();
            List<MenuOrderEntity> entities = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                MenuOrderEntity entity = new MenuOrderEntity();
                entity.setPageClass(pages.get(i).getClass().getName());
                entity.setMenuOrder(i);
                entities.add(entity);
            }
            mapper.insertBatch(entities);
            session.commit();

            // Update cache
            savedMenuOrders.clear();
            for (int i = 0; i < pages.size(); i++) {
                savedMenuOrders.put(pages.get(i).getClass().getName(), i);
            }
            logger.info("Saved menu order for {} page(s)", pages.size());
        } catch (Exception e) {
            logger.error("Failed to save menu order", e);
        }
    }

    /**
     * Clears the cached menu orders, forcing a reload on next scan.
     */
    public static void clearCache() {
        savedMenuOrders.clear();
    }

    /**
     * Applies saved menu order to a list of pages (e.g., after plugin install/uninstall).
     * Pages not in saved order are sorted by annotation order, then class name.
     *
     * @param pages list of pages to sort
     */
    public static void applySavedOrder(List<KitPage> pages) {
        loadSavedMenuOrders();
        pages.sort(Comparator
                .comparingInt((KitPage p) -> {
                    Integer saved = savedMenuOrders.get(p.getClass().getName());
                    return saved != null ? saved : Integer.MAX_VALUE;
                })
                .thenComparingInt(p -> p.getClass().getAnnotation(SwissKitPage.class).order())
                .thenComparing(p -> p.getClass().getName())
        );
    }

    /**
     * Scans and returns only built-in KitPages (loaded by the application ClassLoader).
     * Does not include plugin pages from external JARs.
     *
     * @return List of built-in KitPages
     */
    public static List<KitPage> scanBuiltinPages() {
        List<KitPage> pages = new ArrayList<>();

        ServiceLoader<KitPage> loader = ServiceLoader.load(
                KitPage.class,
                Thread.currentThread().getContextClassLoader()
        );

        for (KitPage page : loader) {
            SwissKitPage annotation = page.getClass().getAnnotation(SwissKitPage.class);

            if (annotation == null) {
                logger.debug("Skipped (no annotation): {}", page.getClass().getName());
                continue;
            }

            if (!annotation.visible()) {
                logger.debug("Skipped (invisible): {}", page.getClass().getName());
                continue;
            }

            pages.add(page);
            logger.info("Registered KitPage: {} (order: {})",
                    annotation.menuName(), annotation.order());
        }

        return pages;
    }
}
