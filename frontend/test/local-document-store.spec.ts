import { expect, test } from '@playwright/test';

test('local document writes are ordered, scoped, and protected from a second writer', async ({ page }) => {
  await page.goto('/');
  const result = await page.evaluate(async () => {
    const path = '/src/features/offline/localDocumentStore.ts';
    const { LocalDocumentStore } = await import(/* @vite-ignore */ path) as typeof import('../src/features/offline/localDocumentStore');
    const user = '10000000-0000-4000-8000-000000000001';
    const other = '10000000-0000-4000-8000-000000000002';
    const workspace = '20000000-0000-4000-8000-000000000001';
    const document = '30000000-0000-4000-8000-000000000001';
    const id = '40000000-0000-4000-8000-000000000001';
    const first = await LocalDocumentStore.open(user, workspace, document, 0);
    const input = { version: 1 as const, state: new Uint8Array([1]), pending: [{ id, payload: 'AQ==' }] };
    const write1 = first.save(input);
    input.state[0] = 9;
    await write1;
    const captured = await first.load();
    const write2 = first.save({ version: 1, state: new Uint8Array([2]), pending: [{ id, payload: 'Ag==' }] });
    await write2;
    let competingBlocked = false;
    try { await LocalDocumentStore.open(user, workspace, document, 0); } catch { competingBlocked = true; }
    const secondUser = await LocalDocumentStore.open(other, workspace, document, 0);
    const otherData = await secondUser.load();
    await secondUser.close();
    const rich = await LocalDocumentStore.open(user, workspace, document, 0, 'RICH_TEXT_V1');
    const richData = await rich.load();
    await rich.close();
    await first.close();
    const reopened = await LocalDocumentStore.open(user, workspace, document, 0);
    const restored = await reopened.load();
    await reopened.close();
    return { competingBlocked, otherEmpty: otherData === undefined, richEmpty: richData === undefined, captured: Array.from(captured?.state ?? []), state: Array.from(restored?.state ?? []), pending: restored?.pending };
  });
  expect(result).toEqual({ competingBlocked: true, otherEmpty: true, richEmpty: true, captured: [1], state: [2],
    pending: [{ id: '40000000-0000-4000-8000-000000000001', payload: 'Ag==' }] });
});

test('legacy local metadata defaults to plain text and unknown formats are rejected', async ({ page }) => {
  await page.goto('/');
  const result = await page.evaluate(async () => {
    const path = '/src/features/offline/offlineContext.ts';
    const { readOfflineContext } = await import(/* @vite-ignore */ path) as typeof import('../src/features/offline/offlineContext');
    const key = 'workspace-last-offline-document-v1';
    const workspaceId = '20000000-0000-4000-8000-000000000001';
    const context = {
      user: { id: '10000000-0000-4000-8000-000000000001', username: 'user@example.com' },
      workspace: { id: workspaceId, name: 'Team', role: 'EDITOR', createdAt: '2026-01-01T00:00:00Z' },
      document: { id: '30000000-0000-4000-8000-000000000001', workspaceId, title: 'Notes', createdAt: '2026-01-01T00:00:00Z', version: 0, archived: false },
    };
    localStorage.setItem(key, JSON.stringify(context));
    const legacy = readOfflineContext()?.document.collaborationFormat;
    localStorage.setItem(key, JSON.stringify({ ...context, document: { ...context.document, collaborationFormat: 'FUTURE_SCHEMA' } }));
    const unknown = readOfflineContext();
    localStorage.removeItem(key);
    return { legacy, rejected: unknown === undefined };
  });
  expect(result).toEqual({ legacy: 'PLAIN_TEXT_V1', rejected: true });
});
