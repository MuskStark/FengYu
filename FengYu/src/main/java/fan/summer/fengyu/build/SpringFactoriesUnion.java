package fan.summer.fengyu.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Build-time repair for the shaded fat jar's Spring SPI files, run by exec-maven-plugin right
 * after maven-shade-plugin (package phase; the jar path arrives as {@code args[0]}).
 *
 * <p>Shade's {@code AppendingTransformer} concatenates whole files, which is correct for the
 * line-per-class {@code *.imports} format but WRONG for {@code META-INF/spring.factories}:
 * that file is {@link Properties}, so two artifacts declaring the same key (spring-boot and
 * spring-boot-autoconfigure both ship {@code ApplicationListener=}) silently collapse to the
 * LAST block — dropping spring-boot's core listeners. With
 * {@code EnvironmentPostProcessorApplicationListener} gone, ConfigData never runs and the fat
 * jar loads {@code application.yml} for nothing; with {@code LoggingApplicationListener} gone,
 * {@code logging.level.*} is ignored. Exactly this shipped in 4.0.0-rc.1 (see the shaded-jar
 * spring.factories: the autoconfigure block overwrites the core block).
 *
 * <p>This tool enumerates every SPI resource on the compile classpath — the same set of jars
 * shade packed — and rewrites the jar entries with proper unions:
 * <ul>
 *   <li>{@code META-INF/spring.factories}: per-key comma union (dedup, first-seen order),
 *       matching what {@code SpringFactoriesLoader} would see across the unshaded jars;</li>
 *   <li>every {@code META-INF/spring/*.imports} entry already present in the jar: line union
 *       across all source jars, generalizing the pom's AutoConfiguration-only transformer
 *       (actuator web endpoints ship their own multi-jar
 *       {@code ManagementContextConfiguration.imports} collision).</li>
 * </ul>
 *
 * <p>The pom's AppendingTransformers stay in place as a degraded fallback if this execution
 * is ever skipped; this union simply overwrites their output. Keys are emitted sorted and
 * values in classpath order so the result is deterministic per dependency set.
 */
public final class SpringFactoriesUnion {

    private static final String FACTORIES = "META-INF/spring.factories";
    private static final String SPRING_DIR = "META-INF/spring/";
    private static final String IMPORTS_SUFFIX = ".imports";

    private SpringFactoriesUnion() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: SpringFactoriesUnion <shaded-jar>");
        }
        Path jar = Path.of(args[0]);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("shaded jar not found: " + jar);
        }

        Map<String, byte[]> replacements = new LinkedHashMap<>();
        List<URL> factoriesSources = resourceUrls(FACTORIES);
        Map<String, LinkedHashSet<String>> factoriesUnion = unionProperties(factoriesSources);
        replacements.put(FACTORIES, renderProperties(factoriesUnion)
                .getBytes(StandardCharsets.UTF_8));

        List<String> mergedImports = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> importsPaths = zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith(SPRING_DIR) && name.endsWith(IMPORTS_SUFFIX))
                    .sorted()
                    .toList();
            for (String path : importsPaths) {
                List<URL> sources = resourceUrls(path);
                if (sources.size() > 1) {
                    replacements.put(path, renderLines(unionLines(sources))
                            .getBytes(StandardCharsets.UTF_8));
                    mergedImports.add(path + " x" + sources.size());
                }
            }
        }

        rewriteEntries(jar, replacements);
        System.out.println("[spring-factories-union] " + jar.getFileName() + ": "
                + FACTORIES + " union of " + factoriesSources.size() + " dependency jars ("
                + factoriesUnion.size() + " keys)"
                + (mergedImports.isEmpty() ? "" : "; imports unions: " + mergedImports));
    }

    // ---- source enumeration ----

    private static List<URL> resourceUrls(String resource) throws IOException {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ClassLoader loader : distinctLoaders()) {
            Enumeration<URL> urls = loader.getResources(resource);
            while (urls.hasMoreElements()) {
                seen.add(urls.nextElement().toString());
            }
        }
        List<URL> result = new ArrayList<>();
        for (String spec : seen) {
            result.add(new URL(spec));
        }
        return result;
    }

    private static List<ClassLoader> distinctLoaders() {
        List<ClassLoader> loaders = new ArrayList<>();
        for (ClassLoader candidate : List.of(
                SpringFactoriesUnion.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader())) {
            if (candidate != null && !loaders.contains(candidate)) {
                loaders.add(candidate);
            }
        }
        return loaders;
    }

    // ---- union semantics ----

    /** Per-key comma union preserving first-seen value order — SpringFactoriesLoader's view. */
    private static Map<String, LinkedHashSet<String>> unionProperties(List<URL> sources)
            throws IOException {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        for (URL url : sources) {
            Properties properties = new Properties();
            try (InputStream in = url.openStream()) {
                properties.load(in);
            }
            for (String key : properties.stringPropertyNames()) {
                LinkedHashSet<String> values = merged.computeIfAbsent(key, k -> new LinkedHashSet<>());
                for (String value : properties.getProperty(key).split(",")) {
                    String trimmed = value.trim();
                    if (!trimmed.isEmpty()) {
                        values.add(trimmed);
                    }
                }
            }
        }
        return merged;
    }

    private static String renderProperties(Map<String, LinkedHashSet<String>> merged) {
        StringBuilder out = new StringBuilder();
        for (String key : new TreeMap<>(merged).keySet()) {
            out.append(key).append('=').append(String.join(",", merged.get(key))).append('\n');
        }
        return out.toString();
    }

    /** Line union for the one-class-per-line .imports format. */
    private static LinkedHashSet<String> unionLines(List<URL> sources) throws IOException {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (URL url : sources) {
            try (InputStream in = url.openStream()) {
                new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(lines::add);
            }
        }
        return lines;
    }

    private static String renderLines(LinkedHashSet<String> lines) {
        StringBuilder out = new StringBuilder();
        lines.forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    // ---- jar rewrite ----

    private static void rewriteEntries(Path jar, Map<String, byte[]> replacements)
            throws IOException {
        Path staging = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".union");
        try {
            try (ZipFile zip = new ZipFile(jar.toFile());
                    ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(staging))) {
                for (Enumeration<? extends ZipEntry> entries = zip.entries();
                        entries.hasMoreElements();) {
                    ZipEntry entry = entries.nextElement();
                    ZipEntry copy = new ZipEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    out.putNextEntry(copy);
                    byte[] replacement = replacements.get(entry.getName());
                    if (replacement != null) {
                        out.write(replacement);
                    } else {
                        zip.getInputStream(entry).transferTo(out);
                    }
                    out.closeEntry();
                }
            }
            Files.move(staging, jar, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
    }
}
