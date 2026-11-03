// AI 家庭助手的流式对话客户端（家庭端 / 移动端共用）。
//
// 后端 /api/ai/chat 返回 SSE：每行 `data: {"type":"delta"|"tool"|"done"|"error",...}`。
// uni.request 开 enableChunked 后经 onChunkReceived 收 ArrayBuffer 分片——
// 分片边界不保证落在事件边界上，所以要按 \n\n 切并缓存残包（parseSseBuffer 抽成纯函数，可单测）。
// 运行时不支持 chunked（老基础库）时降级：success 一次性拿到全文，整段解析，只是没有打字机效果。

/** 解析一段 SSE 文本缓冲：返回完整事件数组与剩下的残包。 */
export function parseSseBuffer(buffer) {
  const events = [];
  let rest = "";
  // 事件以空行分隔；兼容 \r\n
  const normalized = buffer.replace(/\r\n/g, "\n");
  const parts = normalized.split("\n\n");
  rest = parts.pop() || "";
  for (const part of parts) {
    for (const line of part.split("\n")) {
      if (!line.startsWith("data:")) continue;
      const payload = line.slice(5).trim();
      if (!payload || payload === "[DONE]") continue;
      try { events.push(JSON.parse(payload)); } catch { /* 半截 JSON 不该出现，出现就丢弃 */ }
    }
  }
  return { events, rest };
}

export function createAiClient({ uni, baseUrl, storageKey = "family_access_token" }) {
  /**
   * 发起一次流式对话。
   * - messages：[{role:'user'|'assistant', content}] 历史（含本轮提问）
   * - onTool(name)：模型开始查家庭数据（前端显示「正在查询…」）
   * - onDelta(text)：回答增量
   * 返回 Promise，结束时 resolve（error 事件会 reject）。
   */
  function streamChat({ messages, onTool = () => {}, onDelta = () => {} }) {
    return new Promise((resolve, reject) => {
      const task = uni.request({
        url: baseUrl + "/ai/chat",
        method: "POST",
        enableChunked: true,
        data: { messages },
        header: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + uni.getStorageSync(storageKey)
        },
        success: res => {
          // 降级路径：没走 chunked 的运行时把整段 SSE 文本一次性给到，这里统一解析
          if (res.statusCode !== 200) {
            reject(new Error(res.data?.msg || "AI 助手暂时不可用（" + res.statusCode + "）"));
            return;
          }
          finish(typeof res.data === "string" ? res.data : "");
        },
        fail: () => reject(new Error("AI 助手连接失败，请检查网络"))
      });

      let buffer = "", settled = false;

      function handleText(text) {
        buffer += text;
        const parsed = parseSseBuffer(buffer);
        buffer = parsed.rest;
        for (const event of parsed.events) {
          if (event.type === "delta" && event.text) onDelta(event.text);
          else if (event.type === "tool" && event.name) onTool(event.name);
          else if (event.type === "error") {
            settled = true;
            reject(new Error(event.message || "AI 助手出了点问题"));
          } else if (event.type === "done" && !settled) {
            settled = true;
            resolve();
          }
        }
      }

      function finish(fullText) {
        if (settled) return;
        if (fullText) handleText(fullText);
        // 全部事件处理完仍没收到 done（连接中断）：有正文就算成功，否则报错
        if (!settled) {
          settled = true;
          reject(new Error("AI 助手响应中断，请重试"));
        }
      }

      if (task && typeof task.onChunkReceived === "function") {
        const decoder = new TextDecoder("utf-8");
        task.onChunkReceived(chunk => handleText(decoder.decode(chunk.data, { stream: true })));
        // H5 与部分运行时会在流结束后触发 onHeadersReceived/complete；onChunkReceived 模式下
        // success 也可能先于最后分片到达——done 事件是唯一结束信号，success 里只兜底。
      }
    });
  }

  return { streamChat };
}
