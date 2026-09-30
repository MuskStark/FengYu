package fan.summer.fengyu.ai.codemode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The in-process code-mode runtime (the port of codex {@code code-mode-runtime/} onto
 * GraalJS): every {@code exec} call is one CELL — a dedicated virtual thread holding a
 * FRESH polyglot {@link Context} (shared {@link Engine} for the compile cache) created
 * with no IO, no native access, no host threads/processes, no environment, and
 * {@code HostAccess.EXPLICIT} with a single exported bridge object — the engine only
 * exposes what the bootstrap installs. The cell thread runs a command loop
 * ({@code ToolResponse / Terminate}, mirroring codex's {@code RuntimeCommand} cycle)
 * and is the ONLY thread that touches polyglot {@link Value}s: nested tool promises
 * resolve on it, from results the host posts.
 *
 * <p>Lifetime rules (codex semantics): {@code exit()} is the success sentinel; pending
 * {@code setTimeout} callbacks do NOT extend a cell's life; the first yield window ends
 * on the deadline while the cell keeps running; termination is the double assurance of
 * the Terminate command plus {@code context.close(true)}. Session semantics: the
 * session-scoped {@code store}/{@code load} map is committed only by SUCCESSFUL cells
 * (the atomic-commit rule); cells isolate by construction (fresh context each).</p>
 */
public final class GraalJsCodeModeSession {

    private static final Logger log = LoggerFactory.getLogger(GraalJsCodeModeSession.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final AtomicLong CELL_SEQ = new AtomicLong();
    /** The exit() sentinel — thrown as an error, treated as SUCCESS (codex semantics). */
    static final String EXIT_SENTINEL = "__fengyu_code_mode_exit__";

    /** Shared across cells of every session: parse/compile cache, no engine state. */
    private static final Engine ENGINE = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false").build();

    /** Session-scoped store; entries committed only by successful cells. */
    private final Map<String, Object> store = new ConcurrentHashMap<>();

    // ── host bridge (the ONLY host surface the engine can reach) ────────────────────────

    /**
     * The exported bridge: every method the script's bootstrap may call on the host.
     * {@code HostAccess.EXPLICIT} + {@code @Export} means nothing else — no
     * {@code java.*}, no reflection, no file/network — is reachable from JS.
     */
    public static final class HostBridge {
        CodeModeCell cell;

        @org.graalvm.polyglot.HostAccess.Export
        public void requestTool(String id, String name, String argumentsJson) {
            cell.onToolCall(id, name, argumentsJson);
        }

        @org.graalvm.polyglot.HostAccess.Export
        public void notify(String text) {
            cell.onNotify(text);
        }

        @org.graalvm.polyglot.HostAccess.Export
        public long scheduleTimer(long delayMs) {
            return cell.scheduleTimer(delayMs);
        }

        @org.graalvm.polyglot.HostAccess.Export
        public void cancelTimer(long timerId) {
            cell.cancelTimer(timerId);
        }

        @org.graalvm.polyglot.HostAccess.Export
        public void yieldControl() {
            cell.yieldRequested = true;
        }
    }

    // ── cell commands (the codex RuntimeCommand cycle, timer-lean V1) ──────────────────

    private sealed interface CellCommand permits ToolResponseCommand, TerminateCommand {}

    private record ToolResponseCommand(CodeModeProtocol.NestedToolResult result) implements CellCommand {}

    private record TerminateCommand() implements CellCommand {}

    // ── the cell ────────────────────────────────────────────────────────────────────────

    private final class CodeModeCell implements Runnable {

        final String cellId = "cell-" + CELL_SEQ.incrementAndGet();
        final CodeModeProtocol.ExecuteRequest request;
        final Function<CodeModeProtocol.NestedToolCall,
                CompletableFuture<CodeModeProtocol.NestedToolResult>> host;

        final LinkedBlockingQueue<CellCommand> commands = new LinkedBlockingQueue<>();
        /** Pending setTimeout callbacks: id -> absolute deadline; they never extend life. */
        final Map<Long, Long> timerDeadlines = new ConcurrentHashMap<>();
        final AtomicLong timerSeq = new AtomicLong();
        final AtomicBoolean terminated = new AtomicBoolean();
        final AtomicBoolean terminatedResponse = new AtomicBoolean();
        volatile boolean yieldRequested;

        /** Model-visible responses in order: yields first, exactly one terminal last. */
        final LinkedBlockingQueue<CodeModeProtocol.RuntimeResponse> responses = new LinkedBlockingQueue<>();

        Context context;
        volatile boolean exited;    // exit() sentinel seen → success

        CodeModeCell(CodeModeProtocol.ExecuteRequest request,
                Function<CodeModeProtocol.NestedToolCall,
                        CompletableFuture<CodeModeProtocol.NestedToolResult>> host) {
            this.request = request;
            this.host = host;
        }

        @Override public void run() {
            HostBridge bridge = new HostBridge();
            bridge.cell = this;
            long yieldDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    request.yieldTimeMs() == null
                            ? CodeModeToolDescription.DEFAULT_YIELD_TIME_MS
                            : request.yieldTimeMs());
            boolean yielded = false;
            try {
                context = Context.newBuilder("js")
                        .engine(ENGINE)
                        .allowHostAccess(org.graalvm.polyglot.HostAccess.EXPLICIT)
                        .allowIO(IOAccess.NONE)
                        .allowNativeAccess(false)
                        .allowCreateThread(false)
                        .allowCreateProcess(false)
                        .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                        .build();
                context.getBindings("js").putMember("__host", bridge);
                // The seed MUST precede the bootstrap eval — the bootstrap reads it while
                // evaluating, so injecting afterwards left every `load()` undefined (P1-3).
                context.getBindings("js").putMember("__sessionStoreJson", sessionStoreJson());
                context.eval(Source.newBuilder("js", BOOTSTRAP, "bootstrap.js").build());
                installToolsObject();

                Source module = Source.newBuilder("js",
                        new StringReader(request.source()), "exec_main.mjs").build();
                try {
                    Value moduleResult = context.eval(module);
                    // A top-level-await module settles ASYNCHRONOUSLY: its completion is a
                    // promise. Watching it (instead of appending a trailing statement that
                    // a rejection would skip) is how exit()-after-await and throw-after-
                    // await reach a terminal state at all (P1-2).
                    if (isThenable(moduleResult)) {
                        callJsValue("__watchModule", moduleResult);
                    } else {
                        callJsFn("__finishModule");
                    }
                } catch (PolyglotException e) {
                    if (terminated.get()) {
                        // The forced context close surfaced here as a cancellation.
                        addTerminal(new CodeModeProtocol.Terminated(cellId));
                        return;
                    }
                    finish(isExit(e) ? null : isImportRejection(e)
                            ? "Unsupported import in exec" : scriptError(e));
                    return;
                }

                while (true) {
                    if (terminated.get()) {
                        addTerminal(new CodeModeProtocol.Terminated(cellId));
                        return;
                    }
                    fireDueTimers();
                    CellCommand command = commands.poll(5, TimeUnit.MILLISECONDS);
                    if (command instanceof ToolResponseCommand tool) {
                        callJs("__resolveTool", tool.result().id(),
                                tool.result().success(), tool.result().output());
                        continue;
                    }
                    if (command instanceof TerminateCommand) {
                        addTerminal(new CodeModeProtocol.Terminated(cellId));
                        return;
                    }
                    ModuleState state = moduleState();
                    if (state.done()) {
                        // Timers do not extend life; a settled module is the end.
                        finish(state.error());
                        return;
                    }
                    if (state.pendingTools()) {
                        // A no-op JS eval reaches an engine safepoint: pending promise
                        // reactions and the top-level await make progress.
                        safeEval("0");
                        if (!yielded && System.nanoTime() >= yieldDeadline) {
                            responses.add(new CodeModeProtocol.Yielded(
                                    cellId, snapshotOutput()));
                            yielded = true;
                        }
                        continue;
                    }
                    // Module not yet settled and nothing to pump: brief wait, re-check.
                    if (!yielded && System.nanoTime() >= yieldDeadline) {
                        responses.add(new CodeModeProtocol.Yielded(cellId, snapshotOutput()));
                        yielded = true;
                    }
                }
            } catch (Throwable t) {
                if (terminated.get()) {
                    addTerminal(new CodeModeProtocol.Terminated(cellId));
                } else {
                    finish(t instanceof PolyglotException pe && isExit(pe) ? null : scriptError(t));
                }
            } finally {
                closeContext();
            }
        }

        /** The module settlement the pump keys on: done + rejection text (if any). */
        private record ModuleState(boolean done, String error, boolean pendingTools) {}

        private ModuleState moduleState() {
            try {
                Value state = context.getBindings("js").getMember("__moduleState").execute();
                Value done = memberOrNull(state, "done");
                Value error = memberOrNull(state, "error");
                Value pending = memberOrNull(state, "pendingTools");
                String errorText = error == null ? null : error.asString();
                return new ModuleState(done != null && done.asBoolean(),
                        errorText == null || errorText.isBlank()
                                || errorText.contains(EXIT_SENTINEL) ? null
                                : errorText.replaceFirst("^Error: ", ""),
                        pending != null && pending.asBoolean());
            } catch (Exception e) {
                return new ModuleState(true, null, false);
            }
        }

        private static boolean isThenable(Value value) {
            try {
                return value != null && !value.isNull() && value.hasMember("then");
            } catch (Exception e) {
                return false;
            }
        }

        private void callJsValue(String function, Value argument) {
            try {
                context.getBindings("js").getMember(function).execute(argument);
            } catch (Exception e) {
                log.debug("cell {} js call {} failed: {}", cellId, function, e.toString());
            }
        }

        // ── terminal ───────────────────────────────────────────────────────────────

        private void finish(String error) {
            if (error == null) commitSessionStore();
            List<CodeModeProtocol.ContentItem> output = drainOutput();
            responses.add(new CodeModeProtocol.Result(cellId, output, error));
        }

        private List<CodeModeProtocol.ContentItem> snapshotOutput() {
            return parseItems(callJsFn("__snapshotOutput"));
        }

        private List<CodeModeProtocol.ContentItem> drainOutput() {
            return parseItems(callJsFn("__drainOutput"));
        }

        private List<CodeModeProtocol.ContentItem> parseItems(Value array) {
            List<CodeModeProtocol.ContentItem> parsed = new ArrayList<>();
            if (array == null || !array.hasArrayElements()) return parsed;
            for (int i = 0; i < array.getArraySize(); i++) {
                Value item = array.getArrayElement(i);
                Value text = memberOrNull(item, "text");
                if (text != null) {
                    parsed.add(new CodeModeProtocol.Text(text.asString()));
                    continue;
                }
                Value image = memberOrNull(item, "image");
                if (image != null) {
                    Value detail = memberOrNull(item, "detail");
                    parsed.add(new CodeModeProtocol.Image(image.asString(),
                            detail == null ? null : detail.asString()));
                }
            }
            return parsed;
        }

        // ── host callbacks (from JS via the bridge — all non-blocking) ──────────────

        void onToolCall(String id, String name, String argumentsJson) {
            CompletableFuture<CodeModeProtocol.NestedToolResult> future =
                    host.apply(new CodeModeProtocol.NestedToolCall(id, name, argumentsJson));
            future.whenComplete((result, failure) -> commands.add(new ToolResponseCommand(
                    failure != null
                            ? new CodeModeProtocol.NestedToolResult(id,
                                    "nested tool error: " + failure, false)
                            : result)));
        }

        void onNotify(String text) {
            callJs("__notify", text);
        }

        long scheduleTimer(long delayMs) {
            long id = timerSeq.incrementAndGet();
            timerDeadlines.put(id, System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(Math.max(0, delayMs)));
            return id;
        }

        void cancelTimer(long timerId) {
            timerDeadlines.remove(timerId);
        }

        private void fireDueTimers() {
            long now = System.nanoTime();
            for (Map.Entry<Long, Long> timer : timerDeadlines.entrySet()) {
                if (timer.getValue() <= now) {
                    timerDeadlines.remove(timer.getKey());
                    callJs("__runTimer", timer.getKey());
                }
            }
        }

        private boolean hasPendingWork() {
            try {
                Value pending = context.getBindings("js").getMember("__hasPendingWork");
                return pending.execute().asBoolean();
            } catch (Exception e) {
                return false;
            }
        }

        private void installToolsObject() {
            StringBuilder declarations = new StringBuilder("const __tools = {\n");
            for (CodeModeProtocol.ToolDefinition tool : request.enabledTools()) {
                declarations.append("  ")
                        .append(CodeModeToolDescription.identifier(tool.name()))
                        .append(": __makeTool(").append(json(tool.name())).append("),\n");
            }
            declarations.append("};\n")
                    .append("var tools = new Proxy(__tools, {\n")
                    .append("  get(target, name) { if (name in target) return target[name];\n")
                    .append("    throw new Error(\"No tool named '\" + String(name)")
                    .append(" + \"' is available in this exec\"); }\n")
                    .append("});\n")
                    .append("var ALL_TOOLS = ").append(allToolsJson()).append(";\n");
            safeEval(declarations.toString());
        }

        private String allToolsJson() {
            try {
                List<Map<String, String>> meta = new ArrayList<>();
                for (CodeModeProtocol.ToolDefinition tool : request.enabledTools()) {
                    meta.add(Map.of("name", tool.name(),
                            "description", tool.description() == null ? "" : tool.description()));
                }
                return JSON.writeValueAsString(meta);
            } catch (Exception e) {
                return "[]";
            }
        }

        private String sessionStoreJson() {
            try {
                return JSON.writeValueAsString(store);
            } catch (Exception e) {
                return "{}";
            }
        }

        private void commitSessionStore() {
            try {
                String json = context.getBindings("js")
                        .getMember("__committedStore").execute().asString();
                Map<?, ?> written = JSON.readValue(json, Map.class);
                // MERGE, never replace: only the keys this cell explicitly wrote change —
                // earlier cells' committed entries survive (P1-3).
                written.forEach((key, value) -> store.put(String.valueOf(key), value));
            } catch (Exception e) {
                log.debug("session-store commit skipped: {}", e.toString());
            }
        }

        private void callJs(String function, Object... args) {
            try {
                context.eval("js", "0");   // safepoint first: never re-enter mid-reaction
                var bindings = context.getBindings("js");
                if (bindings.getMember(function) == null) return;
                bindings.getMember(function).execute(args);
            } catch (Exception e) {
                log.debug("cell {} js call {} failed: {}", cellId, function, e.toString());
            }
        }

        private Value callJsFn(String function) {
            try {
                return context.getBindings("js").getMember(function).execute();
            } catch (Exception e) {
                return null;
            }
        }

        private void safeEval(String code) {
            try {
                context.eval("js", code);
            } catch (PolyglotException e) {
                if (isExit(e)) {
                    exited = true;
                    return;
                }
                throw e;
            }
        }

        /**
         * The forced cancel half of termination. Safe from any thread: polyglot
         * supports closing a context while its thread is evaluating — the in-flight
         * eval throws and the cell's catch turns that into the Terminated response.
         */
        void forceCloseContext() {
            Context current = context;
            if (current != null) {
                try {
                    current.close(true);
                } catch (Exception ignored) {
                }
            }
        }

        void closeContext() {
            try {
                if (context != null) context.close(terminated.get());
            } catch (Exception ignored) {
            }
        }

        /** Exactly one terminal response ever leaves the cell. */
        private void addTerminal(CodeModeProtocol.RuntimeResponse response) {
            if (terminatedResponse.compareAndSet(false, true)) {
                responses.add(response);
            }
        }

        private boolean isExit(PolyglotException e) {
            return e.getMessage() != null && e.getMessage().contains(EXIT_SENTINEL);
        }

        private boolean isImportRejection(PolyglotException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            return message.contains("import")
                    && (message.contains("unsupported") || message.contains("not supported")
                            || message.contains("module") || message.contains("loader"));
        }

        private String scriptError(Throwable t) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            if (message.contains(EXIT_SENTINEL)) return null;
            return message.replaceFirst("^Error: ", "");
        }
    }

    private static Value memberOrNull(Value item, String name) {
        try {
            Value member = item.getMember(name);
            return member == null || member.isNull() ? null : member;
        } catch (Exception e) {
            return null;
        }
    }

    private static String json(String raw) {
        try {
            return JSON.writeValueAsString(raw == null ? "" : raw);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    // ── session API (the host-facing contract) ─────────────────────────────────────────

    /** The host's handle on one running cell: first response, then the stream. */
    public static final class CellHandle {
        final CodeModeCell cell;
        final CompletableFuture<CodeModeProtocol.RuntimeResponse> first;

        CellHandle(CodeModeCell cell, CompletableFuture<CodeModeProtocol.RuntimeResponse> first) {
            this.cell = cell;
            this.first = first;
        }

        public String cellId() { return cell.cellId; }

        public CompletableFuture<CodeModeProtocol.RuntimeResponse> first() { return first; }

        /** Subsequent responses (next yields, then exactly one terminal). */
        public CodeModeProtocol.RuntimeResponse poll(long windowMs) throws InterruptedException {
            return cell.responses.poll(windowMs, TimeUnit.MILLISECONDS);
        }

        /**
         * Terminate — the codex double assurance, for real: the Terminate command for a
         * pump-reachable cell, PLUS the cross-thread {@code context.close(true)} forced
         * cancel that kills even a blocked {@code eval} (a pure CPU loop). Idempotent.
         */
        public void terminate() {
            if (cell.terminated.compareAndSet(false, true)) {
                cell.commands.add(new TerminateCommand());
                cell.forceCloseContext();
            }
        }
    }

    /**
     * Executes one cell. The handle's first future completes with the FIRST response —
     * a {@link CodeModeProtocol.Yielded} while the cell keeps running, or the terminal
     * {@link CodeModeProtocol.Result}/{@link CodeModeProtocol.Terminated} when it
     * finished inside the first yield window (the codex initialResponse semantics).
     */
    public CellHandle execute(
            CodeModeProtocol.ExecuteRequest request,
            Function<CodeModeProtocol.NestedToolCall,
                    CompletableFuture<CodeModeProtocol.NestedToolResult>> host) {
        CodeModeCell cell = new CodeModeCell(request, host);
        Thread.ofVirtual().name("code-mode-" + cell.cellId).start(cell);
        CompletableFuture<CodeModeProtocol.RuntimeResponse> first = new CompletableFuture<>();
        long yieldMs = request.yieldTimeMs() == null
                ? CodeModeToolDescription.DEFAULT_YIELD_TIME_MS : request.yieldTimeMs();
        Thread.ofVirtual().start(() -> {
            try {
                // The FIRST response arrives either from the cell (it finished or its
                // pump yielded) or from this deadline — a blocked eval (a pure CPU
                // loop) still honors the yield window with whatever exists so far.
                CodeModeProtocol.RuntimeResponse response =
                        cell.responses.poll(yieldMs, TimeUnit.MILLISECONDS);
                first.complete(response != null ? response
                        : new CodeModeProtocol.Yielded(cell.cellId, List.of()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                first.completeExceptionally(e);
            }
        });
        return new CellHandle(cell, first);
    }

    /** The session store snapshot (tests + tooling). */
    public Map<String, Object> storeSnapshot() {
        return Map.copyOf(store);
    }

    // ── the JS bootstrap ────────────────────────────────────────────────────────────────

    /**
     * Everything the script sees beyond the ECMAScript core: the {@code tools} proxy,
     * the content helpers, the session store, timers, notify, exit, and the plumbing
     * the cell thread drives. No console, no fetch, no Node builtins.
     */
    static final String BOOTSTRAP = """
            // codex globals.rs: remove the engine extras the sandbox must not expose —
            // plus the GraalJS-specific ones the enumeration test caught: print/printErr
            // (output channels), loadWithNewGlobal (script loading), Graal (engine
            // object), arguments (module artifact).
            for (const __removed of ['console', 'Atomics', 'SharedArrayBuffer', 'WebAssembly',
                'print', 'printErr', 'loadWithNewGlobal', 'Graal', 'arguments']) {
              try { delete globalThis[__removed]; } catch (e) { /* already absent */ }
            }
            const __pendingTools = new Map();
            const __output = [];
            const __session = (typeof __sessionStoreJson === 'string')
              ? (() => { try { return JSON.parse(__sessionStoreJson); } catch (e) { return {}; } })()
              : {};
            let __moduleDone = false;
            let __exited = false;

            const __makeTool = (name) => (args) => new Promise((resolve, reject) => {
              const id = 'tool-' + (__toolSeq++) + '-' + Math.random().toString(36).slice(2, 8);
              __pendingTools.set(id, { resolve, reject });
              let payload = args;
              if (payload !== null && payload !== undefined
                  && typeof payload !== 'object' && typeof payload !== 'string') {
                payload = String(payload);
              }
              __host.requestTool(id, name,
                typeof payload === 'string' ? JSON.stringify({ input: payload })
                  : JSON.stringify(payload ?? {}));
            });
            let __toolSeq = 1;

            globalThis.__resolveTool = (id, ok, payload) => {
              const pending = __pendingTools.get(id);
              if (!pending) return;
              __pendingTools.delete(id);
              if (ok) {
                let value = payload;
                if (typeof value === 'string') { try { value = JSON.parse(value); } catch (e) { /* keep */ } }
                pending.resolve(value);
              } else {
                pending.reject(new Error(String(payload)));
              }
            };
            let __moduleError = null;
            globalThis.__finishModule = () => { __moduleDone = true; };
            globalThis.__watchModule = (p) => {
              Promise.resolve(p).then(
                () => { __moduleDone = true; },
                (e) => {
                  const text = (e && e.message) ? e.message : String(e);
                  if (String(text).indexOf('__fengyu_code_mode_exit__') >= 0) {
                    __exited = true;          // exit() after an await is still SUCCESS
                  } else {
                    __moduleError = text;
                  }
                  __moduleDone = true;
                });
            };
            globalThis.__moduleState = () => ({
              done: __moduleDone,
              error: __moduleError,
              pendingTools: __pendingTools.size > 0,
            });

            globalThis.text = (value) => {
              let text;
              if (value === null || value === undefined) text = String(value);
              else if (typeof value === 'object') {
                try { text = JSON.stringify(value); } catch (e) { text = String(value); }
              } else text = String(value);
              __output.push({ text });
            };
            globalThis.image = (dataUri, detail) => {
              const uri = String(dataUri ?? '');
              if (!uri.startsWith('data:')) {
                throw new Error('image() accepts base64 data: URIs only — remote URLs are not supported');
              }
              __output.push({ image: uri, detail: detail ?? null });
            };
            const __writtenKeys = new Set();
            globalThis.store = (key, value) => {
              const k = String(key);
              __session[k] = value;
              __writtenKeys.add(k);
            };
            globalThis.load = (key) => __session[String(key)];
            globalThis.notify = (value) => {
              const text = typeof value === 'object'
                ? (() => { try { return JSON.stringify(value); } catch (e) { return String(value); } })()
                : String(value);
              __host.notify(text);
            };
            const __timers = new Map();
            globalThis.setTimeout = (callback, delayMs) => {
              const id = __host.scheduleTimer(Number(delayMs) || 0);
              __timers.set(id, callback);
              return id;
            };
            globalThis.clearTimeout = (id) => {
              __timers.delete(Number(id));
              __host.cancelTimer(Number(id));
            };
            globalThis.__runTimer = (id) => {
              const cb = __timers.get(id);
              if (cb) { __timers.delete(id); cb(); }
            };
            globalThis.exit = () => { __exited = true; throw new Error('__fengyu_code_mode_exit__'); };
            globalThis.yield_control = () => { __host.yieldControl(); };
            globalThis.__notify = (text) => { __output.push({ text: String(text) }); };
            globalThis.__committedStore = () => JSON.stringify(
              Object.fromEntries([...__writtenKeys].map(k => [k, __session[k]])));
            globalThis.__snapshotOutput = () => __output.slice();
            globalThis.__drainOutput = () => { const out = __output.slice(); __output.length = 0; return out; };
            """;
}
