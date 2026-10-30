import { test } from "node:test";
import assert from "node:assert/strict";
import { buildAnniversary, anniversariesToday, remindDoneKey, remindText } from "./anniversary-remind.mjs";

const TODAY = "2026-10-30";

test("buildAnniversary：yearly 今年未到折今年，已过折明年并累计第 N 个", () => {
  // 生日 11-02：今年还有 3 天
  const soon = buildAnniversary({ id: 1, title: "妈妈生日", eventDate: "1990-11-02", yearly: true }, TODAY);
  assert.equal(soon.days, 3);
  assert.equal(soon.date, "2026-11-02");
  // 第 37 个：2026 - 1990 + 1
  assert.equal(soon.nth, 37);
  // 生日 10-01：今年已过，折到 2027
  const passed = buildAnniversary({ id: 2, title: "结婚纪念日", eventDate: "2015-10-01", yearly: true }, TODAY);
  assert.equal(passed.days, 336);
  assert.equal(passed.date, "2027-10-01");
  assert.equal(passed.nth, 13);
});

test("buildAnniversary：一次性日程已过去 days 为负，缺日期返回 null", () => {
  const gone = buildAnniversary({ id: 3, title: "体检", eventDate: "2026-10-20" }, TODAY);
  assert.equal(gone.days, -10);
  assert.equal(buildAnniversary({ title: "没有日期" }, TODAY), null);
  assert.equal(buildAnniversary({ eventDate: "垃圾数据" }, TODAY), null);
});

test("anniversariesToday 只挑出 days === 0 的条目", () => {
  const rows = [
    { id: 1, title: "今天生日", eventDate: "2000-10-30", yearly: true },
    { id: 2, title: "明天的事", eventDate: "2026-10-31" },
    { id: 3, title: "也是今天", eventDate: "2026-10-30" }
  ];
  const today = anniversariesToday(rows, TODAY);
  assert.deepEqual(today.map(item => item.id), [1, 3]);
});

test("remindDoneKey 按天区分，同一天固定", () => {
  assert.equal(remindDoneKey("2026-10-30"), "family_anniv_reminded_2026-10-30");
  assert.notEqual(remindDoneKey("2026-10-30"), remindDoneKey("2026-10-31"));
});

test("remindText：一两条直说，超过三条截断并带总数", () => {
  const one = remindText([{ title: "妈妈生日", yearly: true, nth: 37 }]);
  assert.match(one, /妈妈生日（第 37 个）/);
  const many = remindText([
    { title: "一", yearly: false, nth: 0 },
    { title: "二", yearly: false, nth: 0 },
    { title: "三", yearly: false, nth: 0 },
    { title: "四", yearly: false, nth: 0 }
  ]);
  assert.match(many, /· 一\n· 二\n· 三\n…等 4 个/);
  assert.ok(!many.includes("四\n"));
});
