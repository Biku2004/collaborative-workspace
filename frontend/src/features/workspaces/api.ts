import { checked, csrf } from '../../lib/http';
import type { Page, Workspace } from '../../lib/api';
export type WorkspaceRole = Workspace['role'];
export type Member = { userId: string; email: string; role: WorkspaceRole };
const path = (workspace: string) => `/api/workspaces/${encodeURIComponent(workspace)}`;
export async function createWorkspace(name: string, requestKey: string): Promise<Workspace> {
  const token = await csrf();
  return (await checked('/api/workspaces', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestKey, [token.headerName]: token.token }, body: JSON.stringify({ name }) })).json() as Promise<Workspace>;
}
export async function workspaceDetails(workspace: string, signal?: AbortSignal): Promise<Workspace> {
  return (await checked(path(workspace), { signal, cache: 'no-store' })).json() as Promise<Workspace>;
}
export async function memberPage(workspace: string, after?: string, signal?: AbortSignal): Promise<Page<Member>> {
  const response = await checked(`${path(workspace)}/members${after ? `?after=${encodeURIComponent(after)}` : ''}`, { signal, cache: 'no-store' });
  return { items: await response.json() as Member[], nextCursor: response.headers.get('X-Next-Cursor') };
}
export async function setMemberRole(workspace: string, email: string, role: WorkspaceRole): Promise<Member> {
  const token = await csrf();
  return (await checked(`${path(workspace)}/members`, { method: 'POST', headers: { 'Content-Type': 'application/json', [token.headerName]: token.token }, body: JSON.stringify({ email, role }) })).json() as Promise<Member>;
}
export async function removeMember(workspace: string, user: string): Promise<void> {
  const token = await csrf();
  await checked(`${path(workspace)}/members/${encodeURIComponent(user)}`, { method: 'DELETE', headers: { [token.headerName]: token.token } });
}
