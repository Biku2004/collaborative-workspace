import type { CurrentUser, Document, Workspace } from './api';

/** Convenience metadata only. This is neither a session nor evidence of current access. */
export type OfflineContext = { user: CurrentUser & { id: string }; workspace: Workspace; document: Document };
const KEY = 'workspace-last-offline-document-v1';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function readOfflineContext(): OfflineContext | undefined {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    if (!value || typeof value !== 'object') return undefined;
    const context = value as OfflineContext;
    if (!context.user || typeof context.user.username !== 'string' || typeof context.user.id !== 'string' || !UUID.test(context.user.id) ||
        !context.workspace || typeof context.workspace.id !== 'string' || !UUID.test(context.workspace.id) ||
        typeof context.workspace.name !== 'string' || !['OWNER', 'EDITOR', 'VIEWER'].includes(context.workspace.role) ||
        !context.document || typeof context.document.id !== 'string' || !UUID.test(context.document.id) ||
        context.document.workspaceId !== context.workspace.id || typeof context.document.title !== 'string') return undefined;
    // Metadata written before format labels existed can only refer to the old plain-text format.
    if (context.document.collaborationFormat === undefined) context.document.collaborationFormat = 'PLAIN_TEXT_V1';
    if (context.document.collaborationFormat !== 'PLAIN_TEXT_V1' && context.document.collaborationFormat !== 'RICH_TEXT_V1') return undefined;
    return context;
  } catch { return undefined; }
}

export function saveOfflineContext(context: OfflineContext): void {
  try { localStorage.setItem(KEY, JSON.stringify(context)); } catch { /* Optional metadata, not the draft itself. */ }
}

export function clearOfflineContext(): void {
  try { localStorage.removeItem(KEY); } catch { /* Storage can be disabled by the browser. */ }
}
