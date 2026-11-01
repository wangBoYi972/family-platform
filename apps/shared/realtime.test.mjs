import test from "node:test";
import assert from "node:assert/strict";
import { createRefreshGate } from "./realtime.mjs";

/** 手动调度的假定时器：测试不依赖真实时间，也不会因为机器慢而抖动。 */
function fakeClock() {
  let seq = 0;
  const timers = new Map();
  return {
    schedule(fn) {
      const id = ++seq;
      timers.set(id, fn);
      return id;
    },
    cancel(id) {
      timers.delete(id);
    },
    tick() {
      const pending = [...timers.values()];
      timers.clear();
      pending.forEach(fn => fn());
    },
    get size() {
      return timers.size;
    }
  };
}

test("一次突发里的多条事件只刷新一次", () => {
  const clock = fakeClock();
  const flushes = [];
  const gate = createRefreshGate({ onFlush: count => flushes.push(count), schedule: clock.schedule, cancel: clock.cancel });

  gate.push();
  gate.push();
  gate.push();

  assert.equal(flushes.length, 0, "窗口期结束前不应该刷新");
  clock.tick();
  assert.deepEqual(flushes, [3], "三条事件应合并成一次刷新并带上真实条数");
  assert.equal(gate.pending, 0);
});

test("前后两批事件分别刷新", () => {
  const clock = fakeClock();
  const flushes = [];
  const gate = createRefreshGate({ onFlush: count => flushes.push(count), schedule: clock.schedule, cancel: clock.cancel });

  gate.push();
  clock.tick();
  gate.push();
  clock.tick();

  assert.deepEqual(flushes, [1, 1]);
});

test("flushNow 立刻兑现且不会重复回调", () => {
  const clock = fakeClock();
  const flushes = [];
  const gate = createRefreshGate({ onFlush: count => flushes.push(count), schedule: clock.schedule, cancel: clock.cancel });

  gate.push();
  gate.flushNow();
  assert.deepEqual(flushes, [1]);
  assert.equal(clock.size, 0, "已被 flushNow 消费的定时器必须撤销");

  clock.tick();
  assert.deepEqual(flushes, [1], "窗口期再走一遍也不能重复刷新");
});

test("dispose 之后迟到的事件不再触发刷新", () => {
  const clock = fakeClock();
  const flushes = [];
  const gate = createRefreshGate({ onFlush: count => flushes.push(count), schedule: clock.schedule, cancel: clock.cancel });

  gate.push();
  gate.dispose();
  clock.tick();

  assert.deepEqual(flushes, []);
  assert.equal(gate.pending, 0);
});
