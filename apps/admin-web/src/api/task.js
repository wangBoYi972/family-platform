import { request } from './http';

/** 空值不进查询串：否则 "keyword=" 这类空参数会被后端当成一个真实的筛选条件传下去。 */
function query(params = {}) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params))
    if (value !== undefined && value !== null && value !== '') search.set(key, value);
  const text = search.toString();
  return text ? '?' + text : '';
}

export const listTasks = (params = {}) => request('/tasks' + query(params));
/** 看板指标走独立汇总接口，不能拿「当前这一页」的数据算。 */
export const taskStats = () => request('/tasks/stats');
export const createTask = data => request('/tasks', { method: 'POST', data });
export const updateTaskStatus = (id, status) => request('/tasks/' + id + '/status', { method: 'PATCH', data: { status } });
export const updateTask = (id, data) => request('/tasks/' + id, { method: 'PUT', data });
export const removeTask = id => request('/tasks/' + id, { method: 'DELETE' });
