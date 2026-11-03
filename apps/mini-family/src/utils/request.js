import { createUniClient } from "@shared/uni-client.js";
import { createAiClient } from "@shared/ai-client.mjs";

// 实现统一放在 apps/shared/uni-client.js：家庭端与移动端共用，避免一端的修复落不到另一端。
const baseUrl = import.meta.env.VITE_API_BASE_URL || "http://127.0.0.1:8080/api";

const client = createUniClient({ uni, baseUrl });

export const request = client.request;
export const upload = client.upload;
export const loadImageUrl = client.loadImageUrl;
export const loadAudioUrl = client.loadAudioUrl;
export const loadFileUrl = client.loadFileUrl;
export const login = client.login;
export const register = client.register;
/** AI 家庭助手流式对话（SSE），见 apps/shared/ai-client.mjs。 */
export const aiChat = createAiClient({ uni, baseUrl }).streamChat;

export const taskStatusLabel = { TODO: "待办", IN_PROGRESS: "进行中", COMPLETED: "已完成" };
export const roleLabel = { ADMIN: "管理员", MEMBER: "成员" };
