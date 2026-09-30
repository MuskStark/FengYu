export class FileRefRegistry {
    refs = new Map();
    register(path, kind, access, metadata = {}) {
        if (!path.trim())
            throw new Error('path is required');
        const id = 'ref_' + (this.refs.size + 1) + '_' + Date.now().toString(36);
        this.refs.set(id, path);
        const name = metadata.name ?? path.split(/[\\/]/).filter(Boolean).pop() ?? path;
        return { id, name, kind, access, size: metadata.size ?? 0 };
    }
    /** Resolve a single ref id to its path, or undefined if unregistered. */
    pathOf(id) {
        return this.refs.get(id);
    }
    /**
     * Recursively rewrite FileRef params to their registered filesystem path. A value is treated
     * as a FileRef when it is a plain object with an `id` starting `"ref_"` and a non-null `kind`.
     * Arrays and nested objects are recursed; primitives are returned unchanged.
     */
    resolve(value) {
        return this.resolveValue(value);
    }
    resolveValue(value) {
        if (value && typeof value === 'object' && !Array.isArray(value)) {
            const candidate = value;
            if (typeof candidate.id === 'string' && candidate.id.startsWith('ref_') && candidate.kind != null) {
                const path = this.refs.get(candidate.id);
                if (path !== undefined)
                    return path;
            }
            const out = {};
            for (const [k, v] of Object.entries(value))
                out[k] = this.resolveValue(v);
            return out;
        }
        if (Array.isArray(value))
            return value.map((item) => this.resolveValue(item));
        return value;
    }
}
//# sourceMappingURL=file-refs.js.map