import { request } from './http';
export const listEntries = kind => request('/' + kind);
export const createEntry = (kind, body) => request('/' + kind, { method: 'POST', data: body });
export const updateEntry = (kind, id, body) => request('/' + kind + '/' + id, { method: 'PUT', data: body });
export const deleteEntry = (kind, id) => request('/' + kind + '/' + id, { method: 'DELETE' });
export const listArchives = () => listEntries('archives');
export const archiveDetail = id => request('/archives/' + id);
export const archiveAudit = id => request('/archives/' + id + '/audit');
