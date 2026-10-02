const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || "/api";

export function clearSession() {
  localStorage.removeItem("family_access_token");
  localStorage.removeItem("family_household_id");
  window.dispatchEvent(new Event("family-session-ended"));
}

export async function request(path, { method = "GET", data, authenticated = true } = {}) {
  const token = authenticated ? localStorage.getItem("family_access_token") : null;
  const response = await fetch(apiBaseUrl + path, {
    method,
    headers: { ...(data ? { "Content-Type": "application/json" } : {}), ...(token ? { Authorization: "Bearer " + token } : {}) },
    body: data === undefined ? undefined : JSON.stringify(data)
  });
  const payload = await response.json().catch(() => null);
  if (response.status === 401 && authenticated) clearSession();
  if (!response.ok || payload?.code !== 0) throw new Error(payload?.msg || (response.status === 429 ? "操作过于频繁，请稍后再试" : "服务暂时不可用，请稍后重试"));
  return payload.data;
}
