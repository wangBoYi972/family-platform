import fs from "node:fs";
import path from "node:path";
import { applyCommand } from "./devices.js";

/**
 * mock：只改本地状态（无硬件也能验证控制）。
 * broadlink：若存在对应红外码文件则“发送”（当前实现为读取码并记录日志；
 * 真机发送需按 Broadlink 协议补齐 UDP 发包，避免在未接线时假装成功）。
 */
export function createExecutor(config, stateDir) {
  const mode = config.executor || "mock";
  if (mode === "broadlink") {
    return broadlinkExecutor(config, stateDir);
  }
  return mockExecutor();
}

function mockExecutor() {
  return {
    name: "mock",
    async execute(device, commandType, parameters) {
      return applyCommand(device, commandType, parameters);
    },
  };
}

function broadlinkExecutor(config, stateDir) {
  const codesDir = path.join(stateDir, "ir-codes");
  fs.mkdirSync(codesDir, { recursive: true });
  return {
    name: "broadlink",
    async execute(device, commandType, parameters) {
      const next = applyCommand(device, commandType, parameters);
      if (device.attributes?.ir) {
        const codeFile = path.join(codesDir, `${device.entityId}.${commandType}.b64`);
        if (!fs.existsSync(codeFile)) {
          throw new Error(
            `缺少红外码文件 ${path.basename(codeFile)}。请先学习并放入 ir-codes/，或改用 mock 执行器联调。`
          );
        }
        const code = fs.readFileSync(codeFile, "utf8").trim();
        const host = config.broadlink?.host;
        if (!host) {
          throw new Error("未配置 broadlink.host");
        }
        // 占位：真实 Broadlink 发送需设备认证与 UDP 载荷；此处强制要求码文件存在，避免假成功。
        console.log(`[broadlink] 将向 ${host} 发送 ${device.entityId} ${commandType}（码长 ${code.length}）`);
      }
      return next;
    },
  };
}
