const { _electron } = require('/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
(async () => {
 const dir=fs.mkdtempSync('/private/tmp/fengyu-review-launch-');
 const root='/Users/phoebej/Develop/Java/FengYu';
 let app;
 try {
  app=await _electron.launch({cwd:dir,args:[root+'/desktop/electron/dist/main.js','--user-data-dir='+path.join(dir,'profile')],env:{...process.env,FENGYU_JAR:root+'/FengYu/target/FengYu-4.0.0-rc.2.jar',FENGYU_DEV_BACKEND:'disabled',NODE_ENV:'test'},timeout:45000});
  let win=await app.firstWindow();
  if(win.url().includes('splash.html')) win=await app.waitForEvent('window',{predicate:w=>!w.url().includes('splash.html'),timeout:45000});
  await win.waitForLoadState('domcontentloaded',{timeout:45000});
  const bridge=await win.evaluate(()=>({base:window.fengyu?.apiBase?.(),token:window.fengyu?.token?.()}));
  assert.match(bridge.base,/^http:\/\/127\.0\.0\.1:\d+$/);
  let ok=false;
  for(let i=0;i<150;i++) {try {const r=await fetch(bridge.base+'/api/health',{headers:{'X-FengYu-Token':bridge.token}});if(r.ok){ok=true;break;}}catch{} await new Promise(r=>setTimeout(r,200));}
  assert.ok(ok,'backend health');
  const status=await app.evaluate(async ({net})=>(await net.fetch('app://shell/index.html')).status);
  assert.equal(status,200);
  console.log('PASS isolated Electron -> preload -> backend health; app://shell status=200');
 } finally {if(app) await app.close().catch(()=>{});console.log('isolatedRuntime='+dir);}
})().catch(e=>{console.error(e);process.exitCode=1});
