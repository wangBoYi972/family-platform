import test from "node:test";
import assert from "node:assert/strict";
import {
  familyToday, formatDoseTimes, healthRemindDoneKey, healthRemindText,
  parseDoseTimes, pendingDoses, todayDoses, todayFollowUps
} from "./health-schedule.mjs";

const TODAY = "2026-11-22";

test("家庭时区决定今天，UTC 跨日不漂移", () => {
  assert.equal(familyToday(new Date("2026-11-22T15:59:00Z")), "2026-11-22");
  assert.equal(familyToday(new Date("2026-11-22T16:01:00Z")), "2026-11-23");
});

test("今天该吃哪几次：结束日当天还吃，过了结束日不再出现", () => {
  const plans = [
    { id: "p1", name: "早药", dosage: "1 片", times: ["20:00", "08:00"], startDate: "2026-11-20", endDate: TODAY },
    { id: "p2", name: "过期药", times: ["09:00"], startDate: "2026-11-01", endDate: "2026-11-21" },
    { id: "p3", name: "未开始", times: ["09:00"], startDate: "2026-11-23", endDate: "2026-11-30" },
    { id: "p4", name: "停用", times: ["09:00"], enabled: false, startDate: "2026-11-01", endDate: "2026-11-30" }
  ];
  assert.deepEqual(todayDoses(plans, { today: TODAY }).map(v => `${v.planId}:${v.time}`), ["p1:08:00", "p1:20:00"]);

  const taken = todayDoses(plans, { today: TODAY, checks: [{ planId: "p1", time: "08:00" }] });
  assert.deepEqual(pendingDoses(taken).map(v => v.time), ["20:00"]);
});

test("表单时刻去重排序，复诊只取今天的日期", () => {
  assert.deepEqual(parseDoseTimes("20:00，08:00, 08:00"), ["08:00", "20:00"]);
  // 省略前导零要补成 HH:mm，否则后端 times 的两位小时校验会拒。
  assert.deepEqual(parseDoseTimes("8:00，9:5"), ["08:00", "09:05"]);
  // 无法识别的片段直接丢弃，不往后端送。
  assert.deepEqual(parseDoseTimes("08:00, 早上, 25:99"), ["08:00"]);
  assert.equal(formatDoseTimes(["08:00", "20:00"]), "08:00，20:00");
  const visits = [
    { id: "v1", title: "今天复诊", visitAt: "2026-11-22T10:30:00" },
    { id: "v2", title: "明天复诊", visitAt: "2026-11-23T10:30:00" },
    { id: "v3", title: "Z 尾今天", visitAt: "2026-11-22T02:30:00Z" }
  ];
  // Z 尾 02:30 折到家庭时区是当天 10:30，仍算今天；23:30Z 则已跨到次日。
  assert.deepEqual(todayFollowUps(visits, TODAY).map(v => v.id), ["v1", "v3"]);
  assert.deepEqual(todayFollowUps([{ id: "v4", visitAt: "2026-11-22T23:30:00Z" }], TODAY), []);
});

test("两类提醒按日期各自去重，文案分两段", () => {
  assert.equal(healthRemindDoneKey("medication", TODAY), `family_health_medication_reminded_${TODAY}`);
  assert.equal(healthRemindDoneKey("follow_up", TODAY), `family_health_follow_up_reminded_${TODAY}`);
  const text = healthRemindText({
    medicines: [{ name: "降压药", time: "08:00", dosage: "1 片" }],
    visits: [{ title: "心内科复诊", hospital: "中心医院" }]
  });
  assert.match(text, /今天的药/);
  assert.match(text, /降压药 08:00/);
  assert.match(text, /今天的复诊/);
  assert.match(text, /心内科复诊/);
});
