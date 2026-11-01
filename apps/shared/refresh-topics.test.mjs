import test from "node:test";
import assert from "node:assert/strict";
import { refreshPlan, hitsTopic } from "./refresh-topics.mjs";

const KNOWN = new Set(["task", "file", "notice", "album", "calendar"]);

test("事件类型按点号取前缀作为主题", () => {
  const plan = refreshPlan(new Set(["task.created", "task.status-changed", "file.deleted"]), KNOWN);
  assert.equal(plan.all, false);
  assert.deepEqual([...plan.topics].sort(), ["file", "task"]);
});

test("没带事件类型的帧回退全量刷新", () => {
  assert.equal(refreshPlan(new Set(), KNOWN).all, true);
  assert.equal(refreshPlan(undefined, KNOWN).all, true);
});

test("不认识的事件类型回退全量刷新：映射表没跟上也不能漏变化", () => {
  const plan = refreshPlan(new Set(["member.joined"]), KNOWN);
  assert.equal(plan.all, true);
  assert.equal(hitsTopic(plan, ["task"]), true);
});

test("命中主题的面板刷新，无关面板跳过", () => {
  const plan = refreshPlan(new Set(["task.updated"]), KNOWN);
  assert.equal(hitsTopic(plan, ["task", "calendar"]), true, "TodayOverview 关心任务");
  assert.equal(hitsTopic(plan, ["album"]), false, "相册面板不该跟着重拉");
});
