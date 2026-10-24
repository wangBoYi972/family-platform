const baseUrl = import.meta.env.VITE_API_BASE_URL || "http://127.0.0.1:8080/api";

function token() {
  return uni.getStorageSync("family_access_token");
}

/** 通用 JSON 请求；payload.code !== 0 或非 2xx 视为失败。 */
export async function request(path, options = {}) {
  const t = options.authenticated === false ? null : token();
  const response = await uni.request({
    url: baseUrl + path,
    method: options.method || "GET",
    data: options.data,
    header: {
      ...(options.header || {}),
      ...(t ? { Authorization: "Bearer " + t } : {})
    }
  });
  const payload = response.data;
  if (response.statusCode === 401 && options.authenticated !== false) uni.removeStorageSync("family_access_token");
  if (response.statusCode < 200 || response.statusCode >= 300 || payload?.code !== 0) {
    const error = new Error(payload?.msg || (response.statusCode === 429 ? "操作过于频繁，请稍后重试" : "请求失败"));
    error.status = response.statusCode;
    throw error;
  }
  return payload.data;
}

export const login = data =>
  request("/auth/password-login", { method: "POST", data, authenticated: false, header: { "Content-Type": "application/json" } });

/** 开放注册：必须携带管理员发放的邀请码，成功后直接返回令牌。 */
export const register = data =>
  request("/auth/register", { method: "POST", data, authenticated: false, header: { "Content-Type": "application/json" } });

/** 带鉴权头的文件上传（字段名 file，后端 FileUploadDTO 契约）。 */
export function upload(path, filePath, formData = {}) {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: baseUrl + path,
      filePath,
      name: "file",
      formData,
      header: { Authorization: "Bearer " + token() },
      success: res => {
        try {
          const payload = JSON.parse(res.data);
          if (res.statusCode >= 200 && res.statusCode < 300 && payload?.code === 0) resolve(payload.data);
          else reject(new Error(payload?.msg || "上传失败"));
        } catch (error) {
          reject(new Error("上传响应异常"));
        }
      },
      fail: err => reject(new Error(err.errMsg || "上传失败"))
    });
  });
}

/** 鉴权下载文件为临时 URL（用于 <image> 展示；请求经网关统一入口）。 */
export function loadImageUrl(fileId) {
  return new Promise((resolve, reject) => {
    uni.downloadFile({
      url: baseUrl + "/files/" + fileId + "/download",
      header: { Authorization: "Bearer " + token() },
      success: res => {
        if (res.statusCode === 200) resolve(res.tempFilePath);
        else reject(new Error("图片加载失败"));
      },
      fail: err => reject(new Error(err.errMsg || "图片加载失败"))
    });
  });
}

export const taskStatusLabel = { TODO: "待办", IN_PROGRESS: "进行中", COMPLETED: "已完成" };
export const roleLabel = { ADMIN: "管理员", MEMBER: "成员" };
