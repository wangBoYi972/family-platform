import { request } from './http';

/** 读取 AI 助手配置。服务端只回尾号，整串 key 不出服务端。 */
export const getAiSettings = () => request('/ai/settings');

/**
 * 保存 AI 助手配置。
 * - apiKey 为空 → 只改开关，保留已保存的 key（服务端按「未提供」处理）
 * - apiKey 非空 → 覆盖 key
 */
export const saveAiSettings = ({ enabled, apiKey }) =>
  request('/ai/settings', { method: 'PUT', data: { enabled, apiKey: apiKey || null } });
