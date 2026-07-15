import http from 'node:http'
import net from 'node:net'
import fs from 'node:fs/promises'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { readManifest } from './manifest.mjs'
import { detectProject } from './project.mjs'

export { detectProject }

const HOST = '127.0.0.1'

/**
 * Start the FengYu dev host for a plugin project.
 *
 * Detects whether `root` is a Vue/Vite project or a legacy static-HTML project
 * and dispatches accordingly:
 *  - `vue-vite`: spawns `npm run dev` (Vite) on `options.uiPort` and serves a
 *    simulator page on `port` whose iframe points at the Vite server (HMR).
 *  - `static`: serves the project files on `port` plus an SSE reload watcher
 *    and a simulator whose iframe points at the manifest UI entry.
 *
 * @param {string} root - project root
 * @param {number} [port=4173] - simulator host port
 * @param {{ run?: Function, uiPort?: number, open?: boolean }} [options]
 * @returns {Promise<{ close: () => Promise<void>, port: number, kind: string }>}
 */
export async function dev(root, port = 4173, options = {}) {
  const dir = path.resolve(root)
  const kind = await detectProject(dir)
  return kind === 'vue-vite'
    ? devVue(dir, port, options)
    : devStatic(dir, port, options)
}

/**
 * Vue/Vite dev: run the project's Vite dev server, wait for its TCP port, then
 * serve the simulator page whose iframe points at it. No SSE (Vite does HMR).
 */
async function devVue(root, port, { run = defaultRun, uiPort = 5173, open = true } = {}) {
  const child = await run('npm', ['run', 'dev', '--', '--host', HOST, '--port', String(uiPort)], { cwd: root })
  // Wait for the Vite TCP port. Only poll when `run` returned a real process
  // handle; a stubbed run (tests) returns nothing, so serve the simulator at
  // once. Tolerate the port never opening so the page is always served.
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

/**
 * Default runner for Vue dev. Unlike `runCommand` (which resolves on process
 * exit), this resolves immediately with the spawned child process so the dev
 * host can kill it when the session closes. The test injects a plain async stub
 * that returns `undefined`; `devVue` guards against a missing handle.
 */
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

/**
 * Render the simulator host page. The iframe `src` is injected so the same page
 * works for Vue (Vite URL) and static (manifest entry path) projects. The page
 * answers SDK requests with the same message shape the production host uses.
 */
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

/* -------------------------------------------------------------------------- */
/* Helpers                                                                     */
/* -------------------------------------------------------------------------- */

/** Wait for a TCP port to accept connections (Vite ready). */
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

/** Listen on a port, resolving once listening (promise-based). */
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
