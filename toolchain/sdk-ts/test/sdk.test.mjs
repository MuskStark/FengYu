import test from 'node:test'; import assert from 'node:assert/strict';
class FakeWindow extends EventTarget { constructor(search=''){super();this.parent=this;this.sent=[];this.lastTargetOrigin=undefined;this.location={search};this.document={documentElement:{dataset:{}}}} postMessage(v,targetOrigin){this.sent.push(v);this.lastTargetOrigin=targetOrigin} emit(data,origin='x'){const e=new Event('message');Object.assign(e,{data:{protocolVersion:'3.0.0',...data},source:this,origin});this.dispatchEvent(e)} }
const fake=new FakeWindow();globalThis.window=fake;globalThis.document=fake.document;
const {FengYuClient,HOST_METHODS}=await import('../dist/index.js');
test('request response',async()=>{const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'});const p=c.invoke('ping',{});fake.emit({source:'fengyu-host',type:'response',id:fake.sent.at(-1).id,result:{ok:true}});assert.deepEqual(await p,{ok:true});c.dispose()});
test('timeout posts a cancel and surfaces a TIMEOUT error',async()=>{const c=new FengYuClient({target:fake,timeoutMs:5,allowedOrigin:'*'});const p=c.invoke('slow',{});const request=fake.sent.at(-1);await assert.rejects(p,error=>error.code==='TIMEOUT'&&error.name==='FengYuHostError'&&/timed out/.test(error.message));const cancel=fake.sent.at(-1);assert.equal(cancel.type,'cancel');assert.equal(cancel.id,request.id);assert.equal(cancel.protocolVersion,'3.0.0');c.dispose()});
test('abort emits a protocol cancellation with the same correlation id',async()=>{const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'});const controller=new AbortController();const p=c.invoke('slow',{}, {signal:controller.signal});const request=fake.sent.at(-1);controller.abort();await assert.rejects(p,error=>error.code==='ABORTED'&&error.name==='FengYuHostError');const cancel=fake.sent.at(-1);assert.equal(cancel.type,'cancel');assert.equal(cancel.id,request.id);assert.equal(cancel.protocolVersion,'3.0.0');c.dispose()});
test('structured host errors preserve their code',async()=>{const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'});const p=c.invoke('denied',{});const sent=fake.sent.at(-1);fake.emit({source:'fengyu-host',type:'response',id:sent.id,error:{code:'PERMISSION_DENIED',message:'denied'}});await assert.rejects(p,error=>error.code==='PERMISSION_DENIED'&&error.message==='denied');c.dispose()});
test('request ids work inside an opaque sandbox without Web Crypto',async()=>{const original=globalThis.crypto;Object.defineProperty(globalThis,'crypto',{value:undefined,configurable:true});const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'});try{const p=c.invoke('sandboxed',{});const sent=fake.sent.at(-1);assert.equal(typeof sent.id,'string');assert.ok(sent.id.length>0);fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{ok:true}});assert.deepEqual(await p,{ok:true})}finally{c.dispose();Object.defineProperty(globalThis,'crypto',{value:original,configurable:true})}});
test('input directory uses the official host capability',async()=>{const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'});const p=c.files.inputDirectory();const sent=fake.sent.at(-1);assert.equal(sent.method,'files.inputDirectory');fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{id:'ref_dir',name:'reports',kind:'directory',access:'read',size:0}});assert.equal((await p).id,'ref_dir');c.dispose()});
test('workspace directory requests a writable existing project',async()=>{const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'});const p=c.files.workspaceDirectory();const sent=fake.sent.at(-1);assert.equal(sent.method,'files.workspaceDirectory');fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{id:'ref_workspace',name:'project',kind:'directory',access:'read-write',size:0}});assert.equal((await p).access,'read-write');c.dispose()});

test('ready is deduplicated and environment events merge into current state',async()=>{
  const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'})
  const first=c.ready(),second=c.ready()
  const readyRequests=fake.sent.filter(item=>item.method==='host.ready')
  assert.equal(readyRequests.length>=1,true)
  const sent=readyRequests.at(-1)
  fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{protocolVersion:'3.0.0',pluginId:'demo',pluginVersion:'1.2.0',permissions:['files.read'],theme:'light',locale:'en',platform:'web',capabilities:[]}})
  assert.deepEqual(await first,await second)
  const before=fake.sent.length
  assert.equal((await c.ready()).theme,'light')
  assert.equal(fake.sent.length,before)
  fake.emit({source:'fengyu-host',type:'event',event:'environment',data:{locale:'zh-CN'}})
  assert.deepEqual(c.currentEnvironment(),{protocolVersion:'3.0.0',pluginId:'demo',pluginVersion:'1.2.0',permissions:['files.read'],theme:'light',locale:'zh-CN',platform:'web',capabilities:[]})
  c.dispose()
})

test('an environment event before ready does not complete the handshake',async()=>{
  const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'})
  fake.emit({source:'fengyu-host',type:'event',event:'environment',data:{theme:'dark',locale:'zh-CN'}})
  assert.equal(c.currentEnvironment(),undefined)
  const before=fake.sent.length
  const ready=c.ready()
  assert.equal(fake.sent.length,before+1)
  const sent=fake.sent.at(-1)
  assert.equal(sent.method,'host.ready')
  fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{protocolVersion:'3.0.0',pluginId:'demo',pluginVersion:'1.2.0',permissions:['files.read'],theme:'dark',locale:'zh-CN',platform:'desktop',capabilities:Object.values(HOST_METHODS)}})
  assert.deepEqual(await ready,{protocolVersion:'3.0.0',pluginId:'demo',pluginVersion:'1.2.0',permissions:['files.read'],theme:'dark',locale:'zh-CN',platform:'desktop',capabilities:Object.values(HOST_METHODS)})
  c.dispose()
})

test('settled requests remove abort listeners',async()=>{
  const controller=new AbortController()
  let adds=0,removes=0
  const add=controller.signal.addEventListener.bind(controller.signal)
  const remove=controller.signal.removeEventListener.bind(controller.signal)
  controller.signal.addEventListener=(...args)=>{adds++;return add(...args)}
  controller.signal.removeEventListener=(...args)=>{removes++;return remove(...args)}
  const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'})
  const p=c.invoke('ping',{}, {signal:controller.signal})
  const sent=fake.sent.at(-1)
  fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:true})
  await p
  assert.equal(adds,1)
  assert.equal(removes,1)
  c.dispose()
});

// Regression: a Vue `ref()` / `reactive()` wraps nested objects in a Proxy that the
// structured-clone algorithm used by postMessage CANNOT clone (DataCloneError). Plugin
// UIs pass `props.project` (a reactive FileRef) straight into invoke(); request() must
// strip the Proxy wrapper before posting so the call does not fail silently.
function reactiveProxy(target){
  // Mirrors Vue's deep reactivity: every object/array access returns a nested Proxy.
  const wrap=(v)=> (v && typeof v==='object') ? reactiveProxy(v) : v
  return new Proxy(target,{
    get(t,k){ return wrap(Reflect.get(t,k)) },
    getPrototypeOf(){ return Object.getPrototypeOf(target) }
  })
}
test('invoke strips reactive Proxy params so postMessage can clone them',async()=>{
  const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'})
  // A Vue-style reactive FileRef: deeply proxied. structuredClone(raw) throws.
  const fileRef=reactiveProxy({id:'ref_proxy',name:'proj',kind:'directory',access:'read-write',size:0})
  // Sanity: the un-sanitized form is indeed non-cloneable.
  assert.throws(()=>structuredClone(fileRef),/could not be cloned/i)
  const p=c.invoke('config.save',{session:'ui',projectDir:fileRef,config:{python:{version:'3.11.9'}}})
  const sent=fake.sent.at(-1)
  // Resolve the call before dispose so no timeout rejection leaks after the test.
  fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{success:true}})
  await p
  c.dispose()
  // The whole postMessage payload must be structured-cloneable (no Proxy survives).
  assert.doesNotThrow(()=>structuredClone(sent))
  // The inner call params (sent.params.params — invoke wraps method+params) must be a plain
  // object deeply equal to a non-proxied equivalent, with the Proxy fully stripped.
  assert.deepEqual(sent.params.params,{session:'ui',projectDir:{id:'ref_proxy',name:'proj',kind:'directory',access:'read-write',size:0},config:{python:{version:'3.11.9'}}})
})

test('capability pre-check rejects invoke with PERMISSION_DENIED when the host omits the capability',async()=>{
  const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'})
  // Negotiate an environment that advertises every capability EXCEPT rpc.invoke.
  const allExceptInvoke=Object.values(HOST_METHODS).filter(m=>m!==HOST_METHODS.invoke)
  const ready=c.ready()
  // fake.sent is shared across tests — grab the request this client just posted (the last one).
  const readyReq=fake.sent.at(-1)
  fake.emit({source:'fengyu-host',type:'response',id:readyReq.id,result:{protocolVersion:'3.0.0',pluginId:'demo',pluginVersion:'1.2.0',permissions:[],theme:'light',locale:'en',platform:'web',capabilities:allExceptInvoke}})
  await ready
  const before=fake.sent.length
  await assert.rejects(c.invoke('denied',{}),error=>error.code==='PERMISSION_DENIED'&&error.name==='FengYuHostError'&&/rpc\.invoke/.test(error.message))
  // The rejected call must NOT have posted anything to the host (validated before posting).
  assert.equal(fake.sent.length,before)
  // A capability the host DID advertise still works.
  const p=c.files.inputDirectory()
  const sent=fake.sent.at(-1)
  assert.equal(sent.method,'files.inputDirectory')
  fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{id:'ref_dir',name:'reports',kind:'directory',access:'read',size:0}})
  assert.equal((await p).id,'ref_dir')
  c.dispose()
})

test('a response whose id is not pending is dropped silently',async()=>{
  const c=new FengYuClient({target:fake,timeoutMs:100,allowedOrigin:'*'})
  const p=c.invoke('ping',{})
  const sent=fake.sent.at(-1)
  // A stranger response with an unrelated id must not resolve/reject our pending request.
  fake.emit({source:'fengyu-host',type:'response',id:'unknown-id',result:{noise:true}})
  // The matching response still settles the original promise.
  fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{ok:true}})
  assert.deepEqual(await p,{ok:true})
  c.dispose()
})
test('bridge refuses to operate without a resolvable origin',async()=>{
  // A stranger's page iframing this loopback-served plugin UI provides neither an option
  // nor ?shellOrigin — the client must not postMessage('*') and must not accept input.
  const stranger=new FakeWindow();
  const c=new FengYuClient({target:stranger,timeoutMs:50});
  await assert.rejects(c.invoke('ping',{}),error=>error.code==='PERMISSION_DENIED'&&/allowedOrigin/.test(error.message));
  assert.equal(stranger.sent.length,0,'nothing may be posted');
  stranger.emit({source:'fengyu-host',type:'response',id:'probe',result:{ok:true}});
  c.dispose();
});
test('shellOrigin query parameter pins the bridge',async()=>{
  // resolveAllowedOrigin reads the frame's OWN location.search (globalThis.window here).
  const previousSearch=fake.location.search;
  fake.location.search='?shellOrigin=http%3A%2F%2Flocalhost%3A5173';
  const shell=fake;
  const c=new FengYuClient({target:shell,timeoutMs:100});
  const p=c.invoke('ping',{});
  assert.equal(shell.lastTargetOrigin,'http://localhost:5173','requests post to the pinned origin only');
  const sent=shell.sent.at(-1);
  shell.emit({source:'fengyu-host',type:'response',id:sent.id,result:{ok:true}},'http://localhost:5173');
  assert.deepEqual(await p,{ok:true});
  // A response from any other origin is ignored — forged replies cannot settle requests.
  const p2=c.invoke('ping',{});
  const sent2=shell.sent.at(-1);
  shell.emit({source:'fengyu-host',type:'response',id:sent2.id,result:{evil:true}},'https://evil.example');
  await assert.rejects(p2,error=>error.code==='TIMEOUT');
  c.dispose();
  fake.location.search=previousSearch;
});
test('a serialized file:// shellOrigin pins the bridge verbatim (packaged desktop shells)',async()=>{
  // Packaged desktop shells load the plugin iframe from disk, where the serialized origin
  // is the literal "file://" — normalizing through URL().origin would corrupt it into the
  // string "null". The SDK deliberately does NOT normalize the parameter, so "file://"
  // must resolve to itself as the allowed target.
  const previousSearch=fake.location.search;
  fake.location.search='?shellOrigin=file%3A%2F%2F';
  const shell=fake;
  const c=new FengYuClient({target:shell,timeoutMs:100});
  const p=c.invoke('ping',{});
  assert.equal(shell.lastTargetOrigin,'file://','file:// is pinned verbatim, never normalized to "null"');
  const sent=shell.sent.at(-1);
  shell.emit({source:'fengyu-host',type:'response',id:sent.id,result:{ok:true}},'file://');
  assert.deepEqual(await p,{ok:true});
  c.dispose();
  fake.location.search=previousSearch;
});
test('a file:// pin also accepts the null serialization of inbound host messages (Electron)',async()=>{
  // Inside packaged Electron builds (installed AND portable, every platform) the shell page
  // runs on file:// and appends ?shellOrigin=file://, but Chromium serializes that parent's
  // origin as the string 'null' in MessageEvent.origin seen by the iframe. Before this
  // regression test the strict equality check dropped every host response and environment
  // event: ready() fell back after its timeout, every invoke timed out, and theme/locale
  // never followed the host. Both serializations must bridge; unrelated origins must not.
  const previousSearch=fake.location.search;
  fake.location.search='?shellOrigin=file%3A%2F%2F';
  const shell=fake;
  const c=new FengYuClient({target:shell,timeoutMs:100});
  const ready=c.ready();
  const sent=shell.sent.at(-1);
  shell.emit({source:'fengyu-host',type:'response',id:sent.id,result:{protocolVersion:'3.0.0',pluginId:'demo',pluginVersion:'1.0.0',permissions:[],theme:'dark',locale:'zh-CN',platform:'desktop',capabilities:Object.values(HOST_METHODS)}},'null');
  assert.equal((await ready).locale,'zh-CN');
  shell.emit({source:'fengyu-host',type:'event',event:'environment',data:{theme:'light'}},'null');
  assert.equal(c.currentEnvironment().theme,'light');
  assert.equal(document.documentElement.dataset.theme,'light');
  const p=c.invoke('ping',{});
  const request=shell.sent.at(-1);
  assert.equal(shell.lastTargetOrigin,'file://','outbound posts still target file://, never the literal "null"');
  shell.emit({source:'fengyu-host',type:'response',id:request.id,result:{ok:true}},'null');
  assert.deepEqual(await p,{ok:true});
  const p2=c.invoke('ping',{});
  const request2=shell.sent.at(-1);
  shell.emit({source:'fengyu-host',type:'response',id:request2.id,result:{evil:true}},'https://evil.example');
  await assert.rejects(p2,error=>error.code==='TIMEOUT');
  c.dispose();
  fake.location.search=previousSearch;
});
