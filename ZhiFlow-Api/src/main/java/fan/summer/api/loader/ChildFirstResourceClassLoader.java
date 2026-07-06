package fan.summer.api.loader;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A {@link URLClassLoader} that performs <strong>child-first resource</strong> lookup
 * while keeping <strong>parent-first class</strong> loading.
 *
 * <p>The host application ships root-level classpath resources (e.g.
 * {@code mybatis-config.xml}, {@code init.sql}, {@code mapper/}) that may collide by
 * name with resources a plugin bundles inside its own JAR. The standard parent-first
 * {@link URLClassLoader#getResource(String)} would return the <em>host's</em> copy,
 * causing the plugin to build its MyBatis {@code SqlSessionFactory} and run its schema
 * from the wrong files. This loader resolves resources from the plugin JAR first and
 * only delegates to the parent when the plugin does not provide them.</p>
 *
 * <p><strong>Class loading is intentionally left parent-first</strong> (no override of
 * {@code loadClass}/{@code findClass}). Shared API types such as
 * {@code fan.summer.api.ZhiFlowPlugin} must resolve to the same {@code Class} objects
 * the host loaded, otherwise {@link java.util.ServiceLoader}, casts, and
 * {@code instanceof} would break. The reported bug is purely a resource-name conflict,
 * so only resource resolution is made child-first.</p>
 *
 * @since 3.2.0 (moved from the host module so the preview window shares identical loading semantics)
 */
public class ChildFirstResourceClassLoader extends URLClassLoader {

    private static final PluginLogger log = LoggerFactory.getLogger(ChildFirstResourceClassLoader.class);

    /**
     * Creates a child-first-resource loader over the given URLs.
     *
     * @param urls   the plugin JAR URL(s) searched first for resources
     * @param parent the parent class loader (the host application class loader)
     */
    public ChildFirstResourceClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /**
     * Returns the plugin JAR's own copy of {@code name} if present, otherwise delegates
     * to the standard parent-first lookup.
     */
    @Override
    public URL getResource(String name) {
        URL own = findResource(name);   // searches only this loader's URLs (the plugin JAR)
        if (own != null) {
            return own;
        }
        return super.getResource(name); // parent-first fallback
    }

    /**
     * Opens a stream to the resource resolved by {@link #getResource(String)} (child-first).
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        URL url = getResource(name);
        if (url == null) {
            return null;
        }
        try {
            return url.openStream();
        } catch (IOException e) {
            log.warn("Failed to open resource stream for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Enumerates the plugin JAR's own copies of {@code name} first, then the parent's,
     * so a plugin-bundled resource shadows an identically-named host resource.
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> ordered = new ArrayList<>();
        // Plugin JAR first
        Enumeration<URL> own = findResources(name);
        while (own.hasMoreElements()) {
            ordered.add(own.nextElement());
        }
        // Parent next
        ClassLoader parent = getParent();
        if (parent != null) {
            Enumeration<URL> fromParent = parent.getResources(name);
            while (fromParent.hasMoreElements()) {
                ordered.add(fromParent.nextElement());
            }
        }
        return Collections.enumeration(ordered);
    }
}
