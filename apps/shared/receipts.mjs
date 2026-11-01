/**
 * 已读回执的本地口径。
 *
 * 后端只在水位真的前进时推一帧 `{type:"read", conversationId, userId, lastMessageId}`，
 * 但网络重排、断线重连后重放都可能让旧帧后到，所以合并必须单调：水位只进不退。
 */

/**
 * 把一帧回执并进本地列表。
 *
 * 返回新数组（没有变化时返回原数组，调用方可以靠引用比较省掉一次重渲染）。
 */
export function mergeReceipt(list, frame) {
  if (!frame || frame.type !== "read") return list;
  const userId = String(frame.userId ?? "");
  const lastMessageId = Number(frame.lastMessageId) || 0;
  if (!userId || lastMessageId <= 0) return list;
  const current = list.find(item => String(item.userId) === userId);
  if (current && (Number(current.lastMessageId) || 0) >= lastMessageId) return list;
  return [...list.filter(item => String(item.userId) !== userId), { userId: frame.userId, lastMessageId }];
}

/**
 * 「已读：谁」的口径。
 *
 * @param list 回执列表
 * @param selfId 当前登录用户，不能出现在已读名单里（自己总是读过自己的消息）
 * @param upTo 我发出的最后一条消息 id；水位达到它才算真的读到
 */
export function readMemberIds(list, selfId, upTo) {
  const target = Number(upTo) || 0;
  if (!target) return [];
  const self = String(selfId ?? "");
  return list
    .filter(item => String(item.userId) !== self && (Number(item.lastMessageId) || 0) >= target)
    .map(item => item.userId);
}
