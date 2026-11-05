import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import {spawn,spawnSync} from 'node:child_process';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.FOCUSDECK_PLAYWRIGHT_CORE || '/Users/stanislav/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');
// Reject accidental direct invocation before starting any Chrome, agent or server.
const scopeId=process.env.FOCUSDECK_SITE_QA_SCOPE_ID, scopeLog=process.env.FOCUSDECK_SITE_QA_SCOPE_LOG;
if(!scopeId || !scopeLog || !path.isAbsolute(scopeLog)) throw Error('QA scope missing');
const own=fs.readFileSync(scopeLog,'utf8').trim().split('\n').map(JSON.parse).filter(row=>row.scopeId===scopeId);
if(own.length!==1 || own[0].event!=='start') throw Error('QA scope is not open');
const [dir,hash]=process.argv.slice(2), socket=path.join(dir,'agent.sock'), host=path.join(dir,'Host');
const mode=process.env.FOCUSDECK_QA_DOMAIN_MODE;
if (!['legacy','subdomains'].includes(mode)) throw Error('QA domain mode missing');
const subdomains=mode==='subdomains';
const manifest=path.join(dir,'profile/NativeMessagingHosts/dev.stanislav.focusdeck.sites.json');
const hostEnv={...process.env,FOCUSDECK_SITE_TEST_CHROME_DATA:path.join(dir,'profile')};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function waitFor(fn,label,timeout=15000) { const until=Date.now()+timeout; while(Date.now()<until){if(await fn())return;await sleep(150);}throw Error('timeout: '+label); }
function client(cmd) {const r=spawnSync(path.join(dir,'Client'),[socket,hash,cmd],{encoding:'utf8'}); if(r.status!==0)throw Error('client '+cmd+': '+r.stdout+r.stderr);}
let agent, context, server, registered=false;
async function startAgent(){agent=spawn(path.join(dir,'Agent'),[],{env:{...process.env,FOCUSDECK_USER_TEST_DIR:path.join(dir,'agent-data'),FOCUSDECK_USER_TEST_SOCKET:socket},stdio:'ignore'});await waitFor(()=>fs.existsSync(socket),'agent');}
async function stopAgent(){if(!agent || agent.exitCode!==null || agent.signalCode!==null)return; const finished=new Promise(r=>agent.once('exit',r));agent.kill('SIGTERM');await finished;await waitFor(()=>!fs.existsSync(socket),'agent stopped');}
try {
 if(fs.existsSync(manifest))throw Error('Existing native registration: QA refuses to replace it');
 await startAgent();
 const installation=spawnSync(host,['--install'],{encoding:'utf8',env:hostEnv});if(installation.status!==0)throw Error(installation.stderr);registered=true;
 const rate=8000, samples=rate, tone=Buffer.alloc(44+samples*2);
 tone.write('RIFF',0);tone.writeUInt32LE(36+samples*2,4);tone.write('WAVEfmt ',8);tone.writeUInt32LE(16,16);tone.writeUInt16LE(1,20);tone.writeUInt16LE(1,22);tone.writeUInt32LE(rate,24);tone.writeUInt32LE(rate*2,28);tone.writeUInt16LE(2,32);tone.writeUInt16LE(16,34);tone.write('data',36);tone.writeUInt32LE(samples*2,40);
 for(let i=0;i<samples;i++)tone.writeInt16LE(Math.round(5000*Math.sin(2*Math.PI*440*i/rate)),44+i*2);
 server=http.createServer((req,res)=>{
  if(req.url==='/tone.wav'){res.writeHead(200,{'Content-Type':'audio/wav'}).end(tone);return;}
  if(req.url==='/frame'){res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end('<!doctype html><audio id="frame-media" src="/tone.wav" loop></audio>');return;}
  const other=req.headers.host.startsWith('qa.')?'allowed.focusdeck.test':'qa.focusdeck.test';
  res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(`<!doctype html><title>Учебная QA страница</title><main id="content">Учебный материал</main><button id="test" style="position:absolute;top:250px;left:200px;width:180px;height:60px" onclick="window.clicks=(window.clicks||0)+1">QA нажать</button><audio id="media" src="/tone.wav" loop></audio><video id="muted-media" src="/tone.wav" loop muted></video><iframe src="http://${other}:${port}/frame"></iframe>`);
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;
 context=await chromium.launchPersistentContext(path.join(dir,'profile'),{executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true,ignoreDefaultArgs:['--disable-extensions'],env:{...process.env,FOCUSDECK_SITE_TEST_SOCKET:socket},args:['--enable-automation','--enable-unsafe-extension-debugging','--disable-background-networking','--disable-component-update','--disable-sync','--no-first-run','--no-default-browser-check','--mute-audio','--autoplay-policy=no-user-gesture-required','--no-proxy-server','--host-resolver-rules=MAP qa.focusdeck.test 127.0.0.1, MAP www.qa.focusdeck.test 127.0.0.1, MAP m.qa.focusdeck.test 127.0.0.1, MAP notqa.focusdeck.test 127.0.0.1, MAP qa.focusdeck.test.evil.test 127.0.0.1, MAP allowed.focusdeck.test 127.0.0.1']});
 const cdp=await context._parent.newBrowserCDPSession();const args=(await cdp.send('Browser.getBrowserCommandLine')).arguments;
 if(!args.includes('--user-data-dir='+path.join(dir,'profile'))||args.includes('--disable-extensions'))throw Error('Profile isolation failed');
 const target=await context.newPage(), allowed=await context.newPage(), www=await context.newPage(), mobile=await context.newPage(), prefix=await context.newPage(), suffix=await context.newPage();
 await target.goto(`http://qa.focusdeck.test:${port}/`);await allowed.goto(`http://allowed.focusdeck.test:${port}/`);
 await www.goto(`http://www.qa.focusdeck.test:${port}/`); await mobile.goto(`http://m.qa.focusdeck.test:${port}/`);
 await prefix.goto(`http://notqa.focusdeck.test:${port}/`); await suffix.goto(`http://qa.focusdeck.test.evil.test:${port}/`);
 await www.evaluate(()=>document.querySelector('#media').play()); await prefix.evaluate(()=>document.querySelector('#media').play());
 await target.evaluate(async()=>{await document.querySelector('#media').play(); await document.querySelector('#muted-media').play()});
 await allowed.evaluate(async()=>{await document.querySelector('#media').play(); await document.querySelector('#muted-media').play()});
 await target.frameLocator('iframe').locator('#frame-media').evaluate(e=>e.play());
 await allowed.frameLocator('iframe').locator('#frame-media').evaluate(e=>e.play());
 if(await target.evaluate(()=>document.querySelector('#media').paused)||await allowed.evaluate(()=>document.querySelector('#media').paused))throw Error('Synthetic media did not start');
 const loaded=await cdp.send('Extensions.loadUnpacked',{path:path.resolve('macos-study/site-protection/extension')});
 if(loaded.id!=='dcfilodddhkflkekabenoekoaeoblhao')throw Error('Wrong extension ID: '+loaded.id);
 let badge=await context.newPage();await badge.goto('chrome-extension://'+loaded.id+'/popup.html');
 await waitFor(async()=>await badge.locator('#coverage').textContent()==='Действие на сайты выключено.','disabled setup');
 const hasBarrier=async p=>p.locator('#focusdeck-site-surface').evaluateAll(ns=>ns.some(n=>getComputedStyle(n).visibility!=='hidden'));
 await waitFor(async()=>!(await hasBarrier(target)),'initial release');
 client('publish');
 await sleep(1800);
 if(await hasBarrier(target)||await hasBarrier(www)||await target.evaluate(()=>document.querySelector('#media').paused))throw Error('disabled installation touched selected test pages');
 if((await badge.evaluate(()=>chrome.scripting.getRegisteredContentScripts())).length)throw Error('disabled installation registered site scripts');
 const diagnostic=await context.newPage();await diagnostic.goto('chrome-extension://'+loaded.id+'/diagnostics.html');
 await waitFor(async()=>(await diagnostic.locator('#result').textContent())==='Мост и служба FocusDeck отвечают.','diagnostic host+agent');
 await badge.locator('#toggle').click();
 try { await waitFor(()=>hasBarrier(target),'barrier on already-open page'); } catch(e) { console.error('diagnostic', await badge.locator('#state').textContent(), 'worker replies:', await badge.evaluate(() => chrome.runtime.sendMessage({kind:'status'})), 'content attached:', await target.locator('#focusdeck-site-surface').count(), 'native raw:', await badge.evaluate(async () => { try { return await chrome.runtime.sendNativeMessage('dev.stanislav.focusdeck.sites',{kind:'status'}) } catch(e) { return String(e) } }), 'direct:', (()=>{let x=Buffer.from(JSON.stringify({kind:'status'}));let h=Buffer.alloc(4);h.writeUInt32LE(x.length);let r=spawnSync(host,[],{input:Buffer.concat([h,x]),env:{...process.env,FOCUSDECK_SITE_TEST_SOCKET:socket}});return {status:r.status,out:r.stdout.toString(),err:r.stderr.toString()}})()); throw e; }
 await waitFor(()=>target.evaluate(()=>document.querySelector('#media').paused&&document.querySelector('#muted-media').paused),'selected media paused');
 const rawStatus=await badge.evaluate(()=>chrome.runtime.sendNativeMessage('dev.stanislav.focusdeck.sites',{kind:'status'}));
 if(rawStatus.includeSubdomains!==subdomains)throw Error('native host policy flag mismatch');
 if(subdomains) {
  await waitFor(async()=>await hasBarrier(www) && await hasBarrier(mobile),'selected www and mobile subdomains');
  await waitFor(()=>www.evaluate(()=>document.querySelector('#media').paused),'subdomain media paused');
 } else {
  await sleep(1500);
  if(await hasBarrier(www)||await hasBarrier(mobile)||await www.evaluate(()=>document.querySelector('#media').paused))throw Error('legacy exact policy expanded to subdomains');
 }
 if(await hasBarrier(prefix)||await hasBarrier(suffix))throw Error('domain label boundary not respected');
 if(await prefix.evaluate(()=>document.querySelector('#media').paused))throw Error('unselected near-match media paused');
 if(await target.evaluate(()=>document.querySelector('#media').muted || !document.querySelector('#muted-media').muted))throw Error('User mute changed');
 if(await allowed.evaluate(()=>document.querySelector('#media').paused || document.querySelector('#muted-media').paused))throw Error('Allowed media paused');
 await waitFor(()=>target.frameLocator('iframe').locator('#frame-media').evaluate(e=>e.paused),'selected tab embedded media paused');
 if(await allowed.frameLocator('iframe').locator('#frame-media').evaluate(e=>e.paused))throw Error('Allowed tab embedded media paused');
 await target.evaluate(()=>{let box=document.createElement('div');box.id='open-shadow-test';let shadow=box.attachShadow({mode:'open'});let audio=document.createElement('audio');audio.src='/tone.wav';audio.loop=true;shadow.append(audio);document.body.append(box);audio.play().catch(()=>{});});
 await waitFor(()=>target.evaluate(()=>document.querySelector('#open-shadow-test').shadowRoot.querySelector('audio').paused),'new open shadow media paused');
 await target.evaluate(()=>{document.querySelector('#media').play().catch(()=>{});});
 await waitFor(()=>target.evaluate(()=>document.querySelector('#media').paused),'blocked restart');
 await badge.locator('#toggle').click();
 await waitFor(async()=>(await badge.locator('#notice').textContent()).includes('Окно ещё действует'),'active disable refused');
 if(!(await hasBarrier(target)) || (await badge.evaluate(()=>chrome.scripting.getRegisteredContentScripts())).length!==1)throw Error('active disable removed protection');
 client('rest');
 await waitFor(async()=>(await badge.evaluate(()=>chrome.runtime.sendNativeMessage('dev.stanislav.focusdeck.sites',{kind:'status'}))).windowState==='rest','rest state');
 await badge.locator('#toggle').click();await waitFor(async()=>(await badge.locator('#notice').textContent()).includes('Окно ещё действует'),'rest disable refused');
 if(!(await hasBarrier(target)))throw Error('rest disable removed barrier');
 client('return');
 await target.screenshot({path:path.join(dir,'barrier.png')});
 await target.mouse.click(250,280);if(await target.evaluate(()=>window.clicks||0))throw Error('visual barrier did not block mouse');
 if(await hasBarrier(allowed))throw Error('Allowed page blocked');
 await allowed.mouse.click(250,280);if(await allowed.evaluate(()=>window.clicks||0)!==1)throw Error('allowed page not interactive');
 client('relief');await waitFor(async()=>!(await hasBarrier(target)),'relief');
 if((await badge.evaluate(()=>chrome.runtime.sendNativeMessage('dev.stanislav.focusdeck.sites',{kind:'status'}))).windowState!=='limited-relief')throw Error('expected active relief');
 await badge.locator('#toggle').click();await waitFor(async()=>(await badge.locator('#notice').textContent()).includes('Окно ещё действует'),'relief disable refused');
 if((await badge.evaluate(()=>chrome.scripting.getRegisteredContentScripts())).length!==1)throw Error('relief disable unregistered scripts');
 await waitFor(async()=>!(await hasBarrier(www)) && !(await hasBarrier(mobile)),'subdomain relief');
 if(!await target.evaluate(()=>document.querySelector('#media').paused))throw Error('Relief auto-resumed media');
 await target.evaluate(()=>document.querySelector('#media').play());await waitFor(()=>target.evaluate(()=>!document.querySelector('#media').paused),'manual play during relief');
 await target.mouse.click(250,280);if(await target.evaluate(()=>window.clicks||0)!==1)throw Error('relief did not restore clicks');
client('return');await waitFor(()=>hasBarrier(target),'return');if(subdomains) await waitFor(async()=>await hasBarrier(www)&&await hasBarrier(mobile),'subdomain return');await waitFor(()=>target.evaluate(()=>document.querySelector('#media').paused),'media paused after return');
 client('relief');await waitFor(async()=>!(await hasBarrier(target)),'second relief');
 if(!await target.evaluate(()=>document.querySelector('#media').paused))throw Error('Second relief auto-resumed media');
 await target.evaluate(()=>document.querySelector('#media').play());
 await waitFor(()=>hasBarrier(target),'relief expiry');if(subdomains) await waitFor(async()=>await hasBarrier(www)&&await hasBarrier(mobile),'subdomain relief expiry');await waitFor(()=>target.evaluate(()=>document.querySelector('#media').paused),'media paused on relief expiry');
 await stopAgent();await waitFor(async()=>!(await hasBarrier(target)),'fail open');
 await diagnostic.locator('#again').click();
 await waitFor(async()=>(await diagnostic.locator('#result').textContent())==='Мост запущен, но состояние службы неизвестно.','diagnostic host without agent');
 if(!await target.evaluate(()=>document.querySelector('#media').paused))throw Error('Fail-open auto-resumed media');
 await target.evaluate(()=>document.querySelector('#media').play());await waitFor(()=>target.evaluate(()=>!document.querySelector('#media').paused),'manual play with agent unavailable');
 await waitFor(async()=>await badge.evaluate(()=>chrome.action.getBadgeText({}))==='?','unknown badge');
 await badge.locator('#toggle').click();
 await waitFor(async()=>!(await badge.locator('#recover').isHidden()),'offline recovery offered');
 if(await badge.locator('#recover').isEnabled())throw Error('recovery offered before waiting');
 await waitFor(async()=>await badge.locator('#recover').isEnabled(),'offline recovery wait',8000);
 await badge.locator('#recover').click();
 await waitFor(async()=>await badge.locator('#coverage').textContent()==='Действие на сайты выключено.','offline recovery disabled');
 if(await target.evaluate(()=>document.querySelector('#media').paused))throw Error('Recovery interrupted manual playback');
 await startAgent();await badge.evaluate(()=>chrome.runtime.sendMessage({kind:'status'}));
 await badge.locator('#toggle').click();await waitFor(()=>hasBarrier(target),'explicit enable after recovery');await waitFor(()=>target.evaluate(()=>document.querySelector('#media').paused),'media paused after reconnect');
 await cdp.send('Extensions.uninstall',{id:loaded.id});await waitFor(async()=>!(await hasBarrier(target)),'extension removal',8000);
 if(!await target.evaluate(()=>document.querySelector('#media').paused))throw Error('Removal auto-resumed media');
 await target.evaluate(()=>document.querySelector('#media').play());await waitFor(()=>target.evaluate(()=>!document.querySelector('#media').paused),'manual play after extension removal');
 const reloaded=await cdp.send('Extensions.loadUnpacked',{path:path.resolve('macos-study/site-protection/extension')});
 if(reloaded.id!==loaded.id)throw Error('ID changed');
 badge=await context.newPage();await badge.goto('chrome-extension://'+loaded.id+'/popup.html');
 await waitFor(async()=>await badge.locator('#coverage').textContent()==='Действие на сайты выключено.','reinstall defaults disabled');
 await badge.locator('#toggle').click();
 await waitFor(()=>hasBarrier(target),'extension reinstallation on existing page');await waitFor(()=>target.evaluate(()=>document.querySelector('#media').paused),'media paused after extension reinstall');
 client('request_exit');
 await waitFor(async()=>(await badge.evaluate(()=>chrome.runtime.sendNativeMessage('dev.stanislav.focusdeck.sites',{kind:'status'}))).windowState==='exit-pending','pending exit state');
 await badge.locator('#toggle').click();await waitFor(async()=>(await badge.locator('#notice').textContent()).includes('Окно ещё действует'),'pending exit disable refused');
 if(!(await hasBarrier(target)))throw Error('pending exit disable removed barrier');
 await sleep(1200);client('exit');await waitFor(async()=>!(await hasBarrier(target)),'exit');
 await badge.locator('#toggle').click();await waitFor(async()=>await badge.locator('#coverage').textContent()==='Действие на сайты выключено.','disable after exit');
 if((await badge.evaluate(()=>chrome.scripting.getRegisteredContentScripts())).length)throw Error('disable after exit did not unregister');await waitFor(async()=>!(await hasBarrier(www))&&!(await hasBarrier(mobile)),'subdomain exit');
 if(!await target.evaluate(()=>document.querySelector('#media').paused || document.querySelector('#muted-media').muted !== true))throw Error('Exit auto-resumed media');
 if(await target.evaluate(()=>document.querySelector('#media').muted || !document.querySelector('#muted-media').muted))throw Error('User mute not preserved');
 await target.evaluate(()=>document.querySelector('#media').play());await waitFor(()=>target.evaluate(()=>!document.querySelector('#media').paused),'manual play after exit');
 if(await allowed.evaluate(()=>document.querySelector('#media').paused || document.querySelector('#muted-media').paused))throw Error('Allowed media stopped');
 if(await target.locator('#content').textContent()!=='Учебный материал')throw Error('page context lost');
 console.log(`PASS (${mode}): disabled setup and native diagnostics, guarded disable in active/rest/relief/exit-pending, offline recovery and disable after exit, selected open-tab injection, active, mouse blocking, allowed page, relief and return, expiry, agent failure/recovery, extension removal/reinstallation, exit, synthetic media pause/manual resume, ${subdomains ? "root and selected www/mobile subdomains" : "legacy exact root with untouched subdomains"}, near-match exclusions, frame scope, open shadow, mute preservation and page context. Screenshot: ${path.join(dir,'barrier.png')}`);
} catch (error) {
 console.error(error); process.exitCode=1;
} finally {
 let cleanupFailed=false;
 try { if(context) await context.close(); } catch(e) { cleanupFailed=true; console.error('Chrome cleanup failed',e); }
 try { if(server) await new Promise((resolve,reject)=>server.close(e=>e?reject(e):resolve())); } catch(e) { cleanupFailed=true; console.error('Server cleanup failed',e); }
 try { await stopAgent(); } catch(e) { cleanupFailed=true; console.error('Agent cleanup failed',e); }
 try { if(registered){const r=spawnSync(host,['--uninstall'],{encoding:'utf8',env:hostEnv});if(r.status!==0 || fs.existsSync(manifest)) throw Error(r.stderr);} } catch(e) { cleanupFailed=true; console.error('QA registration cleanup failed',e); }
 if(cleanupFailed) process.exitCode=75;
 console.log('QA files: '+dir);
}
