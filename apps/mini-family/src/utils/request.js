import { createUniClient } from "@shared/uni-client.js";
import { createAiClient } from "@shared/ai-client.mjs";
import { uniApi } from "./uni-api.js";

// 实现统一放在 apps/shared/uni-client.js：家庭端与移动端共用，避免一端的修复落不到另一端。
// 默认走同源 /api，由开发代理或生产 Nginx 转发；只有在需要跨机联调时用环境变量覆盖。
const baseUrl = import.meta.env.VITE_API_BASE_URL || "/api";

// 不能直接传裸 uni：模块初始化时它还是残缺对象，且引用会被固定下来。
const client = createUniClient({ uni: uniApi, baseUrl });

export const request = client.request;
export const upload = client.upload;
/** 内存 Blob 上传（通话录音分段），见 shared/uni-client.js。 */
export const uploadBlob = client.uploadBlob;
export const loadImageUrl = client.loadImageUrl;
export const loadAudioUrl = client.loadAudioUrl;
export const loadFileUrl = client.loadFileUrl;
export const login = client.login;
export const register = client.register;
/** AI 家庭助手流式对话（SSE），见 apps/shared/ai-client.mjs。 */
export const aiChat = createAiClient({ uni: uniApi, baseUrl }).streamChat;

export const taskStatusLabel = { TODO: "待办", IN_PROGRESS: "进行中", COMPLETED: "已完成" };
export const roleLabel = { ADMIN: "管理员", MEMBER: "成员" };
