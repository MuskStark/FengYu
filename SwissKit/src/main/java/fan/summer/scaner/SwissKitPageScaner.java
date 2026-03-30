package fan.summer.scaner;


import fan.summer.annoattion.SwissKitPage;
import fan.summer.api.KitPage;
import fan.summer.plugin.PluginLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * KitPage scanner that uses Java SPI to discover and register all KitPage implementations
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/2
 */
public class SwissKitPageScaner {
    private static final Logger logger = LoggerFactory.getLogger(SwissKitPageScaner.class);

    public static List<KitPage> scan() {
        List<KitPage> pages = new ArrayList<>();
        pages.addAll(scanBuiltinPages());
        pages.addAll(PluginLoader.loadFromPluginDir());

        pages.sort(Comparator.comparingInt(
                p -> p.getClass().getAnnotation(SwissKitPage.class).order()
        ));

        logger.info("Total KitPage(s) loaded: {}", pages.size());
        return pages;
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
