// uni-app 端共用的请求封装（家庭端 / 移动端）。
//
// 之前两个应用各写一份：一边有 upload / loadImageUrl，另一边没有，导致共享面板
// 在移动端构建时报「loadImageUrl is not exported」。这里收敛成唯一实现。
import { resolveErrorMessage } from "./request-message.js";

export function createUniClient({ uni, baseUrl, storageKey = "family_access_token" }) {
  function token() {
    return uni.getStorageSync(storageKey);
  }

  /** 通用 JSON 请求；payload.code !== 0 或非 2xx 视为失败。 */
  async function request(path, options = {}) {
    const credential = options.authenticated === false ? null : token();
    let response;
    try {
      response = await uni.request({
        url: baseUrl + path,
        method: options.method || "GET",
        data: options.data,
        header: {
          ...(options.header || {}),
          ...(credential ? { Authorization: "Bearer " + credential } : {})
        }
      });
    } catch {
      // 断网 / 超时时 uni.request 直接 reject，不能把 errMsg 原样抛给页面
      throw new Error(resolveErrorMessage(0));
    }
    const payload = response.data;
    if (response.statusCode === 401 && options.authenticated !== false) uni.removeStorageSync(storageKey);
    if (response.statusCode < 200 || response.statusCode >= 300 || payload?.code !== 0) {
      const error = new Error(resolveErrorMessage(response.statusCode, payload?.msg));
      error.status = response.statusCode;
      throw error;
    }
    return payload.data;
  }

  /** 带鉴权头的文件上传（字段名 file，对应后端 FileUploadDTO 契约）。 */
  function upload(path, filePath, formData = {}) {
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
          } catch {
            reject(new Error("上传响应异常"));
          }
        },
        fail: err => reject(new Error(err.errMsg || "上传失败"))
      });
    });
  }

  /** 鉴权下载为临时 URL（供 <image> 展示）。 */
  function loadImageUrl(fileId) {
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

  const login = data =>
    request("/auth/password-login", { method: "POST", data, authenticated: false, header: { "Content-Type": "application/json" } });

  /** 开放注册：必须携带管理员发放的邀请码，成功后直接返回令牌。 */
  const register = data =>
    request("/auth/register", { method: "POST", data, authenticated: false, header: { "Content-Type": "application/json" } });

  return { request, upload, loadImageUrl, login, register };
}
