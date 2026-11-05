// Only configured domains and window phase are exchanged with the native
// host. Neither the host nor FocusDeck receives a tab URL or page contents.
importScripts('domain-policy.js');
let cached = null, pending = null, updated = 0, badgeTimer = null;
let reconciliation = Promise.resolve(), toggleQueue = Promise.resolve();
const scriptID = 'focusdeck-selected-sites';
const openStates = ['active','limited-relief','rest','exit-pending'];
const states = ['none','inactive','active','limited-relief','rest','exit-pending','exit','expired'];
const unknown = () => ({phase:'unknown', domains:[], includeSubdomains:false, windowState:'unknown'});
async function freshStatus() {
  try {
    const value = await Promise.race([
      chrome.runtime.sendNativeMessage('dev.stanislav.focusdeck.sites', {kind:'status'}),
      new Promise((_, reject) => setTimeout(() => reject(Error('timeout')), 2500))
    ]);
    if (!value || !['active','released'].includes(value.phase) || !states.includes(value.windowState) ||
        !SiteDomains.validList(value.domains, value.includeSubdomains)) throw Error('unknown');
    return value;
  } catch { return unknown(); }
}
async function status() {
  if (cached && Date.now() - updated < 800) return cached;
  if (pending) return pending;
  pending = (async () => { cached = await freshStatus(); updated = Date.now(); return cached; })();
  try { return await pending; } finally { pending = null; }
}
async function changeEnabled(enabled, recovery = false) {
  if (enabled) {
    await chrome.storage.session.remove('siteRecoverySince');
    await chrome.storage.local.set({enabled:true});
    await reconcile(await status(), true);
    return {ok:true};
  }
  if ((await chrome.storage.local.get('enabled')).enabled !== true) return {ok:true};
  const now = Date.now();
  if (recovery) {
    const start = (await chrome.storage.session.get('siteRecoverySince')).siteRecoverySince;
    if (typeof start !== 'number' || now < start || now - start > 60000) {
      await chrome.storage.session.remove('siteRecoverySince');
      return {ok:false,reason:'recovery_not_started'};
    }
    if (now - start < 5000) return {ok:false,reason:'recovery_wait',waitMs:5000-(now-start)};
  }
  // This is deliberately a new bounded request, never a cached reply or an
  // unfinished background poll. Relief is released but the window remains open.
  const current = await freshStatus();
  if (current.phase === 'unknown') {
    if (!recovery) {
      await chrome.storage.session.set({siteRecoverySince:Date.now()});
      return {ok:false,reason:'connection_unknown',waitMs:5000};
    }
    await chrome.storage.session.remove('siteRecoverySince');
    await chrome.storage.local.set({enabled:false});
    cached = current; updated = Date.now();
    await reconcile(current);
    return {ok:true,reason:'recovered_without_connection'};
  }
  await chrome.storage.session.remove('siteRecoverySince');
  if (openStates.includes(current.windowState)) return {ok:false,reason:'window_open'};
  await chrome.storage.local.set({enabled:false});
  cached = current; updated = Date.now();
  await reconcile(current);
  return {ok:true,reason:'disabled'};
}
function patterns(value) {
  return value.domains.flatMap(domain => ['http','https'].map(scheme => `${scheme}://${value.includeSubdomains ? '*.' : ''}${domain}/*`));
}
async function reconcile(value, forceAttach = false) {
  // Serialize registrations so an older response cannot re-enable stale rules.
  const task = reconciliation.catch(() => {}).then(async () => {
    const enabled = (await chrome.storage.local.get('enabled')).enabled === true;
    const desired = enabled && value.phase !== 'unknown' ? patterns(value) : [];
    const current = (await chrome.scripting.getRegisteredContentScripts({ids:[scriptID]}))[0];
    const same = current && JSON.stringify([...current.matches].sort()) === JSON.stringify([...desired].sort());
    if (current && (!same || !desired.length)) await chrome.scripting.unregisterContentScripts({ids:[scriptID]});
    const changed = desired.length > 0 && !same;
    if (changed) await chrome.scripting.registerContentScripts([{id:scriptID, matches:desired, js:['content.js'], runAt:'document_start', allFrames:true, matchOriginAsFallback:true, persistAcrossSessions:true}]);
    if (desired.length && (changed || forceAttach)) {
      // Filter inside Chrome by selected patterns. Never enumerate all tabs.
      const tabs = await chrome.tabs.query({url:desired});
      for (const tab of tabs) if (tab.id != null) {
        chrome.scripting.executeScript({target:{tabId:tab.id,allFrames:true},files:['content.js']}).catch(() => {});
      }
    }
    await chrome.action.setBadgeText({text:!enabled ? 'OFF' : value.phase === 'unknown' ? '?' : value.phase === 'active' ? 'ON' : ''});
    await chrome.action.setTitle({title:!enabled ? 'FocusDeck: действие на сайты выключено' : value.phase === 'unknown' ? 'FocusDeck: связь неизвестна' : value.phase === 'active' ? 'FocusDeck: последнее состояние — окно активно' : 'FocusDeck: последнее состояние — ограничения сняты'});
    clearTimeout(badgeTimer);
    if (enabled) badgeTimer = setTimeout(() => {
      chrome.action.setBadgeText({text:'?'}).catch(() => {});
      chrome.action.setTitle({title:'FocusDeck: состояние не обновлено'}).catch(() => {});
    }, 5000);
    return enabled;
  });
  reconciliation = task; return task;
}
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (sender.id !== chrome.runtime.id) return;
  if (message?.kind === 'setEnabled' || message?.kind === 'recoverDisable') {
    if (sender.url !== chrome.runtime.getURL('popup.html') ||
        (message.kind === 'setEnabled' && typeof message.enabled !== 'boolean')) return;
    const task = toggleQueue.catch(() => {}).then(() => changeEnabled(message.kind === 'setEnabled' ? message.enabled : false, message.kind === 'recoverDisable'));
    toggleQueue = task;
    task.then(respond, () => respond({ok:false,reason:'operation_failed'}));
    return true;
  }
  if (message?.kind !== 'status') return;
  (async () => {
    const value = await status(), enabled = await reconcile(value);
    let selected = false;
    try {
      const url = new URL(sender.tab?.url);
      selected = enabled && ['http:', 'https:'].includes(url.protocol) && SiteDomains.matches(url.hostname, value.domains, value.includeSubdomains);
    } catch { /* popup or restricted tab */ }
    return {...value, enabled, selected};
  })().then(respond, () => respond({phase:'unknown',domains:[],enabled:false,selected:false}));
  return true;
});
async function refreshRules() { try { await reconcile(await status()); } catch { /* status remains unknown */ } }
chrome.runtime.onInstalled.addListener(async ({reason}) => {
  if (reason === 'install') await chrome.storage.local.set({enabled:false});
  await chrome.alarms.create('focusdeck-policy-refresh', {periodInMinutes:0.5});
  await refreshRules();
});
chrome.runtime.onStartup.addListener(async () => {
  await chrome.alarms.create('focusdeck-policy-refresh', {periodInMinutes:0.5});
  await refreshRules();
});
chrome.alarms.onAlarm.addListener(alarm => { if (alarm.name === 'focusdeck-policy-refresh') refreshRules(); });
