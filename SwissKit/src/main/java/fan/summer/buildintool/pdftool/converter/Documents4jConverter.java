package fan.summer.buildintool.pdftool.converter;

import com.documents4j.api.DocumentType;
import com.documents4j.api.IConverter;
import com.documents4j.job.LocalConverter;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Converts documents using the <em>documents4j</em> library, which delegates
 * to a locally installed MS Word or LibreOffice instance.
 *
 * <p>The converter is created fresh for each {@link #convert(Path, Path)} call
 * and shut down afterwards. The conversion timeout is 10 minutes.</p>
 *
 * @since 3.0.0
 */
public class Documents4jConverter implements DocumentConverter {

    private static final PluginLogger log = LoggerFactory.getLogger(Documents4jConverter.class);
    private static final long TIMEOUT_MINUTES = 10;

    @Override
    public void convert(Path inputPath, Path outputPath) throws Exception {
        log.info("Starting documents4j conversion: {} -> {}", inputPath, outputPath);

        IConverter converter = LocalConverter.builder().build();
        try {
            File sourceFile = inputPath.toFile();
            File targetFile = outputPath.toFile();

            Future<Boolean> future = converter.convert(sourceFile).as(DocumentType.PDF)
                    .to(targetFile).as(DocumentType.DOCX)
                    .schedule();

            boolean success;
            try {
                success = future.get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new Exception("documents4j conversion timed out after " + TIMEOUT_MINUTES + " minutes", e);
            }

            if (!success) {
                throw new Exception("documents4j conversion failed: the underlying converter reported failure");
            }

            log.info("documents4j conversion completed successfully: {}", outputPath);
        } finally {
            try {
                converter.shutDown();
            } catch (Exception e) {
                log.warn("Error shutting down documents4j converter: {}", e.getMessage());
            }
        }
    }
}
