package fan.summer.buildintool.pdftool.converter;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Converts documents to DOCX using WPS Office in headless mode.
 *
 * <p>WPS is invoked via {@link ProcessBuilder} with the
 * {@code --headless --convert-to docx} flags. The process timeout is
 * 10 minutes.</p>
 *
 * @since 3.0.0
 */
public class WpsConverter implements DocumentConverter {

    private static final PluginLogger log = LoggerFactory.getLogger(WpsConverter.class);
    private static final long TIMEOUT_MINUTES = 10;

    private final String wpsExecutable;

    /**
     * Creates a converter that uses the given WPS executable.
     *
     * @param wpsExecutable absolute path to the WPS binary
     */
    public WpsConverter(String wpsExecutable) {
        this.wpsExecutable = wpsExecutable;
    }

    @Override
    public void convert(Path inputPath, Path outputPath) throws Exception {
        Path outputDir = outputPath.getParent();
        if (outputDir != null && !Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        log.info("Starting WPS conversion: {} -> {}", inputPath, outputPath);

        ProcessBuilder pb = new ProcessBuilder(
                wpsExecutable,
                "--headless",
                "--convert-to", "docx",
                "--outdir", outputDir != null ? outputDir.toString() : ".",
                inputPath.toString()
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Drain stdout/stderr to prevent blocking
        String output = new String(process.getInputStream().readAllBytes());

        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("WPS conversion timed out after " + TIMEOUT_MINUTES + " minutes");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("WPS conversion failed (exit code {}): {}", exitCode, output);
            throw new IOException("WPS conversion failed with exit code " + exitCode + ": " + output);
        }

        if (!Files.exists(outputPath)) {
            throw new IOException("WPS conversion appeared to succeed but output file not found: " + outputPath);
        }

        log.info("WPS conversion completed successfully: {}", outputPath);
    }
}
