package fan.summer.fengyu.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Initializes the AI backend once the Spring context is up, by delegating to
 * {@link BackendReactivator#reactivate()}. The same reactivator is used by the
 * AI config controller for hot-swapping, so startup and runtime share one path.
 */
@Component
public class AiBackendInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiBackendInitializer.class);

    private final BackendReactivator reactivator;
    /** Remote model-catalog overlay (nullable in focused tests): cached load + async refresh. */
    private final fan.summer.fengyu.ai.config.RemoteModelCatalogService remoteCatalog;

    public AiBackendInitializer(BackendReactivator reactivator,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            fan.summer.fengyu.ai.config.RemoteModelCatalogService remoteCatalog) {
        this.reactivator = reactivator;
        this.remoteCatalog = remoteCatalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("AI backend initializing on startup...");
        if (remoteCatalog != null) {
            // Cached overlay first (synchronous, instant): the last accepted remote
            // catalog is live before the first model resolution; a network refresh
            // then runs best-effort in the background and never blocks startup.
            remoteCatalog.loadCachedIntoCatalog();
            Thread.startVirtualThread(() -> remoteCatalog.refresh());
        }
        reactivator.reactivate();
    }
}
