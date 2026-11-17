import test from "node:test";
import assert from "node:assert/strict";
import { parseSseBuffer, resolveAiHistory } from "./ai-client.mjs";

test("完整事件一次解析：delta/tool/done 各归其位", () => {
  const text = [
    'data: {"type":"delta","text":"你好"}',
    "",
    'data: {"type":"tool","name":"list_tasks"}',
    "",
    'data: {"type":"delta","text":"，家里"}',
    "",
    'data: {"type":"done"}',
    "",
    ""
  ].join("\n");
  const { events, rest } = parseSseBuffer(text);
  assert.equal(events.length, 4);
  assert.deepEqual(events[0], { type: "delta", text: "你好" });
  assert.deepEqual(events[1], { type: "tool", name: "list_tasks" });
  assert.deepEqual(events[2], { type: "delta", text: "，家里" });
  assert.equal(events[3].type, "done");
  assert.equal(rest, "");
});

test("半截事件留在缓冲：跨分片的事件能拼回来", () => {
  const first = parseSseBuffer('data: {"type":"delta","text":"家');
  assert.equal(first.events.length, 0);
  assert.equal(first.rest, 'data: {"type":"delta","text":"家');
  const second = parseSseBuffer(first.rest + '"}\n\n');
  assert.equal(second.events.length, 1);
  assert.equal(second.events[0].text, "家");
});

test("\\r\\n 行尾兼容：换行风格不吞事件", () => {
  const text = 'data: {"type":"delta","text":"a"}\r\n\r\ndata: {"type":"done"}\r\n\r\n';
  const { events } = parseSseBuffer(text);
  assert.equal(events.length, 2);
});

test("非 data 行与 [DONE] 哨兵被忽略", () => {
  const text = ": keep-alive\n\ndata: [DONE]\n\ndata: {\"type\":\"done\"}\n\n";
  const { events } = parseSseBuffer(text);
  assert.equal(events.length, 1);
  assert.equal(events[0].type, "done");
});

test("服务端有历史就用服务端，空库才把本机旧记录标成待导入", () => {
  const local = [{ role: "user", content: "旧问题" }, { role: "system", content: "丢掉" }];
  const remote = [{ role: "assistant", content: "新回答", createdAt: "2026-11-16T08:00:00" }];
  const fromServer = resolveAiHistory(remote, local);
  assert.equal(fromServer.shouldImport, false);
  assert.equal(fromServer.history.length, 1);
  assert.equal(fromServer.history[0].content, "新回答");

  const pending = resolveAiHistory([], local);
  assert.equal(pending.shouldImport, true);
  assert.deepEqual(pending.history.map(item => item.content), ["旧问题"]);

  const offline = resolveAiHistory(null, local);
  assert.equal(offline.shouldImport, false);
  assert.equal(offline.history[0].content, "旧问题");

  // 本机没有当前账号的缓存时，空库也不该导入（换账号后常见）
  const emptyLocal = resolveAiHistory([], []);
  assert.equal(emptyLocal.shouldImport, false);
  assert.deepEqual(emptyLocal.history, []);
});

test("坏 JSON 丢弃不炸整段流", () => {
  const text = 'data: {"type":"delta","text":\n\ndata: {"type":"done"}\n\n';
  const { events } = parseSseBuffer(text);
  assert.equal(events.length, 1);
});
