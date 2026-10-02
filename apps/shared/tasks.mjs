export const taskStatusLabel = { TODO: "待开始", IN_PROGRESS: "进行中", COMPLETED: "已完成" };

// 使用本地日历日期，避免 UTC 日期在清晨造成逾期判断偏差。
export function localDate(date = new Date()) {
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, "0"), String(date.getDate()).padStart(2, "0")].join("-");
}

export function nextTaskStatus(status) {
  return { TODO: "IN_PROGRESS", IN_PROGRESS: "COMPLETED" }[status] || null;
}

export function canManageTask(task, session) {
  if (!session?.userId) return false;
  return session.role === "ADMIN" || String(task.creatorId) === String(session.userId) || String(task.assigneeId) === String(session.userId);
}

export function filterTasks(tasks, { keyword = "", status = "ALL", today = localDate() } = {}) {
  const query = keyword.trim().toLocaleLowerCase();
  return tasks.filter(task => {
    const matchesText = (task.title + " " + (task.assigneeName || "")).toLocaleLowerCase().includes(query);
    const matchesStatus = status === "ALL" || task.status === status ||
      (status === "OVERDUE" && task.status !== "COMPLETED" && task.deadline && task.deadline < today);
    return matchesText && matchesStatus;
  });
}
