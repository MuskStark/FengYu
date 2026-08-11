/**
 * Canonical iframe <-> host protocol contract.
 *
 * This module is deliberately free of browser side effects so the host shell, the
 * development simulator, and plugin UIs can all consume the same constants and
 * message types.
 */
export const PROTOCOL_VERSION = '3.0.0';
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
export function isPluginMessage(value) {
    if (!value || typeof value !== 'object')
        return false;
    const message = value;
    return message.source === PLUGIN_MESSAGE_SOURCE
        && (message.type === 'request' || message.type === 'cancel')
        && message.protocolVersion === PROTOCOL_VERSION
        && typeof message.id === 'string';
}
export function isHostMessage(value) {
    if (!value || typeof value !== 'object')
        return false;
    const message = value;
    return message.source === HOST_MESSAGE_SOURCE
        && (message.type === 'response' || message.type === 'event')
        && message.protocolVersion === PROTOCOL_VERSION;
}
export function hostError(error, code = 'HOST_ERROR') {
    return {
        code,
        message: error instanceof Error ? error.message : String(error),
    };
}
