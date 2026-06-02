package fan.summer.buildintool.pdftool.converter;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.file.Path;

/**
 * Stub for the removed documents4j-based converter.
 * The dependency was replaced by a pure Java converter (PDFBox + POI).
 * This class is retained for compilation only and should not be used.
 *
 * @since 3.0.0
 * @deprecated Use {@link PurePdfConverter} instead.
 */
@Deprecated
public class Documents4jConverter implements DocumentConverter {

    private static final PluginLogger log = LoggerFactory.getLogger(Documents4jConverter.class);

    @Override
    public void convert(Path inputPath, Path outputPath) throws Exception {
        throw new UnsupportedOperationException(
            "Documents4jConverter is deprecated. Use PurePdfConverter instead.");
    }
}
