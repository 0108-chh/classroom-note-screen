'use strict';

const $ = (s) => document.querySelector(s);
let info = null;
let lastSeq = 0;

function toast(msg, kind = '') {
  const el = document.createElement('div');
  el.className = 'toast ' + kind;
  el.textContent = msg;
  $('#toasts').appendChild(el);
  setTimeout(() => {
    el.style.transition = 'opacity .2s, transform .2s';
    el.style.opacity = '0';
    el.style.transform = 'translateY(-6px)';
    setTimeout(() => el.remove(), 220);
  }, 2000);
}

async function call(name, ...args) {
  if (!window.pywebview || !window.pywebview.api) return null;
  try {
    return await window.pywebview.api[name](...args);
  } catch (e) {
    console.warn(name, e);
    return null;
  }
}

const STATE_TEXT = { online: '已连接', connecting: '连接中', offline: '重连中', error: '失败' };

/* 节点显示名：主机名太长会挤压右侧卡片 */
const HOST_ALIAS = {
  'broker-cn.emqx.io': 'EMQX 中国',
  'broker.emqx.io': 'EMQX 国际',
  'broker.hivemq.com': 'HiveMQ',
  'test.mosquitto.org': 'Mosquitto',
};

function renderBrokers(states) {
  const ul = $('#brokers');
  ul.innerHTML = '';
  Object.entries(states || {}).forEach(([host, st]) => {
    const li = document.createElement('li');
    li.className = st;
    const label = HOST_ALIAS[host] || host;
    li.innerHTML = `<i></i><span class="h">${label}</span><span class="state">${STATE_TEXT[st] || st}</span>`;
    li.title = host;
    ul.appendChild(li);
  });
  const online = Object.values(states || {}).filter((s) => s === 'online').length;
  const chip = $('#chip');
  chip.classList.remove('on', 'off');
  if (online > 0) {
    chip.classList.add('on');
    $('#chipText').textContent = `通道正常 · ${online} 路在线`;
  } else {
    chip.classList.add('off');
    $('#chipText').textContent = '通道未连通，正在重试…';
  }
}

function timeText(ts) {
  const d = new Date((ts || 0) * 1000);
  const now = new Date();
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  if (d.toDateString() === now.toDateString()) return hm;
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}

function renderNotes(notes) {
  const ul = $('#notes');
  ul.innerHTML = '';
  if (!notes || !notes.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = '还没有便签。老师在手机 App 里发一条，就会立刻弹在这台大屏上。';
    ul.appendChild(li);
    return;
  }
  notes.forEach((n) => {
    const li = document.createElement('li');
    li.className = n.color || 'amber';
    const txt = document.createElement('div');
    txt.className = 'txt';
    txt.textContent = n.text || '';
    const tm = document.createElement('div');
    tm.className = 'tm';
    tm.textContent = timeText(n.ts);
    li.append(txt, tm);
    ul.appendChild(li);
  });
}

function renderInfo(d) {
  $('#devId').textContent = d.device_id;
  $('#devKey').textContent = d.device_key.slice(0, 4) + ' ' + d.device_key.slice(4);
  $('#devTag').textContent = '设备号 ' + d.device_id;
  $('#devName').value = d.name || '';
  $('#qr').src = d.qr;
  renderBrokers(d.brokers);
  renderNotes(d.notes);
}

async function poll() {
  const d = await call('get_status');
  if (!d) return;
  renderBrokers(d.brokers);
  if (d.notes_seq !== lastSeq) {
    lastSeq = d.notes_seq;
    renderNotes(d.notes);
  }
  if (d.device_id) $('#devId').textContent = d.device_id;
}

async function boot() {
  await new Promise((res) => {
    if (window.pywebview && window.pywebview.api) return res();
    window.addEventListener('pywebviewready', res, { once: true });
    setTimeout(res, 2500);
  });
  info = await call('get_state');
  if (info) {
    renderInfo(info);
    lastSeq = info.notes_seq || 0;
  } else {
    toast('原生接口未就绪', 'err');
  }
  setInterval(poll, 2200);
}

// 设备改名
let nameTimer = null;
$('#devName').addEventListener('input', (e) => {
  clearTimeout(nameTimer);
  nameTimer = setTimeout(async () => {
    const r = await call('set_name', e.target.value);
    if (r && r.qr) {
      $('#qr').src = r.qr;
      toast('名称已更新，重新扫码即可带着新名字绑定');
    }
  }, 700);
});

$('#btnTest').addEventListener('click', async () => {
  await call('test_note');
});
$('#btnClear').addEventListener('click', async () => {
  await call('clear_notes');
  renderNotes([]);
  toast('记录已清空');
});
$('#btnHide').addEventListener('click', () => call('hide_dashboard'));
$('#btnQuit').addEventListener('click', async () => {
  const ok = await call('confirm_quit');
  if (ok) await call('quit_app');
});

window.addEventListener('pywebviewready', () => {});
boot();
