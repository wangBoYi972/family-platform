import { resolveErrorMessage } from "@shared/request-message.js";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || "/api";

/**
 * 安全建议：当前 token 存储在 localStorage，容易被 XSS 攻击窃取。
 *
 * 改进方案：
 * 1. 后端登录接口改为设置 httpOnly, Secure 的 cookie（名称如 Authorization）
 * 2. 前端无需手动设置 Authorization 头，浏览器会自动附加 cookie
 * 3. 同时在 response 中返回短期 access_token，用于验证登录状态
 *
 * 实现步骤：
 * - 后端：response.addHeader("Set-Cookie", "Authorization=Bearer...;HttpOnly;Secure;SameSite=Strict;Max-Age=3600");
 * - 前端：删除 Authorization 手动设置，改用 credentials: 'include'
 */

export function clearSession() {
  localStorage.removeItem("family_access_token");
  localStorage.removeItem("family_household_id");
  window.dispatchEvent(new Event("family-session-ended"));
}

export async function request(path, { method = "GET", data, authenticated = true } = {}) {
  const token = authenticated ? localStorage.getItem("family_access_token") : null;
  let response;
  try {
    response = await fetch(apiBaseUrl + path, {
      method,
      credentials: 'include', // 允许跨域请求时发送 cookie
      headers: { ...(data ? { "Content-Type": "application/json" } : {}), ...(token ? { Authorization: "Bearer " + token } : {}) },
      body: data === undefined ? undefined : JSON.stringify(data)
    });
  } catch {
    // fetch 在网络中断时抛 TypeError，原来会直接把 "Failed to fetch" 甩给用户
    throw new Error(resolveErrorMessage(0));
  }
  const payload = await response.json().catch(() => null);
  if (response.status === 401 && authenticated) clearSession();
  if (!response.ok || payload?.code !== 0) {
    throw new Error(resolveErrorMessage(response.status, payload?.msg));
  }
  return payload.data;
}

