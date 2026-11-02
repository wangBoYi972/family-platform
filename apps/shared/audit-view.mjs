// 审计总览的展示侧计算：柱状图的补零与比例，纯函数可单测。
// 聚合本身在 archive-svc 完成（按人/按天/按动作），这里只负责把数据变成能画的形状。

export const ACTION_LABELS = { READ: "查看", CREATE: "新建", UPDATE: "修改", DELETE: "删除" };

/**
 * 把服务端返回的 byDay（只有有记录的日期）补成连续 days 天、旧→新排列的数组。
 * 缺的日子补 0——柱状图需要均匀的时间轴，空档期有意义（最近没人动档案也是一种信息）。
 */
export function fillDays(byDay, days = 30, today) {
  const counts = new Map((byDay || []).map(item => [String(item.name), Number(item.count) || 0]));
  const end = today ? parseDay(today) : new Date();
  const result = [];
  for (let offset = days - 1; offset >= 0; offset--) {
    const d = new Date(end.getFullYear(), end.getMonth(), end.getDate() - offset);
    const key = formatDay(d);
    result.push({ day: key, count: counts.get(key) || 0 });
  }
  return result;
}

/** 条形占比（%），最小 4% 让零星的一两次访问也看得见；max 为 0 时安全返回 0。 */
export function percent(count, max) {
  if (!max) return 0;
  return Math.max(4, Math.round((Number(count) || 0) / max * 100));
}

function parseDay(text) {
  const [y, m, d] = String(text).slice(0, 10).split("-").map(Number);
  return new Date(y, m - 1, d);
}

function formatDay(d) {
  const pad = n => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
