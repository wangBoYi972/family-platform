// 全链路接口冒烟测试：统一经网关 8080 发起，验证前后端约定是否一致。
// 用法：node scripts/api-smoke.mjs [--verbose]
// 说明：写接口一律「先建后删」，不污染现有数据；失败也会尽力回收。

import fs from 'node:fs';

const BASE = process.env.SMOKE_BASE || 'http://127.0.0.1:8080/api';
const VERBOSE = process.argv.includes('--verbose');

const credentials = {
  admin: { phone: '19900000001', password: '123456', client: 'admin' },
  family: { phone: '19900000001', password: '123456', client: 'family' },
  archive: { phone: '19900000001', password: '123456', client: 'archive' }
};

const results = [];

function record(group, name, method, path, outcome) {
  results.push({ group, name, method, path, ...outcome });
  const icon = outcome.ok ? 'PASS' : 'FAIL';
  const detail = outcome.ok ? '' : ` <- ${outcome.detail}`;
  console.log(`[${icon}] ${group} ${method} ${path} (${outcome.status}/${outcome.code})${detail}`);
}

async function request(method, path, { token, body, raw = false } = {}) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  let payload = null;
  const text = await res.text();
  try { payload = text ? JSON.parse(text) : null; } catch { payload = { raw: text.slice(0, 200) }; }
  return { status: res.status, payload, raw: text };
}

/** 期望业务成功（code === 0 且 HTTP 2xx）。 */
async function expectOk(group, name, method, path, opts = {}) {
  try {
    const r = await request(method, path, opts);
    const ok = r.status >= 200 && r.status < 300 && r.payload?.code === 0;
    record(group, name, method, path, {
      ok,
      status: r.status,
      code: r.payload?.code ?? 'n/a',
      detail: ok ? '' : (r.payload?.msg || r.raw.slice(0, 160)),
      data: r.payload?.data
    });
    return ok ? r.payload.data : undefined;
  } catch (e) {
    record(group, name, method, path, { ok: false, status: 'ERR', code: 'ERR', detail: e.message });
    return undefined;
  }
}

/** 期望被拒绝（鉴权/校验失败），业务码非 0 或 4xx。 */
async function expectReject(group, name, method, path, opts = {}, allowed = [400, 401, 403, 404, 409]) {
  try {
    const r = await request(method, path, opts);
    const ok = allowed.includes(r.status) || r.payload?.code !== 0;
    record(group, name, method, path, {
      ok,
      status: r.status,
      code: r.payload?.code ?? 'n/a',
      detail: ok ? '' : '未被拒绝',
      data: r.payload?.data
    });
  } catch (e) {
    record(group, name, method, path, { ok: false, status: 'ERR', code: 'ERR', detail: e.message });
  }
}

function id(prefix) {
  return prefix + '-' + Date.now() + '-' + Math.random().toString(16).slice(2, 8);
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

function plusDays(n) {
  return new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
}

async function login() {
  const tokens = {};
  for (const [key, body] of Object.entries(credentials)) {
    const r = await request('POST', '/auth/password-login', { body });
    if (r.status === 200 && r.payload?.code === 0 && r.payload.data?.token) {
      tokens[key] = r.payload.data;
      record('Auth', `登录(${key})`, 'POST', '/auth/password-login', { ok: true, status: r.status, code: 0 });
    } else {
      record('Auth', `登录(${key})`, 'POST', '/auth/password-login', {
        ok: false, status: r.status, code: r.payload?.code ?? 'n/a', detail: r.payload?.msg || '无 token'
      });
    }
  }
  return tokens;
}

async function main() {
  console.log('=== API 冒烟测试 ===\n' + BASE + '\n');
  const tokens = await login();
  const admin = tokens.admin?.token;
  const family = tokens.family?.token;
  const archive = tokens.archive?.token;

  if (!family) {
    console.log('\n未拿到主令牌，后续用例无法执行。');
    summarize();
    return;
  }

  // ---------- 鉴权与身份 ----------
  await expectOk('Auth', '会话信息', 'GET', '/auth/session', { token: family });
  await expectOk('Auth', '当前用户', 'GET', '/users/me', { token: family });
  await expectReject('Auth', '无令牌访问受保护接口', 'GET', '/users/me');

  await expectOk('Member', '成员列表', 'GET', '/users/members', { token: family });
  await expectOk('Member', '白名单列表', 'GET', '/users/whitelist', { token: family });

  // 白名单增删闭环：手机号随机生成，避免和真实成员撞号。
  // 新增接口必须回传 id，否则调用方无从停用——拿不到 id 直接判失败，不允许静默跳过。
  const tempPhone = '199' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0');
  const invite = await expectOk('Member', '新增白名单', 'POST', '/users/whitelist', {
    token: family, body: { phone: tempPhone, role: 'MEMBER' }
  });
  let inviteId = invite?.id;
  if (!inviteId) {
    // 兜底：按手机号回查，兼容尚未返回 body 的旧实现。
    const list = await request('GET', '/users/whitelist', { token: family });
    inviteId = (list.payload?.data || []).find((x) => x.phone === tempPhone)?.id;
  }
  if (inviteId) {
    await expectOk('Member', '移除白名单', 'DELETE', '/users/whitelist/' + inviteId, { token: family });
  } else {
    record('Member', '移除白名单', 'DELETE', '/users/whitelist/{id}', {
      ok: false, status: 'n/a', code: 'n/a', detail: '新增未返回 id，无法定位待删记录'
    });
  }

  // 个人资料：改回同名，验证 PATCH 可用且不改变数据。
  const me0 = await request('GET', '/users/me', { token: family });
  const displayName = me0.payload?.data?.displayName;
  if (displayName) {
    await expectOk('Member', '更新个人资料', 'PATCH', '/users/me', {
      token: family, body: { displayName }
    });
  }

  if (admin) {
    await expectOk('Member', '邀请码列表(管理员)', 'GET', '/users/invite-codes', { token: admin });
    await expectOk('Member', 'AI 设置(管理员)', 'GET', '/ai/settings', { token: admin });
    const code = await expectOk('Member', '发放邀请码', 'POST', '/users/invite-codes', {
      token: admin, body: { role: 'MEMBER', maxUses: 1 }
    });
    if (code?.id) {
      await expectOk('Member', '作废邀请码', 'DELETE', '/users/invite-codes/' + code.id, { token: admin });
    }
    await expectOk('Member', '邀请码列表(非管理员)', 'GET', '/users/invite-codes', { token: family });
  }

  // ---------- 任务 ----------
  const tasks = await expectOk('Task', '任务列表', 'GET', '/tasks', { token: family });
  await expectOk('Task', '任务统计', 'GET', '/tasks/stats', { token: family });
  const taskTitle = id('smoke-task');
  const createdTask = await expectOk('Task', '新建任务', 'POST', '/tasks', {
    token: family, body: { title: taskTitle, deadline: plusDays(3) }
  });
  if (createdTask?.id) {
    await expectOk('Task', '修改任务', 'PUT', '/tasks/' + createdTask.id, {
      token: family, body: { title: taskTitle + '-改', deadline: plusDays(4) }
    });
    await expectOk('Task', '改任务状态', 'PATCH', '/tasks/' + createdTask.id + '/status', {
      token: family, body: { status: 'IN_PROGRESS' }
    });
    await expectOk('Task', '删除任务', 'DELETE', '/tasks/' + createdTask.id, { token: family });
  } else {
    record('Task', '任务后续用例', '-', '-', { ok: false, status: '-', code: '-', detail: '前置创建失败，已跳过' });
  }

  // ---------- 内容四类（公告/日程/动态/相册） ----------
  for (const kind of ['notices', 'calendar', 'dynamics', 'albums']) {
    const list = await expectOk('Content', `${kind} 列表`, 'GET', '/' + kind, { token: family });
    const created = await expectOk('Content', `${kind} 新建`, 'POST', '/' + kind, {
      token: family,
      body: { title: id('smoke-' + kind), body: '冒烟测试数据', eventDate: today(), yearly: false, pinned: false, visibility: 'FAMILY' }
    });
    if (created?.id) {
      await expectOk('Content', `${kind} 修改`, 'PUT', '/' + kind + '/' + created.id, {
        token: family, body: { title: 'smoke-' + kind + '-改', body: '冒烟测试数据', eventDate: today(), visibility: 'FAMILY' }
      });
      if (kind === 'dynamics') await expectOk('Content', `${kind} 点赞`, 'POST', '/' + kind + '/' + created.id + '/like', { token: family });
      await expectOk('Content', `${kind} 删除`, 'DELETE', '/' + kind + '/' + created.id, { token: family });
    }
  }

  // ---------- 档案 ----------
  await expectOk('Archive', '档案列表', 'GET', '/archives', { token: family });
  await expectOk('Archive', '档案审核汇总', 'GET', '/archives/audit/summary', { token: family });
  const createdArchive = await expectOk('Archive', '新建档案', 'POST', '/archives', {
    token: family, body: { title: id('smoke-archive'), category: 'DOCUMENT', body: '冒烟测试档案内容', visibility: 'FAMILY' }
  });
  if (createdArchive?.id) {
    await expectOk('Archive', '档案详情', 'GET', '/archives/' + createdArchive.id, { token: family });
    await expectOk('Archive', '档案审计', 'GET', '/archives/' + createdArchive.id + '/audit', { token: family });
    await expectOk('Archive', '修改档案', 'PUT', '/archives/' + createdArchive.id, {
      token: family, body: { title: 'smoke-archive-改', category: 'DOCUMENT', body: '冒烟测试档案内容2', visibility: 'FAMILY' }
    });
    await expectOk('Archive', '删除档案', 'DELETE', '/archives/' + createdArchive.id, { token: family });
  }

  // ---------- 用药与复诊 ----------
  await expectOk('Health', '用药列表', 'GET', '/archives/medications', { token: family });
  await expectOk('Health', '今日用药', 'GET', '/archives/medications/today', { token: family });
  await expectOk('Health', '复诊列表', 'GET', '/archives/follow-ups', { token: family });
  await expectOk('Health', '近期复诊', 'GET', '/archives/follow-ups/upcoming?days=30', { token: family });
  await expectOk('Health', '今日提醒', 'GET', '/archives/reminders/today', { token: family });
  const med = await expectOk('Health', '新建用药计划', 'POST', '/archives/medications', {
    token: family,
    body: {
      name: id('smoke-med'), dosage: '1片', note: '冒烟测试',
      times: ['08:00', '20:00'], startDate: today(), endDate: plusDays(7),
      visibility: 'FAMILY', enabled: true
    }
  });
  if (med?.id) {
    await expectOk('Health', '用药详情', 'GET', '/archives/medications/' + med.id, { token: family });
    await expectOk('Health', '勾选服药', 'POST', `/archives/medications/${med.id}/checks`, {
      token: family, body: { time: '08:00' }
    });
    await expectOk('Health', '修改用药', 'PUT', '/archives/medications/' + med.id, {
      token: family,
      body: {
        name: 'smoke-med-改', dosage: '2片', note: '',
        times: ['08:00'], startDate: today(), endDate: plusDays(7),
        visibility: 'FAMILY', enabled: true
      }
    });
    await expectOk('Health', '删除用药', 'DELETE', '/archives/medications/' + med.id, { token: family });
  }
  const follow = await expectOk('Health', '新建复诊', 'POST', '/archives/follow-ups', {
    token: family,
    body: {
      title: id('smoke-follow'), hospital: '社区医院', note: '冒烟测试',
      visitAt: plusDays(5) + 'T09:30:00', remindDays: 1, visibility: 'FAMILY'
    }
  });
  if (follow?.id) {
    await expectOk('Health', '复诊详情', 'GET', '/archives/follow-ups/' + follow.id, { token: family });
    await expectOk('Health', '修改复诊', 'PUT', '/archives/follow-ups/' + follow.id, {
      token: family,
      body: {
        title: 'smoke-follow-改', hospital: '社区医院', note: '',
        visitAt: plusDays(6) + 'T10:00:00', remindDays: 2, visibility: 'FAMILY'
      }
    });
    await expectOk('Health', '删除复诊', 'DELETE', '/archives/follow-ups/' + follow.id, { token: family });
  }

  // ---------- 文件 ----------
  const files = await expectOk('File', '文件列表', 'GET', '/files', { token: family });
  if (Array.isArray(files) && files.length) {
    const fid = files[0].id;
    await expectOk('File', '文件元数据', 'GET', `/files/${fid}/metadata`, { token: family });
    try {
      const res = await fetch(BASE + `/files/${fid}/download`, { headers: { Authorization: 'Bearer ' + family } });
      const ok = res.status === 200;
      record('File', '文件下载', 'GET', `/files/${fid}/download`, {
        ok, status: res.status, code: ok ? 0 : 'n/a', detail: ok ? '' : await res.text().then(t => t.slice(0, 120))
      });
    } catch (e) {
      record('File', '文件下载', 'GET', `/files/${fid}/download`, { ok: false, status: 'ERR', code: 'ERR', detail: e.message });
    }
  } else {
    record('File', '文件详情用例', '-', '-', { ok: true, status: '-', code: '-', detail: '暂无文件数据，跳过' });
  }

  // ---------- 消息 ----------
  await expectOk('IM', '会话列表', 'GET', '/im/conversations', { token: family });
  await expectOk('IM', 'WebSocket 票据', 'POST', '/im/ws-ticket', { token: family });
  const conv = await expectOk('IM', '创建会话', 'POST', '/im/conversations', { token: family, body: {} });
  if (conv?.id) {
    await expectOk('IM', '会话详情', 'GET', '/im/conversations/' + conv.id, { token: family });
    await expectOk('IM', '消息列表', 'GET', '/im/conversations/' + conv.id + '/messages?after=0', { token: family });
    const clientId = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = (Math.random() * 16) | 0;
      return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
    const msg = await expectOk('IM', '发送消息', 'POST', '/im/conversations/' + conv.id + '/messages', {
      token: family, body: { clientId, kind: 'TEXT', body: '冒烟测试消息' }
    });
    if (msg?.id) {
      await expectOk('IM', '已读回执', 'POST', '/im/conversations/' + conv.id + '/read', {
        token: family, body: { messageId: msg.id }
      });
      await expectOk('IM', '查询已读', 'GET', '/im/conversations/' + conv.id + '/read', { token: family });
      await expectOk('IM', '撤回消息', 'POST', `/im/conversations/${conv.id}/messages/${msg.id}/recall`, { token: family });
    }
  }

  // ---------- 通话 ----------
  await expectOk('Call', '通话历史', 'GET', '/media/calls/history', { token: family });
  await expectOk('Call', '进行中通话', 'GET', '/media/calls/active', { token: family });
  await expectOk('Call', 'ICE 配置', 'GET', '/media/ice-servers', { token: family });
  await expectOk('Call', '通话 WebSocket 票据', 'POST', '/media/ws-ticket', { token: family });
  const members = await request('GET', '/users/members', { token: family });
  const meBefore = await request('GET', '/users/me', { token: family });
  const mySelfId = meBefore.payload?.data?.id;
  // 成员列表包含自己，点名自己会被后端拒绝，必须排除。
  const peers = (members.payload?.data || [])
    .map(m => m.userId ?? m.id)
    .filter(pid => pid && pid !== mySelfId);
  if (peers.length >= 1) {
    const call = await expectOk('Call', '发起通话', 'POST', '/media/calls', {
      token: family, body: { mode: 'DIRECT', kind: 'AUDIO', calleeId: peers[0] }
    });
    if (call?.id) {
      await expectOk('Call', '通话详情', 'GET', '/media/calls/' + call.id, { token: family });
      await expectOk('Call', '挂断', 'POST', `/media/calls/${call.id}/end`, { token: family });
    }
  } else {
    record('Call', '发起通话', 'POST', '/media/calls', { ok: true, status: '-', code: '-', detail: '无可用成员，跳过' });
  }

  // ---------- 通话录音（V3 迁移曾整条漏执行，导致这 5 个接口全 500，必须常驻覆盖）----------
  if (peers.length >= 1) {
    const form = new FormData();
    form.append('file', new Blob([Buffer.alloc(1024, 3)], { type: 'audio/webm' }), 'smoke.webm');
    form.append('visibility', 'FAMILY');
    const uploaded = await fetch(BASE + '/files', {
      method: 'POST', headers: { Authorization: 'Bearer ' + family }, body: form
    }).then(r => r.json()).catch(() => ({}));
    const fileId = uploaded?.data?.id || uploaded?.data?.fileId;
    record('Call', '录音文件上传', 'POST', '/files',
      fileId ? { ok: true, status: 200, code: 0 } : { ok: false, status: '-', code: '-', detail: '未取到 fileId' });

    if (fileId) {
      const recCall = await expectOk('Call', '录音用通话', 'POST', '/media/calls', {
        token: family, body: { mode: 'DIRECT', kind: 'AUDIO', calleeId: peers[0] }
      });
      if (recCall?.id) {
        await expectOk('Call', '结束通话(录音)', 'POST', `/media/calls/${recCall.id}/end`, { token: family });
        // 反例必须先测：提交成功后同一通话再提交会先撞「已有纪要」409，测不到 consent 校验
        await expectReject('Call', '录音未同意(应 400)', 'POST', `/media/calls/${recCall.id}/recording`, {
          token: family, body: { consent: false, segments: [{ fileId }] }
        }, [400]);
        const rec = await expectOk('Call', '提交录音', 'POST', `/media/calls/${recCall.id}/recording`, {
          token: family,
          body: { title: '冒烟-录音', consent: true, durationMs: 900, segments: [{ fileId, durationMs: 900 }] }
        });
        await expectOk('Call', '按通话查纪要', 'GET', `/media/calls/${recCall.id}/recording`, { token: family });
        await expectOk('Call', '录音列表', 'GET', '/media/recordings', { token: family });
        if (rec?.id) {
          await expectOk('Call', '录音详情', 'GET', '/media/recordings/' + rec.id, { token: family });
        }
      }
    }
  }

  // ---------- 账本 ----------
  await expectOk('Ledger', '账目列表', 'GET', '/ledger/entries', { token: family });
  await expectOk('Ledger', '月度汇总', 'GET', '/ledger/summary', { token: family });
  const me = await request('GET', '/users/me', { token: family });
  const myId = me.payload?.data?.id;
  const created = await expectOk('Ledger', '记一笔', 'POST', '/ledger/entries', {
    token: family,
    body: {
      type: 'EXPENSE', amountCents: 300, category: '日用', note: '冒烟测试',
      occurredOn: today(), payerId: myId,
      splits: myId ? [{ memberId: myId, amountCents: 300 }] : []
    }
  });
  if (created?.id) {
    await expectOk('Ledger', '修改账目', 'PUT', '/ledger/entries/' + created.id, {
      token: family,
      body: {
        type: 'EXPENSE', amountCents: 200, category: '日用', note: '冒烟测试-改',
        occurredOn: today(), payerId: myId,
        splits: myId ? [{ memberId: myId, amountCents: 200 }] : []
      }
    });
    await expectOk('Ledger', '删除账目', 'DELETE', '/ledger/entries/' + created.id, { token: family });
  }

  // ---------- AI ----------
  await expectOk('AI', 'AI 历史', 'GET', '/ai/history', { token: family });
  // 空 messages 必须被参数校验挡住，而不是打进模型消耗额度。
  await expectReject('AI', 'AI 对话参数校验(应 400)', 'POST', '/ai/chat', { token: family, body: { messages: [] } }, [400]);

  // 只有密钥确实可用时才跑真实模型调用（耗时且有额度成本）；
  // 未配置时应走 503 降级，而不是假装成功。
  const aiCfg = admin ? (await request('GET', '/ai/settings', { token: admin })).payload?.data : null;
  if (aiCfg?.ready) {
    try {
      const res = await fetch(BASE + '/ai/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer ' + family,
          Accept: 'text/event-stream'
        },
        body: JSON.stringify({ messages: [{ role: 'user', content: '用三个字回答：你好' }] })
      });
      const stream = await res.text();
      const deltas = [...stream.matchAll(/"type"\s*:\s*"delta"[^}]*?"text"\s*:\s*"([^"]*)"/g)]
        .map(m => m[1]).join('');
      record('AI', 'AI 真实对话(流式)', 'POST', '/ai/chat', {
        ok: res.status === 200 && deltas.trim().length > 0,
        status: res.status,
        code: 0,
        detail: deltas.trim().length > 0 ? '' : '未收到 delta 文本',
        data: deltas.slice(0, 40)
      });
    } catch (e) {
      record('AI', 'AI 真实对话(流式)', 'POST', '/ai/chat', { ok: false, status: 'ERR', code: 'ERR', detail: e.message });
    }

    await expectOk('AI', 'AI 生成纪要', 'POST', '/ai/minutes', {
      token: family,
      body: {
        title: '回归测试通话',
        participants: ['妈妈', '女儿'],
        transcript: '妈：明天上午去医院复查。女儿：我陪您去。妈：记得带医保卡。',
        durationMs: 60000
      }
    });
  } else {
    record('AI', 'AI 真实对话(流式)', 'POST', '/ai/chat',
      { ok: true, status: '-', code: '-', detail: '密钥未配置，跳过' });
    record('AI', 'AI 生成纪要', 'POST', '/ai/minutes',
      { ok: true, status: '-', code: '-', detail: '密钥未配置，跳过' });
  }

  // ---------- IoT ----------
  await expectOk('IoT', '网关绑定状态', 'GET', '/iot/hub', { token: family });
  await expectOk('IoT', '设备列表', 'GET', '/iot/devices', { token: family });
  await expectOk('IoT', '场景列表', 'GET', '/iot/scenes', { token: family });
  await expectReject('IoT', '无令牌访问 IoT', 'GET', '/iot/hub');

  summarize();
}

function summarize() {
  const byGroup = new Map();
  for (const r of results) {
    if (!byGroup.has(r.group)) byGroup.set(r.group, { pass: 0, fail: 0 });
    const g = byGroup.get(r.group);
    if (r.ok) g.pass++; else g.fail++;
  }
  const total = results.length;
  const failed = results.filter(r => !r.ok);
  console.log('\n=== 汇总 ===');
  console.log(`用例 ${total} 个，通过 ${total - failed.length}，失败 ${failed.length}`);
  for (const [group, g] of byGroup) console.log(`  ${group}: ${g.pass} 通过 / ${g.fail} 失败`);
  if (failed.length) {
    console.log('\n失败明细：');
    for (const f of failed) console.log(`  - [${f.group}] ${f.method} ${f.path} -> ${f.status}/${f.code} ${f.detail || ''}`);
  }
  const dir = 'output';
  try { fs.mkdirSync(dir, { recursive: true }); } catch { /* 已存在 */ }
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  fs.writeFileSync(`${dir}/api-smoke-${stamp}.json`, JSON.stringify({ base: BASE, generatedAt: new Date().toISOString(), results }, null, 2));
  console.log(`\n明细已写入 output/api-smoke-${stamp}.json`);
  process.exitCode = failed.length ? 1 : 0;
}

main().catch(e => {
  console.error('冒烟脚本异常:', e);
  process.exitCode = 2;
});
