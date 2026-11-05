// This is a visual, bypassable intervention, not a network filter.
(() => {
  if (globalThis.__focusDeckSiteAttached) return;
  globalThis.__focusDeckSiteAttached = true;
  const marker = 'focusdeck-site-surface'; let host = null, busy = false, active = false, activeUntil = 0, stopped = false, timer = null;
  const mainFrame = window === window.top;
  const observedRoots = new WeakSet(), roots = new Set(), observers = [];
  function pauseMedia(event) {
    if (!active || performance.now() >= activeUntil) return;
    const media = event.target;
    if (media instanceof HTMLMediaElement) { try { media.pause(); } catch {} }
  }
  function discover(node) {
    if (!(node instanceof Element) && !(node instanceof Document) && !(node instanceof ShadowRoot)) return;
    if (node instanceof HTMLMediaElement && active && performance.now() < activeUntil && !node.paused) {
      try { node.pause(); } catch {}
    }
    if (node instanceof Element && node.shadowRoot) watch(node.shadowRoot);
    for (const element of node.querySelectorAll('*')) if (element.shadowRoot) watch(element.shadowRoot);
  }
  function watch(root) {
    if (observedRoots.has(root)) { roots.add(root); return; }
    observedRoots.add(root); roots.add(root);
    root.addEventListener('play', pauseMedia, true);
    const observer = new MutationObserver(records => {
      if (!active || performance.now() >= activeUntil) return;
      for (const record of records) for (const node of record.addedNodes) discover(node);
    });
    observer.observe(root, {childList:true, subtree:true}); observers.push(observer);
    discover(root);
  }
  function pauseExisting() {
    for (const root of roots) {
      if (root instanceof ShadowRoot && !root.host.isConnected) { roots.delete(root); continue; }
      for (const media of root.querySelectorAll('audio,video')) {
        if (!media.paused) { try { media.pause(); } catch {} }
      }
    }
  }
  watch(document);
  function clear() { host?.remove(); host = null; }
  function show() {
    clear();
    host = document.createElement('div'); host.id = marker;
    host.style.cssText = 'all:initial;position:fixed;inset:0;z-index:2147483647;animation:fdExpire 5s step-end forwards';
    const shadow = host.attachShadow({mode:'closed'});
    shadow.innerHTML = `<style>@keyframes fdExpire{to{visibility:hidden}}:host{animation:fdExpire 5s step-end forwards!important}div{box-sizing:border-box;height:100%;display:grid;place-content:center;padding:32px;text-align:center;background:#f8f6ef;color:#202620;font:18px system-ui;line-height:1.5}h1{font-size:28px;margin:0 0 12px}p{max-width:560px;margin:4px auto}</style><div role="dialog" aria-label="Окно фокуса"><h1>Сейчас окно фокуса</h1><p>Вернитесь к задаче. Страница останется открытой.</p><p>Аудио и видео поставлены на паузу, если страница это поддерживает.</p><p>Послабление, возврат и выход доступны в FocusDeck.</p></div>`;
    (document.documentElement || document).appendChild(host);
  }
  // Prevent typing into a focused element behind the barrier. Leave browser
  // shortcuts (Command/Control) alone, and stop once the CSS lease expires.
  function blockInput(e) {
    if (host?.isConnected && getComputedStyle(host).visibility !== 'hidden' && !(e.metaKey || e.ctrlKey)) { e.preventDefault(); e.stopImmediatePropagation(); }
  }
  for (const type of ['keydown','beforeinput','submit']) document.addEventListener(type, blockInput, true);
  function detach() {
    stopped = true; active = false; clear(); clearInterval(timer);
    for (const observer of observers) observer.disconnect();
    for (const root of roots) root.removeEventListener('play', pauseMedia, true);
    for (const type of ['keydown','beforeinput','submit']) document.removeEventListener(type, blockInput, true);
    delete globalThis.__focusDeckSiteAttached;
  }
  async function refresh() {
    if (busy) return; busy = true;
    try {
      const r = await chrome.runtime.sendMessage({kind:'status'});
      if (r?.selected !== true) { detach(); return; }
      const wasActive = active && performance.now() < activeUntil;
      active = r?.phase === 'active' && r.selected === true;
      activeUntil = active ? performance.now() + 5000 : 0;
      if (active) { if (!wasActive) discover(document); pauseExisting(); if (mainFrame) show(); }
      else if (mainFrame) clear();
    } catch { detach(); } finally { busy = false; }
  }
  timer = setInterval(() => { if (!stopped) refresh(); }, 1000); refresh();
})();
