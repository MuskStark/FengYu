package fan.summer.scaner;


import fan.summer.annoattion.SwissKitPage;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.MenuOrderEntity;
import fan.summer.database.mapper.MenuOrderMapper;
import fan.summer.kitpage.email.EmailKitPage;
import fan.summer.kitpage.excel.ExcelKitPage;
import fan.summer.kitpage.setting.SettingKitPage;
import fan.summer.kitpage.welcome.WelcomePage;
import fan.summer.plugin.PluginLoader;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KitPage scanner that discovers and registers all KitPage implementations
 * using the @SwissKitPage annotation.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/2
 */
public class SwissKitPageScaner {
    private static final Logger logger = LoggerFactory.getLogger(SwissKitPageScaner.class);

    /**
     * Cached saved menu orders: className -> menuOrder
     */
    private static final Map<String, Integer> savedMenuOrders = new ConcurrentHashMap<>();

    /**
     * Built-in page classes
     */
    private static final List<Class<?>> BUILTIN_PAGE_CLASSES = Arrays.asList(
            WelcomePage.class,
            ExcelKitPage.class,
            EmailKitPage.class,
            SettingKitPage.class
    );

    /**
     * Holds scanned pages separated by category.
     */
    public static class ScannedPages {
        private final List<Object> builtinPages;
        private final List<Object> pluginPages;

        public ScannedPages(List<Object> builtinPages, List<Object> pluginPages) {
            this.builtinPages = new ArrayList<>(builtinPages);
            this.pluginPages = new ArrayList<>(pluginPages);
        }

        public List<Object> builtinPages() {
            return new ArrayList<>(builtinPages);
        }

        public List<Object> pluginPages() {
            return new ArrayList<>(pluginPages);
        }

        /**
         * Returns total number of pages across both categories.
         */
        public int totalCount() {
            return builtinPages.size() + pluginPages.size();
        }

        /**
         * Returns all pages in order (builtin first, then plugins).
         */
        public List<Object> toFlatList() {
            List<Object> all = new ArrayList<>(builtinPages);
            all.addAll(pluginPages);
            return all;
        }
    }

    public static ScannedPages scan() {
        List<Object> builtinPages = scanBuiltinPages();
        List<Object> pluginPages = PluginLoader.loadFromPluginDir();

        // Load saved menu orders from DB
        loadSavedMenuOrders();

        // Sort each category independently: saved user order → annotation order → class name
        sortPages(builtinPages);
        sortPages(pluginPages);

        logger.info("Scanned KitPages - builtin: {}, plugin: {}", builtinPages.size(), pluginPages.size());
        return new ScannedPages(builtinPages, pluginPages);
    }

    private static void sortPages(List<Object> pages) {
        pages.sort(Comparator
                .comparingInt((Object p) -> {
                    Integer saved = savedMenuOrders.get(p.getClass().getName());
                    return saved != null ? saved : Integer.MAX_VALUE;
                })
                .thenComparingInt(p -> getAnnotationOrder(p))
                .thenComparing(p -> p.getClass().getName())
        );
    }

    private static int getAnnotationOrder(Object page) {
        SwissKitPage annotation = page.getClass().getAnnotation(SwissKitPage.class);
        return annotation != null ? annotation.order() : Integer.MAX_VALUE;
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
    public static void saveMenuOrder(List<Object> pages) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            MenuOrderMapper mapper = session.getMapper(MenuOrderMapper.class);
            mapper.deleteAll();
            List<MenuOrderEntity> entities = new ArrayList<>();
            // TODO:增加使用插件名称
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
     * Refreshes the plugin pages after a plugin install/uninstall/reload.
     * This should be called by PluginService after making changes to plugins.
     */
    public static void refreshPluginPages() {
        clearCache();
        logger.info("Plugin pages cache cleared for refresh");
    }

    /**
     * Applies saved menu order to a list of pages (e.g., after plugin install/uninstall).
     * Pages not in saved order are sorted by annotation order, then class name.
     *
     * @param pages list of pages to sort
     */
    public static void applySavedOrder(List<Object> pages) {
        loadSavedMenuOrders();
        pages.sort(Comparator
                .comparingInt((Object p) -> {
                    Integer saved = savedMenuOrders.get(p.getClass().getName());
                    return saved != null ? saved : Integer.MAX_VALUE;
                })
                .thenComparingInt(p -> getAnnotationOrder(p))
                .thenComparing(p -> p.getClass().getName())
        );
    }

    /**
     * Applies saved menu order to a ScannedPages instance.
     * Each category is sorted independently.
     *
     * @param scannedPages the scanned pages to sort
     */
    public static void applySavedOrder(ScannedPages scannedPages) {
        loadSavedMenuOrders();
        sortPages(scannedPages.builtinPages());
        sortPages(scannedPages.pluginPages());
    }

    /**
     * Scans and returns only built-in KitPages.
     * Does not include plugin pages from external JARs.
     *
     * @return List of built-in page instances
     */
    public static List<Object> scanBuiltinPages() {
        List<Object> pages = new ArrayList<>();

        for (Class<?> clazz : BUILTIN_PAGE_CLASSES) {
            SwissKitPage annotation = clazz.getAnnotation(SwissKitPage.class);

            if (annotation == null) {
                logger.debug("Skipped (no annotation): {}", clazz.getName());
                continue;
            }

            if (!annotation.visible()) {
                logger.debug("Skipped (invisible): {}", clazz.getName());
                continue;
            }

            try {
                Object page = clazz.getDeclaredConstructor().newInstance();
                pages.add(page);
                logger.info("Registered KitPage: {} (order: {})",
                        annotation.menuName(), annotation.order());
            } catch (Exception e) {
                logger.error("Failed to instantiate page: {}", clazz.getName(), e);
            }
        }

        return pages;
    }

    /**
     * Gets the menu name for a page from its @SwissKitPage annotation.
     *
     * @param page the page instance
     * @return menu name
     */
    public static String getMenuName(Object page) {
        SwissKitPage annotation = page.getClass().getAnnotation(SwissKitPage.class);
        if (annotation != null && !annotation.menuName().isEmpty()) {
            return annotation.menuName();
        }
        return page.getClass().getSimpleName();
    }

    /**
     * Gets the plugin name for a page from its @SwissKitPage annotation.
     *
     * @param page the page instance
     * @return plugin name or null if built-in page
     */
    public static String getPluginName(Object page) {
        SwissKitPage annotation = page.getClass().getAnnotation(SwissKitPage.class);
        if (annotation != null) {
            String pluginName = annotation.pluginName();
            // Built-in pages have empty pluginName, external plugins have non-empty
            return pluginName.isEmpty() ? null : pluginName;
        }
        return null;
    }

    /**
     * Gets the plugin version for a page from its @SwissKitPage annotation.
     *
     * @param page the page instance
     * @return plugin version or null if built-in page
     */
    public static String getPluginVersion(Object page) {
        SwissKitPage annotation = page.getClass().getAnnotation(SwissKitPage.class);
        if (annotation != null) {
            String pluginVersion = annotation.pluginVersion();
            return pluginVersion.isEmpty() ? null : pluginVersion;
        }
        return null;
    }

    /**
     * Gets the menu icon path from @SwissKitPage annotation.
     *
     * @param page the page instance
     * @return icon path or null
     */
    public static String getMenuIconPath(Object page) {
        SwissKitPage annotation = page.getClass().getAnnotation(SwissKitPage.class);
        return annotation != null ? annotation.iconPath() : null;
    }

    /**
     * Gets the menu tooltip for a page from its @SwissKitPage annotation.
     *
     * @param page the page instance
     * @return menu tooltip or null
     */
    public static String getMenuTooltip(Object page) {
        SwissKitPage annotation = page.getClass().getAnnotation(SwissKitPage.class);
        return annotation != null ? annotation.menuTooltip() : null;
    }

    /**
     * Gets the panel from a page using reflection.
     *
     * @param page the page instance
     * @return the JPanel
     */
    public static JPanel getPanel(Object page) {
        try {
            Method method = page.getClass().getMethod("getPanel");
            return (JPanel) method.invoke(page);
        } catch (Exception e) {
            logger.error("Failed to get panel from page: {}", page.getClass().getName(), e);
            return new JPanel();
        }
    }
}
