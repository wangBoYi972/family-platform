// 通话记录的展示逻辑：纯函数，不依赖 Vue / uni，可直接用 node --test 覆盖边界。
// 字段沿用后端 CallVO：createdAt / answeredAt / endedAt / status / endReason / participants。

export const UNKNOWN_DURATION = "未知";

/** 把后端时间字段转成毫秒；后端可能给 "2026-11-22T10:00:00" 或 "2026-11-22 10:00:00"。 */
function timeValue(value) {
  if (value === null || value === undefined || value === "") return null;
  const text = typeof value === "string" ? value.trim().replace(" ", "T") : value;
  const time = new Date(text).getTime();
  return Number.isFinite(time) ? time : null;
}

/**
 * 已接通时长按「接听 → 结束」计算，未接 / 拒接没有 answeredAt，不能拿 createdAt 顶替。
 * 时钟回拨造成的负差值也视为未知，界面上绝不出现负数时长。
 */
export function callDurationSeconds(call) {
  const start = timeValue(call?.answeredAt);
  const end = timeValue(call?.endedAt);
  if (start === null || end === null || end < start) return null;
  return Math.floor((end - start) / 1000);
}

/** 秒数格式化为 mm:ss；跨小时用 h:mm:ss。非法值统一显示「未知」。 */
export function formatDuration(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return UNKNOWN_DURATION;
  const total = Math.floor(seconds);
  const pad = value => String(value).padStart(2, "0");
  const ss = pad(total % 60);
  const mm = Math.floor(total / 60) % 60;
  const hh = Math.floor(total / 3600);
  return hh > 0 ? `${hh}:${pad(mm)}:${ss}` : `${pad(Math.floor(total / 60))}:${ss}`;
}

/** 列表与详情共用的时长文案：缺少任一时间字段就显示「未知」。 */
export function callDurationText(call) {
  return formatDuration(callDurationSeconds(call));
}

/** 是否为我发起的通话。 */
export function isCallCaller(call, selfId) {
  return selfId !== null && selfId !== undefined && String(call?.callerId) === String(selfId);
}

/**
 * 接通 / 未接文案。未接来电既看会话的 NO_ANSWER，也看我自己的参与者状态，
 * 兼容多人通话中「整通有人接听、但我没接到」的情况。
 */
export function callOutcome(call, selfId) {
  if (!call) return "";
  if (call.status !== "ENDED") return "通话中";
  const mine = (call.participants || []).find(item => String(item.userId) === String(selfId));
  if (mine?.state === "MISSED") return "未接";
  if (mine?.state === "REJECTED") return "已拒绝";
  if (isCallCaller(call, selfId)) {
    if (call.endReason === "NO_ANSWER") return "无人接听";
    if (call.endReason === "REJECTED") return "对方已拒绝";
    return "已接通";
  }
  if (call.endReason === "NO_ANSWER") return "未接";
  if (call.endReason === "REJECTED") return "已拒绝";
  if (call.answeredAt) return "已接听";
  return "已接通";
}

/** 未接来电标红用；只有落在「未接」上的记录才需要突出显示。 */
export function isMissedCall(call, selfId) {
  return callOutcome(call, selfId) === "未接";
}
