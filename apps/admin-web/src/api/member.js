import { request } from './http';
export const listMembers = () => request('/users/members');
// keyword 交给服务端在 SQL 里过滤：账号超过一屏后本地过滤会漏掉没加载的那部分
export const listWhitelist = keyword => request('/users/whitelist' + (keyword ? '?keyword=' + encodeURIComponent(keyword) : ''));
export const addMember = data => request('/users/whitelist', { method: 'POST', data });
export const disableMember = id => request('/users/whitelist/' + id, { method: 'DELETE' });
export const setMemberPassword = (id, password) => request('/users/whitelist/' + id + '/password', { method: 'PUT', data: { password } });
