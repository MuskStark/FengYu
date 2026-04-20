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

    public static List<Object> scan() {
        List<Object> pages = new ArrayList<>();
        pages.addAll(scanBuiltinPages());
        pages.addAll(PluginLoader.loadFromPluginDir());

        // Load saved menu orders from DB
        loadSavedMenuOrders();

        // Sort: first by saved user order (ascending), then by annotation order, then by class name (stable)
        pages.sort(Comparator
                .comparingInt((Object p) -> {
                    // TODO:增加使用插件名称进行比对
                    Integer saved = savedMenuOrders.get(p.getClass().getName());
                    return saved != null ? saved : Integer.MAX_VALUE;
                })
                .thenComparingInt(p -> getAnnotationOrder(p))
                .thenComparing(p -> p.getClass().getName())
        );

        logger.info("Total KitPage(s) loaded: {}", pages.size());
        return pages;
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
