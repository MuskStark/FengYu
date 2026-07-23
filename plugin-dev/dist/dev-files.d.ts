import type { IncomingMessage } from 'node:http';
import { FileRefRegistry, type DevFileRef } from './file-refs.js';
/** Temporary snapshots used by the browser-based development host. */
export declare class DevFileStore {
    private readonly refs;
    private root?;
    private readonly uploads;
    constructor(refs: FileRefRegistry);
    uploadFile(name: string, source: IncomingMessage): Promise<DevFileRef>;
    startDirectory(name: string, access: unknown): Promise<string>;
    uploadDirectoryFile(uploadId: string, relativePath: string, source: IncomingMessage): Promise<void>;
    finishDirectory(uploadId: string): DevFileRef;
    outputDirectory(): Promise<DevFileRef>;
    exportZip(refId: string): Promise<Buffer>;
    cleanup(): Promise<void>;
    private makeDirectory;
    private requireUpload;
}
