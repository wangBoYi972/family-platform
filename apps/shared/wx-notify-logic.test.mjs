/**
 * wx-notify-logic.mjs 的单元测试
 * 运行：node --test apps/shared/wx-notify-logic.test.mjs
 */

import { describe, it } from "node:test";
import assert from "node:assert";
import {
  shouldRequestNotify,
  shouldEnterBackoff,
  extractAcceptedKeys
} from "./wx-notify-logic.mjs";

describe("shouldRequestNotify", () => {
  const now = Date.now();

  it("非微信小程序平台应跳过", () => {
    const result = shouldRequestNotify({
      isMpWeixin: false,
      askedThisSession: false,
      lastRejectedAt: 0,
      lastAskedToday: 0,
      now
    });
    assert.strictEqual(result.should, false);
    assert.strictEqual(result.reason, "not-mp-weixin");
  });

  it("本会话已弹过且配置once=true应跳过", () => {
    const result = shouldRequestNotify({
      isMpWeixin: true,
      askedThisSession: true,
      lastRejectedAt: 0,
      lastAskedToday: 0,
      now,
      config: { once: true }
    });
    assert.strictEqual(result.should, false);
    assert.strictEqual(result.reason, "asked-this-session");
  });

  it("在7天拒绝退避期内应跳过", () => {
    const lastRejectedAt = now - 3 * 24 * 3600 * 1000; // 3天前
    const result = shouldRequestNotify({
      isMpWeixin: true,
      askedThisSession: false,
      lastRejectedAt,
      lastAskedToday: 0,
      now
    });
    assert.strictEqual(result.should, false);
    assert.strictEqual(result.reason, "in-backoff-period");
  });

  it("今天已弹过且配置oncePerDay=true应跳过", () => {
    const lastAskedToday = now - 2 * 3600 * 1000; // 2小时前，同一天
    const result = shouldRequestNotify({
      isMpWeixin: true,
      askedThisSession: false,
      lastRejectedAt: 0,
      lastAskedToday,
      now,
      config: { oncePerDay: true }
    });
    assert.strictEqual(result.should, false);
    assert.strictEqual(result.reason, "asked-today");
  });

  it("昨天弹过今天应该可以再弹", () => {
    const yesterday = now - 25 * 3600 * 1000; // 25小时前，昨天
    const result = shouldRequestNotify({
      isMpWeixin: true,
      askedThisSession: false,
      lastRejectedAt: 0,
      lastAskedToday: yesterday,
      now,
      config: { oncePerDay: true }
    });
    assert.strictEqual(result.should, true);
    assert.strictEqual(result.reason, null);
  });

  it("8天前拒绝过现在应该可以再弹", () => {
    const lastRejectedAt = now - 8 * 24 * 3600 * 1000; // 8天前
    const result = shouldRequestNotify({
      isMpWeixin: true,
      askedThisSession: false,
      lastRejectedAt,
      lastAskedToday: 0,
      now
    });
    assert.strictEqual(result.should, true);
    assert.strictEqual(result.reason, null);
  });

  it("满足所有条件应该弹窗", () => {
    const result = shouldRequestNotify({
      isMpWeixin: true,
      askedThisSession: false,
      lastRejectedAt: 0,
      lastAskedToday: 0,
      now
    });
    assert.strictEqual(result.should, true);
    assert.strictEqual(result.reason, null);
  });
});

describe("shouldEnterBackoff", () => {
  it("所有模板都被拒绝应进入退避期", () => {
    const results = [
      { key: "task-reminder", result: "reject" },
      { key: "anniversary-reminder", result: "reject" }
    ];
    assert.strictEqual(shouldEnterBackoff(results), true);
  });

  it("部分模板被接受不应进入退避期", () => {
    const results = [
      { key: "task-reminder", result: "accept" },
      { key: "anniversary-reminder", result: "reject" }
    ];
    assert.strictEqual(shouldEnterBackoff(results), false);
  });

  it("所有模板都被接受不应进入退避期", () => {
    const results = [
      { key: "task-reminder", result: "accept" },
      { key: "anniversary-reminder", result: "accept" }
    ];
    assert.strictEqual(shouldEnterBackoff(results), false);
  });

  it("空结果不应进入退避期", () => {
    assert.strictEqual(shouldEnterBackoff([]), false);
    assert.strictEqual(shouldEnterBackoff(null), false);
    assert.strictEqual(shouldEnterBackoff(undefined), false);
  });
});

describe("extractAcceptedKeys", () => {
  it("应提取已接受的模板键", () => {
    const wxResult = {
      "task-reminder": "accept",
      "anniversary-reminder": "reject"
    };
    const keys = ["task-reminder", "anniversary-reminder"];
    const accepted = extractAcceptedKeys(wxResult, keys);
    assert.deepStrictEqual(accepted, ["task-reminder"]);
  });

  it("所有模板都接受", () => {
    const wxResult = {
      "task-reminder": "accept",
      "anniversary-reminder": "accept"
    };
    const keys = ["task-reminder", "anniversary-reminder"];
    const accepted = extractAcceptedKeys(wxResult, keys);
    assert.deepStrictEqual(accepted, ["task-reminder", "anniversary-reminder"]);
  });

  it("所有模板都拒绝", () => {
    const wxResult = {
      "task-reminder": "reject",
      "anniversary-reminder": "reject"
    };
    const keys = ["task-reminder", "anniversary-reminder"];
    const accepted = extractAcceptedKeys(wxResult, keys);
    assert.deepStrictEqual(accepted, []);
  });

  it("空结果应返回空数组", () => {
    assert.deepStrictEqual(extractAcceptedKeys(null, ["key"]), []);
    assert.deepStrictEqual(extractAcceptedKeys({}, []), []);
    assert.deepStrictEqual(extractAcceptedKeys({}, null), []);
  });
});
