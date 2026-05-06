package fan.summer.plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Isolated plugin ClassLoader with break-parent-delegation strategy.
 *
 * <p>Loading strategy:</p>
 * <pre>
 * Class name starts with fan.summer.?
 *   YES → delegate to parent (main app) ClassLoader   ← annotations/UI components share same Class object
 *   NO  → first search in plugin JAR itself          ← plugin implementation classes isolated
 *         if not found, then delegate to parent ClassLoader
 * </pre>
 */
public class IsolatedPluginClassLoader extends URLClassLoader {

    private final ClassLoader appClassLoader;

    public IsolatedPluginClassLoader(URL[] urls, ClassLoader appClassLoader) {
        super(urls, null);
        this.appClassLoader = appClassLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> cached = findLoadedClass(name);
            if (cached != null) {
                if (resolve) resolveClass(cached);
                return cached;
            }

            // fan.summer.* → delegate to main app ClassLoader
            if (name.startsWith("fan.summer.")) {
                return appClassLoader.loadClass(name);
            }

            // JDK core classes → delegate to app ClassLoader
            if (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("sun.") || name.startsWith("com.sun.")) {
                return appClassLoader.loadClass(name);
            }

            // Try appClassLoader first
            try {
                return appClassLoader.loadClass(name);
            } catch (ClassNotFoundException e) {
                // Not in appClassLoader, try plugin JAR
            }

            // Plugin's own classes and third-party libraries → load from plugin JAR
            try {
                Class<?> c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException e) {
                return appClassLoader.loadClass(name);
            }
        }
    }
}
