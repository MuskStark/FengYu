/**
 * Canonical iframe <-> host protocol contract.
 *
 * This module is deliberately free of browser side effects so the host shell, the
 * development simulator, and plugin UIs can all consume the same constants and
 * message types.
 */
export declare const PROTOCOL_VERSION: "4.0.0";
/**
 * Wire versions the bridge accepts alongside {@link PROTOCOL_VERSION}. Protocol 3.0.0
 * (app 4.0.x-era plugin UIs) is byte-for-byte wire-identical to 4.0.0 — the 3→4 bump
 * (toolchain 2.1.x) only renamed the constant to match the unified version line — so
 * already-installed plugin UIs keep bridging. Hosts answer each plugin in the version
 * that plugin speaks (per-frame negotiated echo), never a mix.
 */
export declare const LEGACY_PROTOCOL_VERSIONS: readonly ["3.0.0"];
export declare const SUPPORTED_PROTOCOL_VERSIONS: readonly string[];
export declare function isSupportedProtocolVersion(value: unknown): value is string;
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
    /** Wire version the sender speaks; within SUPPORTED_PROTOCOL_VERSIONS when accepted. */
    protocolVersion: string;
    id: string;
    method: HostMethod;
    params?: Record<string, unknown>;
}
export interface PluginCancelMessage {
    source: typeof PLUGIN_MESSAGE_SOURCE;
    type: 'cancel';
    protocolVersion: string;
    id: string;
}
export type PluginMessage = PluginRequestMessage | PluginCancelMessage;
export interface HostResponseMessage {
    source: typeof HOST_MESSAGE_SOURCE;
    type: 'response';
    protocolVersion: string;
    id: string;
    result?: unknown;
    error?: HostError;
}
export interface HostEventMessage {
    source: typeof HOST_MESSAGE_SOURCE;
    type: 'event';
    protocolVersion: string;
    event: 'environment';
    data: Partial<HostEnvironment>;
}
export type HostMessage = HostResponseMessage | HostEventMessage;
export declare const HOST_CAPABILITIES: HostMethod[];
/**
 * The structural half of {@link isPluginMessage} WITHOUT the version gate. A host uses
 * this to recognize a plugin whose wire version it does not support, so it can answer
 * the handshake with an INCOMPATIBLE_PROTOCOL error stamped in the plugin's own version
 * (which the plugin's gate then accepts) instead of dropping the message and leaving
 * the plugin to hang until its ready() timeout.
 */
export declare function isPluginMessageLoose(value: unknown): value is PluginMessage;
export declare function isPluginMessage(value: unknown): value is PluginMessage;
export declare function isHostMessage(value: unknown): value is HostMessage;
export declare function hostError(error: unknown, code?: HostError['code']): HostError;
