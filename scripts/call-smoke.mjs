#!/usr/bin/env node
/**
 * 实时通话端到端冒烟脚本（真实 HTTP + 真实 WebSocket，经网关）。
 *
 * 覆盖：口令登录 → 邀请码注册第二位成员 → 换一次性票据 → WS 握手 →
 *       发起通话 → 接听 → 双向 SDP 转发 → 挂断 → 历史记录。
 *
 * 前置：user-svc(8081)、media-svc(8090)、gateway(8080) 以 local profile 启动。
 * 用法：FAMILY_MEMBER_PASSWORD=xxx node scripts/call-smoke.mjs [网关地址]
 */
import { requireAccounts } from "./accounts.mjs";

const BASE = (process.argv[2] || "http://127.0.0.1:8080/api").replace(/\/+$/, "");
const WS = BASE.replace(/^http/, "ws").replace(/\/api$/, "") + "/ws/call";

const [ADMIN, MEMBER] = requireAccounts("admin", "member");

const log = (...args) => console.log(...args);
const fail = message => {
  console.error("✗ " + message);
  process.exitCode = 1;
  throw new Error(message);
};

async function api(path, { method = "GET", token, body } = {}) {
  const response = await fetch(BASE + path, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: "Bearer " + token } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.code !== 0) {
    const error = new Error(`${method} ${path} -> ${response.status} ${payload.msg || ""}`);
    error.status = response.status;
    throw error;
  }
  return payload.data;
}

const login = creds =>
  api("/auth/password-login", { method: "POST", body: { ...creds, client: "family" } });

/** 打开一条通话信令连接，返回 { socket, next(type), waiters }。 */
function connect(token) {
  return api("/media/ws-ticket", { method: "POST", token }).then(({ ticket }) => {
    const socket = new WebSocket(`${WS}?ticket=${encodeURIComponent(ticket)}`);
    const queue = [];
    const waiters = [];
    socket.addEventListener("message", event => {
      let payload;
      try {
        payload = JSON.parse(event.data);
      } catch {
        return;
      }
      if (payload.type === "pong") return;
      const index = waiters.findIndex(item => item.type === payload.type);
      if (index >= 0) {
        const [waiter] = waiters.splice(index, 1);
        clearTimeout(waiter.timer);
        waiter.resolve(payload);
      } else {
        queue.push(payload);
      }
    });
    const opened = new Promise((resolve, reject) => {
      socket.addEventListener("open", resolve, { once: true });
      socket.addEventListener("error", () => reject(new Error("WebSocket 连接失败")), { once: true });
    });
    const client = {
      socket,
      opened,
      send(payload) {
        socket.send(JSON.stringify(payload));
      },
      wait(type, timeout = 8000) {
        const index = queue.findIndex(item => item.type === type);
        if (index >= 0) return Promise.resolve(queue.splice(index, 1)[0]);
        return new Promise((resolve, reject) => {
          const waiter = { type, resolve };
          waiter.timer = setTimeout(() => {
            const at = waiters.indexOf(waiter);
            if (at >= 0) waiters.splice(at, 1);
            reject(new Error(`等待 ${type} 超时，已收到：${queue.map(item => item.type).join(",") || "无"}`));
          }, timeout);
          waiters.push(waiter);
        });
      },
      close() {
        try {
          socket.close();
        } catch {
          /* 已关闭 */
        }
      }
    };
    return client;
  });
}

async function main() {
  log("① 登录管理员 …");
  const admin = await login(ADMIN);
  log("   userId=" + admin.userId + " householdId=" + admin.householdId);

  log("② 准备第二位家庭成员 …");
  let member;
  try {
    member = await login(MEMBER);
    log("   已存在，直接登录 userId=" + member.userId);
  } catch {
    const invite = await api("/users/invite-codes", {
      method: "POST",
      token: admin.token,
      body: { role: "MEMBER", maxUses: 1, expiresInHours: 24 }
    });
    const code = invite.code || invite.plainCode;
    if (!code) fail("邀请码接口未返回明文 code：" + JSON.stringify(invite));
    member = await api("/auth/register", {
      method: "POST",
      body: { ...MEMBER, inviteCode: code, displayName: "冒烟成员", client: "family" }
    });
    log("   注册成功 userId=" + member.userId);
  }

  log("③ 换取一次性票据并建立两条 WS 连接 …");
  const [caller, callee] = await Promise.all([connect(admin.token), connect(member.token)]);
  await Promise.all([caller.opened, callee.opened]);
  log("   两条信令连接已就绪");

  log("④ 发起 1 对 1 视频通话 …");
  const call = await api("/media/calls", {
    method: "POST",
    token: admin.token,
    body: { mode: "DIRECT", kind: "VIDEO", calleeId: member.userId, participantIds: [member.userId] }
  });
  log("   callId=" + call.id + " status=" + call.status);

  const invited = await callee.wait("call.invited");
  if (String(invited.call.id) !== String(call.id)) fail("被叫收到的 callId 不一致");

  log("⑤ 被叫接听 …");
  const answered = await api(`/media/calls/${call.id}/answer`, { method: "POST", token: member.token });
  if (answered.status !== "ACTIVE") fail("接听后状态应为 ACTIVE，实际 " + answered.status);
  await caller.wait("call.accepted");

  log("⑥ 双向 SDP 转发 …");
  caller.send({ type: "webrtc.offer", callId: call.id, to: member.userId, sdp: "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n" });
  const offer = await callee.wait("webrtc.offer");
  if (Number(offer.from) !== Number(admin.userId)) fail("offer 的 from 不正确");
  callee.send({ type: "webrtc.answer", callId: call.id, to: admin.userId, sdp: "v=0\r\no=- 2 2 IN IP4 127.0.0.1\r\n" });
  const answer = await caller.wait("webrtc.answer");
  if (!answer.sdp) fail("answer 未携带 sdp");
  caller.send({ type: "webrtc.ice", callId: call.id, to: member.userId, candidate: { candidate: "candidate:1 1 udp 1 127.0.0.1 5000 typ host" } });
  const ice = await callee.wait("webrtc.ice");
  if (!ice.candidate) fail("ICE 候选未转发");
  log("   offer / answer / ICE 全部到达对端");

  log("⑦ 媒体状态与越权拦截 …");
  await api(`/media/calls/${call.id}/state`, { method: "POST", token: member.token, body: { mic: false, cam: true } });
  const state = await caller.wait("call.state");
  if (state.mic !== false) fail("静音状态未同步");
  caller.send({ type: "webrtc.offer", callId: call.id, to: 999, sdp: "x" });
  const denied = await caller.wait("error");
  log("   非法收件人被拒绝：" + denied.message);

  log("⑧ 被叫离开、主叫结束 …");
  await api(`/media/calls/${call.id}/leave`, { method: "POST", token: member.token });
  await caller.wait("call.left");
  const active = await api("/media/calls/active", { token: admin.token });
  if (active) log("   被叫离开后主叫仍在通话（1 对 1 应保持到主叫挂断）");
  await api(`/media/calls/${call.id}/end`, { method: "POST", token: admin.token });
  const ended = await caller.wait("call.ended");
  log("   结束原因：" + ended.reason);

  log("⑨ 通话历史 …");
  const history = await api("/media/calls/history", { token: admin.token });
  const record = (history || []).find(item => String(item.id) === String(call.id));
  if (!record) fail("历史记录里找不到这通电话");
  log(`   共 ${history.length} 条记录，最近一条 status=${record.status} reason=${record.endReason}`);

  caller.close();
  callee.close();
  log("\n✓ 实时通话端到端冒烟全部通过");
}

main().catch(error => {
  console.error("\n✗ 冒烟失败：" + error.message);
  process.exitCode = 1;
});
