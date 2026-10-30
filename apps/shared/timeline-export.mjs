// 时光轴导出：把当前加载的时刻拼成一份单文件 HTML 纪念册。
//
// 图片以 data URL 内嵌，导出后不依赖网络和登录态——发给家人一个 .html 文件，
// 双击即开，浏览器里 Ctrl+P 就能存成 PDF。纯函数（buildTimelineHtml）拆出来
// 单测，平台差异（下载 vs 写文件）收在 exportTimelineHtml 里。

const MIME_BY_EXT = {
  jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png",
  gif: "image/gif", webp: "image/webp", heic: "image/heic"
};

export function escapeHtml(text) {
  return String(text ?? "")
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

function guessMime(path) {
  const ext = String(path).split("?")[0].split(".").pop().toLowerCase();
  return MIME_BY_EXT[ext] || "image/jpeg";
}

/**
 * 把展示用的图片 URL 转成 data URL：
 * - H5 的 blob: / http URL 走 fetch + FileReader；
 * - 小程序的本地临时路径走 FileSystemManager 读 base64。
 * 单张失败返回 null，由调用方决定占位还是跳过。
 */
export async function toDataUrl(url, { uni = globalThis.uni } = {}) {
  if (!url) return null;
  if (typeof fetch === "function" && /^(blob:|https?:)/.test(url) && typeof FileReader !== "undefined") {
    const blob = await (await fetch(url)).blob();
    return await new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(new Error("读取图片失败"));
      reader.readAsDataURL(blob);
    });
  }
  const fs = uni && uni.getFileSystemManager && uni.getFileSystemManager();
  if (fs && fs.readFile) {
    const base64 = await new Promise((resolve, reject) => {
      fs.readFile({
        filePath: url,
        encoding: "base64",
        success: res => resolve(res.data),
        fail: err => reject(new Error(err.errMsg || "读取图片失败"))
      });
    });
    return "data:" + guessMime(url) + ";base64," + base64;
  }
  return null;
}

/**
 * 生成自包含 HTML。groups 直接复用面板的分组计算结果：
 * [{ label, items: [{ dateLabel, source, title, note, images: [dataUrl] }] }]
 */
export function buildTimelineHtml({ title = "家庭时光轴", exportedAt = "", groups = [] } = {}) {
  const sections = groups.map(group => {
    const items = group.items.map(item => {
      const note = item.note ? `<p class="note">${escapeHtml(item.note)}</p>` : "";
      const cells = (item.images || []).map(src => src
        ? `<figure><img src="${src}" alt="" loading="lazy" /></figure>`
        : `<figure class="missing"><span>图片未能嵌入</span></figure>`).join("");
      return `<article class="entry">
  <header><span class="when">${escapeHtml(item.dateLabel)}</span><span class="from">${escapeHtml(item.source)}</span><strong class="title">${escapeHtml(item.title)}</strong></header>
  ${note}
  <div class="grid">${cells}</div>
</article>`;
    }).join("\n");
    return `<section class="group"><h2>${escapeHtml(group.label)}</h2>${items}</section>`;
  }).join("\n");

  const photoCount = groups.reduce((sum, g) => sum + g.items.reduce((n, i) => n + (i.images || []).filter(Boolean).length, 0), 0);
  const momentCount = groups.reduce((sum, g) => sum + g.items.length, 0);
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${escapeHtml(title)}</title>
<style>
  :root { color-scheme: light; --ink:#2c2620; --muted:#8a8178; --line:#e8e2da; --primary:#b4552d; --soft:#faf6f1; }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--soft); color: var(--ink); font: 15px/1.7 "PingFang SC", "Microsoft YaHei", sans-serif; }
  main { max-width: 860px; margin: 0 auto; padding: 48px 24px 80px; }
  header.cover { border-bottom: 2px solid var(--line); padding-bottom: 28px; margin-bottom: 36px; }
  h1 { margin: 0 0 10px; font-size: 30px; letter-spacing: -.02em; }
  .meta { color: var(--muted); font-size: 13px; }
  .meta b { color: var(--primary); font-weight: 700; }
  .group { margin-bottom: 40px; }
  h2 { font-size: 20px; margin: 0 0 18px; padding-left: 14px; border-left: 4px solid var(--primary); }
  .entry { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 18px 20px; margin-bottom: 16px; break-inside: avoid; }
  .entry header { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
  .when { background: var(--primary); color: #fff; border-radius: 8px; padding: 2px 9px; font-size: 12px; font-weight: 700; }
  .from { color: var(--muted); font-size: 12px; }
  .title { font-size: 15px; }
  .note { margin: 8px 0 0; color: var(--muted); font-size: 13px; }
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 12px; }
  figure { margin: 0; }
  img { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 10px; display: block; background: var(--soft); }
  figure.missing { aspect-ratio: 1; display: grid; place-items: center; border: 1px dashed var(--line); border-radius: 10px; color: var(--muted); font-size: 12px; }
  footer { margin-top: 56px; text-align: center; color: var(--muted); font-size: 12px; border-top: 1px solid var(--line); padding-top: 20px; }
  @media print { body { background: #fff; } .entry { border-color: #ddd; } }
  @media (max-width: 560px) { .grid { grid-template-columns: repeat(2, 1fr); } h1 { font-size: 24px; } }
</style>
</head>
<body>
<main>
  <header class="cover">
    <h1>${escapeHtml(title)}</h1>
    <p class="meta">共 <b>${momentCount}</b> 个时刻 · <b>${photoCount}</b> 张照片${exportedAt ? " · 导出于 " + escapeHtml(exportedAt) : ""}</p>
  </header>
  ${sections || `<p class="meta">暂无内容。</p>`}
  <footer>由「把时间，留给家人」导出 · 图片已内嵌，无需联网即可查看</footer>
</main>
</body>
</html>`;
}

/**
 * 平台落盘：H5 触发浏览器另存；小程序写入用户目录后尝试转发，不支持转发时提示路径。
 * 返回一句话结果，供调用方 toast。
 */
export async function exportTimelineHtml(html, filename, { uni = globalThis.uni } = {}) {
  // #ifdef H5
  const blob = new Blob([html], { type: "text/html;charset=utf-8" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
  return "已开始下载 " + filename;
  // #endif
  // #ifndef H5
  const fs = uni && uni.getFileSystemManager && uni.getFileSystemManager();
  if (!fs) throw new Error("当前环境不支持导出文件");
  const root = (uni.env && uni.env.USER_DATA_PATH) || "";
  const filePath = root + "/" + filename;
  await new Promise((resolve, reject) => {
    fs.writeFile({ filePath, data: html, encoding: "utf8", success: resolve, fail: err => reject(new Error(err.errMsg || "写入文件失败")) });
  });
  const w = typeof wx !== "undefined" ? wx : uni;
  if (w && typeof w.shareFileMessage === "function") {
    await new Promise((resolve, reject) => {
      w.shareFileMessage({ filePath, fileName: filename, success: resolve, fail: err => reject(new Error(err.errMsg || "分享取消")) });
    });
    return "纪念册已生成，可转发给家人";
  }
  uni.showModal({ title: "纪念册已生成", content: "文件已保存，可在聊天中转发：" + filePath, showCancel: false });
  return "纪念册已保存";
  // #endif
}
