import {
  HOST_CAPABILITIES,
  HOST_MESSAGE_SOURCE,
  HOST_METHODS,
  PLUGIN_MESSAGE_SOURCE,
  PROTOCOL_VERSION,
  type HostEnvironment,
} from '@infinia/plugin-sdk/protocol'

/**
 * Generates the simulator HTML shell that hosts the plugin iframe and bridges `postMessage`
 * (from the @infinia/plugin-sdk FengYuClient) to the Vite dev server's middleware.
 *
 * This is the development twin of the production host's plugin shell. The iframe runs the real
 * plugin UI (served by Vite with HMR); the shell:
 *  - answers `host.ready` with a mock Environment (theme/locale/capabilities)
 *  - forwards `rpc.invoke` to `POST /__fengyu/rpc`, which the Vite middleware proxies to the
 *    loopback dev worker (or returns a devMock only when `mockWorker` is set)
 *  - offers browser file/directory pickers backed by temporary snapshots, while retaining a
 *    manual native-path field for desktop-style debugging
 *  - allocates temporary output directories and downloads their contents through `files.export`
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
  // The mock host environment is the development twin of the production PluginView.vue
  // handshake: it MUST carry the same fields the real host sends on `host.ready` (post-T2-05:
  // pluginId, pluginVersion, permissions, plus the existing protocol/theme/locale/platform/
  // capabilities). Every literal here comes from the shared protocol module or the parsed
  // manifest — the simulator never hardcodes a protocol version or invents an env shape.
  const manifestId = manifest?.id
  const manifestVersion = manifest?.version
  const manifestPermissions = manifest?.permissions
  const environment: HostEnvironment = {
    protocolVersion: PROTOCOL_VERSION,
    pluginId: typeof manifestId === 'string' ? manifestId : 'dev-plugin',
    pluginVersion: typeof manifestVersion === 'string' ? manifestVersion : '0.0.0-dev',
    permissions: Array.isArray(manifestPermissions)
      ? manifestPermissions.filter((p): p is string => typeof p === 'string')
      : [],
    theme: 'dark',
    locale: 'en',
    platform: 'web',
    capabilities: HOST_CAPABILITIES,
  }
  const manifestJson = manifest ? JSON.stringify(manifest).replace(/</g, '\\u003c') : '{}'
  return `<!doctype html><html><head><meta charset="utf-8"><title>FengYu Dev</title><style>body{margin:0;display:grid;grid-template-columns:1fr 380px;height:100vh;font:13px system-ui;background:#111;color:#eee}iframe{width:100%;height:100%;border:0}aside{padding:12px;overflow:auto;border-left:1px solid #444}pre{white-space:pre-wrap}h3{margin:0 0 8px}.recent{font-size:11px;color:#aaa}.recent div{cursor:pointer;padding:2px 4px;border-radius:3px}.recent div:hover{background:#222}input,button{font:inherit;color:#eee;background:#222;border:1px solid #444;border-radius:4px;padding:4px 6px}button{cursor:pointer}section{margin-bottom:16px;padding-bottom:12px;border-bottom:1px solid #333}.pending{background:#3a2a00;padding:6px;border-radius:4px;margin:6px 0}.controls{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px}button.on{background:#2d6a2d}</style></head><body><iframe id=f name=f sandbox="allow-scripts allow-forms allow-downloads allow-same-origin"></iframe><aside><h3>FengYu Dev</h3>
<section><b>Manifest</b><pre id=manifest style="max-height:120px;overflow:auto;font-size:11px"></pre></section>
<section><div class=controls><button id=t-theme>theme: dark</button><button id=t-locale>locale: en</button><button id=t-deny>deny: off</button></div></section>
<section><b>Files (dev bridge)</b><p class=recent>可通过系统选择器上传临时快照，或输入本机绝对路径测试原地读写。</p><div id=pendingWrap></div><div class=recent id=recent></div></section>
<section><b>RPC Inspector</b><pre id=log style="max-height:40vh"></pre></section>
</aside><script type=module>
const env=${JSON.stringify(environment)};
const protocol=${JSON.stringify({
    version: PROTOCOL_VERSION,
    pluginSource: PLUGIN_MESSAGE_SOURCE,
    hostSource: HOST_MESSAGE_SOURCE,
    methods: HOST_METHODS,
  })};
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
function envEvent(){f.contentWindow.postMessage({source:protocol.hostSource,type:'event',protocolVersion:protocol.version,event:'environment',data:{theme:env.theme,locale:env.locale}},'*')}
async function registerRef(path,kind,access){
  const res=await fetch('/__fengyu/ref',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path,kind,access})});
  if(!res.ok){const t=await res.text();throw new Error('register failed: '+t)}
  return await res.json();
}
async function jsonResponse(res){
  const body=await res.json().catch(()=>({}));
  if(!res.ok)throw new Error(body.error||('request failed: '+res.status));
  return body;
}
function failure(error,code='HOST_ERROR'){return {code,message:error instanceof Error?error.message:String(error)}}
function respond(id,result,error){f.contentWindow.postMessage({source:protocol.hostSource,type:'response',protocolVersion:protocol.version,id,result,error},'*')}
async function uploadFile(file){
  return jsonResponse(await fetch('/__fengyu/files/upload?name='+encodeURIComponent(file.name),{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:file}));
}
async function uploadDirectory(fileList,access){
  const selected=[...fileList];
  const firstPath=selected[0]?.webkitRelativePath||selected[0]?.name||'selected-directory';
  const name=firstPath.split('/')[0];
  const start=await jsonResponse(await fetch('/__fengyu/files/directory/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,access})}));
  for(const file of selected){
    const relative=file.webkitRelativePath?file.webkitRelativePath.split('/').slice(1).join('/'):file.name;
    const target='/__fengyu/files/directory/file?uploadId='+encodeURIComponent(start.uploadId)+'&path='+encodeURIComponent(relative);
    await jsonResponse(await fetch(target,{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:file}));
  }
  return jsonResponse(await fetch('/__fengyu/files/directory/finish',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({uploadId:start.uploadId})}));
}
function requestFile(kind,access,id,opts){
  const fileLabel=kind==='directory'?'目录':'文件';
  const accept=(opts&&Array.isArray(opts.extensions)&&opts.extensions.length)?' (.'+opts.extensions.join(', .')+')':'';
  const row=document.createElement('div');row.className='pending';
  row.innerHTML='<div>插件请求选择'+fileLabel+accept+'</div><input class=browser type="file" hidden><button class=browse>系统选择器</button><div style="margin-top:6px"><input class=path style=width:100% placeholder="/abs/path/to/'+fileLabel+'" /></div><button class=confirm>使用路径</button> <button class=cancel>取消</button>';
  pendingWrap.appendChild(row);
  const picker=row.querySelector('.browser');const browse=row.querySelector('.browse');const inp=row.querySelector('.path');const btn=row.querySelector('.confirm');const cancel=row.querySelector('.cancel');
  if(kind==='directory'){picker.multiple=true;picker.setAttribute('webkitdirectory','')}else if(opts?.extensions?.length){picker.accept=opts.extensions.map(x=>'.'+x).join(',')}
  inp.focus();
  const done=(result,error)=>{row.remove();respond(id,result,error)};
  browse.onclick=()=>picker.click();
  picker.onchange=async()=>{try{const result=kind==='file'?await uploadFile(picker.files[0]):await uploadDirectory(picker.files,access);done(result)}catch(e){done(undefined,failure(e))}};
  const submit=async()=>{const p=inp.value.trim();if(!p)return;try{const ref=await registerRef(p,kind,access);addRecent(p);done(ref)}catch(e){done(undefined,failure(e))}};
  btn.onclick=submit;inp.onkeydown=(e)=>{if(e.key==='Enter')submit()};
  cancel.onclick=()=>done(null);
}
function requestOutput(id){
  const row=document.createElement('div');row.className='pending';
  row.innerHTML='<div>插件请求输出目录</div><button class=temp>创建临时输出目录</button><div style="margin-top:6px"><input class=path style=width:100% placeholder="/abs/path/to/目录" /></div><button class=confirm>使用路径</button> <button class=cancel>取消</button>';
  pendingWrap.appendChild(row);
  const done=(result,error)=>{row.remove();respond(id,result,error)};
  row.querySelector('.temp').onclick=async()=>{try{done(await jsonResponse(await fetch('/__fengyu/files/output',{method:'POST'})))}catch(e){done(undefined,failure(e))}};
  row.querySelector('.confirm').onclick=async()=>{const p=row.querySelector('.path').value.trim();if(!p)return;try{addRecent(p);done(await registerRef(p,'directory','write'))}catch(e){done(undefined,failure(e))}};
  row.querySelector('.cancel').onclick=()=>done(null);
}
async function exportOutput(id,ref){
  try{
    const res=await fetch('/__fengyu/files/export/'+encodeURIComponent(ref.id));
    if(!res.ok)await jsonResponse(res);
    const url=URL.createObjectURL(await res.blob());const link=document.createElement('a');
    link.href=url;link.download='plugin-output.zip';link.click();URL.revokeObjectURL(url);respond(id,true);
  }catch(e){respond(id,undefined,failure(e))}
}
addEventListener('message',async e=>{
  const q=e.data;
  if(q?.source!==protocol.pluginSource||q.protocolVersion!==protocol.version)return;
  note(q);
  if(q.type==='cancel'){document.dispatchEvent(new CustomEvent('fengyu-cancel',{detail:q.id}));return}
  if(q.method===protocol.methods.ready){respond(q.id,env);return}
  if(deny){respond(q.id,undefined,failure('permission denied','PERMISSION_DENIED'));return}
  if(q.method===protocol.methods.invoke){
    let result,error;
    const controller=new AbortController();
    const cancel=e=>{if(e.detail===q.id)controller.abort()};document.addEventListener('fengyu-cancel',cancel);
    try{
      const res=await fetch('/__fengyu/rpc',{method:'POST',headers:{'Content-Type':'application/json'},signal:controller.signal,body:JSON.stringify({id:q.id,method:q.params.method,params:q.params.params})});
      const json=await res.json();
      result=json.result;error=json.error?failure(json.error):undefined;
    }catch(err){if(controller.signal.aborted)return;error=failure(err)}finally{document.removeEventListener('fengyu-cancel',cancel)}
    respond(q.id,result,error);
    return;
  }
  if(q.method===protocol.methods.notify){respond(q.id,true);return}
  if(q.method===protocol.methods.filesOpen){requestFile('file','read',q.id,q.params);return}
  if(q.method===protocol.methods.filesInputDirectory){requestFile('directory','read',q.id);return}
  if(q.method===protocol.methods.filesWorkspaceDirectory){requestFile('directory','read-write',q.id);return}
  if(q.method===protocol.methods.filesOutputDirectory){requestOutput(q.id);return}
  if(q.method===protocol.methods.filesExport){exportOutput(q.id,q.params);return}
});
document.querySelector('#t-theme').onclick=()=>{env.theme=env.theme==='dark'?'light':'dark';document.querySelector('#t-theme').textContent='theme: '+env.theme;envEvent()};
document.querySelector('#t-locale').onclick=()=>{env.locale=env.locale==='en'?'zh':'en';document.querySelector('#t-locale').textContent='locale: '+env.locale;envEvent()};
document.querySelector('#t-deny').onclick=(b)=>{deny=!deny;b.target.textContent='deny: '+(deny?'on':'off');b.target.classList.toggle('on',deny)};
</script></body></html>`
}
