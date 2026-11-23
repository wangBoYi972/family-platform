import test from "node:test";
import assert from "node:assert/strict";
import { evenSplit, formatYuan, toCents, validateLedgerForm } from "./ledger-split.mjs";

const active = [11, 12, 13];
const base = { type: "EXPENSE", amountCents: 1000, category: "餐饮", payerId: 11, activeMemberIds: active };

test("平均分摊把余数分给前面的人，总和精确等于账单", () => {
  const rows = evenSplit(1000, active);
  assert.deepEqual(rows, [
    { memberId: 11, amountCents: 334 },
    { memberId: 12, amountCents: 333 },
    { memberId: 13, amountCents: 333 }
  ]);
  assert.equal(rows.reduce((sum, r) => sum + r.amountCents, 0), 1000);
});

test("0.1 元加 0.2 元是 30 分，显示没有浮点尾巴", () => {
  assert.equal(toCents("0.1"), 10);
  assert.equal(toCents("0.2"), 20);
  assert.equal(10 + 20, 30);
  assert.equal(formatYuan(30), "0.30");
  assert.equal(formatYuan(-1234), "-12.34");
  assert.equal(toCents("0.001"), null);
});

test("分摊总和不等于账单时校验失败", () => {
  const result = validateLedgerForm({
    ...base,
    splits: [{ memberId: 11, amountCents: 600 }, { memberId: 12, amountCents: 300 }]
  });
  assert.equal(result.ok, false);
  assert.ok(result.errors.some(e => e.includes("不一致")));
});

test("跨家庭成员和已停用成员都不能分摊", () => {
  const stranger = validateLedgerForm({
    ...base,
    splits: [{ memberId: 99, amountCents: 1000 }]
  });
  assert.equal(stranger.ok, false);
  assert.ok(stranger.errors.some(e => e.includes("本家庭启用成员")));

  const inactive = validateLedgerForm({
    ...base,
    payerId: 13,
    activeMemberIds: [11, 12],
    splits: [{ memberId: 13, amountCents: 1000 }]
  });
  assert.equal(inactive.ok, false);
  assert.ok(inactive.errors.some(e => e.includes("本家庭启用成员")));
});

test("重复分摊人、非正整数金额与未知分类都会被拦住", () => {
  const duplicate = validateLedgerForm({
    ...base,
    splits: [{ memberId: 11, amountCents: 500 }, { memberId: 11, amountCents: 500 }]
  });
  assert.equal(duplicate.ok, false);
  assert.ok(duplicate.errors.some(e => e.includes("重复")));

  const fractional = validateLedgerForm({ ...base, amountCents: 1000.5, splits: evenSplit(1000, active) });
  assert.equal(fractional.ok, false);

  const category = validateLedgerForm({ ...base, category: "游戏", splits: evenSplit(1000, active) });
  assert.equal(category.ok, false);
});

test("收入不分摊，且收款人必须是启用成员", () => {
  const income = validateLedgerForm({
    ...base,
    type: "INCOME",
    payerId: 12,
    splits: []
  });
  assert.equal(income.ok, true);
  const withSplit = validateLedgerForm({ ...base, type: "INCOME", payerId: 12, splits: evenSplit(1000, active) });
  assert.equal(withSplit.ok, false);
});
