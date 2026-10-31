import test from "node:test";
import assert from "node:assert/strict";
import { NAV_KEYS, FAMILY_NAV_TARGETS, MOBILE_NAV_TARGETS, resolveNavTarget, missingNavTargets } from "./navigation.mjs";

test("两端都为每个数据源配了跳转目标", () => {
  assert.deepEqual(missingNavTargets(FAMILY_NAV_TARGETS), []);
  assert.deepEqual(missingNavTargets(MOBILE_NAV_TARGETS), []);
});

test("映射表没有多余的 key", () => {
  assert.deepEqual(Object.keys(FAMILY_NAV_TARGETS).sort(), [...NAV_KEYS].sort());
  assert.deepEqual(Object.keys(MOBILE_NAV_TARGETS).sort(), [...NAV_KEYS].sort());
});

test("每个目标都带了 tab，子标签只出现在需要的那几项", () => {
  for (const map of [FAMILY_NAV_TARGETS, MOBILE_NAV_TARGETS]) {
    for (const key of NAV_KEYS) {
      assert.ok(map[key].tab, key + " 缺少 tab");
      assert.equal(typeof map[key].tab, "string");
    }
  }
  assert.equal(MOBILE_NAV_TARGETS.calendar.sub, "家庭日历");
  assert.equal(FAMILY_NAV_TARGETS.calendar.calTab, "日程");
  assert.equal(MOBILE_NAV_TARGETS.tasks.sub, undefined);
});

test("按 key 解析到对应目标", () => {
  const fallback = { tab: "任务" };
  assert.deepEqual(resolveNavTarget(MOBILE_NAV_TARGETS, { key: "albums" }, fallback), { tab: "家庭圈", sub: "相册" });
  assert.deepEqual(resolveNavTarget(MOBILE_NAV_TARGETS, "members", fallback), { tab: "我的家", sub: "成员" });
});

test("未知 key 与空值回落到兜底目标，不会跳空", () => {
  const fallback = { tab: "任务" };
  assert.deepEqual(resolveNavTarget(MOBILE_NAV_TARGETS, { key: "unknown" }, fallback), fallback);
  assert.deepEqual(resolveNavTarget(MOBILE_NAV_TARGETS, null, fallback), fallback);
  assert.deepEqual(resolveNavTarget(MOBILE_NAV_TARGETS, {}, fallback), fallback);
});
