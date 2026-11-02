import { expect, test } from '@playwright/test';

test('a first synchronization interrupted mid-replay is not an offline editing baseline', async ({ page }) => {
  const user = '10000000-0000-4000-8000-000000000001';
  const workspace = '20000000-0000-4000-8000-000000000001';
  const document = '30000000-0000-4000-8000-000000000001';
  await page.route('**/api/me', route => route.fulfill({ status: 200, json: { username: 'test@example.com', id: user } }));
  await page.goto('/');
  await page.evaluate(async ({ user, workspace, document }) => {
    const path = '/src/provider.ts';
    const { PlainTextProvider } = await import(/* @vite-ignore */ path) as typeof import('../src/provider');
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
          message.type === 'JOIN' ? { type: 'JOINED', version: 1, documentId: document } :
          message.type === 'SYNC' && message.after === 0 ? { type: 'SYNC_PAGE', version: 1, documentId: document, epoch: 0,
            updates: [{ sequence: 1, payload: 'AAA=' }], after: 1, head: 2, hasMore: true } : null;
        if (reply) setTimeout(() => this.onmessage?.({ data: JSON.stringify(reply) }), 0);
      }
      close() { this.readyState = 3; setTimeout(() => this.onclose?.({ code: 1006 }), 0); }
    }
    window.WebSocket = FakeSocket as unknown as typeof WebSocket;
    new PlainTextProvider(user, workspace, document, state => { hooks.phase = state.phase; });
  }, { user, workspace, document });
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { phase: string }).phase)).toBe('syncing');
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { requestedSecondPage: boolean }).requestedSecondPage)).toBe(true);
  await page.evaluate(() => (window as unknown as Window & { drop: () => void }).drop());
  await expect.poll(() => page.evaluate(() => (window as unknown as Window & { phase: string }).phase)).toBe('failed');
});
