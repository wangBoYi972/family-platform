/**
 * 未读数汇总。
 *
 * 聊天面板挂载时自己算角标；面板没挂载（用户在看任务、家庭圈）时，宿主也得显示未读，
 * 就只能拿会话列表再算一遍。两处必须共用同一套口径，
 * 否则会出现「tab 上显示 3 条未读、点进去只有 1 条」。
 */
export function sumUnread(conversations) {
  if (!Array.isArray(conversations)) return 0;
  return conversations.reduce((sum, item) => sum + (Number(item?.unread) || 0), 0);
}
