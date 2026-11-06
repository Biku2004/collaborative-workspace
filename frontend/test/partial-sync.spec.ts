import { expect, test } from '@playwright/test';
import { createHash } from 'node:crypto';

test.beforeEach(async ({ page }) => {
  await page.route('**/baseline', route => route.fulfill({ status: 200, json: { epoch: 0, head: 0,
    format: route.request().url().includes('000000000041') ? 'RICH_TEXT_V1' : 'PLAIN_TEXT_V1', checkpoint: 'AAA=',
    sha256: createHash('sha256').update(new Uint8Array([0, 0])).digest('base64') } }));
});

test('a first synchronization interrupted mid-replay is not an offline editing baseline', async ({ page }) => {
  const user = '10000000-0000-4000-8000-000000000001';
  const workspace = '20000000-0000-4000-8000-000000000001';
  const document = '30000000-0000-4000-8000-000000000001';
  await page.route('**/api/me', route => route.fulfill({ status: 200, json: { username: 'test@example.com', id: user } }));
  await page.goto('/');
  await page.evaluate(async ({ user, workspace, document }) => {
    const path = '/src/provider.ts';
    const { DocumentProvider } = await import(/* @vite-ignore */ path) as typeof import('../src/provider');
    const hooks = window as unknown as Window & { phase: string; drop: () => void; requestedSecondPage: boolean };
    class FakeSocket {
      static readonly OPEN = 1;
      static readonly CONNECTING = 0;
      readyState = 1;
      bufferedAmount = 0;
      onopen?: () => void;
      onmessage?: (event: { data: string }) => void;
      onclose?: (event: { code: number }) => void;
      onerror?: () => void;
      constructor() { setTimeout(() => this.onopen?.(), 0); hooks.drop = () => this.close(); }
      send(data: string) {
        const message = JSON.parse(data) as { type: string; after?: number };
        if (message.type === 'SYNC' && message.after === 1) hooks.requestedSecondPage = true;
        const reply = message.type === 'HELLO' ? { type: 'WELCOME', version: 1, workspaceId: workspace, userId: user } :
          message.type === 'JOIN' ? { type: 'JOINED', version: 1, documentId: document, format: 'PLAIN_TEXT_V1' } :
          message.type === 'SYNC' && message.after === 0 ? { type: 'SYNC_PAGE', version: 1, documentId: document, epoch: 0,
            updates: [{ sequence: 1, payload: 'AAA=' }], after: 1, head: 2, hasMore: true } : null;
        if (reply) setTimeout(() => this.onmessage?.({ data: JSON.stringify(reply) }), 0);
      }
      close() { this.readyState = 3; setTimeout(() => this.onclose?.({ code: 1006 }), 0); }
    }
    window.WebSocket = FakeSocket as unknown as typeof WebSocket;
    new DocumentProvider(user, workspace, document, state => { hooks.phase = state.phase; });
  }, { user, workspace, document });
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { phase: string }).phase)).toBe('syncing');
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { requestedSecondPage: boolean }).requestedSecondPage)).toBe(true);
  await page.evaluate(() => (window as unknown as Window & { drop: () => void }).drop());
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { phase: string }).phase)).toBe('waiting');
});

test('a complete cached draft stays editable during repair while uploads wait', async ({ page }) => {
  const user = '10000000-0000-4000-8000-000000000071', workspace = '20000000-0000-4000-8000-000000000071';
  const document = '30000000-0000-4000-8000-000000000071', initial = 'AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA=';
  await page.route('**/api/me', route => route.fulfill({ status: 200, json: { username: 'test@example.com', id: user } }));
  await page.goto('/');
  await page.evaluate(async ({ user, workspace, document, initial }) => {
    const storagePath = '/src/localDocumentStore.ts', providerPath = '/src/provider.ts';
    const { LocalDocumentStore } = await import(/* @vite-ignore */ storagePath) as typeof import('../src/localDocumentStore');
    const { DocumentProvider } = await import(/* @vite-ignore */ providerPath) as typeof import('../src/provider');
    const store = await LocalDocumentStore.open(user, workspace, document, 0);
    await store.save({ version: 1, state: Uint8Array.from(atob(initial), c => c.charCodeAt(0)), pending: [] }); await store.close();
    const hooks = window as unknown as { repairState: import('../src/provider').EditorState; sentDuringRepair: number; edit: (text?: string) => void; finishRepair: () => void; stopRepair: () => void };
    hooks.sentDuringRepair = 0;
    class FakeSocket {
      static readonly OPEN = 1; static readonly CONNECTING = 0; readyState = 1; bufferedAmount = 0;
      onopen?: () => void; onmessage?: (event: { data: string }) => void; onclose?: (event: { code: number }) => void; onerror?: () => void;
      constructor() { setTimeout(() => this.onopen?.(), 0); }
      send(raw: string) {
        const value = JSON.parse(raw) as { type: string; updateId?: string };
        const reply = value.type === 'HELLO' ? { type: 'WELCOME', version: 1, workspaceId: workspace, userId: user } :
          value.type === 'JOIN' ? { type: 'JOINED', version: 1, documentId: document, format: 'PLAIN_TEXT_V1' } : null;
        if (reply) setTimeout(() => this.onmessage?.({ data: JSON.stringify(reply) }), 0);
        if (value.type === 'SYNC') hooks.finishRepair = () => this.onmessage?.({ data: JSON.stringify({ type: 'SYNC_PAGE', version: 1,
          documentId: document, epoch: 0, after: 1, head: 1, hasMore: false, updates: [{ sequence: 1, payload: initial }] }) });
        if (value.type === 'SUBMIT_UPDATE') {
          hooks.sentDuringRepair++;
          setTimeout(() => this.onmessage?.({ data: JSON.stringify({ type: 'REJECTED', version: 1, code: 'ACCESS_DENIED' }) }), 0);
        }
      }
      close() { this.readyState = 3; }
    }
    window.WebSocket = FakeSocket as unknown as typeof WebSocket;
    const provider = new DocumentProvider(user, workspace, document, state => { hooks.repairState = state; });
    hooks.edit = (text = 'Hello Java!') => provider.setText(text); hooks.stopRepair = () => provider.destroy();
  }, { user, workspace, document, initial });
  await expect.poll(() => page.evaluate(() => (window as unknown as { repairState: { phase: string } }).repairState?.phase)).toBe('syncing');
  await page.evaluate(() => (window as unknown as { edit: () => void }).edit());
  await expect.poll(() => page.evaluate(() => (window as unknown as { repairState: { text: string } }).repairState.text)).toBe('Hello Java!');
  expect(await page.evaluate(() => (window as unknown as { sentDuringRepair: number }).sentDuringRepair)).toBe(0);
  await page.evaluate(() => (window as unknown as { finishRepair: () => void }).finishRepair());
  await expect.poll(() => page.evaluate(() => (window as unknown as { sentDuringRepair: number }).sentDuringRepair)).toBe(1);
  await expect.poll(() => page.evaluate(() => (window as unknown as { repairState: { phase: string } }).repairState.phase)).toBe('failed');
  await page.evaluate(() => (window as unknown as { edit: (text: string) => void }).edit('must not replace a denied draft'));
  expect(await page.evaluate(() => (window as unknown as { repairState: { text: string; editable: boolean } }).repairState)).toMatchObject({ text: 'Hello Java!', editable: false });
  await page.evaluate(() => (window as unknown as { stopRepair: () => void }).stopRepair());
});

test('an epoch change rejects reconciliation before pending updates are sent', async ({ page }) => {
  const user = '10000000-0000-4000-8000-000000000011';
  const workspace = '20000000-0000-4000-8000-000000000011';
  const document = '30000000-0000-4000-8000-000000000011';
  const id = '40000000-0000-4000-8000-000000000011';
  await page.route('**/api/me', route => route.fulfill({ status: 200, json: { username: 'test@example.com', id: user } }));
  await page.goto('/');
  await page.evaluate(async ({ user, workspace, document, id }) => {
    const storagePath = '/src/localDocumentStore.ts', providerPath = '/src/provider.ts';
    const { LocalDocumentStore } = await import(/* @vite-ignore */ storagePath) as typeof import('../src/localDocumentStore');
    const { DocumentProvider } = await import(/* @vite-ignore */ providerPath) as typeof import('../src/provider');
    const store = await LocalDocumentStore.open(user, workspace, document, 0);
    await store.save({ version: 1, state: new Uint8Array([0, 0]), pending: [{ id, payload: 'AAA=' }] });
    await store.close();
    const hooks = window as unknown as Window & { phase: string; message: string; pending: number; submissions: number };
    hooks.submissions = 0;
    class FakeSocket {
      static readonly OPEN = 1;
      static readonly CONNECTING = 0;
      readyState = 1;
      bufferedAmount = 0;
      onopen?: () => void;
      onmessage?: (event: { data: string }) => void;
      onclose?: (event: { code: number }) => void;
      onerror?: () => void;
      constructor() { setTimeout(() => this.onopen?.(), 0); }
      send(data: string) {
        const message = JSON.parse(data) as { type: string };
        if (message.type === 'SUBMIT_UPDATE') hooks.submissions++;
        const reply = message.type === 'HELLO' ? { type: 'WELCOME', version: 1, workspaceId: workspace, userId: user } :
          message.type === 'JOIN' ? { type: 'JOINED', version: 1, documentId: document, format: 'PLAIN_TEXT_V1' } :
          message.type === 'SYNC' ? { type: 'REJECTED', version: 1, code: 'EPOCH_CHANGED' } : null;
        if (reply) setTimeout(() => this.onmessage?.({ data: JSON.stringify(reply) }), 0);
      }
      close() { this.readyState = 3; setTimeout(() => this.onclose?.({ code: 1000 }), 0); }
    }
    window.WebSocket = FakeSocket as unknown as typeof WebSocket;
    new DocumentProvider(user, workspace, document, state => { hooks.phase = state.phase; hooks.message = state.message; hooks.pending = state.pending; });
  }, { user, workspace, document, id });
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { phase: string }).phase)).toBe('failed');
  const result = await page.evaluate(() => {
    const hooks = window as unknown as Window & { message: string; pending: number; submissions: number };
    return { message: hooks.message, pending: hooks.pending, submissions: hooks.submissions };
  });
  expect(result.message).toContain('quarantined');
  expect(result.pending).toBe(1);
  expect(result.submissions).toBe(0);
});

test('a repeatedly unavailable update engine increases retry delays after successful replay', async ({ page }) => {
  const user = '10000000-0000-4000-8000-000000000021';
  const workspace = '20000000-0000-4000-8000-000000000021';
  const document = '30000000-0000-4000-8000-000000000021';
  const id = '40000000-0000-4000-8000-000000000021';
  await page.route('**/api/me', route => route.fulfill({ status: 200, json: { username: 'test@example.com', id: user } }));
  await page.goto('/');
  await page.evaluate(async ({ user, workspace, document, id }) => {
    const storagePath = '/src/localDocumentStore.ts', providerPath = '/src/provider.ts';
    const { LocalDocumentStore } = await import(/* @vite-ignore */ storagePath) as typeof import('../src/localDocumentStore');
    const { DocumentProvider } = await import(/* @vite-ignore */ providerPath) as typeof import('../src/provider');
    const store = await LocalDocumentStore.open(user, workspace, document, 0);
    await store.save({ version: 1, state: new Uint8Array([0, 0]), pending: [{ id, payload: 'AAA=' }] });
    await store.close();
    const hooks = window as unknown as Window & { attempts: number[]; stop: () => void };
    hooks.attempts = [];
    class FakeSocket {
      static readonly OPEN = 1;
      static readonly CONNECTING = 0;
      readyState = 1;
      bufferedAmount = 0;
      onopen?: () => void;
      onmessage?: (event: { data: string }) => void;
      onclose?: (event: { code: number }) => void;
      onerror?: () => void;
      constructor() { setTimeout(() => this.onopen?.(), 0); }
      send(data: string) {
        const message = JSON.parse(data) as { type: string };
        if (message.type === 'SUBMIT_UPDATE') hooks.attempts.push(Date.now());
        const reply = message.type === 'HELLO' ? { type: 'WELCOME', version: 1, workspaceId: workspace, userId: user } :
          message.type === 'JOIN' ? { type: 'JOINED', version: 1, documentId: document, format: 'PLAIN_TEXT_V1' } :
          message.type === 'SYNC' ? { type: 'SYNC_PAGE', version: 1, documentId: document, epoch: 0, after: 0, head: 0, hasMore: false, updates: [] } :
          message.type === 'SUBMIT_UPDATE' ? { type: 'REJECTED', version: 1, code: 'ENGINE_UNAVAILABLE' } : null;
        if (reply) setTimeout(() => this.onmessage?.({ data: JSON.stringify(reply) }), 0);
      }
      close() { this.readyState = 3; setTimeout(() => this.onclose?.({ code: 1000 }), 0); }
    }
    window.WebSocket = FakeSocket as unknown as typeof WebSocket;
    const provider = new DocumentProvider(user, workspace, document, () => {});
    hooks.stop = () => provider.destroy();
  }, { user, workspace, document, id });
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { attempts: number[] }).attempts.length), { timeout: 12000 }).toBeGreaterThanOrEqual(3);
  const attempts = await page.evaluate(() => {
    const hooks = window as unknown as Window & { attempts: number[]; stop: () => void };
    hooks.stop(); return hooks.attempts;
  });
  expect(attempts[1]! - attempts[0]!).toBeGreaterThanOrEqual(900);
  expect(attempts[2]! - attempts[1]!).toBeGreaterThanOrEqual(1900);
});

test('a socket authenticated as a different account cannot join or submit a saved draft', async ({ page }) => {
  const user = '10000000-0000-4000-8000-000000000031';
  const other = '10000000-0000-4000-8000-000000000032';
  const workspace = '20000000-0000-4000-8000-000000000031';
  const document = '30000000-0000-4000-8000-000000000031';
  const id = '40000000-0000-4000-8000-000000000031';
  await page.route('**/api/me', route => route.fulfill({ status: 200, json: { username: 'test@example.com', id: user } }));
  await page.goto('/');
  await page.evaluate(async ({ user, other, workspace, document, id }) => {
    const storagePath = '/src/localDocumentStore.ts', providerPath = '/src/provider.ts';
    const { LocalDocumentStore } = await import(/* @vite-ignore */ storagePath) as typeof import('../src/localDocumentStore');
    const { DocumentProvider } = await import(/* @vite-ignore */ providerPath) as typeof import('../src/provider');
    const store = await LocalDocumentStore.open(user, workspace, document, 0);
    await store.save({ version: 1, state: new Uint8Array([0, 0]), pending: [{ id, payload: 'AAA=' }] });
    await store.close();
    const hooks = window as unknown as Window & { phase: string; sent: string[]; pending: number };
    hooks.sent = [];
    class FakeSocket {
      static readonly OPEN = 1;
      static readonly CONNECTING = 0;
      readyState = 1;
      bufferedAmount = 0;
      onopen?: () => void;
      onmessage?: (event: { data: string }) => void;
      onclose?: (event: { code: number }) => void;
      onerror?: () => void;
      constructor() { setTimeout(() => this.onopen?.(), 0); }
      send(data: string) {
        const message = JSON.parse(data) as { type: string };
        hooks.sent.push(message.type);
        if (message.type === 'HELLO') setTimeout(() => this.onmessage?.({ data: JSON.stringify({ type: 'WELCOME', version: 1, workspaceId: workspace, userId: other }) }), 0);
      }
      close() { this.readyState = 3; setTimeout(() => this.onclose?.({ code: 1000 }), 0); }
    }
    window.WebSocket = FakeSocket as unknown as typeof WebSocket;
    new DocumentProvider(user, workspace, document, state => { hooks.phase = state.phase; hooks.pending = state.pending; });
  }, { user, other, workspace, document, id });
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { phase: string }).phase)).toBe('failed');
  expect(await page.evaluate(() => {
    const hooks = window as unknown as Window & { sent: string[]; pending: number };
    return { sent: hooks.sent, pending: hooks.pending };
  })).toEqual({ sent: ['HELLO'], pending: 1 });
});

test('a rich client negotiates its format without creating the plain-text root', async ({ page }) => {
  const user = '10000000-0000-4000-8000-000000000041';
  const workspace = '20000000-0000-4000-8000-000000000041';
  const document = '30000000-0000-4000-8000-000000000041';
  await page.route('**/api/me', route => route.fulfill({ status: 200, json: { username: 'test@example.com', id: user } }));
  await page.goto('/');
  await page.evaluate(async ({ user, workspace, document }) => {
    const path = '/src/provider.ts';
    const { DocumentProvider } = await import(/* @vite-ignore */ path) as typeof import('../src/provider');
    const hooks = window as unknown as Window & { phase: string; requested?: string; roots: string[] };
    class FakeSocket {
      static readonly OPEN = 1;
      static readonly CONNECTING = 0;
      readyState = 1;
      bufferedAmount = 0;
      onopen?: () => void;
      onmessage?: (event: { data: string }) => void;
      onclose?: (event: { code: number }) => void;
      onerror?: () => void;
      constructor() { setTimeout(() => this.onopen?.(), 0); }
      send(data: string) {
        const message = JSON.parse(data) as { type: string; format?: string };
        if (message.type === 'JOIN') hooks.requested = message.format;
        const reply = message.type === 'HELLO' ? { type: 'WELCOME', version: 1, workspaceId: workspace, userId: user } :
          message.type === 'JOIN' ? { type: 'REJECTED', version: 1, code: 'SCHEMA_UNSUPPORTED' } : null;
        if (reply) setTimeout(() => this.onmessage?.({ data: JSON.stringify(reply) }), 0);
      }
      close() { this.readyState = 3; setTimeout(() => this.onclose?.({ code: 1000 }), 0); }
    }
    window.WebSocket = FakeSocket as unknown as typeof WebSocket;
    const provider = new DocumentProvider(user, workspace, document, state => { hooks.phase = state.phase; }, 'RICH_TEXT_V1');
    hooks.roots = [...provider.sharedDocument.share.keys()];
  }, { user, workspace, document });
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { phase: string }).phase)).toBe('failed');
  expect(await page.evaluate(() => {
    const hooks = window as unknown as Window & { requested?: string; roots: string[] };
    return { requested: hooks.requested, roots: hooks.roots };
  })).toEqual({ requested: 'RICH_TEXT_V1', roots: [] });
});

test('a document limit rejection keeps the pending local draft and explains the failure', async ({ page }) => {
  const user = '10000000-0000-4000-8000-000000000051';
  const workspace = '20000000-0000-4000-8000-000000000051';
  const document = '30000000-0000-4000-8000-000000000051';
  await page.route('**/api/me', route => route.fulfill({ status: 200, json: { username: 'test@example.com', id: user } }));
  await page.goto('/');
  await page.evaluate(async ({ user, workspace, document }) => {
    const storagePath = '/src/localDocumentStore.ts', providerPath = '/src/provider.ts';
    const { LocalDocumentStore } = await import(/* @vite-ignore */ storagePath) as typeof import('../src/localDocumentStore');
    const { DocumentProvider } = await import(/* @vite-ignore */ providerPath) as typeof import('../src/provider');
    const store = await LocalDocumentStore.open(user, workspace, document, 0);
    await store.save({ version: 1, state: new Uint8Array([0, 0]), pending: [{ id: '40000000-0000-4000-8000-000000000051', payload: 'AAA=' }] });
    await store.close();
    const hooks = window as unknown as Window & { phase: string; message: string; pending: number };
    class FakeSocket {
      static readonly OPEN = 1; static readonly CONNECTING = 0;
      readyState = 1; bufferedAmount = 0;
      onopen?: () => void; onmessage?: (event: { data: string }) => void; onclose?: (event: { code: number }) => void;
      constructor() { setTimeout(() => this.onopen?.(), 0); }
      send(data: string) {
        const message = JSON.parse(data) as { type: string };
        const reply = message.type === 'HELLO' ? { type: 'WELCOME', version: 1, workspaceId: workspace, userId: user } :
          message.type === 'JOIN' ? { type: 'REJECTED', version: 1, code: 'DOCUMENT_LIMIT' } : null;
        if (reply) setTimeout(() => this.onmessage?.({ data: JSON.stringify(reply) }), 0);
      }
      close() { this.readyState = 3; setTimeout(() => this.onclose?.({ code: 1000 }), 0); }
    }
    window.WebSocket = FakeSocket as unknown as typeof WebSocket;
    new DocumentProvider(user, workspace, document, state => { hooks.phase = state.phase; hooks.message = state.message; hooks.pending = state.pending; });
  }, { user, workspace, document });
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { phase: string }).phase)).toBe('failed');
  const result = await page.evaluate(() => {
    const hooks = window as unknown as Window & { message: string; pending: number };
    return { message: hooks.message, pending: hooks.pending };
  });
  expect(result.message).toContain('reached a server limit');
  expect(result.message).toContain('not saved to the server');
  expect(result.pending).toBe(1);
});
