import test from "node:test";
import assert from "node:assert/strict";
import { localDate, nextTaskStatus, canManageTask, filterTasks } from "./tasks.mjs";

test("本地清晨日期不回退到 UTC 前一天", () => {
  assert.equal(localDate(new Date(2026, 8, 29, 0, 10)), "2026-09-29");
});
test("任务只能逐步开始、完成，完成后无下一状态", () => {
  assert.equal(nextTaskStatus("TODO"), "IN_PROGRESS");
  assert.equal(nextTaskStatus("IN_PROGRESS"), "COMPLETED");
  assert.equal(nextTaskStatus("COMPLETED"), null);
  assert.equal(nextTaskStatus("UNKNOWN"), null);
});
test("操作入口仅对管理员、创建者和负责人开放", () => {
  const task = { creatorId: 11, assigneeId: 12 };
  assert.equal(canManageTask(task, null), false);
  assert.equal(canManageTask(task, { userId: 13, role: "MEMBER" }), false);
  assert.equal(canManageTask(task, { userId: 11, role: "MEMBER" }), true);
  assert.equal(canManageTask(task, { userId: "12", role: "MEMBER" }), true);
  assert.equal(canManageTask(task, { userId: 13, role: "ADMIN" }), true);
});
test("逾期筛选排除今天、未设日期和已完成任务", () => {
  const tasks = [
    { id: 1, title: "买菜", assigneeName: "爸爸", deadline: "2026-09-28", status: "TODO" },
    { id: 2, title: "取件", deadline: "2026-09-29", status: "IN_PROGRESS" },
    { id: 3, title: "浇花", deadline: null, status: "TODO" },
    { id: 4, title: "清洁", deadline: "2026-09-27", status: "COMPLETED" }
  ];
  assert.deepEqual(filterTasks(tasks, { status: "OVERDUE", today: "2026-09-29" }).map(t => t.id), [1]);
  assert.deepEqual(filterTasks(tasks, { keyword: " 爸爸 " }).map(t => t.id), [1]);
  assert.deepEqual(filterTasks(tasks, { status: "TODO", keyword: "浇花" }).map(t => t.id), [3]);
  assert.deepEqual(filterTasks(tasks, { keyword: "不存在" }), []);
});
