/**
 * 微信小程序订阅消息：申请授权并上报配额。
 *
 * 链路：用户点击动作里调 requestSubscribeMessage（微信要求必须在点击同步链里触发）
 * → accept 的模板上报后端落一条「可发送」配额 → 家人离线时后端消耗配额发服务通知。
 * 首次还要 wx.login 换 code 绑 openid（bindWxSession），没它推送没有收件地址。
 *
 * 其它平台全是空操作：requestSubscribeMessage 是微信小程序专属 API。
 * 用户在授权弹窗里明确拒绝过，7 天内不再弹（反复弹窗比不打扰更招人烦）。
 */

import {
  shouldRequestNotify,
  shouldEnterBackoff,
  extractAcceptedKeys
} from "./wx-notify-logic.mjs";

/** 业务侧模板语义名；后端 yml 的 family.wx.templates 用同一套 key。 */
export const TEMPLATE_KEYS = {
  imMessage: "im-message",
  taskReminder: "task-reminder",
  anniversaryReminder: "anniversary-reminder"
};
const BACKOFF_KEY = "family_wx_notify_reject_at";
const ASKED_TODAY_KEY = "family_wx_notify_asked_today";

/** 每个应用会话只问一次：授权弹窗在会话里反复出现，聊天就没法好好聊了。 */
let askedThisSession = false;

function isMpWeixin() {
  try {
    return typeof wx !== "undefined" && typeof wx.requestSubscribeMessage === "function";
  } catch {
    return false;
  }
}

/**
 * wx.login 换 code，后端 code2session 换 openid 绑到当前账号。
 * 登录后调用一次；未配置微信能力（503）或任何失败都静默——订阅消息不是必需品。
 */
export async function bindWxSession(request) {
  if (!isMpWeixin()) return false;
  try {
    const res = await uni.login({ provider: "weixin" });
    if (!res?.code) return false;
    await request("/wx/session", { method: "POST", data: { code: res.code } });
    return true;
  } catch {
    return false;
  }
}

/**
 * 在用户点击链里申请订阅授权并上报。成功（用户本次允许）返回 true。
 * @param {Function} request 请求函数
 * @param {Array<string>} keys 模板 key（后端语义名，如 im-message）
 * @param {Object} [options] once: 同一会话只问一次（默认 true），oncePerDay: 同一天只问一次（默认 true）
 */
export async function requestNotify(request, keys, { once = true, oncePerDay = true } = {}) {
  if (!isMpWeixin() || !keys?.length) return false;

  const now = Date.now();
  const lastRejectedAt = uni.getStorageSync(BACKOFF_KEY) || 0;
  const lastAskedToday = uni.getStorageSync(ASKED_TODAY_KEY) || 0;

  const { should, reason } = shouldRequestNotify({
    isMpWeixin: true,
    askedThisSession,
    lastRejectedAt,
    lastAskedToday,
    now,
    config: { once, oncePerDay }
  });

  if (!should) return false;

  askedThisSession = true;
  uni.setStorageSync(ASKED_TODAY_KEY, now);

  try {
    const res = await uni.requestSubscribeMessage({ tmplIds: keys });
    const accepted = extractAcceptedKeys(res, keys);

    // 全部明确拒绝才进入退避；「总是保持以上选择」之后不会再弹，无所谓
    const results = keys.map(key => ({ key, result: res[key] || "unknown" }));
    if (shouldEnterBackoff(results)) {
      uni.setStorageSync(BACKOFF_KEY, now);
    }

    if (accepted.length) {
      await request("/wx/subscriptions", {
        method: "POST",
        data: { grants: accepted.map(key => ({ templateKey: key, accepted: true })) }
      });
    }
    return accepted.length > 0;
  } catch {
    return false;
  }
}
