/**
 * 增量刷新的主题匹配。
 *
 * 后端 refresh 帧带事件类型（如 "task.created"），主题取点号前的前缀（task）。
 * 宿主给每个面板声明它关心的主题，收到刷新时按此挑选面板，任务变了不必连相册一起刷。
 *
 * 两条兜底原则：
 * 1. 帧没带 event（老版本后端 / Redis 旧式广播）→ 全量刷新；
 * 2. 帧带的类型前端不认识（新版本后端加了新事件）→ 全量刷新。
 * 宁可多刷，不能因为映射表没跟上就漏掉变化。
 */

/**
 * @param {Set<string>|undefined} events 闸门收集的事件类型集合
 * @param {Set<string>} knownTopics 前端认识的主题全集
 * @returns {{all: boolean, topics: Set<string>}} all=true 表示应全量刷新
 */
export function refreshPlan(events, knownTopics) {
  const topics = events instanceof Set ? events : new Set();
  const names = new Set(
    [...topics].map(event => String(event).split(".")[0]).filter(Boolean)
  );
  const all = !names.size || [...names].some(name => !knownTopics.has(name));
  return { all, topics: names };
}

/** 判断一组面板主题是否命中刷新计划。 */
export function hitsTopic(plan, panelTopics) {
  return plan.all || panelTopics.some(topic => plan.topics.has(topic));
}
