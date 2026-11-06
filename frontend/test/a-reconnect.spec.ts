import { expect, test, type BrowserContext } from '@playwright/test';

test('independent offline insert and delete edits reconcile after automatic reconnect', async ({ browser, baseURL }) => {
  test.skip(process.env.WORKSPACE_E2E_EDITING !== 'true', 'Requires the real local Yjs engine');
  test.setTimeout(90000);
  const alice = await browser.newContext({ baseURL });
  const bob = await browser.newContext({ baseURL });
  let restoreBob: (() => Promise<void>) | undefined;
  async function open(context: BrowserContext, email: string) {
    await context.addInitScript(() => {
      const original = WebSocket.prototype.send;
      let socket: WebSocket | undefined;
      const hooks = window as unknown as Window & { disconnectDocument: () => void; submissions: string[] };
      hooks.submissions = [];
      hooks.disconnectDocument = () => socket?.close();
      WebSocket.prototype.send = function (data: string | ArrayBufferLike | Blob | ArrayBufferView) {
        if (this.url.includes('/socket') && typeof data === 'string') {
          socket = this;
          const message = JSON.parse(data) as { type: string; updateId?: string };
          if (message.type === 'SUBMIT_UPDATE' && message.updateId) hooks.submissions.push(message.updateId);
        }
        original.call(this, data);
      };
    });
    const page = await context.newPage();
    await page.goto('/');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill('browser-test-password');
    await page.getByRole('button', { name: /Continue/ }).click();
    await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
    await page.getByRole('button', { name: /Reconnect notes/ }).click();
    const editor = page.getByLabel('Document text');
    await expect(editor).not.toHaveAttribute('readonly', { timeout: 20000 });
    return { page, editor };
  }
  try {
    const [a, b] = await Promise.all([open(alice, process.env.WORKSPACE_E2E_ALICE!), open(bob, process.env.WORKSPACE_E2E_BOB!)]);
    await a.editor.fill('ABCDE');
    await expect(b.editor).toHaveValue('ABCDE', { timeout: 15000 });
    await a.editor.focus();
    await a.editor.press('Control+Home'); await a.editor.press('ArrowRight');
    await a.editor.press('Shift+ArrowRight'); await a.editor.press('Shift+ArrowRight'); await a.editor.press('Shift+ArrowRight');
    await expect(b.page.getByLabel('Collaborators')).toContainText(`${process.env.WORKSPACE_E2E_ALICE_ID!})`, { timeout: 15000 });
    await expect(b.page.getByLabel('Collaborators')).toContainText('selection 1–4', { timeout: 15000 });
    await a.editor.press('Control+Home'); await a.editor.press('ArrowRight'); await a.editor.press('ArrowRight');
    await expect(b.page.getByLabel('Collaborators')).toContainText('cursor at 2', { timeout: 15000 });
    await expect(a.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await Promise.all([alice.setOffline(true), bob.setOffline(true)]);
    await Promise.all([a.page.evaluate(() => (window as unknown as Window & { disconnectDocument: () => void }).disconnectDocument()),
      b.page.evaluate(() => (window as unknown as Window & { disconnectDocument: () => void }).disconnectDocument())]);
    await expect(a.page.locator('.editor-status')).toContainText(/Offline|Saved locally/);
    await expect(b.page.locator('.editor-status')).toContainText(/Offline|Saved locally/);
    await Promise.all([a.editor.fill('ACE'), b.editor.fill('ABCDE!')]);
    await expect(a.page.locator('.editor-status')).toHaveText('Saved locally; pending server confirmation');
    await expect(b.page.locator('.editor-status')).toHaveText('Saved locally; pending server confirmation');
    await Promise.all([alice.setOffline(false), bob.setOffline(false)]);
    await expect(a.editor).toHaveValue('ACE!', { timeout: 20000 });
    await expect(b.editor).toHaveValue('ACE!', { timeout: 20000 });
    await expect(a.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 20000 });
    await expect(b.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 20000 });

    // Bob edits offline, then Alice removes him before the automatic retry.
    await bob.setOffline(true);
    await b.page.evaluate(() => (window as unknown as Window & { disconnectDocument: () => void }).disconnectDocument());
    await expect(b.page.locator('.editor-status')).toContainText(/Offline|Saved locally/);
    await b.editor.fill('ACE! private draft');
    await expect(b.page.locator('.editor-status')).toHaveText('Saved locally; pending server confirmation');
    const csrf = await (await alice.request.get('/api/csrf')).json() as { token: string; headerName: string };
    const workspace = process.env.WORKSPACE_E2E_SHARED_WORKSPACE!, bobId = process.env.WORKSPACE_E2E_BOB_ID!;
    restoreBob = async () => {
      expect((await alice.request.post(`/api/workspaces/${workspace}/members`, { headers: { [csrf.headerName]: csrf.token }, data: { email: process.env.WORKSPACE_E2E_BOB!, role: 'EDITOR' } })).status()).toBe(200);
    };
    expect((await alice.request.delete(`/api/workspaces/${workspace}/members/${bobId}`, { headers: { [csrf.headerName]: csrf.token } })).status()).toBe(204);
    const sentBefore = await b.page.evaluate(() => (window as unknown as Window & { submissions: string[] }).submissions.length);
    await bob.setOffline(false);
    await expect(b.page.locator('.editor-status')).toContainText('Access was denied or revoked', { timeout: 10000 });
    await expect(b.editor).toHaveAttribute('readonly');
    await b.page.waitForTimeout(2500);
    expect(await b.editor.inputValue()).toBe('ACE! private draft');
    expect(await b.page.evaluate(() => (window as unknown as Window & { submissions: string[] }).submissions.length)).toBe(sentBefore);
  } finally { try { await restoreBob?.(); } finally { await alice.close(); await bob.close(); } }
});
