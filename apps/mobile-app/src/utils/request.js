import { createUniClient } from "@shared/uni-client.js";

// 与家庭端共用同一份实现（apps/shared/uni-client.js），不再各写一份导致能力漂移。
const baseUrl = import.meta.env.VITE_API_BASE_URL || "http://127.0.0.1:8080/api";

const client = createUniClient({ uni, baseUrl });

export const request = client.request;
export const upload = client.upload;
export const loadImageUrl = client.loadImageUrl;
export const login = client.login;
export const register = client.register;

export const taskStatusLabel = { TODO: "待办", IN_PROGRESS: "进行中", COMPLETED: "已完成" };
export const roleLabel = { ADMIN: "管理员", MEMBER: "成员" };
