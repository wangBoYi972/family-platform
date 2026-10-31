// 列表分页的纯逻辑：四端共用，可在 node 下直接单测（不依赖 vue / uni）。
//
// 后端列表接口统一接受 page / size；不传时保持「取前 N 条」的历史行为。
// 接口目前不返回 total，所以「是否还有下一页」用「本页返回条数 >= size」判断：
// 最坏情况是多请求一次空页，不会漏数据。

export const PAGE_SIZE = 20;

/** 拼接分页查询串；path 已带查询参数时用 & 连接。 */
export function pageQuery(path, page, size = PAGE_SIZE) {
  const query = `page=${page}&size=${size}`;
  return String(path).includes("?") ? `${path}&${query}` : `${path}?${query}`;
}

/** 本页条数达到 size 说明可能还有下一页。 */
export function hasMore(returnedCount, size = PAGE_SIZE) {
  return Number(returnedCount) >= Number(size);
}

/**
 * 合并新一页并去重。
 *
 * 列表按创建时间倒序、且新记录从头部插入，翻页期间若有新增，相邻两页会出现
 * 同一条记录（整体下移一位）。按主键去重可避免界面上出现重复项。
 */
export function mergePage(existing, incoming, keyOf = item => item?.id) {
  const merged = [];
  const seen = new Set();
  for (const item of [...(existing || []), ...(incoming || [])]) {
    const key = keyOf(item);
    const usable = key !== undefined && key !== null && key !== "";
    if (!usable) {
      merged.push(item);
      continue;
    }
    if (seen.has(key)) continue;
    seen.add(key);
    merged.push(item);
  }
  return merged;
}
