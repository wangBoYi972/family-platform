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

  /**
   * 上传内存里的 Blob（通话录音分段）。
   *
   * <p>不走 {@link upload}：uni.uploadFile 在小程序端只认本地临时文件路径，
   * H5 端对 Blob 的支持又因版本而异；录音本来就只存在于浏览器内存里，
   * 直接用 fetch + FormData 提交到同一个文件接口，字段名仍是 file。
   */
  function uploadBlob(path, blob, filename, formData = {}) {
    if (typeof fetch !== "function" || typeof FormData !== "function") {
      return Promise.reject(new Error("当前环境不支持录音上传"));
    }
    const form = new FormData();
    form.append("file", blob, filename);
    Object.entries(formData).forEach(([key, value]) => form.append(key, value));
    return fetch(baseUrl + path, {
      method: "POST",
      headers: { Authorization: "Bearer " + token() },
      body: form
    })
      .then(async response => {
        let payload = null;
        try {
          payload = await response.json();
        } catch {
          payload = null;
        }
        if (response.ok && payload?.code === 0) return payload.data;
        throw new Error(payload?.msg || (response.status === 401 ? "登录已过期" : "上传失败"));
      })
      .catch(error => {
        if (error instanceof Error && error.message && !/Failed to fetch|Load failed|NetworkError/i.test(error.message))
          throw error;
        throw new Error("上传失败，请检查网络");
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

  /**
   * 鉴权下载音频为临时文件路径。
   *
   * <p>和图片是同一个接口、同一段实现，但不直接复用 {@link loadImageUrl} 这个名字——
   * InnerAudioContext 播放要的是「本地临时路径」，语义上分开命名，出错时日志才读得懂。
   */
  const loadAudioUrl = loadImageUrl;

  /**
   * 鉴权下载任意文件为本地临时路径（聊天文件消息用）。
   *
   * <p>同 {@link loadImageUrl} 一个实现：小程序端 openDocument 只认本地路径，
   * 不给鉴权头直接 open 下载 URL 会 401；H5 端拿到的是 blob URL，可直接触发另存。
   */
  const loadFileUrl = loadImageUrl;

  const login = data =>
    request("/auth/password-login", { method: "POST", data, authenticated: false, header: { "Content-Type": "application/json" } });

  /** 开放注册：必须携带管理员发放的邀请码，成功后直接返回令牌。 */
  const register = data =>
    request("/auth/register", { method: "POST", data, authenticated: false, header: { "Content-Type": "application/json" } });

  return { request, upload, uploadBlob, loadImageUrl, loadAudioUrl, loadFileUrl, login, register };
}
