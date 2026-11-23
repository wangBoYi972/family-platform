// 用药与复诊的日历折算、当天待服用与提醒文案。
// 与 anniversary-remind.mjs 一样：纯函数放共享层，两端面板和打开应用时的提醒共用。

/** 家庭时区。与后端 family.timezone（默认 Asia/Shanghai）和数据库 serverTimezone 保持一致。 */
export const FAMILY_TIMEZONE = "Asia/Shanghai";

/** 家庭时区的今天，格式 YYYY-MM-DD。 */
export function familyToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: FAMILY_TIMEZONE, year: "numeric", month: "2-digit", day: "2-digit"
  }).formatToParts(now);
  const field = name => parts.find(p => p.type === name)?.value;
  return [field("year"), field("month"), field("day")].join("-");
}

function normalizeTime(value) {
  const text = String(value ?? "").trim();
  if (/^([01]\d|2[0-3]):[0-5]\d$/.test(text)) return text;
  // 兼容 8:00 / 9:5 这类省略前导零的写法，统一补成 HH:mm；
  // 后端入参对 times 有两位小时的 @Pattern 校验，不规范就会被拒。
  const loose = /^(\d{1,2}):(\d{1,2})$/.exec(text);
  if (loose) {
    const hour = Number(loose[1]);
    const minute = Number(loose[2]);
    if (hour <= 23 && minute <= 59) {
      return String(hour).padStart(2, "0") + ":" + String(minute).padStart(2, "0");
    }
  }
  return text.slice(0, 5);
}

/** 表单里的逗号/中文逗号输入 → 去重排序后的时刻列表；无法识别的片段直接丢弃，不往后端送。 */
export function parseDoseTimes(value) {
  return [...new Set(String(value ?? "").split(/[,，\s]+/).map(normalizeTime)
    .filter(text => /^([01]\d|2[0-3]):[0-5]\d$/.test(text)))].sort();
}

/** 时刻列表 → 表单展示文本。 */
export function formatDoseTimes(times) {
  return (Array.isArray(times) ? times : []).map(normalizeTime).join("，");
}

/**
 * 今天该吃哪几次：按家庭时区比较开始/结束日，过结束日不再出现；
 * checks 里已有的 (planId, time) 记为已服用，不改用药计划本身。
 */
export function todayDoses(plans, { today = familyToday(), checks = [] } = {}) {
  const done = new Set((checks || []).map(c => `${c.planId}|${normalizeTime(c.time)}`));
  return (plans || []).flatMap(plan => {
    if (plan.enabled === false) return [];
    const start = String(plan.startDate || "").slice(0, 10);
    const end = String(plan.endDate || "").slice(0, 10);
    if (start && start > today) return [];
    if (end && end < today) return [];
    return (plan.times || []).map(raw => {
      const time = normalizeTime(raw);
      return {
        planId: plan.id,
        name: plan.name || "",
        dosage: plan.dosage || "",
        time,
        taken: done.has(`${plan.id}|${time}`)
      };
    });
  }).sort((a, b) => a.time.localeCompare(b.time) || String(a.planId).localeCompare(String(b.planId)));
}

/** 当天未服用的药，供打开应用时提醒一次。 */
export function pendingDoses(doses) {
  return (doses || []).filter(item => !item.taken);
}

/**
 * 就诊时间折算到家庭时区后，再看是否属于今天。
 * 库里的 LocalDateTime 没有时区后缀，按家庭本地时间直接取日期；
 * 带 Z / 偏移量的时间先折算，避免 UTC 日期把复诊挪到前一天。
 */
export function familyDateOf(value) {
  const text = String(value ?? "");
  if (!text) return "";
  if (/([zZ]|[+-]\d{2}:\d{2})$/.test(text)) {
    const date = new Date(text);
    return Number.isNaN(date.getTime()) ? "" : familyToday(date);
  }
  return text.slice(0, 10);
}

/** 就诊时间属于家庭时区今天的复诊。 */
export function todayFollowUps(visits, today = familyToday()) {
  return (visits || []).filter(item => familyDateOf(item?.visitAt) === today);
}

/** 与 anniversary-remind.mjs 相同的按日期去重方式：两类提醒各记一个 key，跨天自然失效。 */
export function healthRemindDoneKey(kind, date = familyToday()) {
  return `family_health_${kind}_reminded_${date}`;
}

/** 一条弹窗里分两段说清，避免登录时连弹三个框。 */
export function healthRemindText({ medicines = [], visits = [] } = {}) {
  const lines = [];
  if (medicines.length) {
    lines.push("今天的药：");
    medicines.slice(0, 3).forEach(item => lines.push(`· ${item.name} ${item.time}${item.dosage ? "（" + item.dosage + "）" : ""}`));
    if (medicines.length > 3) lines.push(`…等 ${medicines.length} 次`);
  }
  if (visits.length) {
    lines.push("今天的复诊：");
    visits.slice(0, 3).forEach(item => lines.push(`· ${item.title}${item.hospital ? "（" + item.hospital + "）" : ""}`));
    if (visits.length > 3) lines.push(`…等 ${visits.length} 条`);
  }
  return lines.join("\n");
}
