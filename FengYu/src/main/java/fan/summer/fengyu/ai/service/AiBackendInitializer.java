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

    public AiBackendInitializer(BackendReactivator reactivator) {
        this.reactivator = reactivator;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("AI backend initializing on startup...");
        reactivator.reactivate();
    }
}
