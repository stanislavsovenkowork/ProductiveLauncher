let enabled = false, busy = false, recoveryTimer = null;
const toggle = document.getElementById('toggle'), recover = document.getElementById('recover'), notice = document.getElementById('notice');
async function refresh() {
 try {
  const r = await chrome.runtime.sendMessage({kind:'status'});
  enabled = r.enabled === true;
  document.getElementById('coverage').textContent = enabled ? 'Действие на выбранные сайты включено.' : 'Действие на сайты выключено.';
  document.getElementById('state').textContent = r.phase === 'active' ? `Окно активно · доменов: ${r.domains.length}` : r.phase === 'released' ? 'Ограничение сейчас не действует.' : 'Связь неизвестна · страницы доступны.';
  if (!busy) { toggle.disabled = false; toggle.textContent = enabled ? 'Выключить действие на сайты' : 'Включить выбранные сайты'; }
 } catch { document.getElementById('state').textContent = 'Связь неизвестна · страницы доступны.'; }
}
function explain(result) {
 clearTimeout(recoveryTimer);
 if (result?.ok) {
  recover.hidden = true; recover.disabled = true;
  notice.textContent = result.reason === 'recovered_without_connection' ? 'Действие выключено после повторной проверки недоступной связи.' : '';
 } else if (result?.reason === 'window_open') {
  recover.hidden = true; recover.disabled = true;
  notice.textContent = 'Окно ещё действует, в том числе во время послабления. Завершите его осознанно в FocusDeck, затем выключите расширение.';
 } else if (result?.reason === 'connection_unknown' || result?.reason === 'recovery_wait') {
  recover.hidden = false; recover.disabled = true;
  notice.textContent = 'Связь не подтверждена. Через 5 секунд можно повторно проверить её и отключить действие при отсутствии связи.';
  recoveryTimer = setTimeout(() => { recover.disabled = false; }, Math.max(0,result.waitMs || 5000));
 } else {
  recover.hidden = true; recover.disabled = true;
  notice.textContent = result?.reason === 'recovery_not_started' ? 'Сначала повторите обычную попытку выключения.' : 'Изменение не подтверждено.';
 }
}
async function act(message) {
 busy = true; toggle.disabled = true; recover.disabled = true; notice.textContent = 'Проверяем связь…';
 try { explain(await chrome.runtime.sendMessage(message)); }
 catch { explain({ok:false,reason:'operation_failed'}); }
 finally { busy = false; await refresh(); }
}
toggle.addEventListener('click', () => act({kind:'setEnabled', enabled:!enabled}));
recover.addEventListener('click', () => act({kind:'recoverDisable'}));
refresh(); setInterval(refresh, 2000);
