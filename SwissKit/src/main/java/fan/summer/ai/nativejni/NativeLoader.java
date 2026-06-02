package fan.summer.ai.nativejni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Cross-platform native library loader for llama.cpp JNI bindings.
 * <p>
 * Loading priority:
 * <ol>
 *   <li>{@code -Dllama.lib.path=/path/to/libllama_jni.so} — explicit path, highest priority</li>
 *   <li>JAR-embedded {@code /native/libllama_jni.{so|dylib|dll}} — extracted to temp directory</li>
 *   <li>{@code java.library.path} — standard system search</li>
 * </ol>
 */
public class NativeLoader {

    private static final Logger log = LoggerFactory.getLogger(NativeLoader.class);
    private static volatile boolean loaded = false;
    private static volatile FailureReason failureReason = FailureReason.NOT_LOADED;

    /**
     * Why the native library could not be loaded (or {@link FailureReason#NONE} if loaded).
     */
    public enum FailureReason {
        /** Loading succeeded. */
        NONE,
        /** Disabled via {@code -Dllama.native=false} system property. */
        DISABLED_BY_PROPERTY,
        /** Explicit path ({@code -Dllama.lib.path}) was set but {@link System#load} failed. */
        EXPLICIT_PATH_FAILED,
        /** JAR-embedded native library not found or failed to load (wrong platform / architecture). */
        JAR_EMBEDDED_FAILED,
        /** {@code java.library.path} search did not find the library. */
        LIBRARY_PATH_FAILED,
        /** Not yet attempted or all priorities exhausted with no success. */
        NOT_LOADED
    }

    public static synchronized void load() {
        if (loaded) return;

        // Allow disabling native backend via system property
        if ("false".equalsIgnoreCase(System.getProperty("llama.native", "true"))) {
            log.info("Native backend disabled via llama.native=false");
            failureReason = FailureReason.DISABLED_BY_PROPERTY;
            return;
        }

        // Priority 1: explicit path via system property
        String explicitPath = System.getProperty("llama.lib.path");
        if (explicitPath != null && !explicitPath.isBlank()) {
            try {
                System.load(explicitPath);
                loaded = true;
                failureReason = FailureReason.NONE;
                log.info("Loaded native library from explicit path: {}", explicitPath);
                return;
            } catch (UnsatisfiedLinkError e) {
                log.warn("Failed to load from explicit path {}: {}", explicitPath, e.getMessage());
                failureReason = FailureReason.EXPLICIT_PATH_FAILED;
            }
        }

        // Priority 2: JAR-embedded native library
        String libName = getLibName();
        String resourcePath = "/native/" + libName;
        try (InputStream is = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                Path tmpDir = getTempDir();
                Path tmpFile = tmpDir.resolve(libName);
                Files.copy(is, tmpFile, StandardCopyOption.REPLACE_EXISTING);
                tmpFile.toFile().deleteOnExit();
                System.load(tmpFile.toAbsolutePath().toString());
                loaded = true;
                failureReason = FailureReason.NONE;
                log.info("Loaded native library from JAR: {}", tmpFile.toAbsolutePath());
                return;
            }
        } catch (IOException | UnsatisfiedLinkError e) {
            log.debug("JAR-embedded native library not found or failed to load: {}", e.getMessage());
        }
        failureReason = FailureReason.JAR_EMBEDDED_FAILED;

        // Priority 3: java.library.path
        try {
            System.loadLibrary("llama_jni");
            loaded = true;
            failureReason = FailureReason.NONE;
            log.info("Loaded native library from java.library.path");
            return;
        } catch (UnsatisfiedLinkError e) {
            log.debug("Native library not found on java.library.path: {}", e.getMessage());
        }

        failureReason = FailureReason.LIBRARY_PATH_FAILED;
        log.info("Native llama library not available — using pure Java inference engine");
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Returns the reason why the native library is not available.
     * {@link FailureReason#NONE} means the library was loaded successfully.
     */
    public static FailureReason getFailureReason() {
        return failureReason;
    }

    private static String getLibName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String suffix = getPlatformSuffix(os);
        String archTag = getArchTag(arch);

        // e.g. libllama_jni-aarch64.dylib, libllama_jni-x86_64.so
        String baseName = "libllama_jni";
        if (!archTag.isEmpty()) baseName += "-" + archTag;
        return baseName + suffix;
    }

    private static String getPlatformSuffix(String os) {
        if (os.contains("mac") || os.contains("darwin")) return ".dylib";
        if (os.contains("win")) return ".dll";
        return ".so"; // Linux / BSD
    }

    private static String getArchTag(String arch) {
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        if (arch.contains("x86_64") || arch.contains("amd64")) return "x86_64";
        return "";
    }

    private static Path getTempDir() throws IOException {
        String customTmp = System.getProperty("llama.tmp.dir");
        if (customTmp != null && !customTmp.isBlank()) {
            Path dir = Paths.get(customTmp);
            Files.createDirectories(dir);
            return dir;
        }
        return Files.createTempDirectory("llama_jni_");
    }
}
