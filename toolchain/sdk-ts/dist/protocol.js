/**
 * Canonical iframe <-> host protocol contract.
 *
 * This module is deliberately free of browser side effects so the host shell, the
 * development simulator, and plugin UIs can all consume the same constants and
 * message types.
 */
export const PROTOCOL_VERSION = '4.0.0';
/**
 * Wire versions the bridge accepts alongside {@link PROTOCOL_VERSION}. Protocol 3.0.0
 * (app 4.0.x-era plugin UIs) is byte-for-byte wire-identical to 4.0.0 — the 3→4 bump
 * (toolchain 2.1.x) only renamed the constant to match the unified version line — so
 * already-installed plugin UIs keep bridging. Hosts answer each plugin in the version
 * that plugin speaks (per-frame negotiated echo), never a mix.
 */
export const LEGACY_PROTOCOL_VERSIONS = ['3.0.0'];
export const SUPPORTED_PROTOCOL_VERSIONS = [PROTOCOL_VERSION, ...LEGACY_PROTOCOL_VERSIONS];
export function isSupportedProtocolVersion(value) {
    return typeof value === 'string' && SUPPORTED_PROTOCOL_VERSIONS.includes(value);
}
export const PLUGIN_MESSAGE_SOURCE = 'fengyu-plugin';
export const HOST_MESSAGE_SOURCE = 'fengyu-host';
export const HOST_METHODS = {
    ready: 'host.ready',
    invoke: 'rpc.invoke',
    notify: 'notify',
    filesOpen: 'files.open',
    filesInputDirectory: 'files.inputDirectory',
    filesWorkspaceDirectory: 'files.workspaceDirectory',
    filesOutputDirectory: 'files.outputDirectory',
    filesExport: 'files.export',
};
export const HOST_CAPABILITIES = Object.values(HOST_METHODS);
/**
 * The structural half of {@link isPluginMessage} WITHOUT the version gate. A host uses
 * this to recognize a plugin whose wire version it does not support, so it can answer
 * the handshake with an INCOMPATIBLE_PROTOCOL error stamped in the plugin's own version
 * (which the plugin's gate then accepts) instead of dropping the message and leaving
 * the plugin to hang until its ready() timeout.
 */
export function isPluginMessageLoose(value) {
    if (!value || typeof value !== 'object')
        return false;
    const message = value;
    return message.source === PLUGIN_MESSAGE_SOURCE
        && (message.type === 'request' || message.type === 'cancel')
        && typeof message.id === 'string';
}
export function isPluginMessage(value) {
    return isPluginMessageLoose(value)
        && isSupportedProtocolVersion(value.protocolVersion);
}
export function isHostMessage(value) {
    if (!value || typeof value !== 'object')
        return false;
    const message = value;
    return message.source === HOST_MESSAGE_SOURCE
        && (message.type === 'response' || message.type === 'event')
        && isSupportedProtocolVersion(message.protocolVersion);
}
export function hostError(error, code = 'HOST_ERROR') {
    return {
        code,
        message: error instanceof Error ? error.message : String(error),
    };
}
