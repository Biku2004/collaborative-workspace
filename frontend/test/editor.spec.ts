import { expect, test, type BrowserContext, type Page } from '@playwright/test';

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

async function signIn(page: Page, email: string, workspace: string) {
  await page.goto('/');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('browser-test-password');
  await page.getByRole('button', { name: /Continue/ }).click();
  await page.getByRole('button', { name: new RegExp(workspace) }).click();
  await page.getByRole('button', { name: /Live notes/ }).click();
  const editor = page.getByLabel('Document text');
  await expect(editor).toBeVisible();
  await expect(editor).not.toHaveAttribute('readonly');
  return editor;
}

test('two independent browser contexts synchronize concurrent inserts and deletes', async ({ browser, baseURL }) => {
  test.skip(process.env.WORKSPACE_E2E_EDITING !== 'true', 'Requires the real local Yjs engine');
  const alice = required('WORKSPACE_E2E_ALICE'), bob = required('WORKSPACE_E2E_BOB');
  const bobId = required('WORKSPACE_E2E_BOB_ID'), workspaceId = required('WORKSPACE_E2E_SHARED_WORKSPACE');
  const workspace = required('WORKSPACE_E2E_SHARED_NAME');
  const first: BrowserContext = await browser.newContext({ baseURL });
  const second: BrowserContext = await browser.newContext({ baseURL });
  try {
    // Hold only SUBMIT_UPDATE frames so both isolated clients make edits before either reaches the server.
    const installBarrier = () => {
      const original = WebSocket.prototype.send;
      let paused = true;
      let updateSocket: WebSocket | null = null;
      const queue: Array<{ id: string; send: () => void }> = [];
      const hooks = window as unknown as Window & { pauseUpdates: () => void; flushUpdates: () => void; closeUpdateSocket: () => void; queuedIds: () => string[] };
      hooks.pauseUpdates = () => { paused = true; };
      hooks.flushUpdates = () => { paused = false; for (const item of queue.splice(0)) item.send(); };
      hooks.queuedIds = () => queue.map(item => item.id);
      hooks.closeUpdateSocket = () => updateSocket?.close();
      WebSocket.prototype.send = function (data: string | ArrayBufferLike | Blob | ArrayBufferView) {
        if (typeof data === 'string') {
          try {
            const message = JSON.parse(data);
            if (message.type === 'SUBMIT_UPDATE') {
              updateSocket = this;
              if (paused) { queue.push({ id: message.updateId as string, send: () => original.call(this, data) }); return; }
            }
          } catch { /* Non-JSON frames belong to other browser connections. */ }
        }
        original.call(this, data);
      };
    };
    await first.addInitScript(installBarrier); await second.addInitScript(installBarrier);
    const pageA = await first.newPage(), pageB = await second.newPage();
    const errors: string[] = [];
    pageA.on('pageerror', error => errors.push(error.message));
    pageB.on('pageerror', error => errors.push(error.message));
    const [a, b] = await Promise.all([signIn(pageA, alice, workspace), signIn(pageB, bob, workspace)]);
    await Promise.all([a.fill('Alpha'), b.fill('Beta')]);
    await expect(a).toHaveValue('Alpha'); await expect(b).toHaveValue('Beta');
    await Promise.all([pageA.evaluate(() => (window as unknown as Window & { flushUpdates: () => void }).flushUpdates()),
                       pageB.evaluate(() => (window as unknown as Window & { flushUpdates: () => void }).flushUpdates())]);
    await expect.poll(async () => {
      const left = await a.inputValue(), right = await b.inputValue();
      return left === right && left.includes('Alpha') && left.includes('Beta');
    }, { timeout: 15000 }).toBe(true);
    const beforeDelete = await a.inputValue();
    await Promise.all([pageA.evaluate(() => (window as unknown as Window & { pauseUpdates: () => void }).pauseUpdates()),
                       pageB.evaluate(() => (window as unknown as Window & { pauseUpdates: () => void }).pauseUpdates())]);
    await Promise.all([a.fill(beforeDelete.replace('Alpha', '')), b.fill(beforeDelete + '!')]);
    await Promise.all([pageA.evaluate(() => (window as unknown as Window & { flushUpdates: () => void }).flushUpdates()),
                       pageB.evaluate(() => (window as unknown as Window & { flushUpdates: () => void }).flushUpdates())]);
    await expect.poll(async () => (await a.inputValue()) === (await b.inputValue()) && (await b.inputValue()) === 'Beta!', { timeout: 15000 }).toBe(true);
    await expect(pageA.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await expect(pageB.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });

    // The owner removes Bob while both pages remain open; later text must not reach him.
    const csrf = await (await first.request.get('/api/csrf')).json() as { token: string; headerName: string };
    const removed = await first.request.delete(`/api/workspaces/${workspaceId}/members/${bobId}`, { headers: { [csrf.headerName]: csrf.token } });
    expect(removed.status()).toBe(204);
    await expect(pageB.getByRole('alert')).toContainText(/draft|access|connection/i, { timeout: 10000 });
    await expect(b).toHaveAttribute('readonly');
    const revokedText = await b.inputValue();
    await a.fill((await a.inputValue()) + ' after removal');
    await expect(pageA.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await pageB.waitForTimeout(500);
    expect(await b.inputValue()).toBe(revokedText);
    // A disconnected tab retains unsent text and warns before in-app navigation.
    await pageA.evaluate(() => (window as unknown as Window & { pauseUpdates: () => void }).pauseUpdates());
    const draft = (await a.inputValue()) + ' unsaved draft';
    await a.fill(draft);
    await expect(pageA.locator('.editor-status')).toHaveText('Saving…');
    await first.setOffline(true);
    await pageA.evaluate(() => (window as unknown as Window & { closeUpdateSocket: () => void }).closeUpdateSocket());
    await expect(pageA.locator('.editor-status')).toContainText(/Offline|Saved locally/);
    await expect(a).not.toHaveAttribute('readonly');
    await expect(a).toHaveValue(draft);
    await a.fill(draft + ' offline');
    await expect(pageA.locator('.editor-status')).toHaveText('Saved locally; pending server confirmation');
    const beforeReload = await pageA.evaluate(async () => {
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open('workspace-offline-v1');
        request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
      });
      const values = await new Promise<{ pending: { id: string }[] }[]>((resolve, reject) => {
        const request = db.transaction('documents').objectStore('documents').getAll();
        request.onsuccess = () => resolve(request.result as { pending: { id: string }[] }[]); request.onerror = () => reject(request.error);
      });
      db.close(); return values.flatMap(value => value.pending.map(item => item.id));
    });
    expect(beforeReload).toHaveLength(2);
    pageA.once('dialog', dialog => void dialog.dismiss());
    await pageA.getByRole('button', { name: /Back to documents/ }).click();
    await expect(a).toBeVisible();
    await first.setOffline(false);
    pageA.once('dialog', dialog => void dialog.accept());
    await pageA.reload();
    await pageA.getByRole('button', { name: new RegExp(workspace) }).click();
    await pageA.getByRole('button', { name: /Live notes/ }).click();
    await expect(a).toHaveValue(draft + ' offline');
    await expect.poll(() => pageA.evaluate(() => (window as unknown as Window & { queuedIds: () => string[] }).queuedIds())).toEqual([beforeReload[0]]);
    await pageA.evaluate(() => (window as unknown as Window & { flushUpdates: () => void }).flushUpdates());
    await expect(pageA.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    expect(errors).toEqual([]);
  } finally { await first.close(); await second.close(); }
});

