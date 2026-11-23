import assert from "node:assert/strict";
import test from "node:test";
import {
  UNKNOWN_DURATION,
  callDurationSeconds,
  callDurationText,
  callOutcome,
  formatDuration,
  isCallCaller,
  isMissedCall
} from "./call-history.mjs";

const answered = {
  callerId: 11,
  status: "ENDED",
  endReason: "HANGUP",
  createdAt: "2026-11-22T10:00:00Z",
  answeredAt: "2026-11-22T10:00:03Z",
  endedAt: "2026-11-22T10:01:08Z",
  participants: [
    { userId: 11, state: "LEFT" },
    { userId: 12, state: "LEFT" }
  ]
};

test("时长按接听到结束计算，不把响铃时间算进去", () => {
  assert.equal(callDurationSeconds(answered), 65);
  assert.equal(callDurationText(answered), "01:05");
});

test("时长缺失或时间倒流时显示未知，不出现负数", () => {
  assert.equal(callDurationText({ ...answered, answeredAt: null }), UNKNOWN_DURATION);
  assert.equal(callDurationText({ ...answered, endedAt: null }), UNKNOWN_DURATION);
  assert.equal(callDurationText({ ...answered, endedAt: "2026-11-22T09:59:00Z" }), UNKNOWN_DURATION);
  assert.equal(callDurationText({}), UNKNOWN_DURATION);
});

test("时长格式覆盖零秒、整分和跨小时", () => {
  assert.equal(formatDuration(0), "00:00");
  assert.equal(formatDuration(9), "00:09");
  assert.equal(formatDuration(600), "10:00");
  assert.equal(formatDuration(3661), "1:01:01");
  assert.equal(formatDuration(-1), UNKNOWN_DURATION);
  assert.equal(formatDuration(Number.NaN), UNKNOWN_DURATION);
});

test("未接来电从会话理由和参与者状态两处判断", () => {
  const missed = {
    ...answered,
    endReason: "NO_ANSWER",
    answeredAt: null,
    endedAt: "2026-11-22T10:00:45Z",
    participants: [
      { userId: 11, state: "JOINED" },
      { userId: 12, state: "MISSED" }
    ]
  };
  assert.equal(callOutcome(missed, 12), "未接");
  assert.equal(isMissedCall(missed, 12), true);
  // 同一通未接来电，对发起者来说是「无人接听」，不该标成自己的未接来电
  assert.equal(callOutcome(missed, 11), "无人接听");
  assert.equal(isMissedCall(missed, 11), false);
});

test("接通、拒接和进行中的文案互不混淆", () => {
  assert.equal(callOutcome(answered, 12), "已接听");
  assert.equal(isMissedCall(answered, 12), false);
  assert.equal(callOutcome({ ...answered, endReason: "REJECTED", answeredAt: null }, 11), "对方已拒绝");
  assert.equal(callOutcome({ ...answered, status: "ACTIVE" }, 11), "通话中");
  assert.equal(isCallCaller(answered, 11), true);
  assert.equal(isCallCaller(answered, 12), false);
});
