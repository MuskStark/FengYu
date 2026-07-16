import http from 'node:http'
import net from 'node:net'
import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { readManifest } from './manifest.mjs'
import { detectProject } from './project.mjs'
import { resolveCommand, spawnSpec, runCommand } from './commands.mjs'
import { startWorker } from './worker.mjs'

export { detectProject }

const HOST = '127.0.0.1'

/**
 * Start the FengYu dev host for a plugin project.
 *
 * Dispatches by detected project kind:
 *  - `declared` (with a worker): start the real Java JSON-RPC worker, expose
 *    `POST /__rpc` so the simulator UI's `rpc.invoke` reaches the worker, and
 *    rebuild/restart the worker on Java source changes.
 *  - `vue-vite`: spawn the Vite dev server; the simulator points at it (HMR).
 *  - `static`: serve project files + SSE reload + a mock-backed simulator.
 *
 * UI-only declared projects (no worker) fall through to the vue-vite path.
 *
 * @param {string} root - project root
 * @param {number} [port=4173] - simulator host port
 * @param {{ run?: Function, uiPort?: number, open?: boolean, startWorker?: Function }} [options]
 * @returns {Promise<{ close: () => Promise<void>, port: number, kind: string }>}
 */
export async function dev(root, port = 4173, options = {}) {
  const dir = path.resolve(root)
  const project = await detectProject(dir)
  if (project.kind === 'declared' && project.config?.worker) {
    return devDeclaredWorker(project, port, options)
  }
  if (project.kind === 'vue-vite') {
    return devVue(dir, port, options)
  }
  return devStatic(dir, port, options)
}

/**
 * Declared worker dev: build the worker JAR if missing, start it, watch Java
 * sources, and serve a simulator that forwards rpc.invoke to POST /__rpc.
 */
async function devDeclaredWorker(project, port, { run = runCommand, startWorkerImpl = startWorker, uiPort = 5173, open = true } = {}) {
  const cfg = project.config
  const workerRoot = cfg.worker.root
  const artifact = cfg.worker.artifact
  let workerClient
  let rebuilding = null

  // Build the worker artifact first if it does not exist.
  if (!fsSync.existsSync(artifact)) {
    await runWorkerBuild(cfg.worker, run)
  }
  workerClient = await startWorkerImpl({ jar: artifact, cwd: workerRoot, onStderr: (line) => console.error('[worker]', line) })

  // Watch Java sources (excluding target) and rebuild + restart on change.
  let rebuildTimer = null
  const watcher = watchDebounced(workerRoot, ['target'], 300, async () => {
    if (rebuilding) return
    rebuilding = rebuildWorker(cfg.worker, run, startWorkerImpl, workerRoot, artifact, workerClient)
      .then((next) => { if (next) workerClient = next })
      .catch((e) => console.error('[worker] rebuild failed:', e.message))
      .finally(() => { rebuilding = null })
  })

  const manifest = await safeReadManifest(project.root)
  const server = createRpcSimulatorServer({ port, manifest, invoke: (method, params) => workerClient ? workerClient.invoke(method, params) : Promise.reject(new Error('worker rebuilding')) })
  console.log(`FengYu dev host (declared worker): http://${HOST}:${port}/__fengyu`)
  return {
    port,
    kind: 'declared',
    close: async () => {
      if (rebuildTimer) clearTimeout(rebuildTimer)
      await watcher.close?.().catch(() => {})
      await workerClient?.close().catch(() => {})
      await stopServer(server)
    },
  }
}

async function runWorkerBuild(worker, run) {
  const resolved = await resolveCommand(worker.build, worker.root)
  const spec = spawnSpec(resolved)
  await run(spec.command, spec.args, { cwd: worker.root, env: resolved.env, shell: spec.shell })
}

async function rebuildWorker(worker, run, startWorkerImpl, workerRoot, artifact, oldClient) {
  // Build with tests skipped for a fast reload.
  const buildCmd = worker.build[0] === 'maven' ? ['maven', ...worker.build.slice(1), '-DskipTests'] : worker.build
  // Avoid duplicate -DskipTests.
  const dedup = buildCmd[0] === 'maven' && buildCmd.includes('-DskipTests') ? buildCmd : buildCmd
  await runWorkerBuild({ ...worker, build: dedup }, run)
  const next = await startWorkerImpl({ jar: artifact, cwd: workerRoot })
  await oldClient?.close().catch(() => {})
  return next
}

/**
 * Vue/Vite dev: run the project's Vite dev server, wait for its TCP port, then
 * serve the simulator page whose iframe points at it. No SSE (Vite does HMR).
 */
async function devVue(root, port, { run = defaultRun, uiPort = 5173, open = true } = {}) {
  const child = await run('npm', ['run', 'dev', '--', '--host', HOST, '--port', String(uiPort)], { cwd: root })
  const isProcess = child && typeof child.kill === 'function'
  await waitForPort(uiPort, isProcess ? 15000 : 0).catch(() => {
    console.warn(`FengYu: Vite dev server on port ${uiPort} not reachable yet; serving simulator anyway.`)
  })
  const manifest = await safeReadManifest(root)
  const iframeSrc = `http://${HOST}:${uiPort}`
  const server = createSimulatorServer({ iframeSrc, manifest, sse: false, port })
  console.log(`FengYu dev host (vue-vite): http://${HOST}:${port}/__fengyu${open ? ` → ${iframeSrc}` : ''}`)
  return {
    port,
    kind: 'vue-vite',
    close: async () => {
      await stopServer(server)
      if (child?.kill) child.kill('SIGTERM')
    },
  }
}

/**
 * Static dev: serve project files + an SSE reload watcher + the simulator page
 * whose iframe points at the manifest UI entry.
 */
async function devStatic(root, port, { open = true } = {}) {
  const manifest = await safeReadManifest(root)
  const iframeSrc = '/' + normalizeEntry(manifest?.ui?.entry ?? 'ui/index.html')
  const clients = new Set()
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${HOST}`)
    if (url.pathname === '/__events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
      clients.add(res)
      req.on('close', () => clients.delete(res))
      return
    }
    if (url.pathname === '/__fengyu') {
      res.setHeader('Content-Type', 'text/html')
      res.end(simulatorHtml({ iframeSrc, manifest, sse: true }))
      return
    }
    let file = path.resolve(root, '.' + decodeURIComponent(url.pathname))
    if (url.pathname === '/') file = path.join(root, 'manifest.json')
    if (!file.startsWith(root + path.sep)) { res.writeHead(403); res.end(); return }
    try {
      const data = await fs.readFile(file)
      res.setHeader('Content-Type', type(file))
      res.end(data)
    } catch {
      res.writeHead(404); res.end('Not found')
    }
  })
  let stamp = await fingerprint(root)
  const timer = setInterval(async () => {
    const next = await fingerprint(root)
    if (next !== stamp) { stamp = next; clients.forEach((c) => c.write('data: reload\n\n')) }
  }, 500)
  await listen(server, port)
  server.on('close', () => { clearInterval(timer); clients.clear() })
  console.log(`FengYu dev host (static): http://${HOST}:${port}/__fengyu`)
  return {
    port,
    kind: 'static',
    close: async () => { await stopServer(server) },
  }
}

/** Simulator server that forwards rpc.invoke to a worker via POST /__rpc. */
function createRpcSimulatorServer({ port, manifest, invoke }) {
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${HOST}`)
    if (url.pathname === '/__rpc' && req.method === 'POST') {
      let body = ''
      for await (const chunk of req) body += chunk
      let message
      try { message = JSON.parse(body) } catch { res.writeHead(400); res.end(JSON.stringify({ error: 'invalid json' })); return }
      try {
        const result = await invoke(message.method, message.params ?? {})
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ id: message.id, result }))
      } catch (e) {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ id: message.id, error: e.message }))
      }
      return
    }
    if (url.pathname === '/__fengyu') {
      res.setHeader('Content-Type', 'text/html')
      res.end(rpcSimulatorHtml({ manifest }))
      return
    }
    res.writeHead(404); res.end('Not found')
  })
  server.listen(port, HOST)
  return server
}

/** Build the simulator HTTP server for the Vue path (no SSE, no file serving). */
function createSimulatorServer({ iframeSrc, manifest, sse, port }) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${HOST}`)
    if (url.pathname === '/__fengyu') {
      res.setHeader('Content-Type', 'text/html')
      res.end(simulatorHtml({ iframeSrc, manifest, sse }))
      return
    }
    res.writeHead(404); res.end('Not found')
  })
  server.listen(port, HOST)
  return server
}

function defaultRun(command, args, options = {}) {
  const child = spawn(command, args, {
    cwd: options.cwd,
    stdio: options.stdio ?? 'inherit',
    shell: process.platform === 'win32',
  })
  child.on('error', () => { /* surfaced via waitForPort timeout */ })
  return Promise.resolve(child)
}

/* -------------------------------------------------------------------------- */
/* Simulator HTML — production-shaped SDK message handlers                    */
/* -------------------------------------------------------------------------- */

function simulatorHtml({ iframeSrc, manifest, sse }) {
  const environment = {
    sdkVersion: '1.0.0',
    theme: 'dark',
    locale: 'en',
    platform: 'web',
    capabilities: ['rpc.invoke', 'files.open', 'notify'],
  }
  const manifestJson = manifest ? JSON.stringify(manifest).replace(/</g, '\\u003c') : '{}'
  return `<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0;display:grid;grid-template-columns:1fr 360px;height:100vh;font:13px system-ui;background:#111;color:#eee}iframe{width:100%;height:100%;border:0}aside{padding:12px;overflow:auto;border-left:1px solid #444}pre{white-space:pre-wrap}.controls{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px}button{background:#333;color:#eee;border:1px solid #555;border-radius:4px;padding:4px 8px;cursor:pointer}button.on{background:#2d6a2d}</style></head><body><iframe id=f name=f sandbox="allow-scripts allow-forms allow-downloads allow-same-origin"></iframe><aside><h3>RPC Inspector</h3><div class=controls><button id=t-theme>theme: dark</button><button id=t-locale>locale: en</button><button id=t-deny>deny: off</button></div><pre id=log></pre></aside><script type=module>
const env=${JSON.stringify(environment)};
const iframeSrc=${JSON.stringify(iframeSrc)};
let deny=false;
const f=document.querySelector('#f');
const log=document.querySelector('#log');
f.src=iframeSrc;
function note(msg){log.textContent=JSON.stringify(msg,null,2)+'\\n\\n'+log.textContent}
function envEvent(){f.contentWindow.postMessage({source:'fengyu-host',type:'event',event:'environment',data:{theme:env.theme,locale:env.locale}},'*')}
addEventListener('message',e=>{
  const q=e.data;
  if(q?.source!=='fengyu-plugin')return;
  note(q);
  if(q.type==='cancel')return;
  if(deny){f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,error:'permission denied'},'*');return}
  let result=true;
  if(q.method==='host.ready')result={sdkVersion:env.sdkVersion,theme:env.theme,locale:env.locale,platform:env.platform,capabilities:env.capabilities};
  else if(q.method==='rpc.invoke')result={success:true,devMock:true,method:q.params.method,params:q.params.params};
  else if(q.method==='notify')result=true;
  else if(q.method==='files.open')result={id:'file-1',name:'sample.txt',kind:'file',access:'read',size:42};
  else if(q.method==='files.inputDirectory')result={id:'dir-in',name:'input',kind:'directory',access:'read',size:0};
  else if(q.method==='files.outputDirectory')result={id:'dir-out',name:'output',kind:'directory',access:'write',size:0};
  f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,result},'*');
});
document.querySelector('#t-theme').onclick=()=>{env.theme=env.theme==='dark'?'light':'dark';document.querySelector('#t-theme').textContent='theme: '+env.theme;envEvent()};
document.querySelector('#t-locale').onclick=()=>{env.locale=env.locale==='en'?'zh':'en';document.querySelector('#t-locale').textContent='locale: '+env.locale;envEvent()};
document.querySelector('#t-deny').onclick=(b)=>{deny=!deny;b.target.textContent='deny: '+(deny?'on':'off');b.target.classList.toggle('on',deny)};
${sse ? "new EventSource('/__events').onmessage=()=>f.contentWindow.location.reload()" : ''}
</script></body></html>`
}

/** Simulator HTML for the real-worker path: rpc.invoke is forwarded to /__rpc. */
function rpcSimulatorHtml({ manifest }) {
  const environment = {
    sdkVersion: '1.0.0',
    theme: 'dark',
    locale: 'en',
    platform: 'web',
    capabilities: ['rpc.invoke', 'files.open', 'notify'],
  }
  return `<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0;display:grid;grid-template-columns:1fr 360px;height:100vh;font:13px system-ui;background:#111;color:#eee}iframe{width:100%;height:100%;border:0}aside{padding:12px;overflow:auto;border-left:1px solid #444}pre{white-space:pre-wrap}</style></head><body><iframe id=f name=f sandbox="allow-scripts allow-forms allow-downloads allow-same-origin"></iframe><aside><h3>RPC Inspector</h3><pre id=log></pre></aside><script type=module>
const env=${JSON.stringify(environment)};
const f=document.querySelector('#f');
const log=document.querySelector('#log');
// The plugin iframe is served by its own dev server (Vite) in real use; in the
// headless simulator we point at the plugin's ui.entry via a data URL fallback.
f.src='/__plugin';
function note(msg){log.textContent=JSON.stringify(msg,null,2)+'\\n\\n'+log.textContent}
addEventListener('message',async e=>{
  const q=e.data;
  if(q?.source!=='fengyu-plugin')return;
  note(q);
  if(q.type==='cancel')return;
  if(q.method==='host.ready'){f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,result:{sdkVersion:env.sdkVersion,theme:env.theme,locale:env.locale,platform:env.platform,capabilities:env.capabilities}},'*');return}
  if(q.method==='rpc.invoke'){
    let result;
    try{
      const res=await fetch('/__rpc',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:q.id,method:q.params.method,params:q.params.params})});
      const json=await res.json();
      result=json.result;
      if(json.error){f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,error:json.error},'*');return}
    }catch(err){f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,error:String(err)},'*');return}
    f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,result},'*');
    return;
  }
  let result=true;
  if(q.method==='notify')result=true;
  else if(q.method==='files.open')result={id:'file-1',name:'sample.txt',kind:'file',access:'read',size:42};
  f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,result},'*');
});
</script></body></html>`
}

/* -------------------------------------------------------------------------- */
/* Helpers                                                                     */
/* -------------------------------------------------------------------------- */

/** Debounced recursive directory watcher. Returns an object with a close() method. */
function watchDebounced(root, ignoreDirs, debounceMs, onChange) {
  let timer = null
  const watchers = []
  const schedule = () => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { timer = null; onChange() }, debounceMs)
  }
  function watchDir(dir) {
    let w
    try {
      w = fsSync.watch(dir, { recursive: true }, (event, filename) => {
        if (!filename) return
        if (ignoreDirs.some((d) => filename.split(path.sep).includes(d))) return
        schedule()
      })
    } catch {
      return
    }
    if (w) watchers.push(w)
  }
  watchDir(root)
  return {
    close: async () => {
      if (timer) clearTimeout(timer)
      for (const w of watchers) w.close()
    },
  }
}

function waitForPort(port, timeoutMs) {
  const start = Date.now()
  return new Promise((resolve, reject) => {
    const tryConnect = () => {
      const socket = new net.Socket()
      socket.setTimeout(1000)
      socket.once('connect', () => { socket.destroy(); resolve() })
      socket.once('error', () => { socket.destroy(); retryOrGiveUp() })
      socket.once('timeout', () => { socket.destroy(); retryOrGiveUp() })
      socket.connect(port, HOST)
    }
    const retryOrGiveUp = () => {
      if (Date.now() - start >= timeoutMs) reject(new Error(`Vite dev server did not start on port ${port}`))
      else setTimeout(tryConnect, 200)
    }
    tryConnect()
  })
}

function listen(server, port) {
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, HOST, () => { server.removeListener('error', reject); resolve() })
  })
}

function stopServer(server) {
  return new Promise((resolve) => server.close(() => resolve()))
}

async function safeReadManifest(root) {
  try {
    return await readManifest(root)
  } catch {
    return null
  }
}

function normalizeEntry(entry) {
  return String(entry).replace(/^\/+/, '')
}

async function fingerprint(dir) {
  let value = ''
  let entries
  try {
    entries = await fs.readdir(dir, { withFileTypes: true })
  } catch {
    return value
  }
  for (const e of entries) {
    if (['node_modules', '.git', 'dist-package'].includes(e.name)) continue
    const p = path.join(dir, e.name)
    value += e.name + (e.isDirectory() ? await fingerprint(p) : (await fs.stat(p)).mtimeMs)
  }
  return value
}

function type(f) {
  return f.endsWith('.html')
    ? 'text/html'
    : f.endsWith('.js')
      ? 'text/javascript'
      : f.endsWith('.css')
        ? 'text/css'
        : 'application/json'
}
