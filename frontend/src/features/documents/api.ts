import { checked, csrf } from '../../lib/http';
import type { Document } from '../../lib/api';
const path = (document: Document) => `/api/workspaces/${encodeURIComponent(document.workspaceId)}/documents/${encodeURIComponent(document.id)}`;
export async function renameDocument(document: Document, title: string): Promise<Document> {
  const token = await csrf();
  return (await checked(path(document), { method: 'PATCH', headers: { 'Content-Type': 'application/json', [token.headerName]: token.token }, body: JSON.stringify({ title, version: document.version }) })).json() as Promise<Document>;
}
export async function archiveDocument(document: Document): Promise<Document> {
  const token = await csrf();
  return (await checked(`${path(document)}/archive`, { method: 'POST', headers: { 'Content-Type': 'application/json', [token.headerName]: token.token }, body: JSON.stringify({ version: document.version }) })).json() as Promise<Document>;
}
