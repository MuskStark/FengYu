import { createWriteStream, promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { randomUUID } from 'node:crypto';
/** Temporary snapshots used by the browser-based development host. */
export class DevFileStore {
    refs;
    root;
    uploads = new Map();
    constructor(refs) {
        this.refs = refs;
    }
    async uploadFile(name, source) {
        const safeName = safeBaseName(name);
        const directory = await this.makeDirectory('input');
        const target = path.join(directory, safeName);
        await pipeline(source, createWriteStream(target, { flags: 'wx' }));
        const stat = await fs.stat(target);
        return this.refs.register(target, 'file', 'read', { name: safeName, size: stat.size });
    }
    async startDirectory(name, access) {
        const normalizedAccess = access === 'read-write' ? 'read-write' : 'read';
        const uploadId = randomUUID();
        const root = await this.makeDirectory('directory');
        this.uploads.set(uploadId, {
            root,
            name: safeBaseName(name || 'selected-directory'),
            access: normalizedAccess,
            size: 0,
        });
        return uploadId;
    }
    async uploadDirectoryFile(uploadId, relativePath, source) {
        const upload = this.requireUpload(uploadId);
        const normalized = safeRelativePath(relativePath);
        const target = path.resolve(upload.root, ...normalized.split('/'));
        if (!isWithin(upload.root, target))
            throw new Error('file path must be a safe relative path');
        await fs.mkdir(path.dirname(target), { recursive: true });
        await pipeline(source, createWriteStream(target, { flags: 'wx' }));
        upload.size += (await fs.stat(target)).size;
    }
    finishDirectory(uploadId) {
        const upload = this.requireUpload(uploadId);
        this.uploads.delete(uploadId);
        return this.refs.register(upload.root, 'directory', upload.access, {
            name: upload.name,
            size: upload.size,
        });
    }
    async outputDirectory() {
        const directory = await this.makeDirectory('output');
        return this.refs.register(directory, 'directory', 'write', { name: 'plugin-output' });
    }
    async exportZip(refId) {
        const directory = this.refs.pathOf(refId);
        if (!directory)
            throw new Error('unknown file reference');
        const stat = await fs.stat(directory);
        if (!stat.isDirectory())
            throw new Error('output reference is not a directory');
        return createStoredZip(directory);
    }
    async cleanup() {
        if (!this.root)
            return;
        const root = this.root;
        this.root = undefined;
        this.uploads.clear();
        await fs.rm(root, { recursive: true, force: true });
    }
    async makeDirectory(label) {
        if (!this.root)
            this.root = await fs.mkdtemp(path.join(os.tmpdir(), 'fengyu-plugin-dev-'));
        const directory = path.join(this.root, `${label}-${randomUUID()}`);
        await fs.mkdir(directory, { recursive: true });
        return directory;
    }
    requireUpload(uploadId) {
        const upload = this.uploads.get(uploadId);
        if (!upload)
            throw new Error('unknown directory upload');
        return upload;
    }
}
function safeBaseName(value) {
    const name = path.basename(value.trim());
    if (!name || name === '.' || name === '..' || name.includes('\0'))
        throw new Error('invalid file name');
    return name;
}
function safeRelativePath(value) {
    const normalized = value.replaceAll('\\', '/');
    if (!normalized || normalized.startsWith('/') || /^[A-Za-z]:\//.test(normalized)) {
        throw new Error('file path must be a safe relative path');
    }
    const parts = normalized.split('/');
    if (parts.some(part => !part || part === '.' || part === '..' || part.includes('\0'))) {
        throw new Error('file path must be a safe relative path');
    }
    return parts.join('/');
}
function isWithin(root, target) {
    const relative = path.relative(root, target);
    return relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}
async function createStoredZip(directory) {
    const files = await listFiles(directory);
    const localParts = [];
    const centralParts = [];
    let offset = 0;
    for (const file of files) {
        const name = Buffer.from(file.relative.replaceAll(path.sep, '/'), 'utf8');
        const data = await fs.readFile(file.absolute);
        const checksum = crc32(data);
        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4);
        local.writeUInt16LE(0x0800, 6);
        local.writeUInt32LE(checksum, 14);
        local.writeUInt32LE(data.length, 18);
        local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(name.length, 26);
        localParts.push(local, name, data);
        const central = Buffer.alloc(46);
        central.writeUInt32LE(0x02014b50, 0);
        central.writeUInt16LE(20, 4);
        central.writeUInt16LE(20, 6);
        central.writeUInt16LE(0x0800, 8);
        central.writeUInt32LE(checksum, 16);
        central.writeUInt32LE(data.length, 20);
        central.writeUInt32LE(data.length, 24);
        central.writeUInt16LE(name.length, 28);
        central.writeUInt32LE(offset, 42);
        centralParts.push(central, name);
        offset += local.length + name.length + data.length;
    }
    const centralSize = centralParts.reduce((total, part) => total + part.length, 0);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(files.length, 8);
    end.writeUInt16LE(files.length, 10);
    end.writeUInt32LE(centralSize, 12);
    end.writeUInt32LE(offset, 16);
    return Buffer.concat([...localParts, ...centralParts, end]);
}
async function listFiles(root) {
    const found = [];
    const visit = async (directory) => {
        for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
            const absolute = path.join(directory, entry.name);
            if (entry.isDirectory())
                await visit(absolute);
            else if (entry.isFile())
                found.push({ absolute, relative: path.relative(root, absolute) });
        }
    };
    await visit(root);
    return found.sort((a, b) => a.relative.localeCompare(b.relative));
}
const crcTable = Array.from({ length: 256 }, (_, index) => {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1)
        value = (value & 1) ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
    return value >>> 0;
});
function crc32(data) {
    let value = 0xffffffff;
    for (const byte of data)
        value = crcTable[(value ^ byte) & 0xff] ^ (value >>> 8);
    return (value ^ 0xffffffff) >>> 0;
}
//# sourceMappingURL=dev-files.js.map