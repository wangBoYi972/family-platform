// 纪念日提醒：折算、挑选、去重、文案，一份实现三处复用
// （AnniversaryPanel 的列表折算 + 两个宿主的当天提醒弹窗）。
//
// 「提醒」只做当天：3 天内临近的靠面板里的红标提示就够了，
// 弹窗连着弹只会让人把它关掉再也没打开过。

import { localDate } from "./tasks.mjs";

/**
 * 把一条日程折算成「下一次还有多少天」。
 * yearly 的折算到今年，今年的日子过了就折到明年并累计「第 N 个」；
 * 一次性日程已过去时 days 为负。
 */
export function buildAnniversary(row, today = localDate()) {
  const date = String(row?.eventDate || "").slice(0, 10);
  if (!date) return null;
  const [year, month, day] = date.split("-").map(Number);
  if (!year || !month || !day) return null;
  const base = new Date(today + "T00:00:00");
  let target = new Date(row.yearly ? base.getFullYear() : year, month - 1, day);
  if (row.yearly && target < base) target.setFullYear(base.getFullYear() + 1);
  const days = Math.round((target - base) / 86400000);
  return {
    id: row.id,
    title: row.title,
    body: row.body,
    yearly: !!row.yearly,
    pinned: !!row.pinned,
    date: localDate(target),
    origin: date,
    days,
    nth: row.yearly ? target.getFullYear() - year + 1 : 0,
    sort: days < 0 ? 10000 - days : days
  };
}

/** 今天到期的纪念日（days === 0）。 */
export function anniversariesToday(rows, today = localDate()) {
  return rows.map(row => buildAnniversary(row, today)).filter(item => item && item.days === 0);
}

/** 当日去重的 storage key：同一天只提醒一次，跨天自然失效。 */
export function remindDoneKey(date = localDate()) {
  return "family_anniv_reminded_" + date;
}

/**
 * 弹窗文案：一两条直说，多了列前三条再带个总数，别把弹窗撑成滚动列表。
 */
export function remindText(items) {
  const lines = items.slice(0, 3).map(item => {
    const nth = item.yearly && item.nth > 1 ? `（第 ${item.nth} 个）` : "";
    return `· ${item.title}${nth}`;
  });
  if (items.length > 3) lines.push(`…等 ${items.length} 个`);
  return "别忘了说一声：\n" + lines.join("\n");
}
