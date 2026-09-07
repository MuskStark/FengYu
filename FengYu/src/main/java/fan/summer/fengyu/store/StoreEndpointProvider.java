package fan.summer.fengyu.store;

import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
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

    private static final Logger log = LoggerFactory.getLogger(StoreEndpointProvider.class);

    private final String bootstrapBase;
    private final Supplier<String> overrideReader;
    private final boolean bootstrapAllowPrivateNetwork;
    /** Live Settings-UI posture, re-read per resolution (store.allow_private_network). */
    private final java.util.function.BooleanSupplier runtimePrivateNetworkReader;

    @Autowired
    public StoreEndpointProvider(
            @Value("${fengyu.store.api-base:http://localhost:8080}") String apiBase,
            @Value("${fengyu.store.allow-private-network:false}") boolean allowPrivateNetwork) {
        this(normalize(apiBase), () -> AiConfigServiceHeadless.getUpdateApiBase(""),
                allowPrivateNetwork,
                () -> AiConfigServiceHeadless.isStoreAllowPrivateNetwork());
        warnIfPlainHttpToNonLoopback(this.bootstrapBase);
    }

    /**
     * Startup warning for an insecure bootstrap channel: plain HTTP to a non-loopback store
     * ships every catalog request and download digest in the clear across the network. The URL
     * policy only lets that shape through when the operator explicitly allowed private-network
     * plain HTTP, so this marks a deliberate-but-risky deployment, not an opening an attacker
     * found — a warning is the proportionate response.
     */
    private static void warnIfPlainHttpToNonLoopback(String base) {
        URI uri = URI.create(base + "/");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) || isLoopbackHost(uri.getHost())) return;
        log.warn("Store channel bootstrap base {} uses plain HTTP to a non-loopback host — "
                + "traffic to the store is unencrypted; prefer HTTPS in production", base);
    }

    /** Test seam: explicit override reader and policy flag. */
    public StoreEndpointProvider(String bootstrapBase, Supplier<String> overrideReader,
            boolean allowPrivateNetwork) {
        this(bootstrapBase, overrideReader, allowPrivateNetwork, () -> false);
    }

    /** Test seam: explicit readers for both the channel and the runtime posture. */
    public StoreEndpointProvider(String bootstrapBase, Supplier<String> overrideReader,
            boolean allowPrivateNetwork,
            java.util.function.BooleanSupplier runtimePrivateNetworkReader) {
        this.bootstrapBase = normalize(bootstrapBase);
        this.overrideReader = overrideReader;
        this.bootstrapAllowPrivateNetwork = allowPrivateNetwork;
        this.runtimePrivateNetworkReader = runtimePrivateNetworkReader;
    }

    /**
     * Effective SSRF posture for this request: the launch property OR the live
     * Settings toggle — flipping the toggle in the UI re-runs the policy on the
     * very next store call, no restart.
     */
    public boolean allowPrivateNetwork() {
        return bootstrapAllowPrivateNetwork || runtimePrivateNetworkReader.getAsBoolean();
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
            UrlPolicy.requireTraversable(URI.create(value + "/"), allowPrivateNetwork());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Store channel " + value + " rejected by the URL policy: " + e.getMessage(), e);
        }
        return value;
    }

    /**
     * Whether the current channel can safely carry a persistent refresh token:
     * HTTPS, or a loopback host (dev store on this machine — traffic never
     * leaves the host, mirroring {@link UrlPolicy}'s loopback HTTPS exemption).
     * Over anything else (plain HTTP to a LAN/cross-site store) the refresh
     * token must stay memory-only: persisting it would hand a long-lived
     * credential to every network observer on the path.
     */
    public boolean secureTransport() {
        URI uri = URI.create(base() + "/");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return true;
        }
        if (!"http".equals(scheme)) {
            return false;
        }
        return isLoopbackHost(uri.getHost());
    }

    /** Loopback host check shared by {@link #secureTransport()} and the startup warning. */
    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        // URI.getHost() keeps the brackets on IPv6 literals.
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return bare.equalsIgnoreCase("localhost")
                || bare.equalsIgnoreCase("127.0.0.1")
                || bare.equalsIgnoreCase("::1")
                || bare.endsWith(".localhost");
    }

    static String normalize(String base) {
        String trimmed = base == null ? "" : base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
