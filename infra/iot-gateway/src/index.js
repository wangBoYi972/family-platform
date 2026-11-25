#!/usr/bin/env node
/**
 * 家侧 IoT 网关进程。
 * 用法：
 *   PAIRING_CODE=ABCD1234 npm start          # 首次配对并常驻
 *   npm start                                 # 用已保存的令牌重连
 *   CLOUD_API=http://host:8080/api CLOUD_WS=ws://host:8080 npm start
 */
import fs from "node:fs";
import path from "node:path";
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

function envOr(config, key, envKeys, fallback) {
  for (const k of envKeys) {
    if (process.env[k]) return process.env[k];
  }
  return config[key] ?? fallback;
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

async function main() {
  const fileConfig = loadJson(CONFIG_FILE, {});
  const cloudApi = envOr(fileConfig, "cloudApi", ["CLOUD_API"], "http://127.0.0.1:8080/api");
  const cloudWs = envOr(fileConfig, "cloudWs", ["CLOUD_WS"], "ws://127.0.0.1:8080");
  const displayName = envOr(fileConfig, "displayName", ["GATEWAY_NAME"], "家庭网关");
  const pairingCode = process.env.PAIRING_CODE || process.argv.find((a) => a.startsWith("--code="))?.slice(7);

  let state = loadJson(STATE_FILE, null);
  if (pairingCode) {
    console.log("使用配对码注册…");
    const reg = await register(cloudApi, pairingCode, displayName);
    state = {
      gatewayId: reg.gatewayId,
      token: reg.token,
      householdId: reg.householdId,
      registeredAt: new Date().toISOString(),
    };
    saveState(state);
    console.log("注册成功 gatewayId=", state.gatewayId);
  }

  if (!state?.gatewayId || !state?.token) {
    console.error("尚未配对。请设置 PAIRING_CODE=八位码 后重试，或先在 App 生成配对码。");
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
  console.log("网关运行中。Ctrl+C 退出。");
}

main().catch((e) => {
  console.error(e.message || e);
  process.exit(1);
});
