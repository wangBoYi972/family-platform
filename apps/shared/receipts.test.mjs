import assert from "node:assert/strict";
import test from "node:test";
import { mergeReceipt, readMemberIds } from "./receipts.mjs";

const frame = (userId, lastMessageId) => ({ type: "read", conversationId: "dm-1-2", userId, lastMessageId });

test("收到回执后加上这个人的水位", () => {
  const next = mergeReceipt([], frame(2, 7));
  assert.deepEqual(next, [{ userId: 2, lastMessageId: 7 }]);
});

test("同一个人水位前进时替换而不是追加", () => {
  const next = mergeReceipt([{ userId: 2, lastMessageId: 5 }], frame(2, 9));
  assert.deepEqual(next, [{ userId: 2, lastMessageId: 9 }]);
});

test("迟到的旧帧不回退水位", () => {
  const current = [{ userId: 2, lastMessageId: 9 }];
  assert.equal(mergeReceipt(current, frame(2, 4)), current);
});

test("非回执帧、缺字段的帧原样返回", () => {
  const current = [{ userId: 2, lastMessageId: 9 }];
  assert.equal(mergeReceipt(current, { type: "message", messageId: 3 }), current);
  assert.equal(mergeReceipt(current, frame(2, 0)), current);
  assert.equal(mergeReceipt(current, null), current);
});

test("已读名单排除自己，只算水位够到我最后一条的人", () => {
  const list = [
    { userId: 2, lastMessageId: 12 },
    { userId: 3, lastMessageId: 8 },
    { userId: 4, lastMessageId: "12" }
  ];
  assert.deepEqual(readMemberIds(list, 2, 12), [4]);
  assert.deepEqual(readMemberIds(list, 2, 0), []);
  assert.deepEqual(readMemberIds(list, 2, 8), [3, 4]);
});
