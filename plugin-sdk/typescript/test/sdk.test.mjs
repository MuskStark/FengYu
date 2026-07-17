import test from 'node:test'; import assert from 'node:assert/strict';
class FakeWindow extends EventTarget { constructor(){super();this.parent=this;this.sent=[];this.document={documentElement:{dataset:{}}}} postMessage(v){this.sent.push(v)} emit(data){const e=new Event('message');Object.assign(e,{data,source:this,origin:'x'});this.dispatchEvent(e)} }
const fake=new FakeWindow();globalThis.window=fake;globalThis.document=fake.document;
const {FengYuClient}=await import('../dist/index.js');
test('request response',async()=>{const c=new FengYuClient({target:fake,timeoutMs:100});const p=c.invoke('ping',{});fake.emit({source:'fengyu-host',type:'response',id:fake.sent.at(-1).id,result:{ok:true}});assert.deepEqual(await p,{ok:true});c.dispose()});
test('timeout',async()=>{const c=new FengYuClient({target:fake,timeoutMs:5});await assert.rejects(c.invoke('slow',{}),/timed out/);c.dispose()});
test('request ids work inside an opaque sandbox without Web Crypto',async()=>{const original=globalThis.crypto;Object.defineProperty(globalThis,'crypto',{value:undefined,configurable:true});const c=new FengYuClient({target:fake,timeoutMs:100});try{const p=c.invoke('sandboxed',{});const sent=fake.sent.at(-1);assert.equal(typeof sent.id,'string');assert.ok(sent.id.length>0);fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{ok:true}});assert.deepEqual(await p,{ok:true})}finally{c.dispose();Object.defineProperty(globalThis,'crypto',{value:original,configurable:true})}});
test('input directory uses the official host capability',async()=>{const c=new FengYuClient({target:fake,timeoutMs:100});const p=c.files.inputDirectory();const sent=fake.sent.at(-1);assert.equal(sent.method,'files.inputDirectory');fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{id:'ref_dir',name:'reports',kind:'directory',access:'read',size:0}});assert.equal((await p).id,'ref_dir');c.dispose()});
test('workspace directory requests a writable existing project',async()=>{const c=new FengYuClient({target:fake,timeoutMs:100});const p=c.files.workspaceDirectory();const sent=fake.sent.at(-1);assert.equal(sent.method,'files.workspaceDirectory');fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:{id:'ref_workspace',name:'project',kind:'directory',access:'read-write',size:0}});assert.equal((await p).access,'read-write');c.dispose()});

test('settled requests remove abort listeners',async()=>{
  const controller=new AbortController()
  let adds=0,removes=0
  const add=controller.signal.addEventListener.bind(controller.signal)
  const remove=controller.signal.removeEventListener.bind(controller.signal)
  controller.signal.addEventListener=(...args)=>{adds++;return add(...args)}
  controller.signal.removeEventListener=(...args)=>{removes++;return remove(...args)}
  const c=new FengYuClient({target:fake,timeoutMs:100})
  const p=c.invoke('ping',{}, {signal:controller.signal})
  const sent=fake.sent.at(-1)
  fake.emit({source:'fengyu-host',type:'response',id:sent.id,result:true})
  await p
  assert.equal(adds,1)
  assert.equal(removes,1)
  c.dispose()
});
