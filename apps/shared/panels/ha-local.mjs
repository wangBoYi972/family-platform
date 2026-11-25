/**
 * 手机在家里 WiFi 下直连 Home Assistant（不经云端转发到局域网）。
 * 优先 uni.request（小程序 / App），否则回退 fetch（浏览器 H5）。
 */

const RELEVANT = new Set(["light", "switch", "sensor", "scene", "climate", "media_player"]);

function normalizeBase(baseUrl) {
  return String(baseUrl || "").replace(/\/+$/, "");
}

function resolveUni() {
  if (typeof uni !== "undefined" && typeof uni.request === "function") return uni;
  return null;
}

async function haFetch(baseUrl, accessToken, path, { method = "GET", body } = {}) {
  const url = `${normalizeBase(baseUrl)}${path.startsWith("/") ? path : `/${path}`}`;
  const headers = {
    Authorization: `Bearer ${accessToken}`,
    "Content-Type": "application/json",
  };
  const uniApi = resolveUni();
  if (uniApi) {
    const response = await new Promise((resolve, reject) => {
      uniApi.request({
        url,
        method,
        data: body != null ? body : undefined,
        header: headers,
        success: resolve,
        fail: err => reject(new Error(err?.errMsg || "连不上家里中枢，请确认已连家里 WiFi")),
      });
    });
    const status = response.statusCode ?? response.status;
    if (status < 200 || status >= 300) {
      const msg =
        typeof response.data === "string"
          ? response.data
          : response.data?.message || `家庭中枢返回 ${status}`;
      throw new Error(msg);
    }
    if (status === 204) return null;
    return response.data ?? null;
  }

  if (typeof fetch !== "function") {
    throw new Error("当前环境无法直连家里中枢");
  }
  const res = await fetch(url, {
    method,
    headers,
    body: body != null ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(text || `家庭中枢返回 ${res.status}`);
  }
  if (res.status === 204) return null;
  const ct = res.headers.get("content-type") || "";
  if (ct.includes("application/json")) return res.json();
  return null;
}

/** 手机连着家里网时探测中枢是否可达。 */
export async function testHaLocal(baseUrl, accessToken) {
  await haFetch(baseUrl, accessToken, "/api/");
  return true;
}

/**
 * 拉取设备并整理成云端 local-sync 所需结构。
 */
export async function fetchHaDevicesForSync(baseUrl, accessToken) {
  const states = await haFetch(baseUrl, accessToken, "/api/states");
  if (!Array.isArray(states)) return { devices: [], scenes: [] };

  const devices = [];
  const scenes = [];
  for (const row of states) {
    const entityId = row.entity_id;
    if (!entityId || !entityId.includes(".")) continue;
    const entityType = entityId.split(".")[0];
    if (!RELEVANT.has(entityType)) continue;
    const item = {
      entityId,
      entityType,
      deviceName: row.attributes?.friendly_name || entityId,
      areaName: row.attributes?.area_id || "未分组",
      state: row.state || "unknown",
      attributes: row.attributes || {},
      online: true,
    };
    if (entityType === "scene") scenes.push(item);
    else devices.push(item);
  }
  return { devices: [...devices, ...scenes], scenes };
}

export async function callHaService(baseUrl, accessToken, domain, service, data = {}) {
  await haFetch(baseUrl, accessToken, `/api/services/${domain}/${service}`, {
    method: "POST",
    body: data,
  });
  return true;
}
