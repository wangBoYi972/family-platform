import { test } from "node:test";
import assert from "node:assert/strict";
import { ACTION_LABELS, fillDays, percent } from "./audit-view.mjs";

test("fillDays 补零并按旧→新排列，窗口正好 days 天", () => {
  const days = fillDays(
    [{ name: "2026-10-29", count: 3 }, { name: "2026-10-27", count: 1 }],
    7,
    "2026-10-30"
  );
  assert.equal(days.length, 7);
  assert.deepEqual(days.map(d => d.day), [
    "2026-10-24", "2026-10-25", "2026-10-26", "2026-10-27",
    "2026-10-28", "2026-10-29", "2026-10-30"
  ]);
  assert.deepEqual(days.map(d => d.count), [0, 0, 0, 1, 0, 3, 0]);
});

test("fillDays 无数据时全是零，today 缺省取今天", () => {
  const days = fillDays([], 3);
  assert.equal(days.length, 3);
  assert.ok(days.every(d => d.count === 0));
  // 最后一天是今天（本地时区）
  const now = new Date();
  const pad = n => String(n).padStart(2, "0");
  const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  assert.equal(days[2].day, today);
});

test("percent 有最小可见高度，max 为 0 时安全返回 0", () => {
  assert.equal(percent(1, 10), 10);
  assert.equal(percent(5, 10), 50);
  assert.equal(percent(0, 10), 4);   // 零星可见
  assert.equal(percent(3, 0), 0);
  assert.equal(percent(3, undefined), 0);
  assert.equal(percent("bad", 10), 4);
});

test("ACTION_LABELS 覆盖后端的四种动作", () => {
  assert.deepEqual(Object.keys(ACTION_LABELS).sort(), ["CREATE", "DELETE", "READ", "UPDATE"]);
});
