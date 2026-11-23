/**
 * 微信订阅消息授权逻辑（纯函数，可在 Node.js 下测试）。
 *
 * 链路：用户点击动作 → shouldRequestNotify 判断是否弹授权 → 微信 API 申请授权 → 上报后端。
 * 判断依据：平台能力、会话去重、7天拒绝退避、同一天不重复弹。
 */

const BACKOFF_MS = 7 * 24 * 3600 * 1000; // 7天退避期

/**
 * 判断是否应该弹授权窗口。
 *
 * @param {Object} options
 * @param {boolean} options.isMpWeixin - 是否微信小程序平台
 * @param {boolean} options.askedThisSession - 本会话是否已弹过
 * @param {number} options.lastRejectedAt - 上次明确拒绝的时间戳（毫秒）
 * @param {number} options.lastAskedToday - 今天已弹过的时间戳（毫秒），0表示今天未弹
 * @param {number} options.now - 当前时间戳（毫秒）
 * @param {Object} options.config - 配置项
 * @param {boolean} options.config.once - 同一会话只问一次（默认 true）
 * @param {boolean} options.config.oncePerDay - 同一天只问一次（默认 true）
 * @returns {Object} { should, reason } - should: 是否弹窗，reason: 不弹的原因
 */
export function shouldRequestNotify({
  isMpWeixin,
  askedThisSession,
  lastRejectedAt,
  lastAskedToday,
  now,
  config = {}
}) {
  const { once = true, oncePerDay = true } = config;

  // 1. 不是微信小程序，跳过
  if (!isMpWeixin) {
    return { should: false, reason: "not-mp-weixin" };
  }

  // 2. 本会话已弹过，且配置为只弹一次
  if (once && askedThisSession) {
    return { should: false, reason: "asked-this-session" };
  }

  // 3. 在7天拒绝退避期内
  if (lastRejectedAt && now - lastRejectedAt < BACKOFF_MS) {
    return { should: false, reason: "in-backoff-period" };
  }

  // 4. 今天已弹过，且配置为同一天只弹一次
  if (oncePerDay && lastAskedToday && isSameDay(lastAskedToday, now)) {
    return { should: false, reason: "asked-today" };
  }

  return { should: true, reason: null };
}

/**
 * 判断两个时间戳是否同一天（本地时区）。
 *
 * @param {number} ts1 - 时间戳1（毫秒）
 * @param {number} ts2 - 时间戳2（毫秒）
 * @returns {boolean}
 */
function isSameDay(ts1, ts2) {
  const d1 = new Date(ts1);
  const d2 = new Date(ts2);
  return (
    d1.getFullYear() === d2.getFullYear() &&
    d1.getMonth() === d2.getMonth() &&
    d1.getDate() === d2.getDate()
  );
}

/**
 * 更新拒绝退避记录。
 * 只有当所有模板都被明确拒绝时，才进入退避期。
 *
 * @param {Array<{key: string, result: string}>} results - 各模板的授权结果
 * @returns {boolean} - 是否应该进入退避期
 */
export function shouldEnterBackoff(results) {
  if (!results || results.length === 0) return false;
  return results.every(r => r.result === "reject");
}

/**
 * 从授权结果中提取已接受的模板键。
 *
 * @param {Object} wxResult - wx.requestSubscribeMessage 的返回结果
 * @param {Array<string>} keys - 请求的模板键列表
 * @returns {Array<string>} - 已接受的模板键
 */
export function extractAcceptedKeys(wxResult, keys) {
  if (!wxResult || !keys || keys.length === 0) return [];
  return keys.filter(key => wxResult[key] === "accept");
}
