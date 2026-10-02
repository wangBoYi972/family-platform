// 四端共用响应处理，身份失效由调用端统一清理。
export function createApi({ send, token, expire }) {
  return async function request(path, options = {}) {
    const credential = token();
    let response;
    try {
      response = await send(path, { ...options, headers: { ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(credential ? { Authorization: 'Bearer ' + credential } : {}), ...options.headers } });
    } catch {
      throw new Error('网络连接失败，请检查网络后重试');
    }
    if (response.status === 401 && !options.anonymous) expire();
    const payload = response.data;
    if (response.status < 200 || response.status >= 300 || !payload || payload.code !== 0) {
      const error = new Error(payload?.msg || (response.status === 401 ? '登录已失效，请重新登录' : '服务暂时不可用，请稍后重试'));
      error.status = response.status;
      throw error;
    }
    return payload.data;
  };
}
