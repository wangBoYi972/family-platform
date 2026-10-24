import { request } from './http';
export const listTasks = () => request('/tasks');
export const createTask = data => request('/tasks', { method: 'POST', data });
export const updateTaskStatus = (id, status) => request('/tasks/' + id + '/status', { method: 'PATCH', data: { status } });
export const updateTask = (id, data) => request('/tasks/' + id, { method: 'PUT', data });
export const removeTask = id => request('/tasks/' + id, { method: 'DELETE' });
