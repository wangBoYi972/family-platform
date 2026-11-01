import { request } from './http';
// 分页与关键字都交给服务端：不传时后端等价于原来的「取前 500 条」，
// 第 501 条以后的记录在界面上等于不存在，关键字也只能搜到已加载的那部分。
function query({ page, size, keyword } = {}) {
  const params = [];
  if (page) params.push('page=' + page);
  if (size) params.push('size=' + size);
  if (keyword) params.push('keyword=' + encodeURIComponent(keyword));
  return params.length ? '?' + params.join('&') : '';
}
export const listEntries = (kind, options) => request('/' + kind + query(options));
export const createEntry = (kind, body) => request('/' + kind, { method: 'POST', data: body });
export const updateEntry = (kind, id, body) => request('/' + kind + '/' + id, { method: 'PUT', data: body });
export const deleteEntry = (kind, id) => request('/' + kind + '/' + id, { method: 'DELETE' });
export const listArchives = options => listEntries('archives', options);
export const archiveDetail = id => request('/archives/' + id);
export const archiveAudit = id => request('/archives/' + id + '/audit');
