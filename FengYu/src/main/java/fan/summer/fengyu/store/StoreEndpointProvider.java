package fan.summer.fengyu.store;

import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.function.Supplier;

/**
 * The single runtime source of the Infinia Store base URL for every outbound
 * surface — plugin catalog/downloads ({@link StoreClient}), cloud-account
 * OAuth and the user-center proxy (account gateways), and the store status
 * endpoint. The Settings 升级渠道 ({@code updateApiBase}) override wins when
 * set, because production deployments run the store separately from the app;
 * the {@code fengyu.store.api-base} launch property is only the bootstrap
 * default. Each resolution re-runs the shared SSRF policy so a runtime-changed
 * channel can never route store traffic into a private network unless
 * {@code fengyu.store.allow-private-network} explicitly allows it.
 */
@Component
public class StoreEndpointProvider {

    private final String bootstrapBase;
    private final Supplier<String> overrideReader;
    private final boolean allowPrivateNetwork;

    @Autowired
    public StoreEndpointProvider(
            @Value("${fengyu.store.api-base:http://localhost:8080}") String apiBase,
            @Value("${fengyu.store.allow-private-network:false}") boolean allowPrivateNetwork) {
        this(normalize(apiBase), () -> AiConfigServiceHeadless.getUpdateApiBase(""),
                allowPrivateNetwork);
    }

    /** Test seam: explicit override reader and policy flag. */
    public StoreEndpointProvider(String bootstrapBase, Supplier<String> overrideReader,
            boolean allowPrivateNetwork) {
        this.bootstrapBase = normalize(bootstrapBase);
        this.overrideReader = overrideReader;
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    /**
     * Effective store base for this request: the Settings channel override when
     * non-blank, else the bootstrap property. Policy-checked per call; a
     * violation surfaces as {@link IllegalStateException} with the policy's
     * reason.
     */
    public String base() {
        String override = overrideReader.get();
        String value = override == null || override.isBlank() ? bootstrapBase : normalize(override);
        try {
            UrlPolicy.requireTraversable(URI.create(value + "/"), allowPrivateNetwork);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Store channel " + value + " rejected by the URL policy: " + e.getMessage(), e);
        }
        return value;
    }

    static String normalize(String base) {
        String trimmed = base == null ? "" : base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
