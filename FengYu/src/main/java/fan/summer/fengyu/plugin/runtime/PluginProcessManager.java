package fan.summer.fengyu.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.market.PluginIntegrityStore;
import fan.summer.fengyu.security.ProcessSandbox;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Owns isolated plugin backend processes and their newline-delimited JSON-RPC channel.
 *
 * <p><b>Per-call timeout.</b> Every invoke is bounded by a timeout (default {@link
 * #DEFAULT_TIMEOUT_SECONDS}, declarable up to {@link #MAX_TIMEOUT_SECONDS}). When the timeout
 * elapses the worker process is killed — a worker is single-threaded on the SDK side, so a
 * stuck handler cannot be cancelled any other way. On the next call the lazily-restarted
 * worker takes its place. Timeouts therefore behave like crashes: this is deliberate.
 *
 * <p><b>Pipelined concurrency.</b> The per-Worker {@code synchronized} lock was removed: writes
 * to stdin are now guarded by a tiny write lock, and a single resident reader virtual-thread
 * demultiplexes responses by JSON-RPC {@code id} into per-request {@link CompletableFuture}s.
 * Multiple concurrent callers into the same plugin no longer serialize on each other's full
 * round-trip — they only contend on the worker's own single-threaded dispatch (a property of
 * {@code JsonRpcWorker} on the SDK side). This pipelining is what makes the declared timeout
 * meaningful: a slow request no longer blocks unrelated requests.
 */
@Service
public class PluginProcessManager {
    private static final Logger log = LoggerFactory.getLogger(PluginProcessManager.class);
    /** Default per-call timeout when neither the caller nor the manifest declares one. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 60;
    /** Hard cap on any declared timeout; prevents a malicious manifest from pinning a worker. */
    public static final long MAX_TIMEOUT_SECONDS = 600;
    /**
     * Hard cap on a single newline-delimited JSON-RPC frame on stdout (P1-6). A runaway worker that
     * emits an enormous single line (no newline) would otherwise let {@code readLine()} buffer it
     * all and OOM the host. When a frame exceeds this the worker is torn down and every pending call
     * is failed with the overrun reason. 16 MiB is generous for any legitimate result blob while
     * still bounded.
     */
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    /** Per-line cap on forwarded stderr (P1-6): bounded independently of stdout, smaller since logs. */
    static final int MAX_STDERR_LINE_BYTES = 1 * 1024 * 1024;
    /**
     * Grace window (T2-04 bullet 5) the cancel path waits for a cooperative {@code $/cancelRequest}
     * to resolve the in-flight call before falling back to interrupting the request thread (which
     * tears down the worker). A real SDK worker answers in milliseconds; a worker that cannot
     * honour the notification (e.g. a legacy single-threaded one) lets the grace lapse and the
     * interrupt path applies.
     */
    static final long CANCEL_GRACE_NANOS = 1_000_000_000L;

    private final PluginPackageService packages;
    private final PluginFileGrantService files;
    private final PluginRuntimeEnvironmentService runtimeEnvironment;
    private final PluginLogStore logStore;
    private final ProcessSandbox sandbox;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
    /** UI-originated calls keyed by the protocol correlation id, used for explicit cancellation. */
    private final Map<ActiveCallKey, Thread> activeCalls = new ConcurrentHashMap<>();
    /**
     * Plugins mid-update (package swap in progress). While present, {@link #invoke} refuses new
     * calls for the id (P0-6): an in-flight Worker must not be reused against a half-swapped
     * package, and concurrent invokes must not race the stop→install→restart sequence.
     */
    private final java.util.Set<String> updating = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Per-plugin lock that makes the "acquire a Worker" step in {@link #invoke} mutually exclusive
     * with the "begin/end an update" step ({@link #beginUpdate}/{@link #stop}/{@link #endUpdate}).
     *
     * <p>Without it the update gate had a TOCTOU race: {@code invoke} checked {@link #updating} then
     * later entered {@code workers.compute(...)} with no synchronization between the two, so a
     * concurrent {@code beginUpdate → stop} could fire between the check and the acquire — either
     * killing a Worker an invoke just started, or letting an invoke reuse a Worker against a package
     * that is mid-swap. The lock serializes the two critical sections; the long RPC itself runs
     * outside the lock (an update's stop then closes the Worker and the in-flight call fails through
     * the normal worker-error path, which is the intended behavior).
     */
    private final java.util.Map<String, java.util.concurrent.locks.ReentrantLock> updateLocks = new ConcurrentHashMap<>();

    public PluginProcessManager(PluginPackageService packages, PluginFileGrantService files,
            PluginRuntimeEnvironmentService runtimeEnvironment, PluginLogStore logStore) {
        this(packages, files, runtimeEnvironment, logStore,
                new ProcessSandbox());
    }

    @Autowired
    public PluginProcessManager(PluginPackageService packages, PluginFileGrantService files,
            PluginRuntimeEnvironmentService runtimeEnvironment, PluginLogStore logStore,
            ProcessSandbox sandbox) {
        this.packages = packages;
        this.files = files;
        this.runtimeEnvironment = runtimeEnvironment;
        this.logStore = logStore;
        this.sandbox = sandbox;
    }

    /** Invoke with the plugin-wide default timeout (manifest {@code backend.callTimeoutSeconds} or 60s). */
    public Object invoke(String pluginId, String method, Map<String, Object> params) {
        return invoke(pluginId, method, params, -1);
    }

    /** The plugin's sandbox-writable default output folder (see {@link PluginRuntimeEnvironmentService#defaultOutputPath}). */
    public java.nio.file.Path defaultOutputPath(String pluginId) throws java.io.IOException {
        return runtimeEnvironment.defaultOutputPath(pluginId);
    }

    /** Invoke with the plugin-wide default timeout and a request locale (e.g. {@code "zh"}/{@code "en"}). */
    public Object invoke(String pluginId, String method, Map<String, Object> params, String locale) {
        return invoke(pluginId, method, params, -1, locale);
    }

    /**
     * Invoke a UI-originated call with a stable protocol id. The {@code callId} is threaded through
     * as the JSON-RPC {@code id} (T2-04 bullet 4) so the host can correlate a cooperative
     * {@code $/cancelRequest} with the exact in-flight call. Cancellation first asks the worker to
     * cancel cooperatively; only on timeout does it interrupt the request thread (which tears down
     * the single-threaded worker).
     */
    public Object invokeTracked(String pluginId, String callId, String method,
            Map<String, Object> params, String locale) {
        if (callId == null || callId.isBlank()) throw new IllegalArgumentException("callId is required");
        ActiveCallKey key = new ActiveCallKey(pluginId, callId);
        Thread current = Thread.currentThread();
        if (activeCalls.putIfAbsent(key, current) != null) {
            throw new IllegalArgumentException("Duplicate plugin call id: " + callId);
        }
        try {
            return invoke(pluginId, callId, method, params, -1, locale);
        } finally {
            activeCalls.remove(key, current);
            // Servlet container threads may be reused; do not leak the cancellation interrupt.
            Thread.interrupted();
        }
    }

    /**
     * Cancel a tracked UI call (T2-04 bullet 5). Returns {@code false} when the call already
     * completed or was never registered. Otherwise the worker is asked to cancel cooperatively via
     * a {@code $/cancelRequest} notification; if the in-flight call resolves within the grace
     * window (the worker returns a CANCELLED error and stays healthy) the method returns
     * immediately. If it does NOT resolve — the worker cannot honour the notification — the request
     * thread is interrupted as a fallback, which triggers the normal kill + restart path.
     */
    public boolean cancel(String pluginId, String callId) {
        if (callId == null || callId.isBlank()) return false;
        ActiveCallKey key = new ActiveCallKey(pluginId, callId);
        Thread thread = activeCalls.get(key);
        if (thread == null) return false;
        Worker worker = workers.get(pluginId);
        if (worker != null && worker.alive()) {
            worker.sendNotification(PluginWorkerProtocol.CANCEL_REQUEST_METHOD, Map.of("id", callId));
            long deadline = System.nanoTime() + CANCEL_GRACE_NANOS;
            while (System.nanoTime() < deadline) {
                if (!worker.hasPending(callId)) return true; // resolved cooperatively
                try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        // Fallback: the call did not resolve cooperatively within the grace window. Interrupt the
        // request thread; Worker.invoke() converts the interrupt into an IllegalStateException and
        // the outer invoke() tears the worker down so a stuck handler cannot continue.
        thread.interrupt();
        return true;
    }

    private record ActiveCallKey(String pluginId, String callId) {}

    /**
     * Invoke with an explicit per-call timeout in seconds. Caller-supplied values are clamped to
     * {@code [1, MAX_TIMEOUT_SECONDS]}; {@code -1} means "use the plugin-wide default".
     */
    public Object invoke(String pluginId, String method, Map<String, Object> params, long timeoutSeconds) {
        return invoke(pluginId, method, params, timeoutSeconds, null);
    }

    /**
     * Invoke with an explicit per-call timeout and a request locale. The locale is injected into the
     * params map sent to the worker (key {@code "locale"}) so a worker that resolves localized
     * messages can read it via {@code WorkerLocale.current()} without changing the JSON-RPC envelope.
     * A {@code null}/blank locale is omitted entirely — the worker then defaults to English, matching
     * pre-i18n behaviour for callers that don't know the locale.
     */
    public Object invoke(String pluginId, String method, Map<String, Object> params,
            long timeoutSeconds, String locale) {
        return invoke(pluginId, null, method, params, timeoutSeconds, locale);
    }

    /**
     * Invoke with an explicit per-call timeout, request locale, and an optional JSON-RPC id. When
     * {@code callId} is non-blank it is used verbatim as the JSON-RPC {@code id} (T2-04 bullet 4),
     * letting the host correlate a {@code $/cancelRequest} with the exact in-flight call; otherwise a
     * fresh UUID is minted. The locale rides in the params (see {@link #invoke(String, String, Map, long, String)}).
     */
    public Object invoke(String pluginId, String callId, String method, Map<String, Object> params,
            long timeoutSeconds, String locale) {
        if (!packages.isEnabled(pluginId)) throw new IllegalArgumentException("Plugin is disabled: " + pluginId);
        // The entire "is it updating? find manifest + acquire/reuse a Worker" sequence runs under the
        // per-plugin update lock so it is mutually exclusive with beginUpdate/stop/endUpdate. Without
        // the lock the updating-check (line below) and the worker-acquire were two separate steps a
        // concurrent update could interleave between (TOCTOU). The actual RPC (worker.invoke) runs
        // OUTSIDE the lock so a long call does not block updates; an update that stops the Worker
        // mid-RPC closes it and the call fails through the normal worker-error path.
        Worker worker;
        long timeout;
        java.util.concurrent.locks.ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            // P0-6: a plugin whose package is mid-swap must refuse new calls — invoking during an
            // update would race the stop→install→restart sequence and could reuse a stale Worker or
            // hit a half-written package. Checked INSIDE the lock so beginUpdate cannot slip in here.
            if (updating.contains(pluginId)) {
                throw new IllegalStateException("Plugin is being updated; retry shortly: " + pluginId);
            }
            PluginManifest manifest = packages.find(pluginId)
                .orElseThrow(() -> new IllegalArgumentException("Plugin is not installed: " + pluginId));
            if (manifest.backend() == null) {
                throw new IllegalArgumentException("Plugin has no backend: " + pluginId);
            }
            // T2-04 bullet 2: validate the method against rpc.methods BEFORE starting the worker.
            // An unknown method is rejected without paying the cost of a process spawn, and a UI/AI
            // typo never reaches the worker channel.
            if (manifest.rpc() == null || manifest.rpc().methods() == null
                    || !manifest.rpc().methods().containsKey(method)) {
                throw new IllegalArgumentException("Unknown plugin method: " + method);
            }
            timeout = resolveTimeout(timeoutSeconds, manifest);
            long grantVersion = files.grantVersion(pluginId);
            // P0-6: the Worker identity keys on the installed package's CONTENT digest (not just the
            // manifest version), so a same-version repack with different bytes — or an upgrade that
            // forgot to bump the version — still invalidates the cached Worker. The digest is read
            // from the integrity store (recorded at install time); when absent (legacy install, or
            // no store wired in tests) the identity falls back to version-only so existing behavior
            // is preserved.
            PluginIntegrityStore integrity = packages.integrityStore();
            String packageDigest = integrity == null ? null
                    : integrity.packageDigest(pluginId).orElse(null);
            // P0-4: the plugin OS boundary is decoupled from the AI per-turn permission. A user
            // granting the AI "full access" for tool-call *effects* must NOT also disable every
            // called plugin's sandbox — that would let any plugin the AI invokes run bare, bypassing
            // its declared permissions and platform isolation. Only the explicit, host-wide
            // unsandboxed-plugins toggle lifts the plugin OS boundary.
            boolean unsandboxed = AiConfigServiceHeadless.isUnsandboxedPluginsEnabled();
            worker = workers.compute(pluginId, (id, current) -> {
                // Reuse the cached Worker only if it is alive, the file-grant version matches, the
                // unsandboxed flag matches, AND the package identity (version + content digest)
                // matches. Any mismatch closes the old Worker and starts a fresh one.
                if (current != null && current.alive() && current.grantVersion() == grantVersion
                        && current.fullAccess() == unsandboxed
                        && java.util.Objects.equals(current.manifestVersion(), manifest.version())
                        && java.util.Objects.equals(current.packageDigest(), packageDigest)) {
                    return current;
                }
                if (current != null) {
                    // CQ-03: only a worker that actually DIED counts toward the crash-loop
                    // guard. An alive worker replaced because the grant version, sandbox
                    // mode, or package identity changed is a deliberate host restart, not
                    // a crash — counting it engaged the 30s spawn cooldown for a user
                    // merely re-granting files, with a misleading "worker crashed" log.
                    if (!current.alive()) {
                        recordRapidDeath(id, current);
                    } else {
                        log.info("Plugin {}: restarting worker ({})", id, restartReason(current,
                                grantVersion, unsandboxed, manifest.version(), packageDigest));
                    }
                    current.close();
                }
                ensureNotCrashBlocked(id);
                return start(id, manifest, unsandboxed, packageDigest);
            });
        } finally {
            lock.unlock();
        }
        // Log only the param KEYS, never the values. A caller can pass arbitrary credentials or
        // body text in params (e.g. an SMTP password for email_account_save); logging the value —
        // even truncated — leaks it to the console, the host log file, and the plugin log surface.
        // Keys describe the call shape without revealing anything sensitive.
        String keys = paramKeys(params);
        log.info("Plugin {} invoke -> {}{}", pluginId, method, keys);
        logStore.append(pluginId, "INFO", "invoke " + method + keys);
        long startedNanos = System.nanoTime();
        try {
            @SuppressWarnings("unchecked") Map<String, Object> resolved = (Map<String, Object>) resolveRefs(pluginId, params == null ? Map.of() : params);
            // Resolved params carry FileRefs turned into absolute paths (and still hold any secret
            // values), so only their KEYS are safe to log even at DEBUG.
            log.debug("Plugin {} resolved {} keys={}", pluginId, method, resolved.keySet());
            // T2-04 bullet 4: use the caller's callId as the JSON-RPC id when supplied (tracked UI
            // calls), so cancel() can correlate a $/cancelRequest with the exact pending entry.
            // Otherwise mint a UUID (AI/untracked calls). Worker.invoke enforces its own timeout via
            // future.get(timeout); on timeout it throws IllegalStateException, which the catch below
            // turns into a worker kill + restart.
            String rpcId = callId != null && !callId.isBlank() ? callId : UUID.randomUUID().toString();
            Object result = worker.invoke(rpcId, method, resolved, timeout, locale);
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
            log.info("Plugin {} <- {} ok ({} ms)", pluginId, method, elapsedMs);
            logStore.append(pluginId, "INFO", method + " ok (" + elapsedMs + " ms)");
            return result;
        } catch (RuntimeException e) {
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
            // Worker error messages are untrusted and may echo request values such as passwords,
            // mail bodies, or filesystem paths. Preserve the exception for the direct API caller,
            // but keep shared console/file/SSE logs limited to the failure type.
            String failureType = e.getClass().getSimpleName();
            log.warn("Plugin {} <- {} failed after {} ms ({})", pluginId, method, elapsedMs, failureType);
            logStore.append(pluginId, "WARN", method + " failed (" + failureType + ")");
            // Only unrecoverable worker state (EOF / IO / timeout / interrupted) tears down the
            // worker. Business errors (IllegalArgumentException, e.g. "plugin is disabled") leave
            // the worker intact for the next call. failAll() drains every pending caller so the
            // stuck handler's siblings learn about the failure instead of hanging.
            if (e instanceof IllegalStateException) {
                // Count the teardown as a candidate rapid death BEFORE the removal: the most
                // common crash-loop shape dies during its FIRST invoke (spawn → EOF → remove →
                // respawn), which the cached-mismatch branch in ensure() can never see. Only a
                // worker that is STILL the map's entry counts — stop()/beginUpdate() remove the
                // Worker before closing it, so a deliberate host teardown (update, disable,
                // shutdown) never accrues crash-loop strikes. A healthy worker torn down by a
                // mere call timeout has usually outlived the crash window and resets the counter.
                if (workers.get(pluginId) == worker) {
                    recordRapidDeath(pluginId, worker);
                }
                worker.failAll("Plugin worker tearing down: " + e.getMessage());
                workers.remove(pluginId, worker);
                worker.close();
            }
            throw e;
        }
    }

    private static long resolveTimeout(long requested, PluginManifest manifest) {
        Long declared = manifest.backend() != null ? manifest.backend().callTimeoutSeconds() : null;
        long effective = requested == -1 ? (declared != null ? declared : DEFAULT_TIMEOUT_SECONDS) : requested;
        if (effective < 1) effective = 1;
        if (effective > MAX_TIMEOUT_SECONDS) effective = MAX_TIMEOUT_SECONDS;
        return effective;
    }

    /** Why an ALIVE cached worker is being deliberately restarted (CQ-03 log fidelity). */
    private static String restartReason(Worker current, long grantVersion, boolean unsandboxed,
                                        String manifestVersion, String packageDigest) {
        if (current.grantVersion() != grantVersion) return "file-grant version changed";
        if (current.fullAccess() != unsandboxed) return "sandbox mode changed";
        if (!java.util.Objects.equals(current.manifestVersion(), manifestVersion)) {
            return "manifest version changed";
        }
        if (!java.util.Objects.equals(current.packageDigest(), packageDigest)) {
            return "package digest changed";
        }
        return "worker state stale";
    }

    private Object resolveRefs(String pluginId, Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("id") instanceof String id && id.startsWith("ref_") && map.get("kind") != null) {
                return files.resolve(pluginId, id).toString();
            }
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), resolveRefs(pluginId, item)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> resolveRefs(pluginId, item)).toList();
        return value;
    }

    public void stop(String pluginId) {
        // Hold the per-plugin lock so a concurrent invoke cannot (re)acquire a Worker between this
        // remove and the close, and so beginUpdate's "mark updating + stop" is atomic w.r.t. invoke.
        java.util.concurrent.locks.ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            Worker worker = workers.remove(pluginId);
            if (worker != null) worker.close();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Begin an atomic package update for a plugin (P0-6): under the per-plugin lock, mark the id as
     * updating so concurrent {@link #invoke} calls refuse to start/reuse a Worker, then stop any
     * running Worker. The lock makes the "mark updating + stop" sequence atomic with invoke's
     * "check updating + acquire Worker" sequence, eliminating the TOCTOU race where an invoke could
     * slip past the updating-check and start a Worker that stop then kills (or reuse one against a
     * half-swapped package). Callers MUST pair this with {@link #endUpdate} in a {@code finally}.
     */
    public void beginUpdate(String pluginId) {
        java.util.concurrent.locks.ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            updating.add(pluginId);
            Worker worker = workers.remove(pluginId);
            if (worker != null) worker.close();
        } finally {
            lock.unlock();
        }
    }

    /** Re-enable invokes for a plugin after an update attempt (success or failure). */
    public void endUpdate(String pluginId) {
        updating.remove(pluginId);
    }

    // ── crash-loop guard ────────────────────────────────────────────────

    /**
     * Crash-loop guard: a plugin whose worker dies within {@link #RAPID_CRASH_WINDOW_NANOS} of
     * spawning is counted; {@link #MAX_RAPID_CRASHES} consecutive rapid deaths pause spawns for
     * {@link #CRASH_COOLDOWN_NANOS}. Without this, a worker that dies instantly costs one JVM
     * spawn per invoke — a local fork-bomb-by-attrition. A worker that outlives the window
     * resets its plugin's counter (it served, so the plugin is not crash-looping).
     */
    private static final long RAPID_CRASH_WINDOW_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    private static final int MAX_RAPID_CRASHES = 3;
    private static final long CRASH_COOLDOWN_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    private final java.util.concurrent.ConcurrentHashMap<String, RapidCrashState> crashStates =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class RapidCrashState {
        // Atomic: recordRapidDeath is also called from invoke's teardown catch, OUTSIDE the
        // per-plugin update lock, so concurrent failed invokes on the same worker must not
        // lose increments on the shared counter.
        final java.util.concurrent.atomic.AtomicInteger rapidDeaths =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile long blockedUntilNanos = 0;
    }

    private void recordRapidDeath(String pluginId, Worker dead) {
        RapidCrashState state = crashStates.computeIfAbsent(pluginId, id -> new RapidCrashState());
        long lifetime = System.nanoTime() - dead.startedAtNanos();
        if (lifetime > RAPID_CRASH_WINDOW_NANOS) {
            state.rapidDeaths.set(0);
            return;
        }
        if (state.rapidDeaths.incrementAndGet() >= MAX_RAPID_CRASHES) {
            state.blockedUntilNanos = System.nanoTime() + CRASH_COOLDOWN_NANOS;
            log.warn("Plugin {} worker crashed {} times within {}s of spawn — pausing spawns for {}s",
                    pluginId, state.rapidDeaths.get(),
                    RAPID_CRASH_WINDOW_NANOS / 1_000_000_000,
                    CRASH_COOLDOWN_NANOS / 1_000_000_000);
        }
    }

    private void ensureNotCrashBlocked(String pluginId) {
        RapidCrashState state = crashStates.get(pluginId);
        if (state != null && state.blockedUntilNanos > System.nanoTime()) {
            throw new IllegalStateException("Plugin " + pluginId
                    + " worker crashed repeatedly on startup; spawns are paused for crash-loop "
                    + "protection — retry in "
                    + ((state.blockedUntilNanos - System.nanoTime()) / 1_000_000_000 + 1) + "s");
        }
    }

    /** The per-plugin update lock (lazily created, one per id, retained for the manager's life). */
    private java.util.concurrent.locks.ReentrantLock lockFor(String pluginId) {
        return updateLocks.computeIfAbsent(pluginId, id -> new java.util.concurrent.locks.ReentrantLock());
    }

    /**
     * Test-only: number of unreaped in-flight invoke slots for a plugin's worker. Successful
     * responses must atomically reclaim their slot (P0-5), so after a sequence of successful
     * invokes this must read 0. Returns -1 when no worker is currently cached for the plugin.
     */
    public int pendingCountForTest(String pluginId) {
        Worker worker = workers.get(pluginId);
        return worker == null ? -1 : worker.pendingCountForTest();
    }

    private Worker start(String id, PluginManifest manifest, boolean fullAccess, String packageDigest) {
        Path root = packages.directory(id);
        // P0-2: re-verify the installed manifest against the recorded digest before launching. A
        // plugin must not be able to rewrite its own manifest.json (now that the package dir is
        // read-only to the Worker, this catches out-of-band tampering too) and have the host honor
        // new/escalated permissions on restart. Fail closed: a known record that does not match, or
        // an unreadable live manifest, refuses to start the Worker.
        //
        // A MISSING record is also fail-closed once the integrity store is wired: the host
        // re-establishes records for already-installed OFFICIAL plugins at startup by reinstalling
        // them from the trusted bundled archive (OfficialPluginSeeder.seed), and every fresh install
        // records one. So a missing record here means the plugin was dropped onto disk out-of-band
        // (or, for a third-party plugin, predates the store and has no trusted source to reinstall
        // from) — refuse to start rather than run unverified. (Tests that build a manager without a
        // store still pass: integrity is null and this whole block is skipped.)
        PluginIntegrityStore integrity = packages.integrityStore();
        if (integrity != null) {
            java.util.Optional<Boolean> ok = integrity.verify(id, root.resolve("manifest.json"));
            if (ok.isEmpty()) {
                throw new IllegalStateException(
                    "Plugin " + id + " has no integrity record on file. The host records one for every "
                        + "install and re-establishes one for official plugins by reinstalling from the "
                        + "bundled archive at startup; a missing record means the package was introduced "
                        + "out-of-band or predates the store. Reinstall the plugin to establish a record.");
            }
            if (!ok.get()) {
                throw new IllegalStateException(
                    "Plugin " + id + " manifest tamper detected: on-disk manifest.json does not match the "
                        + "installed record. Reinstall the plugin to update the record.");
            }
            // P0-2 whole-package verify: recompute the live package directory digest and compare to
            // the record. This catches tampering of ANY file in the package (the Worker JAR, libs,
            // assets) — not just manifest.json — so a Worker whose JAR was rewritten out-of-band
            // while the manifest was left intact is refused. A record that predates package digests
            // (legacy manifest-only) returns empty here and is NOT enforced for the whole package,
            // but the manifest check above still applies.
            java.util.Optional<Boolean> pkgOk = integrity.verifyPackage(id, root);
            if (pkgOk.isPresent() && !pkgOk.get()) {
                throw new IllegalStateException(
                    "Plugin " + id + " package tamper detected: the on-disk package contents do not match "
                        + "the installed record (a file was added/removed/modified). Reinstall the plugin.");
            }
        }
        // T2-04: the worker command is fixed to a headless `java -jar backend/worker.jar`
        // invocation (schema v2 dropped backend.command/protocol — the channel is always JSON-RPC
        // 2.0 over the bundled JAR). Workers have no desktop surface on any supported platform;
        // forcing headless also prevents spreadsheet/font libraries from registering a second
        // foreground Java application in the macOS Dock.
        List<String> command = fixedWorkerCommand(root);
        Map<String, String> environment = runtimeEnvironment.environmentFor(manifest);
        SensitiveValueRedactor redactor = SensitiveValueRedactor.fromEnvironment(environment);
        try {
            List<String> permissions = manifest.permissions() == null ? List.of() : manifest.permissions();
            // Network gating (P1-9 honest model): `network.email` and `database` are currently treated
            // as full network egress — same as `network` — because the host does not yet broker
            // SMTP/IMAP or restrict DB connections to a specific host. A real mail/DB proxy is a
            // tracked follow-up; until then these permissions are advisory at the network layer (a
            // plugin declaring them gets broad egress). The manifest docs surface this so the UI does
            // not imply finer isolation than the OS enforces.
            boolean allowNetwork = permissions.contains("network")
                    || permissions.contains("network.email")
                    || permissions.contains("database");
            // P0-2: the plugin's installed package directory is read-only to the Worker. Allowing a
            // Worker to write its own install dir let a plugin rewrite its manifest.json and escalate
            // permissions on the next restart. Runtime state lives under plugin-data/<id> (and the
            // plugin-owned tmp dir) plus any explicitly authorized FileRef roots — never the package.
            List<Path> writableRoots = new ArrayList<>();
            String pluginData = environment.get(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV);
            Path workerTemp = null;
            if (pluginData != null && !pluginData.isBlank()) {
                Path dataDirectory = Path.of(pluginData);
                writableRoots.add(dataDirectory);
                workerTemp = Files.createDirectories(dataDirectory.resolve("tmp"));
                command = withJavaTempDirectory(command, workerTemp);
            }
            writableRoots.addAll(files.writablePaths(id));
            List<Path> readableRoots = files.readablePaths(id).stream()
                    .filter(path -> !writableRoots.contains(path)).toList();
            ProcessSandbox.Launch launch = fullAccess
                    ? sandbox.unrestricted(command)
                    : sandbox.plugin(command, root, writableRoots, readableRoots, allowNetwork);
            ProcessBuilder builder = new ProcessBuilder(launch.command()).directory(root.toFile());
            // A Worker must NOT inherit the host JVM's full environment — that would hand the
            // plugin every host secret (OPENAI_API_KEY, GH_TOKEN, proxy creds, CI secrets, ...).
            // Clear the inherited map and restore only an explicit OS/runtime allowlist the JVM and
            // the plugin's own runtime need to function. Plugin protocol vars (FENGYU_*) and the
            // per-plugin environment map are layered on AFTER the allowlist below. This mirrors the
            // positive-allowlist strategy the review requires; the denylist used by the AI command
            // path (CommandExecuteTool) is deliberately NOT reused here.
            applyEnvironmentAllowlist(builder.environment());
            builder.environment().put("FENGYU_PLUGIN_ID", id);
            builder.environment().put("FENGYU_PLUGIN_ROOT", root.toString());
            if (workerTemp != null) {
                builder.environment().put("TMPDIR", workerTemp.toString());
                builder.environment().put("TMP", workerTemp.toString());
                builder.environment().put("TEMP", workerTemp.toString());
            }
            environment.forEach(builder.environment()::put);
            Process process = builder.start();
            // WINDOWS_JOB backend assigns the process to a Job Object after start; the hook writes
            // the job handle into handleOut[0]. On other backends onStarted() is null and jobHandle
            // stays 0 (terminate/close then no-op). If the hook throws (create/assign failed), the
            // worker must fail to start explicitly rather than run un-jailed — AND the just-started
            // process must be destroyed so it cannot leak as an orphan (P1-7). The hook is wrapped
            // for ALL throwables (not just IOException) so a JNA/Win32 error from Job creation also
            // triggers the cleanup; any job handle the hook created before failing is closed too.
            long jobHandle = 0L;
            if (launch.onStarted() != null) {
                long[] handleOut = {0L};
                try {
                    launch.onStarted().accept(process, handleOut);
                    jobHandle = handleOut[0];
                } catch (RuntimeException | Error hookFailure) {
                    safeDestroy(process);
                    if (handleOut[0] != 0L) {
                        try { sandbox.closeJobHandle(handleOut[0]); } catch (RuntimeException ignored) {}
                    }
                    throw hookFailure;
                }
            }
            Thread.ofVirtual().name("plugin-" + id + "-stderr").start(() -> {
                try (InputStream errors = new BufferedInputStream(process.getErrorStream())) {
                    for (String line; (line = readBoundedLine(errors, MAX_STDERR_LINE_BYTES, "stderr")) != null;) {
                        // Parse before redaction: structured JSON escapes quotes/backslashes, so
                        // replacing a raw secret in the encoded frame can miss it. Redact the
                        // decoded fields instead; legacy free-form stderr follows the same path.
                        PluginLogLineParser.Parsed parsed = PluginLogLineParser.parse(line);
                        PluginLogLineParser.Parsed event = new PluginLogLineParser.Parsed(
                            parsed.level(),
                            redactor.redact(parsed.logger()),
                            redactor.redact(parsed.thread()),
                            redactor.redact(parsed.message()));
                        String message = abbreviateLog(event.message());
                        forwardPluginLog(id, event, message);
                        logStore.append(id, event.level(), event.logger(), event.thread(), message);
                    }
                } catch (IOException ignored) {}
            });
            Worker worker = new Worker(id, process, json, redactor, logStore,
                    files.grantVersion(id), fullAccess, manifest.version(), packageDigest, sandbox, jobHandle);
            worker.startReader();
            // Host lifecycle events use the same effective threshold as forwarded Worker events.
            log.info("Plugin {} worker started (pid={})", id, process.pid());
            logStore.append(id, "INFO", "Worker started (pid=" + process.pid() + ")");
            String isolation = "sandbox=" + launch.backend().id()
                    + ", network=" + (fullAccess || allowNetwork ? "allowed" : "isolated")
                    + (AiConfigServiceHeadless.isUnsandboxedPluginsEnabled() ? ", unsandboxedOverride=true" : "");
            log.info("Plugin {} worker isolation: {}", id, isolation);
            logStore.append(id, launch.sandboxed() ? "INFO" : "WARN", isolation);
            return worker;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start plugin backend: " + redactor.redact(e.getMessage()), e);
        }
    }

    /**
     * The fixed v2 worker launch command: the current JVM's {@code java} executable running the
     * plugin's {@code backend/worker.jar} with AWT headless mode forced on. Schema v2 dropped the
     * manifest's {@code backend.command} / {@code backend.protocol} — the worker is always a
     * JSON-RPC 2.0 JAR at this conventional path.
     */
    private static List<String> fixedWorkerCommand(Path root) {
        String javaExe = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        return List.of(javaExe, "-Djava.awt.headless=true", "-jar",
                root.resolve("backend/worker.jar").toString());
    }

    private static List<String> withJavaTempDirectory(List<String> command, Path tempDirectory) {
        if (command.isEmpty()) return command;
        String executable = Path.of(command.getFirst()).getFileName().toString();
        if (!List.of("java", "java.exe").contains(executable.toLowerCase(java.util.Locale.ROOT))) {
            return command;
        }
        List<String> configured = new ArrayList<>(command);
        configured.add(1, "-Djava.io.tmpdir=" + tempDirectory.toAbsolutePath().normalize());
        return configured;
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    /**
     * Read one line from {@code input}, throwing {@link IOException} if the line exceeds
     * {@code maxBytes} of UTF-8-encoded content before a newline (P1-6). A runaway worker that emits
     * an enormous single line would otherwise exhaust host memory. Counting raw bytes avoids the
     * surrogate-pair and malformed-input ambiguity of counting decoded UTF-16 chars. On overrun the
     * caller's read loop propagates the {@link IOException},
     * which triggers {@code failAll()} so every pending caller learns the worker is dead and the
     * worker process is torn down. {@code channel} labels the overrun message ("stdout"/"stderr").
     */
    static String readBoundedLine(InputStream input, int maxBytes, String channel) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return decodeLine(line);
            if (line.size() >= maxBytes) {
                throw new IOException("Plugin " + channel + " frame exceeded " + maxBytes
                    + " byte limit without a newline; worker torn down to bound memory");
            }
            line.write(value);
        }
        return line.size() == 0 ? null : decodeLine(line);
    }

    private static String decodeLine(ByteArrayOutputStream line) {
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') length--;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    static void ensureFrameWithinLimit(String frame, int maxBytes, String channel) throws IOException {
        int bytes = frame.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw new IOException("Plugin " + channel + " frame exceeded " + maxBytes
                + " byte limit (was " + bytes + ")");
        }
    }

    /** Destroy a process tree (root + descendants) best-effort; never throws. (P1-7 cleanup path.) */
    private static void safeDestroy(Process process) {
        if (process == null) return;
        try { process.descendants().forEach(p -> { try { p.destroyForcibly(); } catch (RuntimeException ignored) {} }); } catch (RuntimeException ignored) {}
        try { process.destroyForcibly(); } catch (RuntimeException ignored) {}
    }

    /**
     * Positive allowlist of host environment names a plugin Worker may inherit. Kept deliberately
     * small: only the essentials for a JVM/native runtime to launch and format output. Secrets are
     * excluded by construction (they are not named here), not by pattern-matching their names.
     * Compared per a case-insensitive prefix/equals match so platform variants (e.g. {@code LC_ALL},
     * {@code ComSpec}) are caught without enumerating every locale.
     */
    private static final java.util.Set<String> ENV_ALLOWLIST_EXACT = java.util.Set.of(
            // OS path + runtime lookups
            "PATH", "PATHEXT", "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PROGRAMFILES",
            // Java — JAVA_HOME only. JAVA_OPTS / JAVA_TOOL_OPTIONS are deliberately EXCLUDED: a new
            // JVM auto-interprets them, so they could smuggle in -javaagent, system properties, or
            // host-sensitive configuration into every plugin Worker. (P0-1 review follow-up.)
            "JAVA_HOME",
            // Locale / timezone
            "LANG", "LC_ALL", "LC_CTYPE", "LC_COLLATE", "LC_MESSAGES", "LC_TIME", "LC_NUMERIC",
            "LC_MONETARY", "LANGUAGE", "TZ",
            // POSIX user/home (needed by some toolchains; secrets live under env, not these names)
            "USER", "LOGNAME", "SHELL", "TERM",
            // Windows essentials
            "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP",
            // macOS GUI / X11 display identifiers. XAUTHORITY is deliberately EXCLUDED: it can name
            // a credential file, and X11 apps resolve it from the default ~/.Xauthority when absent.
            // HOME is retained: many JDK/toolchains read it for caches/preferences and redirecting it
            // to a plugin-owned home is a larger change tracked separately.
            "HOME", "DISPLAY");

    /** Prefix families (case-insensitive) that broaden the exact set without admitting secrets. */
    private static final java.util.List<String> ENV_ALLOWLIST_PREFIXES = java.util.List.of("LC_");

    /**
     * Replace {@code env} in place with only the allowlisted host variables. Clears everything the
     * {@link ProcessBuilder} copied from {@code System.getenv()}, then restores the allowlisted
     * entries that actually exist in the host environment. {@code TMPDIR} is handled by the caller
     * (it is redirected to the plugin-owned temp directory), so it is intentionally not restored
     * here even when present on POSIX.
     */
    private static void applyEnvironmentAllowlist(java.util.Map<String, String> env) {
        applyEnvironmentAllowlist(env, System.getenv());
    }

    /**
     * Test seam for {@link #applyEnvironmentAllowlist(java.util.Map)}: filters an arbitrary host
     * environment instead of {@code System.getenv()}. Same logic, observable in isolation so the
     * allowlist contract (drop everything not named; no name-pattern denylist) is unit-testable.
     */
    static void applyEnvironmentAllowlist(java.util.Map<String, String> env, java.util.Map<String, String> host) {
        env.clear();
        for (String name : ENV_ALLOWLIST_EXACT) {
            String value = host.get(name);
            if (value != null) env.put(name, value);
        }
        // LC_* family: cover any LC_FOO locale category without an exhaustive literal list.
        for (java.util.Map.Entry<String, String> entry : host.entrySet()) {
            String upper = entry.getKey().toUpperCase(Locale.ROOT);
            for (String prefix : ENV_ALLOWLIST_PREFIXES) {
                if (upper.startsWith(prefix) && !env.containsKey(entry.getKey())) {
                    env.put(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= 240) return value;
        return value.substring(0, 237) + "...";
    }

    private static String abbreviateLog(String value) {
        if (value == null || value.length() <= 16_384) return value;
        return value.substring(0, 16_381) + "...";
    }

    private static void forwardPluginLog(String pluginId, PluginLogLineParser.Parsed event,
            String message) {
        // safeLoggerName is reused as the MDC value so the on-disk filename (plugin-<that>.log)
        // matches the logger name AND keeps manifest-supplied ids out of the filesystem
        // (path-traversal / weird chars collapse to underscores). Truncation keeps it within
        // OS filename limits.
        String safePluginId = safeLoggerName(pluginId);
        String source = event.logger() == null || event.logger().isBlank()
            ? "stderr" : safeLoggerName(event.logger());
        Logger pluginLogger = LoggerFactory.getLogger("plugin." + safePluginId + "." + source);
        String rendered = event.thread() == null || event.thread().isBlank()
            ? message : "[" + event.thread() + "] " + message;
        MDC.put("pluginId", safePluginId);
        try {
            switch (event.level()) {
                case "TRACE" -> pluginLogger.trace(rendered);
                case "DEBUG" -> pluginLogger.debug(rendered);
                case "WARN" -> pluginLogger.warn(rendered);
                case "ERROR" -> pluginLogger.error(rendered);
                default -> pluginLogger.info(rendered);
            }
        } finally {
            MDC.remove("pluginId");
        }
    }

    private static String safeLoggerName(String value) {
        if (value == null || value.isBlank()) return "worker";
        String safe = value.replaceAll("[^A-Za-z0-9_$.-]", "_");
        return safe.length() <= 160 ? safe : safe.substring(0, 160);
    }

    /**
     * One-line, leak-safe summary of the raw invoke params for operation logs: lists the param
     * KEYS only, never the values. A value (a password, mail body, parsed absolute path) can be a
     * secret, and truncating it is not a safe redaction — so it is never stringified here. Returns
     * an empty string for {@code null}/empty params so the log line stays clean.
     */
    private static String paramKeys(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        return " keys=" + params.keySet();
    }

    @PreDestroy public void close() { workers.values().forEach(Worker::close); workers.clear(); }

    /** Push a log-level change to every running SDK Worker without restarting it. */
    public void updateLogLevel(String level) {
        workers.values().stream().filter(Worker::alive).forEach(worker ->
            worker.sendNotification(PluginWorkerProtocol.SET_LOG_LEVEL_METHOD, Map.of("level", level)));
    }

    /**
     * One Worker per plugin process. Concurrency model:
     * <ul>
     *   <li>writer lock — serialises stdin writes only (sub-millisecond critical section).</li>
     *   <li>reader virtual-thread — resident for the worker's lifetime, demultiplexes stdout
     *       lines by JSON-RPC {@code id} into {@link #pending} futures.</li>
     *   <li>{@link #failAll(String)} — drains {@link #pending} on EOF / IO error / close, so every
     *       blocked caller learns about the failure rather than hanging until its own timeout.</li>
     * </ul>
     */
    static final class Worker {
        private final String pluginId;
        private final Process process;
        private final ObjectMapper json;
        private final SensitiveValueRedactor redactor;
        private final PluginLogStore logStore;
        private final BufferedWriter writer;
        private final InputStream reader;
        private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private volatile boolean closed = false;
        private final long grantVersion;
        private final boolean fullAccess;
        /** Manifest version the Worker was started against (P0-6: cache must not reuse across upgrades). */
        private final String manifestVersion;
        /** Content digest of the installed package the Worker was started against (P0-6). */
        private final String packageDigest;
        private final ProcessSandbox sandbox;
        /** Windows Job Object handle (0 on non-Windows / NONE backend → terminate/close no-op). */
        private final long jobHandle;
        /** Spawn time — feeds the crash-loop guard's "died too young" heuristic. */
        private final long startedAtNanos = System.nanoTime();

        Worker(String pluginId, Process process, ObjectMapper json, SensitiveValueRedactor redactor,
                PluginLogStore logStore, long grantVersion, boolean fullAccess,
                String manifestVersion, String packageDigest, ProcessSandbox sandbox, long jobHandle) {
            this.pluginId = pluginId;
            this.process = process;
            this.json = json;
            this.redactor = redactor;
            this.logStore = logStore;
            this.grantVersion = grantVersion;
            this.fullAccess = fullAccess;
            this.manifestVersion = manifestVersion;
            this.packageDigest = packageDigest;
            this.sandbox = sandbox;
            this.jobHandle = jobHandle;
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedInputStream(process.getInputStream());
        }

        long grantVersion() { return grantVersion; }
        boolean fullAccess() { return fullAccess; }
        String manifestVersion() { return manifestVersion; }
        String packageDigest() { return packageDigest; }
        long startedAtNanos() { return startedAtNanos; }

        /** Test-only: number of in-flight/unreaped invoke slots. Must return to 0 after every
         *  successful response so successful invokes do not leak entries (P0-5 regression guard). */
        int pendingCountForTest() { return pending.size(); }

        /** Whether an in-flight call with this JSON-RPC id is still pending (cancel grace poll). */
        boolean hasPending(String id) { return id != null && pending.containsKey(id); }

        /** Start the resident reader thread that routes stdout lines by JSON-RPC id. */
        void startReader() {
            Thread.ofVirtual().name("plugin-" + pluginId + "-stdout").start(() -> {
                try {
                    for (String line; (line = readBoundedLine(reader, MAX_FRAME_BYTES, "stdout")) != null;) {
                        JsonNode response;
                        try {
                            response = json.readTree(line);
                        } catch (IOException invalidJson) {
                            String safe = abbreviate(redactor.redact(line));
                            log.warn("Plugin {} emitted non-JSON stdout: {}", pluginId, safe);
                            // A non-JSON line on the protocol pipe usually means the worker wrote a
                            // log/print to stdout instead of stderr — surface it so it's diagnosable.
                            logStore.append(pluginId, "WARN", "non-JSON stdout: " + safe);
                            continue;
                        }
                        String responseId = response.path("id").asText("");
                        // Atomically remove+claim the slot as the response arrives, so a successful
                        // invoke does not leak its (id, future) entry for the worker's lifetime.
                        // The invoke() catch arms also remove on their own timeout/error paths
                        // (when no response ever arrives); a no-op there after this remove is safe.
                        CompletableFuture<JsonNode> slot = responseId.isEmpty() ? null : pending.remove(responseId);
                        if (slot == null) {
                            String idText = redactor.redact(response.path("id").asText("<missing>"));
                            log.warn("Plugin {} returned response for unexpected id={}", pluginId, idText);
                            logStore.append(pluginId, "WARN", "unexpected response id=" + idText);
                            continue;
                        }
                        if (response.hasNonNull("error")) {
                            // T2-04 bullets 6 & 7: map the worker's semantic error.data.code to a
                            // typed exception so the HTTP layer can distinguish authorization denial
                            // (403) and cancellation from a generic failure. Unknown codes fall back
                            // to IllegalArgumentException (400). The message is always redacted — a
                            // worker may echo request values (passwords, paths) in its error text.
                            JsonNode errorNode = response.path("error");
                            String message = redactor.redact(
                                errorNode.path("message").asText("Plugin call failed"));
                            String dataCode = errorNode.path("data").path("code").asText(null);
                            slot.completeExceptionally(mapWorkerError(dataCode, message));
                        } else {
                            slot.complete(response.get("result"));
                        }
                    }
                    failAll("Plugin backend stopped unexpectedly: " + pluginId);
                } catch (IOException e) {
                    failAll("Plugin RPC failed: " + redactor.redact(e.getMessage()));
                }
            });
        }

        /**
         * Map a worker error's semantic {@code data.code} (the T2-03 label) to a typed exception.
         * PERMISSION_DENIED → 403, CANCELLED → a clean cancellation; anything else is a generic
         * IllegalArgumentException (400). None of these extend IllegalStateException, so the worker
         * is NOT torn down — these are business-level outcomes, not channel/transport failures.
         */
        private static RuntimeException mapWorkerError(String dataCode, String message) {
            if (dataCode == null) return new IllegalArgumentException(message);
            return switch (dataCode) {
                case "PERMISSION_DENIED" -> new PluginPermissionDeniedException(message);
                case "CANCELLED" -> new PluginCancelledException(message);
                default -> new IllegalArgumentException(message);
            };
        }

        /** Invoke a method with an explicit JSON-RPC id, returning the raw result node. Blocks up to
         *  {@code timeoutSeconds}. The {@code locale} rides in the reserved top-level {@code _fengyu}
         *  envelope (not in {@code params}), so it cannot collide with a plugin method's own input. */
        Object invoke(String id, String method, Map<String, Object> params, long timeoutSeconds, String locale) {
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            try {
                // Frame shape: standard JSON-RPC 2.0 plus a reserved, host-owned `_fengyu` envelope
                // carrying the request locale. The locale rides here — NOT in params — so a plugin
                // method that declares its own `locale` input field receives the caller's value
                // verbatim. The `_fengyu` key is omitted entirely when no locale is set, so legacy
                // and locale-less callers see an unchanged frame.
                Map<String, Object> frame = new java.util.LinkedHashMap<>();
                frame.put("jsonrpc", "2.0");
                frame.put("id", id);
                frame.put("method", method);
                frame.put("params", params);
                if (locale != null && !locale.isBlank()) {
                    frame.put("_fengyu", Map.of("locale", locale));
                }
                String wire = json.writeValueAsString(frame);
                ensureOutboundFrameSize(wire);
                // DEBUG-only wire trace: log the id + method but NOT the frame. The frame carries the
                // full params JSON (caller-supplied passwords, mail bodies, parsed paths); the env
                // redactor only knows env-borne secrets, so a param value would leak verbatim here.
                log.debug("Plugin {} \u2192 {} id={}", pluginId, method, id);
                // Writer lock: keep concurrent callers from interleaving frames on stdin.
                synchronized (this) {
                    writer.write(wire);
                    writer.newLine();
                    writer.flush();
                }
                JsonNode result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                log.debug("Plugin {} \u2190 {} id={} ok", pluginId, method, id);
                return json.treeToValue(result, Object.class);
            } catch (java.util.concurrent.TimeoutException e) {
                pending.remove(id, future);
                // Convert the blocking-timeout into the IllegalStateException the caller already
                // treats as "tear down this worker". The outer invoke() will then kill+restart.
                throw new IllegalStateException("Plugin call timed out after " + timeoutSeconds + " seconds: " + pluginId);
            } catch (InterruptedException e) {
                pending.remove(id, future);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Plugin call was interrupted", e);
            } catch (ExecutionException e) {
                pending.remove(id, future);
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Plugin call failed", cause);
            } catch (IOException e) {
                pending.remove(id, future);
                // An update may close the Worker after the outer gate releases its lock but while
                // this call is writing. Surface that documented in-flight teardown state instead
                // of leaking platform-specific pipe text ("Stream Closed"/"Broken pipe").
                if (closed) {
                    throw new IllegalStateException("Plugin worker tearing down: " + pluginId, e);
                }
                throw new IllegalStateException("Plugin RPC failed: " + redactor.redact(e.getMessage()), e);
            }
        }

        void sendNotification(String method, Map<String, Object> params) {
            if (!alive()) return;
            try {
                String frame = json.writeValueAsString(
                    Map.of("jsonrpc", "2.0", "method", method, "params", params));
                ensureOutboundFrameSize(frame);
                synchronized (this) {
                    writer.write(frame);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                log.warn("Plugin {} control notification failed: {}", pluginId,
                    redactor.redact(e.getMessage()));
            }
        }

        private static void ensureOutboundFrameSize(String frame) throws IOException {
            ensureFrameWithinLimit(frame, MAX_FRAME_BYTES, "stdin");
        }

        /** Drain every pending caller with {@code reason}; idempotent. */
        void failAll(String reason) {
            if (closed) return;
            closed = true;
            String snapshot = redactor.redact(reason);
            logStore.append(pluginId, "WARN", "Worker stopped: " + snapshot);
            pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException(snapshot)));
            pending.clear();
        }

        boolean alive() { return process.isAlive() && !closed; }

        void close() {
            failAll("Plugin worker closed: " + pluginId);
            // Primary on Windows: terminate the entire job tree via the kernel (TerminateJobObject).
            // ProcessHandle.descendants() is unreliable on Windows, so when a job handle is present
            // (WINDOWS_JOB backend) it is the authoritative tree-kill. No-op when jobHandle == 0
            // (macOS sandbox-exec / Linux bwrap / NONE backend). Wrapped so a cleanup failure cannot
            // mask the destroy path below.
            if (jobHandle != 0L) {
                try { sandbox.terminateJob(jobHandle); } catch (RuntimeException ignored) {}
            }
            // Destroy the worker JVM itself (graceful SIGTERM, then SIGKILL after 2s). On macOS the
            // sandbox-exec wrapper execve's into java, so process.destroy() hits the worker JVM
            // directly (verified). On Linux bwrap --die-with-parent already reaps the worker when
            // the host dies, but this path still covers an explicit PluginProcessManager.stop().
            process.destroy();
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            // Backstop: a worker may spawn grandchildren (e.g. offlinepython's pip subprocess) that
            // are NOT reaped when the worker JVM dies. Walk the worker's descendant tree and force-
            // kill any survivors so they cannot leak (a leaked grandchild can hold file handles or
            // child DB locks of its own). On Windows this is now secondary (the Job already
            // terminated the tree); on mac/linux (jobHandle==0) it remains the primary grandchild
            // reaper. Idempotent: already-dead descendants are skipped.
            killDescendants(process.descendants());
            // Close the job handle last. With KILL_ON_JOB_CLOSE the kernel kills any survivors on
            // the final close; this also releases the kernel handle. No-op when jobHandle == 0.
            if (jobHandle != 0L) {
                try { sandbox.closeJobHandle(jobHandle); } catch (RuntimeException ignored) {}
            }
            // Close the host end of the worker's stdin/stdout pipes. The process is reaped by now, so
            // the resident stdout reader thread has normally seen EOF and exited; closing the reader
            // here is also what unblocks it if an escaped grandchild held the pipe's write-end (which
            // would otherwise pin the FD and the reader thread for the host's lifetime). Best-effort:
            // a close failure never masks the teardown that already completed above. Workers are torn
            // down on every call timeout/transport error, so without this the FD churn accumulates.
            try { writer.close(); } catch (Exception ignored) {}
            try { reader.close(); } catch (Exception ignored) {}
        }

        /** Recursively destroy a process tree, leaves-first to avoid orphaning. */
        private static void killDescendants(java.util.stream.Stream<ProcessHandle> descendants) {
            descendants.forEach(child -> {
                killDescendants(child.children());
                if (child.isAlive()) child.destroyForcibly();
            });
        }
    }
}
