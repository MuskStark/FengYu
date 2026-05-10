package fan.summer.ui.scale;

import fan.summer.api.KitPage;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScaleManager {
    private static final Logger logger = LoggerFactory.getLogger(ScaleManager.class);
    private static final ScaleManager INSTANCE = new ScaleManager();

    private final DoubleProperty globalScale = new SimpleDoubleProperty(1.0);
    private final Map<Class<?>, KitPage> registeredPages = new ConcurrentHashMap<>();

    private ScaleManager() {
    }

    public static ScaleManager getInstance() {
        return INSTANCE;
    }

    public DoubleProperty globalScaleProperty() {
        return globalScale;
    }

    public double getGlobalScale() {
        return globalScale.get();
    }

    public void setGlobalScale(double scale) {
        double clamped = Math.max(0.5, Math.min(2.0, scale));
        globalScale.set(clamped);
        applyScaleToAllPages(clamped);
    }

    public void registerPage(KitPage page) {
        if (page != null) {
            registeredPages.put(page.getClass(), page);
            page.setScale(globalScale.get());
        }
    }

    public void unregisterPage(KitPage page) {
        if (page != null) {
            registeredPages.remove(page.getClass());
        }
    }

    private void applyScaleToAllPages(double scale) {
        registeredPages.values().forEach(page -> {
            try {
                page.setScale(scale);
            } catch (Exception e) {
                logger.warn("Failed to apply scale to page {}: {}", page.getClass().getSimpleName(), e.getMessage());
            }
        });
    }

    public double scale(double baseValue) {
        return baseValue * globalScale.get();
    }

    public int scale(int baseValue) {
        return (int) Math.round(baseValue * globalScale.get());
    }
}