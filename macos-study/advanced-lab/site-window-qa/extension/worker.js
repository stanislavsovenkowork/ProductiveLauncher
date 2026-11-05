// QA-only. Generated config contains an ephemeral capability and loopback port.
importScripts('config.js');
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  let url;
  try { url = new URL(sender.url); } catch { return; }
  if (message?.kind !== 'status' || url.protocol !== 'http:' || url.hostname !== 'localhost' || url.port !== String(QA.port)) return;
  (async () => {
    try {
      const r = await fetch(QA.statusURL, {cache: 'no-store', headers: {'X-FocusDeck-QA': QA.token}, signal: AbortSignal.timeout(1500)});
      const v = await r.json();
      if (!r.ok || !['active', 'released'].includes(v.phase)) throw new Error('unknown');
      await chrome.action.setBadgeText({text: v.phase === 'active' ? 'ON' : ''});
      await chrome.action.setTitle({title: v.phase === 'active' ? 'FocusDeck QA: тестовое окно активно' : 'FocusDeck QA: ограничение не запрошено'});
      respond(v);
    } catch {
      await chrome.action.setBadgeText({text:'?'});
      await chrome.action.setTitle({title:'FocusDeck QA: связь неизвестна'});
      respond({phase: 'unknown'});
    }
  })();
  return true;
});
