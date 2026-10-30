/**
 * 多人通话浏览器验证：3 位家庭成员同时在线，验证网状连接（mesh）里每个人都能看到另外两人。
 *
 * 前置与 call-browser-smoke.mjs 相同（三服务 + H5 on 5173）。
 * 用法：node call-browser-group.mjs
 */
import { chromium } from "playwright";
import { requireAccounts } from "./accounts.mjs";

const H5 = "http://127.0.0.1:5173/";
const API = "http://127.0.0.1:8080/api";
const [admin, member, member2] = requireAccounts("admin", "member", "member2");
const PEOPLE = [
  { ...admin, name: "主叫" },
  { ...member, name: "成员一" },
  { ...member2, name: "成员二" }
];

const log = (...a) => console.log(...a);
const fail = m => {
  console.error("✗ " + m);
  process.exitCode = 1;
  throw new Error(m);
};

async function login(person) {
  const response = await fetch(API + "/auth/password-login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone: person.phone, password: person.password, client: "family" })
  });
  const payload = await response.json();
  if (payload.code === 0) return payload.data;
  // 第三位成员可能还没注册：用管理员邀请码补一个
  const admin = await (await fetch(API + "/auth/password-login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone: PEOPLE[0].phone, password: PEOPLE[0].password, client: "family" })
  })).json();
  const invite = await (await fetch(API + "/users/invite-codes", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: "Bearer " + admin.data.token },
    body: JSON.stringify({ role: "MEMBER", maxUses: 1, expiresInHours: 24 })
  })).json();
  const registered = await (await fetch(API + "/auth/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      phone: person.phone,
      password: person.password,
      inviteCode: invite.data.code,
      displayName: "冒烟成员2",
      client: "family"
    })
  })).json();
  if (registered.code !== 0) fail("注册第三位成员失败：" + registered.msg);
  return registered.data;
}

const sessions = await Promise.all(PEOPLE.map(login));

for (const session of sessions) {
  const active = await fetch(API + "/media/calls/active", { headers: { Authorization: "Bearer " + session.token } })
    .then(r => r.json())
    .then(p => p.data)
    .catch(() => null);
  if (active) await fetch(API + `/media/calls/${active.id}/end`, { method: "POST", headers: { Authorization: "Bearer " + session.token } });
}

const browser = await chromium.launch({
  channel: "msedge",
  headless: true,
  args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream", "--autoplay-policy=no-user-gesture-required"]
});

async function open(session, label) {
  const context = await browser.newContext({ viewport: { width: 430, height: 900 }, permissions: ["camera", "microphone"] });
  await context.addInitScript(t => localStorage.setItem("family_access_token", t), session.token);
  const page = await context.newPage();
  page.on("pageerror", e => log(`   [${label}] pageerror: ${e.message.slice(0, 160)}`));
  page.on("console", m => {
    if (m.text().includes("[call]")) log(`   [${label}] ${m.text().slice(0, 200)}`);
  });
  await page.goto(H5, { waitUntil: "networkidle" });
  await page.waitForTimeout(700);
  return page;
}

try {
  const [callerPage, firstPage, secondPage] = await Promise.all([
    open(sessions[0], "主叫"),
    open(sessions[1], "成员一"),
    open(sessions[2], "成员二")
  ]);
  log("① 三个上下文已登录");

  await callerPage.getByText("☎ 通话").click();
  await callerPage.getByText("发起通话").waitFor({ timeout: 10000 });
  await callerPage.getByText("选全家").click();
  const picked = await callerPage.locator(".member.on").count();
  log("② 选全家后已选 " + picked + " 人");
  if (picked !== 2) fail("应选中 2 位家人，实际 " + picked);
  await callerPage.getByText("视频通话").click();

  for (const [page, label] of [[firstPage, "成员一"], [secondPage, "成员二"]]) {
    await page.getByText("接听").waitFor({ timeout: 15000 });
    await page.getByText("接听").click();
    log(`③ ${label} 已接听`);
  }

  const waitAllRemote = async (page, label, expect) => {
    for (let i = 0; i < 60; i++) {
      const tiles = await page.evaluate(() =>
        Array.from(document.querySelectorAll(".tile")).map(tile => {
          const video = tile.querySelector("video");
          return {
            self: tile.classList.contains("is-self"),
            width: video?.videoWidth || 0,
            live: !!video?.srcObject && video.srcObject.getTracks().some(t => t.readyState === "live")
          };
        })
      );
      const remote = tiles.filter(t => !t.self);
      if (remote.length === expect && remote.every(t => t.width > 0 && t.live)) {
        log(`   ${label} 远端 ${expect} 路画面就绪`);
        return;
      }
      await page.waitForTimeout(500);
    }
    fail(`${label} 20 秒内没有渲染出 ${expect} 路远端画面`);
  };

  log("④ 等待三端网状出图 …");
  await Promise.all([
    waitAllRemote(callerPage, "主叫", 2),
    waitAllRemote(firstPage, "成员一", 2),
    waitAllRemote(secondPage, "成员二", 2)
  ]);

  await callerPage.screenshot({ path: "/tmp/group-caller.png" });
  log("⑤ 截图已保存 /tmp/group-caller.png");

  await callerPage.getByText("挂断").click();
  await firstPage.getByText("通话已结束").waitFor({ timeout: 6000 });
  log("⑥ 主叫挂断，全员收到结束");

  await browser.close();
  log("\n✓ 3 人网状通话验证通过（每端 2 路远端画面均为 live）");
} catch (error) {
  console.error("\n✗ 多人通话验证失败：" + error.message);
  try {
    await browser.close();
  } catch {
    /* ignore */
  }
  process.exitCode = 1;
}
