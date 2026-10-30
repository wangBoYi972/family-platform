/**
 * 浏览器端到端验证：两个独立上下文分别登录两位家庭成员，在真实 Chromium(Edge) 中
 * 走完「拨号 → 来电 → 接听 → 建立 PeerConnection → 双向视频」并断言远端画面有像素。
 * 使用 --use-fake-device-for-media-stream 提供虚拟摄像头/麦克风。
 */
import { chromium } from "playwright";
import { requireAccounts } from "./accounts.mjs";

const H5 = "http://127.0.0.1:5173/";
const API = "http://127.0.0.1:8080/api";
const [ADMIN, MEMBER] = requireAccounts("admin", "member");
// 被叫在拨号面板里的显示名；账号可能是新建的，所以允许用环境变量指定
const PEER_NAME = process.env.FAMILY_MEMBER_NAME || "冒烟成员";

const log = (...a) => console.log(...a);
const fail = m => {
  console.error("✗ " + m);
  process.exitCode = 1;
  throw new Error(m);
};

async function token(creds) {
  const r = await fetch(API + "/auth/password-login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...creds, client: "family" })
  });
  const p = await r.json();
  if (p.code !== 0) throw new Error("登录失败: " + p.msg);
  return p.data.token;
}

const browser = await chromium.launch({
  channel: "msedge",
  headless: true,
  args: [
    "--use-fake-ui-for-media-stream",
    "--use-fake-device-for-media-stream",
    "--autoplay-policy=no-user-gesture-required"
  ]
});

async function session(name, jwt) {
  const context = await browser.newContext({ viewport: { width: 430, height: 900 }, permissions: ["camera", "microphone"] });
  await context.addInitScript(t => {
    try {
      localStorage.setItem("family_access_token", t);
    } catch {
      /* 首次导航前无 origin */
    }
  }, jwt);
  const page = await context.newPage();
  page.on("console", m => {
    const text = m.text();
    if (m.type() === "error" && !text.includes("Failed to load resource")) log(`   [${name}] 控制台错误: ${text.slice(0, 200)}`);
    if (text.includes("[call]")) log(`   [${name}] ${text.slice(0, 220)}`);
  });
  page.on("pageerror", e => log(`   [${name}] pageerror: ${e.message.slice(0, 200)}`));
  page.on("requestfailed", r => {
    if (r.url().includes("/api/")) log(`   [${name}] 请求失败: ${r.url()} ${r.failure()?.errorText}`);
  });
  await page.goto(H5, { waitUntil: "networkidle" });
  await page.waitForTimeout(800);
  return { context, page };
}

/** 清理上一轮遗留的进行中通话，否则本轮会因「正在通话中」无法响铃。 */
async function resetCalls(tokens) {
  for (const t of tokens) {
    const active = await fetch(API + "/media/calls/active", { headers: { Authorization: "Bearer " + t } })
      .then(r => r.json())
      .then(p => p.data)
      .catch(() => null);
    if (active) {
      await fetch(API + `/media/calls/${active.id}/end`, { method: "POST", headers: { Authorization: "Bearer " + t } });
      log("   已结束遗留通话 " + active.id);
    }
  }
}

try {
  const [adminToken, memberToken] = await Promise.all([token(ADMIN), token(MEMBER)]);
  await resetCalls([adminToken, memberToken]);
  const a = await session("主叫", adminToken);
  const b = await session("被叫", memberToken);
  log("① 两个浏览器上下文已登录");

  await a.page.getByText("☎ 通话").click();
  await a.page.getByText("发起通话").waitFor({ timeout: 10000 });
  const peer = a.page.getByText(PEER_NAME, { exact: true });
  for (let i = 0; i < 20 && !(await peer.count()); i++) await a.page.waitForTimeout(300);
  if (!(await peer.count())) {
    const list = await a.page.locator(".member-list").innerText().catch(() => "(无成员列表)");
    await a.page.locator(".call-card").screenshot({ path: "/tmp/call-dial.png" }).catch(() => {});
    fail("家庭成员列表里没有测试成员，列表内容：" + list.replace(/\s+/g, " ").slice(0, 120));
  }
  await peer.first().click({ force: true });
  await a.page.getByText("视频通话").click();
  log("② 主叫拨号已提交");

  await b.page.getByText("接听").waitFor({ timeout: 15000 });
  log("③ 被叫收到来电弹窗");
  await b.page.getByText("接听").click();

  // 等媒体真正连通：**远端** tile 里的 video 有尺寸且 readyState >= 2
  // （只看页面上有没有 video 是不够的——本地预览也可能是唯一那个 video）
  const mediaState = async (page, label) => {
    for (let i = 0; i < 40; i++) {
      const tiles = await page.evaluate(() =>
        Array.from(document.querySelectorAll(".tile")).map(tile => {
          const video = tile.querySelector("video");
          return {
            self: tile.classList.contains("is-self"),
            who: tile.querySelector(".who")?.textContent?.trim() || "",
            width: video?.videoWidth || 0,
            height: video?.videoHeight || 0,
            ready: video ? video.readyState : -1,
            live: !!video?.srcObject && video.srcObject.getTracks().some(track => track.readyState === "live")
          };
        })
      );
      const remote = tiles.filter(tile => !tile.self);
      if (remote.length && remote.every(tile => tile.width > 0 && tile.ready >= 2)) {
        log(`   ${label} 画面: ` + JSON.stringify(tiles));
        return tiles;
      }
      await page.waitForTimeout(500);
    }
    fail(`${label} 15 秒内远端画面没有出图（远端 tile 未绑定 MediaStream）`);
  };

  log("④ 等待双向媒体连通 …");
  const [aTiles, bTiles] = await Promise.all([mediaState(a.page, "主叫"), mediaState(b.page, "被叫")]);
  for (const [label, tiles] of [["主叫", aTiles], ["被叫", bTiles]]) {
    const remote = tiles.filter(tile => !tile.self);
    if (!remote.length) fail(`${label} 界面没有远端画面`);
    if (!remote.every(tile => tile.live)) fail(`${label} 远端轨道不是 live 状态`);
  }

  if (await b.page.getByText("接听").count()) fail("接听后来电弹窗未关闭");

  await a.page.screenshot({ path: "/tmp/call-caller.png" });
  await b.page.screenshot({ path: "/tmp/call-callee.png" });
  log("⑤ 界面截图已保存");

  // 挂断：主叫点挂断，被叫应回到结束态
  await a.page.getByText("挂断").click();
  await b.page.getByText("通话已结束").waitFor({ timeout: 5000 });
  log("⑥ 主叫挂断，被叫已显示「通话已结束」");

  await browser.close();
  if (process.exitCode) throw new Error("断言失败");
  log("\n✓ 浏览器端到端通话验证通过（含真实 PeerConnection 与双向视频画面）");
} catch (error) {
  console.error("\n✗ 浏览器验证失败：" + error.message);
  try {
    await browser.close();
  } catch {
    /* ignore */
  }
  process.exitCode = 1;
}
