#!/usr/bin/env node
/**
 * 家侧 IoT 网关进程（给家里人用）。
 *
 * 推荐：双击「启动网关」→ 按中文提示输入 App 上的配对码。
 * 也可：npm start / PAIRING_CODE=xxxx npm start
 */
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";
import { fileURLToPath } from "node:url";
import WebSocket from "ws";
import { defaultDevices } from "./devices.js";
import { createExecutor } from "./executors.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const STATE_FILE = path.join(ROOT, "gateway-state.json");
const CONFIG_FILE = path.join(ROOT, "config.json");

function loadJson(file, fallback = {}) {
  try {
    if (fs.existsSync(file)) return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (e) {
    console.warn("读取配置失败:", file, e.message);
  }
  return fallback;
}

function saveState(state) {
  fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
}

function saveConfig(config) {
  const next = { ...loadJson(CONFIG_FILE, {}), ...config };
  fs.writeFileSync(CONFIG_FILE, JSON.stringify(next, null, 2));
}

function envOr(config, key, envKeys, fallback) {
  for (const k of envKeys) {
    if (process.env[k]) return process.env[k];
  }
  return config[key] ?? fallback;
}

async function ask(question) {
  if (!input.isTTY) {
    throw new Error("无法交互输入。请在 App 复制说明后，用「启动网关」双击运行，或设置 PAIRING_CODE。");
  }
  const rl = readline.createInterface({ input, output });
  try {
    return (await rl.question(question)).trim();
  } finally {
    rl.close();
  }
}

function pauseIfNeeded() {
  // Windows 双击黑窗一闪而过时，方便家人看清错误
  if (process.platform === "win32" && process.env.FAMILY_GATEWAY_PAUSE === "1") {
    return ask("按回车键关闭窗口…").catch(() => {});
  }
  return Promise.resolve();
}

async function register(cloudApi, pairingCode, displayName) {
  const res = await fetch(`${cloudApi.replace(/\/$/, "")}/iot/gateway/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pairingCode, displayName }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(body.message || body.msg || `注册失败 HTTP ${res.status}`);
  }
  const data = body.data ?? body;
  const token = data.gatewayToken || data.token;
  if (!data.gatewayId || !token) {
    throw new Error("注册响应缺少 gatewayId/gatewayToken");
  }
  return { ...data, token };
}

async function reportDevices(cloudApi, token, devices) {
  const res = await fetch(`${cloudApi.replace(/\/$/, "")}/iot/gateway/devices`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Gateway-Token": token,
    },
    body: JSON.stringify({
      devices: devices.map((d) => ({
        entityId: d.entityId,
        entityType: d.entityType,
        deviceName: d.deviceName,
        areaName: d.areaName,
        state: d.state,
        attributes: d.attributes,
        online: d.online !== false,
      })),
    }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || body.msg || `上报设备失败 HTTP ${res.status}`);
  }
}

async function reportState(cloudApi, token, state) {
  const res = await fetch(`${cloudApi.replace(/\/$/, "")}/iot/gateway/state`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Gateway-Token": token,
    },
    body: JSON.stringify(state),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || body.msg || `上报状态失败 HTTP ${res.status}`);
  }
}

function connectWs(cloudWs, gatewayId, token, onCommand) {
  const url = `${cloudWs.replace(/\/$/, "")}/ws/iot-gateway?gatewayId=${encodeURIComponent(gatewayId)}&token=${encodeURIComponent(token)}`;
  let ws;
  let heartbeat;
  let reconnectTimer;
  let closed = false;

  const open = () => {
    ws = new WebSocket(url);
    ws.on("open", () => {
      console.log("[ws] 已连接云端");
      heartbeat = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "ping", ts: Date.now() }));
        }
      }, 25000);
    });
    ws.on("message", async (raw) => {
      let msg;
      try {
        msg = JSON.parse(String(raw));
      } catch {
        return;
      }
      if (msg.type === "pong" || msg.type === "ping") return;
      if (msg.type === "command") {
        const result = await onCommand(msg);
        if (ws.readyState === WebSocket.OPEN) {
          const ack = {
            type: "command.ack",
            requestId: msg.requestId,
            ok: !!result.success,
          };
          if (result.error) ack.error = result.error;
          if (result.state != null) ack.state = result.state;
          if (result.attributes) ack.attributes = result.attributes;
          ws.send(JSON.stringify(ack));
        }
      }
    });
    ws.on("close", () => {
      clearInterval(heartbeat);
      if (!closed) {
        console.warn("[ws] 断开，5s 后重连…");
        reconnectTimer = setTimeout(open, 5000);
      }
    });
    ws.on("error", (err) => console.warn("[ws] 错误:", err.message));
  };

  open();
  return {
    close() {
      closed = true;
      clearInterval(heartbeat);
      clearTimeout(reconnectTimer);
      try {
        ws?.close();
      } catch {
        /* ignore */
      }
    },
  };
}

async function resolveCloudEndpoints(fileConfig) {
  let cloudApi = envOr(fileConfig, "cloudApi", ["CLOUD_API"], "");
  let cloudWs = envOr(fileConfig, "cloudWs", ["CLOUD_WS"], "");
  if (!cloudApi) {
    console.log("");
    console.log("还没配置云端地址。问一下家里用手机 App 的人要填哪个网址。");
    console.log("正式环境一般是 https://你的域名/api");
    console.log("自己电脑调试可直接回车，使用本机 http://127.0.0.1:8080/api");
    const typed = await ask("请输入云端 API 地址（直接回车=本机调试）：");
    cloudApi = typed || "http://127.0.0.1:8080/api";
    if (!cloudWs) {
      try {
        const u = new URL(cloudApi.replace(/\/api\/?$/, ""));
        u.protocol = u.protocol === "https:" ? "wss:" : "ws:";
        cloudWs = u.origin;
      } catch {
        cloudWs = await ask("请输入云端 WebSocket 地址（如 wss://你的域名）：");
      }
    }
    saveConfig({ cloudApi, cloudWs });
    console.log("已记住地址，下次不用再问。");
  }
  if (!cloudWs) {
    try {
      const u = new URL(cloudApi.replace(/\/api\/?$/, ""));
      u.protocol = u.protocol === "https:" ? "wss:" : "ws:";
      cloudWs = u.origin;
    } catch {
      cloudWs = "ws://127.0.0.1:8080";
    }
  }
  return { cloudApi, cloudWs };
}

async function main() {
  console.log("========== 家庭网关 ==========");
  console.log("作用：让手机 App 能控制家里的空调/电视。");
  console.log("窗口请保持打开；关掉就断连了。");
  console.log("");

  const fileConfig = loadJson(CONFIG_FILE, {});
  const { cloudApi, cloudWs } = await resolveCloudEndpoints(fileConfig);
  const displayName = envOr(fileConfig, "displayName", ["GATEWAY_NAME"], "家庭网关");
  let pairingCode =
    process.env.PAIRING_CODE || process.argv.find((a) => a.startsWith("--code="))?.slice(7) || "";

  let state = loadJson(STATE_FILE, null);
  const forcePair = process.argv.includes("--register") || process.argv.includes("--pair");

  if ((!state?.gatewayId || !state?.token || forcePair) && !pairingCode) {
    console.log("请打开手机 App → 家居 → 生成配对码，然后输入到这里。");
    pairingCode = await ask("请输入 8 位配对码：");
  }

  if (pairingCode) {
    console.log("正在配对…");
    const reg = await register(cloudApi, pairingCode, displayName);
    state = {
      gatewayId: reg.gatewayId,
      token: reg.token,
      householdId: reg.householdId,
      registeredAt: new Date().toISOString(),
    };
    saveState(state);
    console.log("");
    console.log("★ 配对成功！请让手机那边的人回到「家居」页等几秒。");
    console.log("");
  }

  if (!state?.gatewayId || !state?.token) {
    console.error("还没有配对成功。请先在 App 生成配对码，再重新运行本程序。");
    await pauseIfNeeded();
    process.exit(1);
  }

  const devices = defaultDevices();
  const deviceMap = new Map(devices.map((d) => [d.entityId, d]));
  const executor = createExecutor(fileConfig, ROOT);

  await reportDevices(cloudApi, state.token, devices);
  console.log(`已上报 ${devices.length} 个设备（执行器: ${executor.name}）`);

  const handleCommand = async (msg) => {
    const entityId = msg.entityId;
    const commandType = msg.commandType;
    const parameters = msg.parameters || {};
    const device = deviceMap.get(entityId);
    if (!device) {
      return { success: false, error: "未知设备: " + entityId };
    }
    try {
      const next = await executor.execute(device, commandType, parameters);
      deviceMap.set(entityId, next);
      try {
        await reportState(cloudApi, state.token, {
          entityId: next.entityId,
          state: next.state,
          attributes: next.attributes,
        });
      } catch (e) {
        console.warn("状态回写失败:", e.message);
      }
      console.log(`[cmd] ${entityId} ${commandType} → ${next.state}`);
      return {
        success: true,
        state: next.state,
        attributes: next.attributes,
      };
    } catch (e) {
      console.warn(`[cmd] 失败 ${entityId} ${commandType}:`, e.message);
      return { success: false, error: e.message };
    }
  };

  connectWs(cloudWs, state.gatewayId, state.token, handleCommand);
  console.log("网关已在线。手机上可以开关设备了。");
  console.log("不要关这个窗口。要退出请按 Ctrl+C。");
}

main().catch(async (e) => {
  console.error("");
  console.error("出错了：", e.message || e);
  console.error("可把这段文字发给家里用 App 的人一起排查。");
  await pauseIfNeeded();
  process.exit(1);
});
