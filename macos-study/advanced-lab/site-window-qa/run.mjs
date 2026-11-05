import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const playwrightPath = process.env.FOCUSDECK_PLAYWRIGHT_CORE || '/Users/stanislav/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core';
const { chromium } = require(playwrightPath);
const [dir, hash] = process.argv.slice(2);
const socket = path.join(dir, 'agent.sock'), agentPath = path.join(dir, 'Agent'), client = path.join(dir, 'Client');
const extension = path.join(dir, 'extension');
fs.cpSync('macos-study/advanced-lab/site-window-qa/extension', extension, {recursive:true});
let agent, context, server, statusRequests = 0, rejectedOrigins = 0, lastOrigin;
const token = crypto.randomBytes(24).toString('hex'); let extensionOrigin = null;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const run = (command, expected = true) => {
 const result = spawnSync(client, [socket, hash, command], {encoding:'utf8', timeout:5000});
 if (expected && result.status !== 0) throw Error(`agent ${command}: ${result.stdout} ${result.stderr}`);
 return JSON.parse(result.stdout || '{"phase":"unknown"}');
};
async function waitFor(check, label, timeout = 12000) {
 const deadline = Date.now() + timeout;
 while (Date.now() < deadline) { if (await check()) return; await pause(150); }
 throw Error('timeout: ' + label);
}
async function startAgent() {
 agent = spawn(agentPath, [], {env:{FOCUSDECK_USER_TEST_DIR:path.join(dir,'agent-data'),FOCUSDECK_USER_TEST_SOCKET:socket}, stdio:'ignore'});
 await waitFor(() => fs.existsSync(socket), 'agent socket');
}
async function stopAgent() {
 if (!agent || agent.exitCode !== null) return;
 const done = new Promise(resolve => agent.once('exit', resolve)); agent.kill('SIGTERM'); await done;
 await waitFor(() => !fs.existsSync(socket), 'socket cleanup');
}
try {
 await startAgent();
 server = http.createServer((req,res) => {
  if (req.url === '/status') {
   statusRequests++;
   if (!extensionOrigin || req.headers['x-focusdeck-qa'] !== token || (req.headers.origin && req.headers.origin !== extensionOrigin)) { lastOrigin = req.headers.origin; rejectedOrigins++; res.writeHead(403).end(); return; }
   const status = run('status', false);
   res.writeHead(200, {'Content-Type':'application/json','Access-Control-Allow-Origin':extensionOrigin,'Cache-Control':'no-store'}).end(JSON.stringify(status));
   return;
  }
  if (req.url === '/page') { res.writeHead(200, {'Content-Type':'text/html','Cache-Control':'no-store'}).end('<!doctype html><title>QA study page</title><main id="qa-content">Synthetic page</main>'); return; }
  res.writeHead(404).end();
 });
 await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
 const port = server.address().port;
 fs.writeFileSync(path.join(extension,'config.js'), `const QA = ${JSON.stringify({port,token,statusURL:`http://127.0.0.1:${port}/status`})};\n`);
 context = await chromium.launchPersistentContext(path.join(dir, 'profile'), {executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless:true, ignoreDefaultArgs:['--disable-extensions'],
  args:['--enable-automation','--enable-unsafe-extension-debugging','--disable-background-networking','--disable-component-update','--disable-sync','--no-first-run','--no-default-browser-check']});
 // Persistent contexts expose no public Browser handle. The parent is the
 // browser created for this isolated profile; CDP's extension domain is browser-wide.
 const cdp = await context._parent.newBrowserCDPSession();
 const launched = (await cdp.send('Browser.getBrowserCommandLine')).arguments;
 if (launched.includes('--disable-extensions') || !launched.includes('--enable-unsafe-extension-debugging') ||
     !launched.includes('--user-data-dir=' + path.join(dir, 'profile'))) throw Error('unexpected Chrome isolation or extension flags');
 console.log('Chrome QA: dedicated temporary profile; extension debugging enabled only in this process; --disable-extensions absent.');
 const loaded = await cdp.send('Extensions.loadUnpacked', {path:extension});
 extensionOrigin = 'chrome-extension://' + loaded.id;
 const extensionPage = await context.newPage();
 await extensionPage.goto(extensionOrigin + '/blocked.html');
 console.log('QA extension:', await extensionPage.evaluate(() => chrome.runtime.id));
 const target = await context.newPage();
 const allowed = await context.newPage();
 const unauthenticated = await fetch(`http://127.0.0.1:${port}/status`);
 if (unauthenticated.status !== 403) throw Error('loopback status accepted unauthenticated request');
 const hasBarrier = async page => page.locator('#focusdeck-qa-surface').evaluateAll(nodes => nodes.some(n => getComputedStyle(n).visibility !== 'hidden'));
 await target.goto(`http://localhost:${port}/page`);
 await allowed.goto(`http://127.0.0.1:${port}/page`);
 if (await hasBarrier(target) || await hasBarrier(allowed)) throw Error('unexpected barrier before window');
 run('publish');
 try { await waitFor(() => hasBarrier(target), 'active window existing target'); }
 catch (e) { console.error(`diagnostic: status requests=${statusRequests}, rejected origins=${rejectedOrigins}, origin=${lastOrigin}, workers=${context.serviceWorkers().length}`); throw e; }
 if (await hasBarrier(allowed)) throw Error('allowed page changed');
 run('relief'); await waitFor(async () => !(await hasBarrier(target)), 'relief');
 run('return'); await waitFor(() => hasBarrier(target), 'return on existing page');
 run('relief'); await waitFor(async () => !(await hasBarrier(target)), 'second relief');
 await waitFor(() => hasBarrier(target), 'relief expiry on existing page');
 await target.evaluate(() => history.pushState({}, '', '/page#spa'));
 await waitFor(() => hasBarrier(target), 'SPA retains barrier');
 await stopAgent();
 await waitFor(async () => !(await hasBarrier(target)), 'agent unavailable releases barrier');
 await waitFor(async () => await extensionPage.evaluate(() => chrome.action.getBadgeText({})) === '?', 'unknown is visible');
 await startAgent();
 await waitFor(() => hasBarrier(target), 'agent restart restores active state');
 const pageCdp = await context.newCDPSession(target);
 await pageCdp.send('Emulation.setScriptExecutionDisabled', {value:true});
 await waitFor(async () => !(await hasBarrier(target)), 'barrier expires without JavaScript', 8000);
 await pageCdp.send('Emulation.setScriptExecutionDisabled', {value:false});
 await waitFor(() => hasBarrier(target), 'barrier refreshes after scripts resume');
 await cdp.send('Extensions.uninstall', {id:loaded.id});
 await waitFor(async () => !(await hasBarrier(target)), 'disabled extension releases barrier', 8000);
 const reloaded = await cdp.send('Extensions.loadUnpacked', {path:extension});
 extensionOrigin = 'chrome-extension://' + reloaded.id;
 await target.reload();
 await waitFor(() => hasBarrier(target), 'reinstalled QA extension sees active state');
 run('request_exit'); await pause(1200); run('exit');
 await waitFor(async () => !(await hasBarrier(target)), 'exit');
 if (!target.url().startsWith(`http://localhost:${port}/page`) || await target.locator('#qa-content').textContent() !== 'Synthetic page') throw Error('context lost');
 console.log('PASS: active → relief → return → relief expiry → exit on existing page; SPA, unknown, agent restart, stopped JS and extension removal; other host and page context preserved. Visual-only QA.');
} finally {
 if (context) await context.close();
 if (server) await new Promise(resolve => server.close(resolve));
 await stopAgent();
 console.log('QA files: ' + dir);
}
