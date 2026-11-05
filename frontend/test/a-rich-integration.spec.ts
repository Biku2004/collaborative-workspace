import { expect, test, type BrowserContext, type Page } from '@playwright/test';

test('rich editors synchronize, recover offline edits, and enforce viewer writes', async ({ browser, baseURL }) => {
  test.skip(process.env.WORKSPACE_E2E_EDITING !== 'true', 'Requires the real local Yjs engine');
  const alice = await browser.newContext({ baseURL }), bob = await browser.newContext({ baseURL });
  const errors: string[] = [];
  let viewerRoleSet = false;
  let releaseList: (() => void) | undefined;
  async function open(context: BrowserContext, email: string) {
    await context.addInitScript(() => {
      const original = WebSocket.prototype.send;
      let socket: WebSocket | undefined;
      (window as unknown as Window & { disconnectDocument: () => void }).disconnectDocument = () => socket?.close();
      WebSocket.prototype.send = function (data: string | ArrayBufferLike | Blob | ArrayBufferView) {
        if (this.url.includes('/socket')) socket = this;
        original.call(this, data);
      };
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto('/');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill('browser-test-password');
    await page.getByRole('button', { name: /Continue/ }).click();
    return page;
  }
  async function select(page: Page) {
    await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
    await page.getByRole('button', { name: /Rich notes/ }).click();
    await expect(page.getByRole('textbox', { name: 'Document text' })).toHaveAttribute('contenteditable', 'true', { timeout: 15000 });
  }
  try {
    const [a, b] = await Promise.all([open(alice, process.env.WORKSPACE_E2E_ALICE!), open(bob, process.env.WORKSPACE_E2E_BOB!)]);
    let listCaptured!: () => void;
    const captured = new Promise<void>(resolve => { listCaptured = resolve; });
    const holdList = new Promise<void>(resolve => { releaseList = resolve; });
    await a.route('**/api/workspaces/*/documents', async route => {
      if (route.request().method() !== 'GET') { await route.continue(); return; }
      const response = await route.fetch();
      listCaptured();
      await holdList;
      await route.fulfill({ response });
    });
    await a.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
    await captured;
    await a.getByLabel('New rich-text document').fill('Rich notes');
    const [creation] = await Promise.all([
      a.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/documents')),
      a.getByRole('button', { name: 'Create document' }).click(),
    ]);
    expect(creation.status()).toBe(201);
    const original = await creation.json() as { id: string; collaborationFormat: string };
    expect(original.collaborationFormat).toBe('RICH_TEXT_V1');
    const requestKey = creation.request().headers()['idempotency-key'];
    expect(requestKey).toBeTruthy();
    const creationCsrf = await (await alice.request.get('/api/csrf')).json() as { token: string; headerName: string };
    const createUrl = `/api/workspaces/${process.env.WORKSPACE_E2E_SHARED_WORKSPACE!}/documents`;
    const headers = { [creationCsrf.headerName]: creationCsrf.token, 'Idempotency-Key': requestKey! };
    const retry = await alice.request.post(createUrl, { headers, data: { title: 'Rich notes', format: 'RICH_TEXT_V1' } });
    expect(retry.status()).toBe(201);
    expect((await retry.json() as { id: string }).id).toBe(original.id);
    expect((await alice.request.post(createUrl, { headers, data: { title: 'Rich notes', format: 'PLAIN_TEXT_V1' } })).status()).toBe(409);
    expect((await alice.request.post(createUrl, { headers: { [creationCsrf.headerName]: creationCsrf.token }, data: { title: 'Invalid', format: 'RICH_TEXT_V2' } })).status()).toBe(400);
    await expect(a.getByRole('button', { name: /Rich notes/ })).toBeVisible();
    releaseList?.();
    await expect(a.getByRole('button', { name: /Live notes/ })).toBeVisible();
    await expect(a.getByRole('button', { name: /Rich notes/ })).toHaveCount(1);
    await a.unroute('**/api/workspaces/*/documents');
    await a.getByRole('button', { name: /Rich notes/ }).click();
    await expect(a.getByRole('textbox', { name: 'Document text' })).toHaveAttribute('contenteditable', 'true', { timeout: 15000 });
    await select(b);
    const editorA = a.getByRole('textbox', { name: 'Document text' }), editorB = b.getByRole('textbox', { name: 'Document text' });
    await editorA.fill('Hello');
    await expect(editorB).toHaveText('Hello', { timeout: 15000 });
    await editorA.press('Control+A'); await a.getByRole('button', { name: 'Bold', exact: true }).click();
    await expect(editorB.locator('strong')).toHaveText('Hello', { timeout: 15000 });
    await a.getByRole('button', { name: 'Bullet list', exact: true }).click();
    await expect(editorB.locator('ul li')).toHaveText('Hello', { timeout: 15000 });
    await a.getByRole('button', { name: 'Numbered list', exact: true }).click();
    await expect(editorB.locator('ol li')).toHaveText('Hello', { timeout: 15000 });
    await a.getByRole('button', { name: 'Numbered list', exact: true }).click();
    await expect(editorB.locator('p')).toHaveText('Hello', { timeout: 15000 });
    await a.getByRole('button', { name: 'Heading 2', exact: true }).click();
    await expect(editorB.locator('h2')).toHaveText('Hello', { timeout: 15000 });
    await expect(a.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await Promise.all([alice.setOffline(true), bob.setOffline(true)]);
    await Promise.all([a.evaluate(() => (window as unknown as Window & { disconnectDocument: () => void }).disconnectDocument()),
      b.evaluate(() => (window as unknown as Window & { disconnectDocument: () => void }).disconnectDocument())]);
    await expect(a.locator('.editor-status')).toContainText(/Offline|Saved locally/);
    await expect(b.locator('.editor-status')).toContainText(/Offline|Saved locally/);
    await editorA.press('Control+A'); await a.getByRole('button', { name: 'Italic', exact: true }).click();
    await editorB.focus(); await editorB.press('Control+End'); await editorB.pressSequentially('!');
    await expect(a.locator('.editor-status')).toHaveText('Saved locally; pending server confirmation');
    await expect(b.locator('.editor-status')).toHaveText('Saved locally; pending server confirmation');
    await Promise.all([alice.setOffline(false), bob.setOffline(false)]);
    await expect(editorA).toHaveText('Hello!', { timeout: 20000 });
    await expect(editorB).toHaveText('Hello!', { timeout: 20000 });
    await expect(editorA.locator('em')).toContainText('Hello');
    await expect(editorB.locator('em')).toContainText('Hello');
    await expect(a.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 20000 });
    await expect(b.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 20000 });
    await Promise.all([a.reload(), b.reload()]);
    await Promise.all([select(a), select(b)]);
    await expect(editorA).toHaveText('Hello!'); await expect(editorB.locator('h2')).toHaveText('Hello!');
    await expect(editorB.locator('em')).toContainText('Hello');

    const csrf = await (await alice.request.get('/api/csrf')).json() as { token: string; headerName: string };
    const workspace = process.env.WORKSPACE_E2E_SHARED_WORKSPACE!;
    const role = await alice.request.post(`/api/workspaces/${workspace}/members`, { headers: { [csrf.headerName]: csrf.token }, data: { email: process.env.WORKSPACE_E2E_BOB!, role: 'VIEWER' } });
    expect(role.status()).toBe(200);
    viewerRoleSet = true;
    const bobCsrf = await (await bob.request.get('/api/csrf')).json() as { token: string; headerName: string };
    expect((await bob.request.post(createUrl, { headers: { [bobCsrf.headerName]: bobCsrf.token }, data: { title: 'Denied', format: 'RICH_TEXT_V1' } })).status()).toBe(403);
    await editorB.focus(); await editorB.press('Control+End'); await editorB.pressSequentially('private');
    await expect(b.getByRole('alert')).toContainText(/draft|access|connection/i, { timeout: 15000 });
    await expect(editorB).toHaveAttribute('contenteditable', 'false');
    const rejectedDraft = await editorB.innerText();
    expect(rejectedDraft).toMatch(/^Hello!\S/);
    await b.waitForTimeout(500);
    await expect(editorB).toHaveText(rejectedDraft);
    await expect(editorA).toHaveText('Hello!');
    expect((await alice.request.post(`/api/workspaces/${workspace}/members`, { headers: { [csrf.headerName]: csrf.token }, data: { email: process.env.WORKSPACE_E2E_BOB!, role: 'EDITOR' } })).status()).toBe(200);
    viewerRoleSet = false;
    expect(errors).toEqual([]);
  } finally {
    releaseList?.();
    if (viewerRoleSet) {
      const csrf = await (await alice.request.get('/api/csrf')).json() as { token: string; headerName: string };
      const restored = await alice.request.post(`/api/workspaces/${process.env.WORKSPACE_E2E_SHARED_WORKSPACE!}/members`, {
        headers: { [csrf.headerName]: csrf.token }, data: { email: process.env.WORKSPACE_E2E_BOB!, role: 'EDITOR' },
      });
      if (restored.status() !== 200) throw new Error('Failed to restore test membership');
    }
    await alice.close(); await bob.close();
  }
});

test('measures repeated structural edits and restores the larger checkpoint', async ({ browser, baseURL }, testInfo) => {
  test.skip(process.env.WORKSPACE_E2E_EDITING !== 'true', 'Requires the real local Yjs engine');
  test.setTimeout(90000);
  const context = await browser.newContext({ baseURL });
  try {
    await context.addInitScript(() => {
      const descriptor = Object.getOwnPropertyDescriptor(WebSocket.prototype, 'onmessage')!;
      const replies: { type: string; code?: string }[] = [];
      (window as unknown as Window & { editReplies: () => typeof replies }).editReplies = () => replies;
      Object.defineProperty(WebSocket.prototype, 'onmessage', {
        configurable: true, enumerable: descriptor.enumerable, get: descriptor.get,
        set(this: WebSocket, listener: ((event: MessageEvent) => void) | null) {
          descriptor.set!.call(this, listener && function (this: WebSocket, event: MessageEvent) {
            try {
              const message = JSON.parse(String(event.data)) as { type: string; code?: string };
              if (message.type === 'SAVED' || message.type === 'REJECTED') replies.push(message);
            } catch { /* Ignore frames outside this protocol. */ }
            listener.call(this, event);
          });
        },
      });
    });
    const page = await context.newPage();
    await page.goto('/');
    await page.getByLabel('Email').fill(process.env.WORKSPACE_E2E_ALICE!);
    await page.getByLabel('Password').fill('browser-test-password');
    await page.getByRole('button', { name: /Continue/ }).click();
    await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
    await page.getByLabel('New rich-text document').fill('Capacity notes');
    await page.getByRole('button', { name: 'Create document' }).click();
    await page.getByRole('button', { name: /Capacity notes/ }).click();
    const editor = page.getByRole('textbox', { name: 'Document text' });
    await expect(editor).toHaveAttribute('contenteditable', 'true', { timeout: 15000 });
    await editor.fill('Capacity sample');
    await expect.poll(() => page.evaluate(() => (window as unknown as Window & { editReplies: () => unknown[] }).editReplies().length), { timeout: 15000 }).toBeGreaterThan(0);
    await expect(page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    let accepted = 0, rejection: string | undefined;
    for (let attempt = 0; attempt < 40; attempt++) {
      const before = await page.evaluate(() => (window as unknown as Window & { editReplies: () => unknown[] }).editReplies().length);
      await page.getByRole('button', { name: 'Heading 2', exact: true }).click();
      await expect.poll(() => page.evaluate(() => (window as unknown as Window & { editReplies: () => unknown[] }).editReplies().length), { timeout: 15000 }).toBeGreaterThan(before);
      const replies = await page.evaluate(() => (window as unknown as Window & { editReplies: () => { type: string; code?: string }[] }).editReplies());
      const result = replies.slice(before).find(reply => reply.type === 'REJECTED');
      if (result) { rejection = result.code; break; }
      accepted++;
      await expect(page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    }
    const local = await page.evaluate(async () => {
      const db = await new Promise<IDBDatabase>((resolve, reject) => { const request = indexedDB.open('workspace-offline-v1'); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
      const entries = await new Promise<{ state: Uint8Array; pending: unknown[] }[]>((resolve, reject) => { const request = db.transaction('documents').objectStore('documents').getAll(); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
      db.close();
      if (entries.length !== 1) throw new Error('Expected one local capacity document');
      const path = '/test/capacity-inspector.ts';
      const inspector = await import(/* @vite-ignore */ path) as typeof import('./capacity-inspector');
      return { ...inspector.inspectCapacity(entries[0]!.state), pending: entries[0]!.pending.length };
    });
    const measurement = JSON.stringify({ attempted: accepted + (rejection ? 1 : 0), accepted, rejection: rejection ?? null, ...local });
    await testInfo.attach('structural-edit-measurement', { body: measurement, contentType: 'application/json' });
    console.log(`STRUCTURAL_EDIT_MEASUREMENT ${measurement}`);
    expect(accepted).toBe(40);
    expect(rejection).toBeUndefined();
    expect(local.pending).toBe(0);
    const observer = await browser.newContext({ baseURL });
    try {
      const other = await observer.newPage();
      await other.goto('/');
      await other.getByLabel('Email').fill(process.env.WORKSPACE_E2E_ALICE!);
      await other.getByLabel('Password').fill('browser-test-password');
      await other.getByRole('button', { name: /Continue/ }).click();
      await other.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
      await other.getByRole('button', { name: /Capacity notes/ }).click();
      const observed = other.getByRole('textbox', { name: 'Document text' });
      await expect(observed).toHaveText('Capacity sample', { timeout: 15000 });
      await observed.focus(); await observed.press('Control+End'); await observed.pressSequentially('!');
      await expect(editor).toHaveText('Capacity sample!', { timeout: 15000 });
      await expect(other.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    } finally { await observer.close(); }
  } finally { await context.close(); }
});
