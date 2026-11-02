import { expect, test } from '@playwright/test';

test('production shell restores offline edits and retries their stable IDs after authentication', async ({ browser, baseURL }) => {
  const email = process.env.WORKSPACE_E2E_ALICE!;
  const workspace = process.env.WORKSPACE_E2E_SHARED_NAME!;
  const context = await browser.newContext({ baseURL });
  try {
    await context.addInitScript(() => {
      const original = WebSocket.prototype.send;
      let socket: WebSocket | undefined;
      const ids: string[] = [];
      let drop = false, dropped = false;
      const hooks = window as unknown as Window & { closeDocumentSocket: () => void; sentUpdateIds: () => string[]; dropNextSaved: () => void; savedWasDropped: () => boolean };
      hooks.closeDocumentSocket = () => socket?.close();
      hooks.sentUpdateIds = () => ids;
      hooks.dropNextSaved = () => { drop = true; dropped = false; };
      hooks.savedWasDropped = () => dropped;
      const onmessage = Object.getOwnPropertyDescriptor(WebSocket.prototype, 'onmessage')!;
      Object.defineProperty(WebSocket.prototype, 'onmessage', {
        configurable: true, enumerable: onmessage.enumerable, get: onmessage.get,
        set(this: WebSocket, listener: ((event: MessageEvent) => void) | null) {
          onmessage.set!.call(this, listener && function (this: WebSocket, event: MessageEvent) {
            try {
              const message = JSON.parse(String(event.data)) as { type?: string };
              if (drop && message.type === 'SAVED') { drop = false; dropped = true; return; }
            } catch { /* Other frames are not document messages. */ }
            listener.call(this, event);
          });
        },
      });
      WebSocket.prototype.send = function (data: string | ArrayBufferLike | Blob | ArrayBufferView) {
        if (this.url.includes('/socket') && typeof data === 'string') {
          socket = this;
          const message = JSON.parse(data) as { type: string; updateId?: string };
          if (message.type === 'SUBMIT_UPDATE' && message.updateId) ids.push(message.updateId);
        }
        original.call(this, data);
      };
    });
    const page = await context.newPage();
    await page.goto('/');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill('browser-test-password');
    await page.getByRole('button', { name: /Continue/ }).click();
    await page.getByRole('button', { name: new RegExp(workspace) }).click();
    await page.getByRole('button', { name: /Live notes/ }).click();
    const editor = page.getByLabel('Document text');
    await expect(editor).not.toHaveAttribute('readonly');
    await page.evaluate(() => navigator.serviceWorker.ready);
    await expect.poll(() => page.evaluate(async () => (await caches.keys()).some(key => key.startsWith('workspace-shell-')))).toBe(true);
    await expect.poll(() => page.evaluate(async () => {
      const db = await new Promise<IDBDatabase>((resolve, reject) => { const r = indexedDB.open('workspace-offline-v1'); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
      const count = await new Promise<number>((resolve, reject) => { const r = db.transaction('documents').objectStore('documents').count(); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
      db.close(); return count;
    })).toBe(1);
    await context.setOffline(true);
    await page.evaluate(() => (window as unknown as Window & { closeDocumentSocket: () => void }).closeDocumentSocket());
    await expect(page.locator('.editor-status')).toContainText(/Offline|Saved locally/);
    const text = (await editor.inputValue()) + ' edited offline';
    await editor.fill(text);
    await expect(page.locator('.editor-status')).toHaveText('Saved locally; pending server confirmation');
    page.once('dialog', dialog => void dialog.accept());
    await page.reload();
    await page.getByRole('button', { name: 'Open last local document' }).click();
    await expect(editor).toHaveValue(text);
    await expect(editor).not.toHaveAttribute('readonly');
    await editor.fill(text + ' again');
    await expect(page.locator('.editor-status')).toHaveText('Saved locally; pending server confirmation');
    const pendingIds = await page.evaluate(async () => {
      const db = await new Promise<IDBDatabase>((resolve, reject) => { const r = indexedDB.open('workspace-offline-v1'); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
      const entries = await new Promise<{ pending: { id: string }[] }[]>((resolve, reject) => { const r = db.transaction('documents').objectStore('documents').getAll(); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
      db.close(); return entries.flatMap(entry => entry.pending.map(item => item.id));
    });
    expect(pendingIds).toHaveLength(2);
    const cachedRequests = await page.evaluate(async () => {
      const names = await caches.keys();
      const requests = await Promise.all(names.map(async name => (await (await caches.open(name)).keys()).map(request => new URL(request.url).pathname)));
      return requests.flat();
    });
    expect(cachedRequests.every(path => path === '/' || path.startsWith('/assets/'))).toBe(true);
    await context.setOffline(false);
    // Switch the shared browser session to Bob; Alice's pending bytes must not be sent as Bob.
    const csrf = await (await context.request.get('/api/csrf')).json() as { token: string; headerName: string };
    const switched = await context.request.post('/login', { form: { username: process.env.WORKSPACE_E2E_BOB!, password: 'browser-test-password' }, headers: { [csrf.headerName]: csrf.token }, maxRedirects: 0 });
    expect(switched.status()).toBe(302);
    page.once('dialog', dialog => void dialog.accept());
    await page.reload();
    await page.getByRole('button', { name: new RegExp(workspace) }).click();
    await page.getByRole('button', { name: /Live notes/ }).click();
    await expect(editor).not.toHaveAttribute('readonly');
    expect(await editor.inputValue()).not.toContain(' edited offline');
    expect(await page.evaluate(() => (window as unknown as Window & { sentUpdateIds: () => string[] }).sentUpdateIds())).toEqual([]);
    await page.getByRole('button', { name: /Sign out/ }).click();
    await expect(page.getByLabel('Email')).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('workspace-last-offline-document-v1'))).toBeNull();
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill('browser-test-password');
    await page.getByRole('button', { name: /Continue/ }).click();
    await page.getByRole('button', { name: new RegExp(workspace) }).click();
    await page.getByRole('button', { name: /Live notes/ }).click();
    await expect(editor).toHaveValue(text + ' again');
    await expect(page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    expect(await page.evaluate(() => (window as unknown as Window & { sentUpdateIds: () => string[] }).sentUpdateIds())).toEqual(pendingIds);

    // Lose an ACK after the server commits, then reload and retry the same ID.
    await page.evaluate(() => (window as unknown as Window & { dropNextSaved: () => void }).dropNextSaved());
    await editor.fill((await editor.inputValue()) + ' lost acknowledgement');
    await expect.poll(() => page.evaluate(() => (window as unknown as Window & { savedWasDropped: () => boolean }).savedWasDropped()), { timeout: 15000 }).toBe(true);
    const lostId = await page.evaluate(() => (window as unknown as Window & { sentUpdateIds: () => string[] }).sentUpdateIds().at(-1));
    expect(lostId).toBeTruthy();
    page.once('dialog', dialog => void dialog.accept());
    await page.reload();
    await page.getByRole('button', { name: new RegExp(workspace) }).click();
    await page.getByRole('button', { name: /Live notes/ }).click();
    await expect(page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    expect(await page.evaluate(() => (window as unknown as Window & { sentUpdateIds: () => string[] }).sentUpdateIds())).toEqual([lostId]);
    const occurrences = await page.evaluate(async id => {
      const context = JSON.parse(localStorage.getItem('workspace-last-offline-document-v1')!) as { workspace: { id: string }; document: { id: string } };
      return new Promise<number>((resolve, reject) => {
        const socket = new WebSocket(`ws://${location.host}/api/workspaces/${context.workspace.id}/socket`);
        let count = 0;
        const timer = setTimeout(() => { socket.close(); reject(new Error('Replay timed out')); }, 10000);
        socket.onopen = () => socket.send(JSON.stringify({ type: 'HELLO', version: 1 }));
        socket.onerror = () => { clearTimeout(timer); reject(new Error('Replay connection failed')); };
        socket.onmessage = event => {
          const message = JSON.parse(String(event.data)) as { type: string; updates?: { updateId: string }[]; hasMore?: boolean; after?: number };
          if (message.type === 'WELCOME') socket.send(JSON.stringify({ type: 'JOIN', version: 1, documentId: context.document.id }));
          if (message.type === 'JOINED') socket.send(JSON.stringify({ type: 'SYNC', version: 1, epoch: 0, after: 0 }));
          if (message.type === 'SYNC_PAGE') {
            count += (message.updates ?? []).filter(update => update.updateId === id).length;
            if (message.hasMore) socket.send(JSON.stringify({ type: 'SYNC', version: 1, epoch: 0, after: message.after }));
            else { clearTimeout(timer); socket.close(); resolve(count); }
          }
        };
      });
    }, lostId);
    expect(occurrences).toBe(1);

    // A failed local transaction must never be followed by a false server submission.
    const sentBeforeFailure = await page.evaluate(() => (window as unknown as Window & { sentUpdateIds: () => string[] }).sentUpdateIds().length);
    await page.evaluate(() => {
      IDBObjectStore.prototype.put = function () { throw new DOMException('Simulated quota failure', 'QuotaExceededError'); };
    });
    const failedDraft = (await editor.inputValue()) + ' storage failure';
    await editor.fill(failedDraft);
    await expect(page.locator('.editor-status')).toContainText('Could not save locally');
    await expect(editor).toHaveValue(failedDraft);
    await expect(editor).toHaveAttribute('readonly');
    expect(await page.evaluate(() => (window as unknown as Window & { sentUpdateIds: () => string[] }).sentUpdateIds().length)).toBe(sentBeforeFailure);
  } finally { await context.close(); }
});

test('a revoked member can recover a local draft without uploading it', async ({ browser, baseURL }) => {
  const member = await browser.newContext({ baseURL });
  const owner = await browser.newContext({ baseURL });
  try {
    await member.addInitScript(() => {
      const original = WebSocket.prototype.send;
      let socket: WebSocket | undefined;
      let submissions = 0;
      const hooks = window as unknown as Window & { closeDocumentSocket: () => void; submissions: () => number };
      hooks.closeDocumentSocket = () => socket?.close(); hooks.submissions = () => submissions;
      WebSocket.prototype.send = function (data: string | ArrayBufferLike | Blob | ArrayBufferView) {
        if (this.url.includes('/socket') && typeof data === 'string') {
          socket = this;
          if ((JSON.parse(data) as { type: string }).type === 'SUBMIT_UPDATE') submissions++;
        }
        original.call(this, data);
      };
    });
    const page = await member.newPage();
    await page.goto('/');
    await page.getByLabel('Email').fill(process.env.WORKSPACE_E2E_BOB!);
    await page.getByLabel('Password').fill('browser-test-password');
    await page.getByRole('button', { name: /Continue/ }).click();
    await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
    await page.getByRole('button', { name: /Live notes/ }).click();
    const editor = page.getByLabel('Document text');
    await expect(editor).not.toHaveAttribute('readonly');
    await expect.poll(() => page.evaluate(async () => {
      const db = await new Promise<IDBDatabase>((resolve, reject) => { const r = indexedDB.open('workspace-offline-v1'); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
      const count = await new Promise<number>((resolve, reject) => { const r = db.transaction('documents').objectStore('documents').count(); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
      db.close(); return count;
    })).toBe(1);
    await member.setOffline(true);
    await page.evaluate(() => (window as unknown as Window & { closeDocumentSocket: () => void }).closeDocumentSocket());
    await expect(page.locator('.editor-status')).toContainText(/Offline|Saved locally/);
    const draft = (await editor.inputValue()) + ' revoked draft';
    await editor.fill(draft);
    await expect(page.locator('.editor-status')).toHaveText('Saved locally; pending server confirmation');
    let csrf = await (await owner.request.get('/api/csrf')).json() as { token: string; headerName: string };
    await owner.request.post('/login', { form: { username: process.env.WORKSPACE_E2E_ALICE!, password: 'browser-test-password' }, headers: { [csrf.headerName]: csrf.token }, maxRedirects: 0 });
    csrf = await (await owner.request.get('/api/csrf')).json() as { token: string; headerName: string };
    const response = await owner.request.delete(`/api/workspaces/${process.env.WORKSPACE_E2E_SHARED_WORKSPACE!}/members/${process.env.WORKSPACE_E2E_BOB_ID!}`, { headers: { [csrf.headerName]: csrf.token } });
    expect(response.status()).toBe(204);
    await member.setOffline(false);
    page.once('dialog', dialog => void dialog.accept());
    await page.reload();
    await page.getByRole('button', { name: 'Open last local document' }).click();
    await expect(editor).toHaveValue(draft);
    await expect(page.locator('.editor-status')).toContainText(/Offline|Saved locally/);
    expect(await page.evaluate(() => (window as unknown as Window & { submissions: () => number }).submissions())).toBe(0);
  } finally { await member.close(); await owner.close(); }
});
