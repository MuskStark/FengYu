/**
 * Typed RPC surface for the Excel Splitter UI.
 *
 * The generated {@link createPluginRpc} client (from `manifest.json` `rpc.methods`) replaces the
 * v1 string-based `client.invoke('method', ...)` calls. The UI builds one `rpc` instance from its
 * host-provided `FengYuClient` and calls typed methods, passing an `AbortSignal` (via
 * `InvokeOptions`) so an in-flight call is transport-cancelled when the wizard step is abandoned.
 *
 * Path inputs (`sourceFile`, `outputDir`) are typed as `string` in the generated Input types because
 * they describe the WORKER contract: the host resolves a UI-supplied FileRef to an absolute path
 * before the worker receives it. UI call sites therefore cast a FileRef `as unknown as string` when
 * invoking — the runtime value sent is the FileRef, which the host resolves (same bridge the
 * offlinepython UI uses).
 */
export { createPluginRpc } from './generated/fengyu-rpc'
