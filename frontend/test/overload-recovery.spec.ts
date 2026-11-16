import { expect, test, type BrowserContext } from '@playwright/test';

test('a slow-consumer overload closes one socket and its browser automatically repairs committed edits', async ({ browser, request, baseURL }) => {
  test.skip(!process.env.WORKSPACE_E2E_OVERLOAD_CONTROL, 'Requires OverloadRecoveryIntegrationTest');
  test.setTimeout(60000);
  const alice = await browser.newContext({ baseURL }), bob = await browser.newContext({ baseURL });
  async function control(action: string) {
    expect((await request.post(`${process.env.WORKSPACE_E2E_OVERLOAD_CONTROL}/${action}`, { headers: { 'X-Test-Key': process.env.WORKSPACE_E2E_OVERLOAD_KEY! } })).status()).toBe(204);
  }
  async function open(context: BrowserContext, email: string) {
    await context.addInitScript(() => {
      const send = WebSocket.prototype.send, observed = new WeakSet<WebSocket>();
      const hooks = window as unknown as { overloadClosed: number[]; overloadConnections: number; overloadReplayPages: number };
      hooks.overloadClosed = []; hooks.overloadConnections = 0; hooks.overloadReplayPages = 0;
      WebSocket.prototype.send = function (data: string | ArrayBufferLike | Blob | ArrayBufferView) {
        if (this.url.includes('/socket') && !observed.has(this)) {
          observed.add(this); hooks.overloadConnections++;
          this.addEventListener('close', event => hooks.overloadClosed.push(event.code));
          this.addEventListener('message', event => { if (typeof event.data === 'string' && (JSON.parse(event.data) as { type: string }).type === 'SYNC_PAGE') hooks.overloadReplayPages++; });
        }
        send.call(this, data);
      };
    });
    const page = await context.newPage(); await page.goto('/'); await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill('browser-test-password'); await page.getByRole('button', { name: /Continue/ }).click();
    await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
    await page.getByRole('button', { name: /Recovery notes/ }).click();
    const editor = page.getByLabel('Document text'); await expect(editor).not.toHaveAttribute('readonly', { timeout: 20000 });
    return { page, editor };
  }
  try {
    const [a, b] = await Promise.all([open(alice, process.env.WORKSPACE_E2E_ALICE!), open(bob, process.env.WORKSPACE_E2E_BOB!)]);
    await a.editor.fill('Baseline'); await expect(b.editor).toHaveValue('Baseline', { timeout: 15000 });
    const pages = await b.page.evaluate(() => (window as unknown as { overloadReplayPages: number }).overloadReplayPages);
    await control('arm'); await a.editor.fill('Committed before overload');
    await expect(a.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await control('overflow');
    await expect.poll(() => b.page.evaluate(() => (window as unknown as { overloadClosed: number[] }).overloadClosed)).toContain(4500);
    // The healthy editor remains usable while the rejected consumer reconnects.
    await a.editor.fill('Healthy writer continues');
    await expect(a.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await expect(b.editor).toHaveValue('Healthy writer continues', { timeout: 20000 });
    await expect.poll(() => b.page.evaluate(() => (window as unknown as { overloadConnections: number }).overloadConnections)).toBeGreaterThanOrEqual(2);
    await expect.poll(() => b.page.evaluate(() => (window as unknown as { overloadReplayPages: number }).overloadReplayPages)).toBeGreaterThan(pages);
    await expect(b.editor).not.toHaveAttribute('readonly');
    await b.editor.fill('Recovered editor can save'); await expect(a.editor).toHaveValue('Recovered editor can save', { timeout: 15000 });
    await expect(b.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
  } finally { await alice.close(); await bob.close(); }
});
