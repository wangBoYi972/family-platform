import { resolveErrorMessage } from "@shared/request-message.js";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || "/api";

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

