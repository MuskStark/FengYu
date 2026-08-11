/**
 * Canonical iframe <-> host protocol contract.
 *
 * This module is deliberately free of browser side effects so the host shell, the
 * development simulator, and plugin UIs can all consume the same constants and
 * message types.
 */
export declare const PROTOCOL_VERSION: "3.0.0";
export declare const PLUGIN_MESSAGE_SOURCE: "fengyu-plugin";
export declare const HOST_MESSAGE_SOURCE: "fengyu-host";
export declare const HOST_METHODS: {
    readonly ready: "host.ready";
    readonly invoke: "rpc.invoke";
    readonly notify: "notify";
    readonly filesOpen: "files.open";
    readonly filesInputDirectory: "files.inputDirectory";
    readonly filesWorkspaceDirectory: "files.workspaceDirectory";
    readonly filesOutputDirectory: "files.outputDirectory";
    readonly filesExport: "files.export";
};
export type HostMethod = typeof HOST_METHODS[keyof typeof HOST_METHODS];
export type Theme = 'dark' | 'light';
export interface HostEnvironment {
    protocolVersion: string;
    /** Id of the plugin the host loaded into this iframe. */
    pluginId: string;
    /** Version declared in the loaded plugin's manifest. */
    pluginVersion: string;
    /** Permissions granted to the plugin by the host runtime (e.g. "files.read"). */
    permissions: string[];
    theme: Theme;
    locale: string;
    platform: 'web' | 'desktop';
    capabilities: HostMethod[];
}
export interface HostError {
    code: 'ABORTED' | 'CANCELLED' | 'INCOMPATIBLE_PROTOCOL' | 'INVALID_REQUEST' | 'PERMISSION_DENIED' | 'TIMEOUT' | 'HOST_ERROR';
    message: string;
    details?: unknown;
}
export interface PluginRequestMessage {
    source: typeof PLUGIN_MESSAGE_SOURCE;
    type: 'request';
    protocolVersion: typeof PROTOCOL_VERSION;
    id: string;
    method: HostMethod;
    params?: Record<string, unknown>;
}
export interface PluginCancelMessage {
    source: typeof PLUGIN_MESSAGE_SOURCE;
    type: 'cancel';
    protocolVersion: typeof PROTOCOL_VERSION;
    id: string;
}
export type PluginMessage = PluginRequestMessage | PluginCancelMessage;
export interface HostResponseMessage {
    source: typeof HOST_MESSAGE_SOURCE;
    type: 'response';
    protocolVersion: typeof PROTOCOL_VERSION;
    id: string;
    result?: unknown;
    error?: HostError;
}
export interface HostEventMessage {
    source: typeof HOST_MESSAGE_SOURCE;
    type: 'event';
    protocolVersion: typeof PROTOCOL_VERSION;
    event: 'environment';
    data: Partial<HostEnvironment>;
}
export type HostMessage = HostResponseMessage | HostEventMessage;
export declare const HOST_CAPABILITIES: HostMethod[];
export declare function isPluginMessage(value: unknown): value is PluginMessage;
export declare function isHostMessage(value: unknown): value is HostMessage;
export declare function hostError(error: unknown, code?: HostError['code']): HostError;
