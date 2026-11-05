async function check() {
 const result = document.getElementById('result'); result.textContent = 'Проверяем…';
 try {
  const response = await chrome.runtime.sendNativeMessage('dev.stanislav.focusdeck.sites',{kind:'status'});
  result.textContent = ['active','released'].includes(response?.phase) ? 'Мост и служба FocusDeck отвечают.' : 'Мост запущен, но состояние службы неизвестно.';
 } catch { result.textContent = 'Мост не найден или не отвечает. Проверьте регистрацию в FocusDeck.'; }
}
document.getElementById('again').addEventListener('click',check); check();
