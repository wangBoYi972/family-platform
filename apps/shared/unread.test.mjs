import assert from "node:assert/strict";
import test from "node:test";
import { sumUnread } from "./unread.mjs";

test("未读数按会话累加", () => {
  assert.equal(sumUnread([{ unread: 2 }, { unread: 3 }, { unread: 0 }]), 5);
});

test("接口里没有 unread 字段（全部已读）时是 0", () => {
  assert.equal(sumUnread([{ id: "a" }, { id: "b" }]), 0);
});

test("字段是字符串也认，脏数据不能把角标算成 NaN", () => {
  assert.equal(sumUnread([{ unread: "4" }, { unread: null }, { unread: "abc" }]), 4);
  assert.ok(!Number.isNaN(sumUnread([{ unread: "abc" }])));
});

test("空列表与异常入参都返回 0", () => {
  assert.equal(sumUnread([]), 0);
  assert.equal(sumUnread(null), 0);
  assert.equal(sumUnread(undefined), 0);
});
