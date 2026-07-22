import net from 'node:net';
export function createWorkerClient(options) {
    const defaultTimeout = options.timeoutMs ?? 30_000;
    return {
        invoke(method, params, callOptions = {}) {
            return invokeOnce({
                host: options.host,
                port: options.port,
                method,
                params,
                timeoutMs: callOptions.timeoutMs ?? defaultTimeout,
            });
        },
    };
}
function invokeOnce(args) {
    return new Promise((resolve, reject) => {
        const id = Math.random().toString(36).slice(2);
        let settled = false;
        let timer;
        const socket = net.createConnection({ host: args.host, port: args.port });
        const cleanup = () => {
            if (timer)
                clearTimeout(timer);
            socket.removeAllListeners();
            socket.destroy();
        };
        const fail = (error) => {
            if (settled)
                return;
            settled = true;
            cleanup();
            reject(error);
        };
        const done = (value) => {
            if (settled)
                return;
            settled = true;
            cleanup();
            resolve(value);
        };
        timer = setTimeout(() => fail(new Error(`dev worker request timed out: ${args.method}`)), args.timeoutMs);
        socket.once('error', (err) => fail(new Error(`dev worker connect failed (${args.host}:${args.port}): ${err.message}. Start PluginDevMain in your IDE, or set mockWorker:true to stub responses.`)));
        socket.once('connect', () => {
            socket.write(JSON.stringify({ jsonrpc: '2.0', id, method: args.method, params: args.params }) + '\n');
        });
        let buffer = '';
        socket.on('data', (chunk) => {
            buffer += chunk.toString('utf8');
            const newline = buffer.indexOf('\n');
            if (newline === -1)
                return;
            const line = buffer.slice(0, newline);
            buffer = buffer.slice(newline + 1);
            let message;
            try {
                message = JSON.parse(line);
            }
            catch {
                fail(new Error('dev worker returned a non-JSON line: ' + line.slice(0, 200)));
                return;
            }
            if (message.id !== id)
                return; // not our response; ignore (shouldn't happen on a fresh socket)
            if (message.error)
                fail(new Error(`worker error ${message.error.code}: ${message.error.message}`));
            else
                done(message.result);
        });
    });
}
/**
 * Probe whether a dev server is reachable. Resolves true on a successful TCP connect,
 * false otherwise. Cheap (opens + immediately closes a socket), used to decide whether to
 * report a precise connection error on the first request.
 */
export function probeWorker(host, port, timeoutMs = 500) {
    return new Promise((resolve) => {
        const socket = net.createConnection({ host, port });
        const timer = setTimeout(() => {
            socket.destroy();
            resolve(false);
        }, timeoutMs);
        socket.once('connect', () => {
            clearTimeout(timer);
            socket.destroy();
            resolve(true);
        });
        socket.once('error', () => {
            clearTimeout(timer);
            socket.destroy();
            resolve(false);
        });
    });
}
//# sourceMappingURL=worker-client.js.map