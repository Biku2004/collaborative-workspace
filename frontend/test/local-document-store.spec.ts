import { expect, test } from '@playwright/test';

test('local document writes are ordered, scoped, and protected from a second writer', async ({ page }) => {
  await page.goto('/');
  const result = await page.evaluate(async () => {
    const path = '/src/localDocumentStore.ts';
    const { LocalDocumentStore } = await import(/* @vite-ignore */ path) as typeof import('../src/localDocumentStore');
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
    await first.close();
    const reopened = await LocalDocumentStore.open(user, workspace, document, 0);
    const restored = await reopened.load();
    await reopened.close();
    return { competingBlocked, otherEmpty: otherData === undefined, captured: Array.from(captured?.state ?? []), state: Array.from(restored?.state ?? []), pending: restored?.pending };
  });
  expect(result).toEqual({ competingBlocked: true, otherEmpty: true, captured: [1], state: [2],
    pending: [{ id: '40000000-0000-4000-8000-000000000001', payload: 'Ag==' }] });
});
