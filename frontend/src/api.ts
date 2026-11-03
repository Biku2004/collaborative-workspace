export type Workspace = { id: string; name: string; role: 'OWNER' | 'EDITOR' | 'VIEWER'; createdAt: string };
export type Document = { id: string; workspaceId: string; title: string; createdAt: string; version: number; archived: boolean };
export type Page<T> = { items: T[]; nextCursor: string | null };
type Csrf = { headerName: string; token: string };
export type CurrentUser = { username: string; id?: string };

export class ApiError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

async function call(path: string, init?: RequestInit): Promise<Response> {
  let response: Response;
  try { response = await fetch(path, { credentials: 'same-origin', ...init }); }
  catch { throw new ApiError(0, 'Cannot reach the workspace service. Check that the backend is running.'); }
  return response;
}

async function checked(path: string, init?: RequestInit): Promise<Response> {
  const response = await call(path, init);
  if (!response.ok) {
    if (response.status === 401) throw new ApiError(401, 'Your session has ended. Please sign in again.');
    if (response.status === 403) throw new ApiError(403, 'You do not have permission to access this resource.');
    if (response.status === 404) throw new ApiError(404, 'This workspace is unavailable or you no longer have access.');
    throw new ApiError(response.status, 'The request could not be completed. Please try again.');
  }
  return response;
}

async function csrf(): Promise<Csrf> { return (await checked('/api/csrf')).json() as Promise<Csrf>; }
export async function currentUser(signal?: AbortSignal): Promise<CurrentUser> {
  return (await checked('/api/me', { signal })).json() as Promise<CurrentUser>;
}
export async function login(email: string, password: string): Promise<CurrentUser> {
  const token = await csrf();
  await call('/login', { method: 'POST', redirect: 'manual', headers: { 'Content-Type': 'application/x-www-form-urlencoded', [token.headerName]: token.token },
    body: new URLSearchParams({ username: email, password }) });
  try { return await currentUser(); }
  catch (error) { if (error instanceof ApiError && error.status === 401) throw new ApiError(401, 'Incorrect email or password.'); throw error; }
}
export async function logout(): Promise<void> {
  const token = await csrf();
  const response = await call('/logout', { method: 'POST', redirect: 'manual', headers: { [token.headerName]: token.token } });
  if (response.type !== 'opaqueredirect' && !response.ok && response.status !== 302) throw new ApiError(response.status, 'Could not sign out. Please try again.');
  try { await currentUser(); }
  catch (error) { if (error instanceof ApiError && error.status === 401) return; throw error; }
  throw new ApiError(0, 'Could not confirm sign-out. Please try again.');
}
export async function workspacePage(after?: string): Promise<Page<Workspace>> {
  const suffix = after ? `?after=${encodeURIComponent(after)}` : '';
  const response = await checked(`/api/workspaces${suffix}`);
  return { items: await response.json() as Workspace[], nextCursor: response.headers.get('X-Next-Cursor') };
}
export async function documentPage(workspaceId: string, cursor?: string): Promise<Page<Document>> {
  const suffix = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
  return (await checked(`/api/workspaces/${encodeURIComponent(workspaceId)}/documents${suffix}`)).json() as Promise<Page<Document>>;
}
