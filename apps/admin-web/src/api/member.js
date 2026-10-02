import { request } from './http';
export const listMembers = () => request('/users/members');
export const listWhitelist = () => request('/users/whitelist');
export const addMember = data => request('/users/whitelist', { method: 'POST', data });
export const disableMember = id => request('/users/whitelist/' + id, { method: 'DELETE' });
export const setMemberPassword = (id, password) => request('/users/whitelist/' + id + '/password', { method: 'PUT', data: { password } });
