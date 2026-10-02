const baseUrl = import.meta.env.VITE_API_BASE_URL || "http://127.0.0.1:8080/api";
export async function request(path, options = {}) {
  const token = options.authenticated === false ? null : uni.getStorageSync("family_access_token");
  const response = await uni.request({ url: baseUrl + path, method: options.method || "GET", data: options.data, header: { ...(options.header || {}), ...(token ? { Authorization: "Bearer " + token } : {}) } });
  const payload = response.data;
  if (response.statusCode === 401 && options.authenticated !== false) uni.removeStorageSync("family_access_token");
  if (response.statusCode < 200 || response.statusCode >= 300 || payload?.code !== 0) {
    const error = new Error(payload?.msg || (response.statusCode === 429 ? "操作过于频繁，请稍后重试" : "请求失败"));
    error.status = response.statusCode; throw error;
  }
  return payload.data;
}
export const login = data => request("/auth/password-login", { method: "POST", data, authenticated: false, header: { "Content-Type": "application/json" } });
