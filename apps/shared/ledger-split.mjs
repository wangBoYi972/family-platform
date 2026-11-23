/**
 * 账本表单的纯逻辑：金额一律按整数分处理。
 * 这里不做任何网络请求，方便 node --test 直接验证分摊规则。
 */

export const LEDGER_CATEGORIES = ["餐饮", "日用", "住房", "交通", "医疗", "教育", "其他"];

/** 把用户输入的「元」字符串按小数位切分，绕开二进制浮点误差。 */
export function toCents(input) {
  const text = String(input ?? "").trim();
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return null;
  const [yuan, fen = ""] = text.split(".");
  const cents = Number(yuan) * 100 + Number(fen.padEnd(2, "0"));
  return Number.isSafeInteger(cents) && cents > 0 ? cents : null;
}

/** 展示用：整数分转「元」，不用 toFixed，避免出现 0.30000000000000004。 */
export function formatYuan(cents) {
  const value = Number(cents);
  if (!Number.isSafeInteger(value)) return "—";
  const sign = value < 0 ? "-" : "";
  const abs = Math.abs(value);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}

/**
 * 默认平均分摊：余数从前往后每人多 1 分，保证每个人都是整数分且总和精确等于账单。
 */
export function evenSplit(totalCents, memberIds) {
  const total = Number(totalCents);
  if (!Number.isSafeInteger(total) || total < 1 || !Array.isArray(memberIds) || memberIds.length === 0) return [];
  const base = Math.floor(total / memberIds.length);
  const remainder = total % memberIds.length;
  return memberIds.map((id, index) => ({ memberId: id, amountCents: base + (index < remainder ? 1 : 0) }));
}

/**
 * 提交前校验。activeMemberIds 来自服务端的本家庭启用成员列表：
 * 其他家庭的人、已停用的人都不会在里面，前端先拦一道，服务端仍会再校验一次。
 */
export function validateLedgerForm({ type, amountCents, category, payerId, splits, activeMemberIds }) {
  const errors = [];
  const active = new Set((activeMemberIds || []).map(Number));
  const amount = Number(amountCents);

  if (type !== "EXPENSE" && type !== "INCOME") errors.push("请选择收支类型");
  if (!Number.isSafeInteger(amount) || amount < 1) errors.push("金额必须是以分为单位的正整数");
  if (!LEDGER_CATEGORIES.includes(category)) errors.push("请选择固定分类");
  if (!active.has(Number(payerId))) errors.push("请选择本家庭启用的垫付人或收款人");

  const rows = Array.isArray(splits) ? splits : [];
  if (type === "INCOME") {
    if (rows.length) errors.push("收入记在收款人名下，不需要分摊");
    return { ok: errors.length === 0, errors };
  }

  if (!rows.length) errors.push("支出至少需要一名分摊人");
  const seen = new Set();
  let sum = 0;
  for (const row of rows) {
    const id = Number(row?.memberId);
    const cents = Number(row?.amountCents);
    if (!active.has(id)) errors.push("分摊人必须是本家庭启用成员");
    if (seen.has(id)) errors.push("同一成员不能重复分摊");
    seen.add(id);
    if (!Number.isSafeInteger(cents) || cents < 1) errors.push("每人分摊金额必须是正整数分");
    else sum += cents;
  }
  if (rows.length && sum !== amount) {
    errors.push(`分摊总和 ${sum} 分，与账单 ${amount} 分不一致`);
  }
  return { ok: errors.length === 0, errors, sum };
}

/** 列表里的一笔分摊摘要，避免把整串成员 ID 直接丢给用户看。 */
export function splitSummary(splits, memberNames) {
  const rows = Array.isArray(splits) ? splits : [];
  if (!rows.length) return "收入 · 不分摊";
  const parts = rows.map(s => `${memberNames?.[s.memberId] || "成员"} ¥${formatYuan(s.amountCents)}`);
  return parts.join("、");
}
