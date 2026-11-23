import assert from "node:assert/strict";
import test from "node:test";
import { CallRecorder, extensionFor, pickMimeType } from "./call/call-recorder.mjs";

test("容器类型映射成文件后缀，错了会让转写端嗅探失败", () => {
  assert.equal(extensionFor("audio/webm;codecs=opus"), "webm");
  assert.equal(extensionFor("audio/mp4"), "m4a");
  assert.equal(extensionFor("audio/ogg;codecs=opus"), "ogg");
  assert.equal(extensionFor("audio/mpeg"), "mp3");
  assert.equal(extensionFor(""), "webm");
});

test("没有 MediaRecorder 的环境返回空 mime，由调用方直接报「不支持录音」", () => {
  assert.equal(typeof MediaRecorder, "undefined");
  assert.equal(pickMimeType(), "");
});

test("分段时长有下限：配置成几秒会把转写费用烧在碎片上", () => {
  assert.equal(new CallRecorder({ segmentMs: 1000 }).segmentMs, 30000);
  assert.equal(new CallRecorder({ segmentMs: 180000 }).segmentMs, 180000);
  assert.equal(new CallRecorder().segmentMs, 180000);
});

test("未开始时 stop 返回空数组，重复调用不抛异常", async () => {
  const recorder = new CallRecorder();
  assert.deepEqual(await recorder.stop(), []);
  assert.deepEqual(await recorder.stop(), []);
});
