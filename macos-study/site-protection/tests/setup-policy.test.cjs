const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const callbacks = {}, queries = [], executions = [], timerCallbacks = [];
let enabled, registered = [], recoverySince, clock = 10000, nativeMode = 'respond';
let native = {phase:'active',domains:['qa.focusdeck.test'],includeSubdomains:true,windowState:'active'};
class TestDate extends Date { static now() { return clock; } }
const chrome = {
 runtime:{id:'testid',getURL:p=>'chrome-extension://testid/'+p,sendNativeMessage:async()=>{
  if(nativeMode==='reject') throw Error('offline');
  if(nativeMode==='pending') return new Promise(()=>{});
  return {...native};
 },onMessage:{addListener:f=>callbacks.message=f},onInstalled:{addListener:f=>callbacks.installed=f},onStartup:{addListener:f=>callbacks.startup=f}},
 storage:{local:{get:async()=>({enabled}),set:async v=>{enabled=v.enabled}},
  session:{get:async()=>({siteRecoverySince:recoverySince}),set:async v=>{recoverySince=v.siteRecoverySince},remove:async()=>{recoverySince=undefined}}},
 scripting:{getRegisteredContentScripts:async()=>registered,unregisterContentScripts:async()=>{registered=[]},
  registerContentScripts:async values=>{registered=values},executeScript:async value=>{executions.push(value)}},
 tabs:{query:async value=>{queries.push(value);return [{id:5}]}},
 action:{setBadgeText:async()=>{},setTitle:async()=>{}},
 alarms:{create:async()=>{},onAlarm:{addListener:f=>callbacks.alarm=f}}
};
const context=vm.createContext({chrome,importScripts:()=>{},setTimeout:(fn,ms)=>{if(ms===2500)timerCallbacks.push(fn);return 1},clearTimeout:()=>{},URL,Date:TestDate,console});
vm.runInContext(fs.readFileSync('macos-study/site-protection/extension/domain-policy.js','utf8'),context);
vm.runInContext(fs.readFileSync('macos-study/site-protection/extension/worker.js','utf8'),context);
function message(value,sender) { return new Promise(resolve => { assert.equal(callbacks.message(value,sender,resolve),true); }); }
(async()=>{
 await callbacks.installed({reason:'install'});
 assert.equal(enabled,false); assert.equal(queries.length,0);assert.equal(executions.length,0);assert.equal(registered.length,0);
 const sender={id:'testid',url:'chrome-extension://testid/popup.html'};
 assert.equal((await message({kind:'setEnabled',enabled:true},sender)).ok,true);
 assert.equal(queries.length,1);assert.deepEqual(Array.from(queries[0].url).sort(),['http://*.qa.focusdeck.test/*','https://*.qa.focusdeck.test/*']);
 assert.equal(executions.length,1);assert.equal(registered.length,1);
 for (const state of ['active','limited-relief','rest','exit-pending']) {
  native.windowState=state;native.phase=state==='limited-relief'?'released':'active';
  const result=await message({kind:'setEnabled',enabled:false},sender);
  assert.equal(result.reason,'window_open',state);assert.equal(enabled,true,state);assert.equal(registered.length,1,state);
 }
 native.windowState='exit';native.phase='released';
 assert.equal((await message({kind:'setEnabled',enabled:false},sender)).ok,true);assert.equal(registered.length,0);
 await message({kind:'setEnabled',enabled:true},sender);
 native.windowState='expired';assert.equal((await message({kind:'setEnabled',enabled:false},sender)).ok,true);
 await message({kind:'setEnabled',enabled:true},sender);
 nativeMode='pending'; let completed=false;
 const waiting=message({kind:'setEnabled',enabled:false},sender).then(v=>{completed=true;return v});
 await new Promise(resolve=>setImmediate(resolve));assert.equal(completed,false);assert.equal(enabled,true);
 timerCallbacks.at(-1)();assert.equal((await waiting).reason,'connection_unknown');
 assert.equal((await message({kind:'recoverDisable'},sender)).reason,'recovery_wait');
 clock+=5000;nativeMode='respond';native={...native,phase:'active',windowState:'active'};
 assert.equal((await message({kind:'recoverDisable'},sender)).reason,'window_open');assert.equal(enabled,true);
 nativeMode='reject';assert.equal((await message({kind:'setEnabled',enabled:false},sender)).reason,'connection_unknown');
 clock+=5000;const recovery=await message({kind:'recoverDisable'},sender);
 assert.equal(recovery.reason,'recovered_without_connection');assert.equal(enabled,false);assert.equal(registered.length,0);
 console.log('PASS: disabled install; selected-only query; fresh disable guard across active/relief/rest/exit-pending, exit/expiry, pending versus timeout and two-check offline recovery.');
})().catch(error=>{console.error(error);process.exitCode=1});
