'use strict';

const $ = (s) => document.querySelector(s);
window.__alive = true;

async function call(name, ...args) {
  if (!window.pywebview || !window.pywebview.api) return null;
  try { return await window.pywebview.api[name](...args); } catch (e) { return null; }
}

function fmtTime(ts) {
  const d = new Date((ts || Date.now() / 1000) * 1000);
  const hm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  const now = new Date();
  return d.toDateString() === now.toDateString()
    ? hm
    : `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`;
}

/* 内容量出来的高度，交给原生窗口自适应 */
function fitHeight() {
  const body = $('#body');
  const contentH = Math.min(body.scrollHeight, 470);
  const barTop = $('.bar').offsetHeight;
  const barBot = document.querySelector('.bar.bottom').offsetHeight;
  const need = Math.ceil(3 + barTop + contentH + barBot + 6);
  call('note_size', Math.max(128, Math.min(560, need)));
}

window.renderNote = function (data) {
  document.body.className = data.color || 'amber';
  $('#text').textContent = data.text || '';
  $('#meta').textContent = data.from ? '来自 ' + data.from : '来自老师的手机';
  $('#time').textContent = fmtTime(data.ts);
  const total = data.total || 1;
  const idx = (data.index || 0) + 1;
  $('#counter').textContent = `${idx} / ${total}`;
  $('#nav').style.display = total > 1 ? 'inline-flex' : 'none';
  requestAnimationFrame(() => requestAnimationFrame(fitHeight));
};

$('#btnClose').addEventListener('click', () => call('close_note'));
$('#btnPrev').addEventListener('click', () => call('nav', -1));
$('#btnNext').addEventListener('click', () => call('nav', 1));

$('#bar').addEventListener('mousedown', (e) => {
  if (e.target.closest('.x')) return;
  call('begin_drag');
});

window.addEventListener('pywebviewready', () => {
  call('note_booted');
});
