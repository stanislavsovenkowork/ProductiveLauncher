// QA only. Never inspect or transmit page contents. This visual intervention
// does not stop scripts or network requests and can be removed by the page.
const marker = 'focusdeck-qa-surface';
function clear() { document.getElementById(marker)?.remove(); }
function show() {
  clear();
  const host = document.createElement('div'); host.id = marker;
  // If communication or the extension dies, the visual barrier expires even
  // when no JavaScript runs. It is refreshed only after a fresh active reply.
  host.style.cssText = 'all:initial;position:fixed;inset:0;z-index:2147483647;animation:fdQaExpire 5s step-end forwards;';
  const root = host.attachShadow({mode:'open'});
  root.innerHTML = `<style>@keyframes fdQaExpire{to{visibility:hidden}} :host{animation:fdQaExpire 5s step-end forwards!important} div{height:100%;display:grid;place-content:center;text-align:center;background:#f8f6ef;color:#202620;font:20px system-ui} h1{font-size:28px}</style><div><h1>Тестовая страница ограничена</h1><p>Вернитесь к задаче или используйте послабление в FocusDeck.</p></div>`;
  (document.documentElement || document).appendChild(host);
}
let busy = false;
async function refresh() {
  if (busy) return; busy = true;
  try {
    const result = await chrome.runtime.sendMessage({kind:'status'});
    if (result?.phase === 'active') show(); else clear();
  } catch { clear(); }
  finally { busy = false; }
}
refresh(); setInterval(refresh, 1000);
