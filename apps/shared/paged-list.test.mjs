import test from "node:test";
import assert from "node:assert/strict";
import { PAGE_SIZE, hasMore, mergePage, pageQuery } from "./paged-list.mjs";

test("分页查询串能拼到无参数与已有参数的路径上", () => {
  assert.equal(pageQuery("/tasks", 2, 20), "/tasks?page=2&size=20");
  assert.equal(pageQuery("/im/conversations?unread=1", 3, 50), "/im/conversations?unread=1&page=3&size=50");
});
test("返回条数达到一页说明可能还有下一页，少了就到底", () => {
  assert.equal(hasMore(PAGE_SIZE), true);
  assert.equal(hasMore(PAGE_SIZE + 1), true);
  assert.equal(hasMore(PAGE_SIZE - 1), false);
  assert.equal(hasMore(0), false);
  assert.equal(hasMore(2, 2), true);
});
test("翻页期间新增记录导致的重复项会被去掉且保持顺序", () => {
  const first = [{ id: 3 }, { id: 2 }];
  const second = [{ id: 2 }, { id: 1 }];
  assert.deepEqual(mergePage(first, second).map(item => item.id), [3, 2, 1]);
});
test("没有主键的项按原样保留，不做误删", () => {
  const merged = mergePage([{ name: "a" }], [{ name: "a" }], item => item.id);
  assert.equal(merged.length, 2);
});
test("空页与空数组不会破坏已有列表", () => {
  assert.deepEqual(mergePage([{ id: 1 }], []).map(item => item.id), [1]);
  assert.deepEqual(mergePage([], [{ id: 1 }]).map(item => item.id), [1]);
  assert.deepEqual(mergePage(null, null), []);
});
