import { request, clearSession } from './http';
export async function login(input) {
  const data = await request('/auth/password-login', { method: 'POST', data: { ...input, client: 'admin' }, authenticated: false });
  localStorage.setItem('family_access_token', data.token);
}
export async function logout() {
  await request('/auth/logout', { method: 'POST' });
  clearSession();
}
export const getProfile = () => request('/users/me');
export const getSession = () => request('/auth/session');
export async function changePassword(body) {
  await request('/auth/password', { method: 'PUT', body });
  clearSession();
}
