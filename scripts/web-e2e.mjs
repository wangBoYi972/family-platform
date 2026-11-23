// 前端联通性 E2E：真实浏览器登录后逐个打开主标签与子标签，
// 记录所有 /api 请求里 HTTP>=400 或业务 code!==0 的调用，以及页面异常。
// 用法：node scripts/web-e2e.mjs <url> [outPng]
// 依赖：CDP 无头 Chrome（CDP_PORT，默认 9334）。

import http from 'node:http';
import net from 'node:net';
import crypto from 'node:crypto';
import fs from 'node:fs';
import { URL } from 'node:url';

const [, , targetUrl, outPath] = process.argv;
const CDP_PORT = Number(process.env.CDP_PORT || 9334);
if (!targetUrl) {
  console.error('用法: node scripts/web-e2e.mjs <url> [outPng]');
  process.exit(2);
}

function httpGet(path) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: CDP_PORT, path }, res => {
      let d = '';
      res.on('data', c => (d += c));
      res.on('end', () => resolve(JSON.parse(d)));
    }).on('error', reject);
  });
}

function wsConnect(wsUrl) {
  const u = new URL(wsUrl);
  return new Promise((resolve, reject) => {
    const key = crypto.randomBytes(16).toString('base64');
    const sock = net.connect(Number(u.port), u.hostname, () => {
      sock.write(
        `GET ${u.pathname} HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\n` +
        `Connection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`
      );
    });
    let handshake = false;
    let buf = Buffer.alloc(0);
    const handlers = [];
    let id = 0;
    const pending = new Map();

    sock.on('data', chunk => {
      buf = Buffer.concat([buf, chunk]);
      if (!handshake) {
        const idx = buf.indexOf('\r\n\r\n');
        if (idx === -1) return;
        if (!buf.slice(0, idx).toString().includes('101')) return reject(new Error('WS 握手失败'));
        buf = buf.slice(idx + 4);
        handshake = true;
        resolve({
          send(method, params) {
            const msgId = ++id;
            const data = Buffer.from(JSON.stringify({ id: msgId, method, params }));
            const mask = crypto.randomBytes(4);
            const masked = Buffer.alloc(data.length);
            for (let i = 0; i < data.length; i++) masked[i] = data[i] ^ mask[i % 4];
            let header;
            if (data.length < 126) {
              header = Buffer.alloc(6); header[0] = 0x81; header[1] = 0x80 | data.length; mask.copy(header, 2);
            } else if (data.length < 65536) {
              header = Buffer.alloc(8); header[0] = 0x81; header[1] = 0x80 | 126;
              header.writeUInt16BE(data.length, 2); mask.copy(header, 4);
            } else {
              header = Buffer.alloc(14); header[0] = 0x81; header[1] = 0x80 | 127;
              header.writeBigUInt64BE(BigInt(data.length), 2); mask.copy(header, 10);
            }
            sock.write(Buffer.concat([header, masked]));
            return new Promise((res2, rej2) => pending.set(msgId, { res2, rej2 }));
          },
          on(fn) { handlers.push(fn); }
        });
      }
      while (buf.length >= 2) {
        const len0 = buf[1] & 0x7f;
        let off = 2, len = len0;
        if (len0 === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len0 === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const payload = buf.slice(off, off + len).toString();
        buf = buf.slice(off + len);
        try {
          const msg = JSON.parse(payload);
          if (msg.id && pending.has(msg.id)) {
            const p = pending.get(msg.id); pending.delete(msg.id);
            msg.error ? p.rej2(new Error(JSON.stringify(msg.error))) : p.res2(msg.result);
          } else if (msg.method) handlers.forEach(h => h(msg));
        } catch { /* 忽略非 JSON 帧 */ }
      }
    });
    sock.on('error', reject);
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function evalJs(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || '页面脚本异常');
  return r.result?.value;
}

// 页面内：拦截 XHR/fetch 失败 + 错误事件，登录，然后遍历标签
const scenario = (labels, opts) => `(async () => {
  const failures = [];
  const errors = [];
  const push = (kind, detail) => { if (failures.length < 300) failures.push({ kind, ...detail }); };
  const inspect = (url, status, body) => {
    if (!String(url).includes('/api/')) return;
    let code = null; let msg = '';
    try { const j = JSON.parse(body); code = j.code; msg = j.msg; } catch {}
    if (status >= 400 || (code !== null && code !== 0)) {
      push('api', { url: String(url).replace(location.origin, ''), status, code, msg });
    }
  };
  const OrigOpen = XMLHttpRequest.prototype.open;
  const OrigSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u) { this.__u = u; return OrigOpen.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function () {
    this.addEventListener('loadend', () => { try { inspect(this.__u, this.status, this.responseText); } catch {} });
    return OrigSend.apply(this, arguments);
  };
  const origFetch = window.fetch;
  window.fetch = async function (...args) {
    const res = await origFetch.apply(this, args);
    try {
      const url = typeof args[0] === 'string' ? args[0] : args[0].url;
      const text = await res.clone().text();
      inspect(url, res.status, text);
    } catch {}
    return res;
  };
  window.addEventListener('unhandledrejection', e => errors.push('unhandledrejection: ' + (e.reason?.message || e.reason)));
  window.addEventListener('error', e => errors.push('error: ' + e.message));

  const wait = ms => new Promise(r => setTimeout(r, ms));
  // 底部导航按钮里还有图标文字，不能只按完全相等匹配：先找相等，再退化到包含。
  const findByText = (root, text) => {
    if (!root) return null;
    const all = [...root.querySelectorAll('*')];
    const exact = all.filter(n => (n.innerText || '').trim() === text);
    if (exact.length) return exact[exact.length - 1];
    const loose = all.filter(n => (n.innerText || '').includes(text));
    loose.sort((a, b) => (a.innerText || '').length - (b.innerText || '').length);
    return loose[0] || null;
  };
  // 按文本找可点击元素：只按包含匹配会先命中页面标题（如「进入我们的家」），
  // 所以先在按钮类元素里找，找不到再退回全量文本匹配。
  const findClickable = text => {
    const sel = 'button, uni-button, [role="button"], .btn, .el-button, [class*="btn"]';
    const cands = [...document.querySelectorAll(sel)].filter(n => (n.innerText || '').includes(text));
    cands.sort((a, b) => (a.innerText || '').length - (b.innerText || '').length);
    return cands[0] || null;
  };
  const clickIn = (rootSelector, text) => {
    const el = findByText(document.querySelector(rootSelector), text);
    if (!el) return false;
    el.click();
    return true;
  };
  const clickText = (selector, text) => {
    const el = [...document.querySelectorAll(selector)].find(n => (n.innerText || '').trim() === text);
    if (el) { el.click(); return true; }
    return false;
  };
  const setInput = (el, value) => {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };

  // 登录（若无表单说明已有会话）
  // uni-app 会把 class 挂在外层自定义元素上，真正的输入框是内层原生 input。
  const inputs = [...document.querySelectorAll('input')].filter(i => !['checkbox', 'radio', 'file'].includes(i.type));
  let loggedIn = false;
  let debug = '';
  if (inputs.length >= 2) {
    setInput(inputs[0], '19900000001');
    setInput(inputs[1], '123456');
    await wait(200);
    debug = 'values=' + inputs.map(i => i.value).join(',');
    const btn = (() => {
      for (const label of ${JSON.stringify(String(opts.loginButton).split('|'))}) {
        const found = findClickable(label) || findByText(document.body, label);
        if (found) return found;
      }
      return null;
    })();
    if (btn) btn.click();
    debug += ' clicked=' + !!btn;
    for (let i = 0; i < 40; i++) { await wait(300); if (document.querySelector(${JSON.stringify(opts.navSelector)})) { loggedIn = true; break; } }
    debug += ' after=' + (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 200);
  } else {
    loggedIn = !!document.querySelector(${JSON.stringify(opts.navSelector)});
  }

  const visited = [];
  if (loggedIn) {
    for (const label of ${JSON.stringify(labels)}) {
      const ok = clickIn(${JSON.stringify(opts.navSelector)}, label);
      visited.push({ label, clicked: ok });
      await wait(1400);
      // 子标签：把当前面板里的胶囊按钮都点一遍
      const subs = [...document.querySelectorAll(${JSON.stringify(opts.subSelector)} + ' > *')].map(b => (b.innerText || '').trim()).filter(Boolean);
      for (const sub of subs) {
        clickIn(${JSON.stringify(opts.subSelector)}, sub);
        await wait(1200);
        visited.push({ label: label + '/' + sub, clicked: true });
      }
    }
  }
  return JSON.stringify({ loggedIn, debug, visited, failures, errors });
})()`;

(async () => {
  // 尽量新开一个标签，避免与其他调试会话共用同一个页面目标。
  let page;
  try {
    page = await new Promise((resolve, reject) => {
      const req = http.request(
        { host: '127.0.0.1', port: CDP_PORT, path: '/json/new?about:blank', method: 'PUT' },
        res => { let d = ''; res.on('data', c => (d += c)); res.on('end', () => resolve(JSON.parse(d))); }
      );
      req.on('error', reject);
      req.end();
    });
  } catch {
    const targets = await httpGet('/json/list');
    page = targets.find(t => t.type === 'page');
  }
  if (!page) throw new Error('没有可用的页面目标');
  const cdp = await wsConnect(page.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  await cdp.send('Page.navigate', { url: targetUrl });
  await sleep(5000);

  const labels = (process.env.TAB_LABELS || '首页,日历,任务,账本,聊天,家庭圈,家居,我的家').split(',');
  // 登录按钮文案各端不同（管理端改版后是「进 入」，家庭端是「进入家庭空间」），
  // 用 | 分隔多候选，按顺序取第一个命中的元素。
  const raw = await evalJs(cdp, scenario(labels, { navSelector: process.env.NAV_SELECTOR || '.navigation', subSelector: process.env.SUB_SELECTOR || '.subnav', loginButton: process.env.LOGIN_BTN || '进入家庭空间|查看家庭档案|进 入|登 录|登录' }));
  const report = JSON.parse(raw);

  console.log('URL:', targetUrl);
  console.log('登录成功:', report.loggedIn); if (report.debug) console.log('调试:', report.debug);
  console.log('打开的面板:', report.visited.map(v => v.label + (v.clicked ? '' : '(未找到)')).join(' | '));
  console.log('\n接口失败数:', report.failures.length);
  const seen = new Set();
  for (const f of report.failures) {
    const key = f.url + '|' + f.status + '|' + f.code;
    if (seen.has(key)) continue;
    seen.add(key);
    console.log(`  - ${f.url} -> ${f.status}/${f.code} ${f.msg || ''}`);
  }
  if (report.errors.length) {
    console.log('\n页面异常:');
    for (const e of [...new Set(report.errors)]) console.log('  - ' + e);
  }

  if (outPath) {
    const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(outPath, Buffer.from(shot.data, 'base64'));
    console.log('\n截图:', outPath);
  }
  process.exit(report.failures.length || report.errors.length ? 1 : 0);
})().catch(e => { console.error('E2E 失败:', e.message); process.exit(2); });
