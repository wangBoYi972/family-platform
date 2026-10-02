// 兼容统一请求接口，认证事件沿用现有账号管理模块。
import { request as send, clearSession } from './request';
export { clearSession };
export const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api';
// 统一转发 data，保证登录、任务和成员接口的 JSON 请求体不会被丢弃。
export const request = (path, options = {}) => send(path, {
  method: options.method || 'GET',
  data: options.data ?? options.body,
  authenticated: options.authenticated ?? !options.anonymous
});
