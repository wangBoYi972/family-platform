import test from "node:test";
import assert from "node:assert/strict";
import { dayMark, dayCaption, monthCells } from "./calendar-marks.mjs";

test("春节当天同时标出正月初一和春节放假", () => {
  const mark = dayMark("2026-02-17");
  assert.equal(mark.lunar, "农历正月初一");
  assert.equal(mark.lunarShort, "正月");
  assert.equal(mark.highlight, "春节");
  assert.equal(mark.rest, true);
  assert.equal(mark.badge, "休");
  assert.equal(dayCaption("2026-02-17"), "农历正月初一 · 春节");
});

test("除夕保留传统节日名，并标放假", () => {
  const mark = dayMark("2026-02-16");
  assert.equal(mark.highlight, "除夕");
  assert.equal(mark.badge, "休");
});

test("补班只标班，不把放假名盖住农历", () => {
  const mark = dayMark("2026-02-14");
  assert.equal(mark.work, true);
  assert.equal(mark.badge, "班");
  assert.equal(mark.highlight, "");
  assert.match(mark.lunar, /^农历腊月/);
  assert.match(dayCaption("2026-02-14"), /补班$/);
});

test("国庆假日标国庆，普通日子只标农历", () => {
  const national = dayMark("2026-10-01");
  assert.equal(national.highlight, "国庆");
  assert.equal(national.badge, "休");
  const today = dayMark("2026-11-17");
  assert.equal(today.lunar, "农历十月初九");
  assert.equal(today.lunarShort, "初九");
  assert.equal(today.highlight, "");
  assert.equal(today.badge, "");
});

test("节气写在农历旁边", () => {
  const mark = dayMark("2026-11-22");
  assert.equal(mark.lunar, "农历十月十四");
  assert.equal(mark.highlight, "小雪");
  assert.equal(mark.term, true);
  assert.equal(mark.festival, false);
});

test("闰月初一用闰月名", () => {
  const mark = dayMark("2025-07-25");
  assert.equal(mark.lunar, "农历闰六月初一");
  assert.equal(mark.lunarShort, "闰六月");
});

test("月历从周一开始，并带上每一天的标记", () => {
  const cells = monthCells(2026, 1);
  assert.equal(cells.filter(cell => cell === null).length, new Date(2026, 1, 1).getDay() === 0 ? 6 : new Date(2026, 1, 1).getDay() - 1);
  assert.equal(cells.find(cell => cell?.iso === "2026-02-17").highlight, "春节");
});
