/**
 * 共享面板 → 宿主 tab 的导航契约。
 *
 * 共享面板（GlobalSearch、AnniversaryPanel）只认识数据源 key，不认识任何一端的 tab 名称，
 * 否则把面板搬到另一个宿主时就会跳进不存在的 tab。key 与两端的目标结构集中放在这里，
 * 好处是「新增一个搜索数据源却忘了在某一端配跳转」能在单测里直接暴露，而不是等用户点了没反应。
 */

/** 共享面板可能上报的全部 key，必须与 GlobalSearch 的 sources 一一对应。 */
export const NAV_KEYS = ["tasks", "calendar", "dynamics", "albums", "notices", "archives", "members", "files"];

/** 家庭端：主 tab + 子标签（日历 / 家庭圈 / 我的家）。 */
export const FAMILY_NAV_TARGETS = {
  tasks: { tab: "任务" },
  calendar: { tab: "日历", calTab: "日程" },
  dynamics: { tab: "家庭圈", circleTab: "动态" },
  albums: { tab: "家庭圈", circleTab: "相册" },
  notices: { tab: "我的家", homeTab: "公告" },
  archives: { tab: "我的家", homeTab: "档案" },
  members: { tab: "我的家", homeTab: "成员" },
  files: { tab: "我的家", homeTab: "文件" }
};

/** 移动端：五个分组 tab，子标签统一挂在 `sub` 上。 */
export const MOBILE_NAV_TARGETS = {
  tasks: { tab: "任务" },
  calendar: { tab: "日程", sub: "家庭日历" },
  dynamics: { tab: "家庭圈", sub: "动态" },
  albums: { tab: "家庭圈", sub: "相册" },
  notices: { tab: "我的家", sub: "公告" },
  archives: { tab: "我的家", sub: "档案" },
  members: { tab: "我的家", sub: "成员" },
  files: { tab: "我的家", sub: "文件" }
};

/**
 * 把面板上报的 `{ key }` 解析成宿主的跳转目标。
 * 未知 key 一律回落到 `fallback`（各端自己给，通常是第一个 tab），
 * 不能让「多了一个搜索数据源」变成「点了没反应」。
 */
export function resolveNavTarget(map, target, fallback) {
  const key = target && typeof target === "object" ? target.key : target;
  return (key && map[key]) || fallback;
}

/** 返回该端没配跳转的 key，供单测与自检使用。 */
export function missingNavTargets(map, keys = NAV_KEYS) {
  return keys.filter(key => !map[key]);
}
