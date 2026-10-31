/**
 * 四端共用的请求错误文案。
 *
 * 背景：管理端、家庭端、档案端、移动端原来各写一份三元表达式，同一个 503
 * 在管理端提示「对应后端服务未启动」，在小程序端只显示「请求失败」。
 * 网关拿不到下游实例时回的是空响应体的 502/503/504，这才是本地最常见的故障，
 * 必须把真实原因透出来，否则排查成本全落在用户身上。
 */

/** HTTP 状态码 → 用户可读的提示；后端给了 msg 时优先用后端的。 */
export function resolveErrorMessage(statusCode, payloadMessage) {
  if (payloadMessage) return payloadMessage;
  if (statusCode === 429) return "操作过于频繁，请稍后重试";
  if (statusCode === 502 || statusCode === 503 || statusCode === 504) {
    return "对应后端服务未启动或正在重启，请稍后刷新重试";
  }
  if (statusCode === 0) return "网络连接失败，请检查网络后重试";
  return "服务暂时不可用，请稍后重试";
}
