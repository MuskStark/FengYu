/**
 * Generates the simulator HTML shell that hosts the plugin iframe and bridges `postMessage`
 * (from the @infinia/plugin-sdk FengYuClient) to the Vite dev server's middleware.
 *
 * This is the development twin of the production host's plugin shell. The iframe runs the real
 * plugin UI (served by Vite with HMR); the shell:
 *  - answers `host.ready` with a mock Environment (theme/locale/capabilities)
 *  - forwards `rpc.invoke` to `POST /__fengyu/rpc`, which the Vite middleware proxies to the
 *    loopback dev worker (or returns a devMock only when `mockWorker` is set)
 *  - renders a path prompt for `files.open` / `files.inputDirectory` / `files.outputDirectory`
 *    (browsers can't pop a native picker), registers the typed path via `POST /__fengyu/ref`,
 *    and replies to the iframe with the resulting FileRef
 *
 * The postMessage envelope matches `@infinia/plugin-sdk`'s FengYuClient exactly
 * (`source: 'fengyu-host'` / `source: 'fengyu-plugin'`), so the plugin UI is identical between
 * development and production.
 *
 * Migrated from `plugin-cli/src/dev.mjs` (rpcSimulatorHtml + simulatorHtml, merged).
 */
export interface SimulatorHtmlOptions {
  /** Iframe src — the Vite dev server root (so the plugin UI is same-origin with HMR). */
  iframeSrc: string
  /** Parsed manifest, surfaced for the inspector. */
  manifest: Record<string, unknown> | null
}

export function simulatorHtml({ iframeSrc, manifest }: SimulatorHtmlOptions): string {
  const environment = {
    sdkVersion: '1.0.0',
    theme: 'dark',
    locale: 'en',
    platform: 'web',
    capabilities: ['rpc.invoke', 'files.open', 'files.inputDirectory', 'files.outputDirectory', 'notify'],
  }
  const manifestJson = manifest ? JSON.stringify(manifest).replace(/</g, '\\u003c') : '{}'
  return `<!doctype html><html><head><meta charset="utf-8"><title>FengYu Dev</title><style>body{margin:0;display:grid;grid-template-columns:1fr 380px;height:100vh;font:13px system-ui;background:#111;color:#eee}iframe{width:100%;height:100%;border:0}aside{padding:12px;overflow:auto;border-left:1px solid #444}pre{white-space:pre-wrap}h3{margin:0 0 8px}.recent{font-size:11px;color:#aaa}.recent div{cursor:pointer;padding:2px 4px;border-radius:3px}.recent div:hover{background:#222}input,button{font:inherit;color:#eee;background:#222;border:1px solid #444;border-radius:4px;padding:4px 6px}button{cursor:pointer}section{margin-bottom:16px;padding-bottom:12px;border-bottom:1px solid #333}.pending{background:#3a2a00;padding:6px;border-radius:4px;margin:6px 0}.controls{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px}button.on{background:#2d6a2d}</style></head><body><iframe id=f name=f sandbox="allow-scripts allow-forms allow-downloads allow-same-origin"></iframe><aside><h3>FengYu Dev</h3>
<section><b>Manifest</b><pre id=manifest style="max-height:120px;overflow:auto;font-size:11px"></pre></section>
<section><div class=controls><button id=t-theme>theme: dark</button><button id=t-locale>locale: en</button><button id=t-deny>deny: off</button></div></section>
<section><b>Files (dev bridge)</b><p class=recent>插件请求选文件/目录时，在此填真实路径（浏览器无法弹原生选择器）。</p><div id=pendingWrap></div><div class=recent id=recent></div></section>
<section><b>RPC Inspector</b><pre id=log style="max-height:40vh"></pre></section>
</aside><script type=module>
const env=${JSON.stringify(environment)};
const manifestJson=${manifestJson};
const iframeSrc=${JSON.stringify(iframeSrc)};
const f=document.querySelector('#f');
const log=document.querySelector('#log');
const manifestEl=document.querySelector('#manifest');
const pendingWrap=document.querySelector('#pendingWrap');
const recentEl=document.querySelector('#recent');
f.src=iframeSrc;
manifestEl.textContent=manifestJson;
let deny=false;
const recent=[];
function note(msg){log.textContent=JSON.stringify(msg,null,2)+'\\n\\n'+log.textContent}
function addRecent(path){if(!recent.includes(path)){recent.unshift(path);if(recent.length>8)recent.pop();renderRecent()}}
function renderRecent(){recentEl.innerHTML='<div style=color:#888>最近：</div>'+recent.map(p=>'<div data-p="'+p.replace(/"/g,'&quot;')+'">'+p+'</div>').join('');[...recentEl.querySelectorAll('[data-p]')].forEach(el=>el.onclick=()=>{const i=pathInput();if(i)i.value=el.dataset.p})}
function pathInput(){return pendingWrap.querySelector('input')}
function envEvent(){f.contentWindow.postMessage({source:'fengyu-host',type:'event',event:'environment',data:{theme:env.theme,locale:env.locale}},'*')}
async function registerRef(path,kind,access){
  const res=await fetch('/__fengyu/ref',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path,kind,access})});
  if(!res.ok){const t=await res.text();throw new Error('register failed: '+t)}
  return await res.json();
}
function requestFile(kind,access,id,opts){
  const fileLabel=kind==='directory'?'目录':'文件';
  const accept=(opts&&Array.isArray(opts.extensions)&&opts.extensions.length)?' (.'+opts.extensions.join(', .')+')':'';
  const row=document.createElement('div');row.className='pending';
  row.innerHTML='<div>插件请求选择'+fileLabel+accept+'</div><input style=width:100% placeholder="/abs/path/to/'+fileLabel+'" /><button>确认</button> <button class=cancel>取消</button>';
  pendingWrap.appendChild(row);
  const inp=row.querySelector('input');const btn=row.querySelector('button:not(.cancel)');const cancel=row.querySelector('.cancel');
  inp.focus();
  const done=(result)=>{row.remove();f.contentWindow.postMessage({source:'fengyu-host',type:'response',id,result},'*')};
  const submit=async()=>{const p=inp.value.trim();if(!p)return;try{const ref=await registerRef(p,kind,access);addRecent(p);done(ref)}catch(e){done({__error:String(e)})}};
  btn.onclick=submit;inp.onkeydown=(e)=>{if(e.key==='Enter')submit()};
  cancel.onclick=()=>done(null);
}
addEventListener('message',async e=>{
  const q=e.data;
  if(q?.source!=='fengyu-plugin')return;
  note(q);
  if(q.type==='cancel')return;
  if(q.method==='host.ready'){f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,result:{sdkVersion:env.sdkVersion,theme:env.theme,locale:env.locale,platform:env.platform,capabilities:env.capabilities}},'*');return}
  if(deny){f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,error:'permission denied'},'*');return}
  if(q.method==='rpc.invoke'){
    let result,error;
    try{
      const res=await fetch('/__fengyu/rpc',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:q.id,method:q.params.method,params:q.params.params})});
      const json=await res.json();
      result=json.result;error=json.error;
    }catch(err){error=String(err)}
    f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,result,error},'*');
    return;
  }
  if(q.method==='notify'){f.contentWindow.postMessage({source:'fengyu-host',type:'response',id:q.id,result:true},'*');return}
  if(q.method==='files.open'){requestFile('file','read',q.id,q.params);return}
  if(q.method==='files.inputDirectory'){requestFile('directory','read',q.id);return}
  if(q.method==='files.outputDirectory'){requestFile('directory','write',q.id);return}
});
document.querySelector('#t-theme').onclick=()=>{env.theme=env.theme==='dark'?'light':'dark';document.querySelector('#t-theme').textContent='theme: '+env.theme;envEvent()};
document.querySelector('#t-locale').onclick=()=>{env.locale=env.locale==='en'?'zh':'en';document.querySelector('#t-locale').textContent='locale: '+env.locale;envEvent()};
document.querySelector('#t-deny').onclick=(b)=>{deny=!deny;b.target.textContent='deny: '+(deny?'on':'off');b.target.classList.toggle('on',deny)};
</script></body></html>`
}
