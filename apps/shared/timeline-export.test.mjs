import { test } from "node:test";
import assert from "node:assert/strict";
import { buildTimelineHtml, escapeHtml, toDataUrl } from "./timeline-export.mjs";

test("escapeHtml 覆盖标题、正文里会破坏结构的字符", () => {
  assert.equal(escapeHtml(`<img src=x onerror="a&b">'`), "&lt;img src=x onerror=&quot;a&amp;b&quot;&gt;&#39;");
  assert.equal(escapeHtml(null), "");
  assert.equal(escapeHtml(123), "123");
});

test("buildTimelineHtml 分组渲染并统计时刻与照片数", () => {
  const html = buildTimelineHtml({
    title: "王家时光轴",
    exportedAt: "2026-10-30",
    groups: [
      {
        label: "2026 年 10 月",
        items: [
          { dateLabel: "10/1", source: "相册", title: "国庆出游", note: "人很多", images: ["data:image/png;base64,AAA", null] },
          { dateLabel: "10/2", source: "动态", title: "晚饭", images: ["data:image/jpeg;base64,BBB"] }
        ]
      },
      { label: "2026 年 9 月", items: [{ dateLabel: "9/8", source: "相册", title: "开学", images: [] }] }
    ]
  });
  assert.ok(html.includes("<title>王家时光轴</title>"));
  assert.ok(html.includes("2026 年 10 月"));
  assert.ok(html.includes("国庆出游"));
  assert.ok(html.includes("人很多"));
  // 嵌入的图片以 data URL 原样内嵌
  assert.ok(html.includes('src="data:image/png;base64,AAA"'));
  // 失败的图片给占位，不留空 <img>
  assert.ok(html.includes("图片未能嵌入"));
  // 统计：3 个时刻、2 张成功的照片
  assert.ok(html.includes("<b>3</b> 个时刻"));
  assert.ok(html.includes("<b>2</b> 张照片"));
  // 分组顺序保持传入顺序（面板已排好序）
  assert.ok(html.indexOf("2026 年 10 月") < html.indexOf("2026 年 9 月"));
  // 自包含：无外链脚本与样式
  assert.ok(!/src="http/.test(html));
  assert.ok(!/<script/.test(html));
});

test("buildTimelineHtml 没有分组时给出空态而不是残缺文档", () => {
  const html = buildTimelineHtml({});
  assert.ok(html.startsWith("<!DOCTYPE html>"));
  assert.ok(html.includes("暂无内容"));
});

test("toDataUrl 无可用通道时返回 null 而不是抛错", async () => {
  // node 里既没有 fetch/FileReader 组合也没有 uni FileSystemManager
  assert.equal(await toDataUrl("wxfile://tmp/abc.jpg", { uni: {} }), null);
  assert.equal(await toDataUrl("", { uni: undefined }), null);
});

test("toDataUrl 走小程序文件系统读 base64 并按扩展名猜 MIME", async () => {
  const calls = [];
  const uni = {
    getFileSystemManager: () => ({
      readFile({ filePath, encoding, success }) {
        calls.push({ filePath, encoding });
        success({ data: "QUJD" });
      }
    })
  };
  const url = await toDataUrl("wxfile://tmp/IMG_0001.PNG", { uni });
  assert.deepEqual(calls, [{ filePath: "wxfile://tmp/IMG_0001.PNG", encoding: "base64" }]);
  assert.equal(url, "data:image/png;base64,QUJD");
  // 未知扩展名回落 jpeg
  const fallback = await toDataUrl("wxfile://tmp/photo.bin", { uni });
  assert.equal(fallback, "data:image/jpeg;base64,QUJD");
});
