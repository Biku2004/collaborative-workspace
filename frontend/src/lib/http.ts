type Csrf = { headerName: string; token: string };
export class ApiError extends Error {
  constructor(readonly status: number, message: string, readonly code?: string) { super(message); }
}
export async function call(path: string, init?: RequestInit): Promise<Response> {
  try { return await fetch(path, { credentials: 'same-origin', ...init }); }
  catch { throw new ApiError(0, 'Cannot reach the workspace service. Check that the backend is running.'); }
}
export async function checked(path: string, init?: RequestInit): Promise<Response> {
  const response = await call(path, init);
  if (response.ok) return response;
  if (response.status === 401) throw new ApiError(401, 'Your session has ended. Please sign in again.');
  if (response.status === 403) throw new ApiError(403, 'You do not have permission to access this resource.');
  if (response.status === 404) throw new ApiError(404, 'This workspace is unavailable or you no longer have access.');
  let code: string | undefined;
  if (response.status === 409) {
    try { const body = await response.json(); if (typeof body.code === 'string' && /^[A-Z_]{1,64}$/.test(body.code)) code = body.code; } catch { }
  }
  const messages: Record<string, string> = {
    LAST_OWNER: 'A workspace must keep at least one owner. Add another owner before changing or removing this one.',
    DOCUMENT_CONFLICT: 'This document changed or was archived. Your proposed title is retained; load the latest version before trying again.',
    IDEMPOTENCY_CONFLICT: 'This retry no longer matches the original request. Check the existing result before submitting a new request.',
  };
  throw new ApiError(response.status, code && messages[code] ? messages[code] : 'The request could not be completed. Please try again.', code);
}
export async function csrf(): Promise<Csrf> { return (await checked('/api/csrf')).json() as Promise<Csrf>; }
