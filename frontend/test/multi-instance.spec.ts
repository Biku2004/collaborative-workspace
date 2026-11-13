import { expect, test, type BrowserContext } from '@playwright/test';

test('two backend instances deliver edits and presence, repair dropped messages and survive Redis outage', async ({ browser, request }) => {
  test.skip(!process.env.WORKSPACE_E2E_ORIGIN_B, 'Requires MultiInstanceIntegrationTest');
  test.setTimeout(120000);
  const alice = await browser.newContext({ baseURL: process.env.WORKSPACE_E2E_ORIGIN });
  const bob = await browser.newContext({ baseURL: process.env.WORKSPACE_E2E_ORIGIN_B });
  async function control(action: string) {
    expect((await request.post(`${process.env.WORKSPACE_E2E_CONTROL}/${action}`, { headers: { 'X-Test-Key': process.env.WORKSPACE_E2E_CONTROL_KEY! } })).status()).toBe(204);
  }
  async function open(context: BrowserContext, email: string) {
    const page = await context.newPage();
    const received: string[] = [];
    page.on('websocket', socket => socket.on('framereceived', event => {
      if (typeof event.payload === 'string') received.push((JSON.parse(event.payload) as { type: string }).type);
    }));
    await page.goto('/'); await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill('browser-test-password');
    await page.getByRole('button', { name: /Continue/ }).click();
    await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
    await page.getByRole('button', { name: /Cluster notes/ }).click();
    const editor = page.getByLabel('Document text');
    await expect(editor).not.toHaveAttribute('readonly', { timeout: 20000 });
    return { page, editor, received };
  }
  try {
    const [a, b] = await Promise.all([open(alice, process.env.WORKSPACE_E2E_ALICE!), open(bob, process.env.WORKSPACE_E2E_BOB!)]);
    // Login is process-local: copying A's cookie to B cannot authenticate against B.
    const isolated = await browser.newContext({ baseURL: process.env.WORKSPACE_E2E_ORIGIN_B });
    await isolated.addCookies(await alice.cookies());
    expect((await isolated.request.get('/api/me')).status()).toBe(401);
    await isolated.close();

    await a.editor.fill('Across nodes'); await expect(b.editor).toHaveValue('Across nodes', { timeout: 15000 });
    await expect.poll(() => b.received.filter(type => type === 'UPDATE').length).toBeGreaterThan(0);
    await b.editor.fill('Both directions'); await expect(a.editor).toHaveValue('Both directions', { timeout: 15000 });
    await a.editor.focus(); await a.editor.press('Control+Home'); await a.editor.press('ArrowRight');
    await expect(b.page.getByLabel('Collaborators')).toContainText(`${process.env.WORKSPACE_E2E_ALICE_ID!})`, { timeout: 15000 });
    await expect(b.page.getByLabel('Collaborators')).toContainText('cursor at 1', { timeout: 15000 });

    // Remove only B's subscriber. The durable HEAD/SYNC repair must work without UPDATE.
    await control('drop');
    const updates = b.received.filter(type => type === 'UPDATE').length;
    const pages = b.received.filter(type => type === 'SYNC_PAGE').length;
    await a.editor.fill('Missed notification repaired');
    await expect(b.editor).toHaveValue('Missed notification repaired', { timeout: 15000 });
    expect(b.received.filter(type => type === 'UPDATE').length).toBe(updates);
    expect(b.received.filter(type => type === 'SYNC_PAGE').length).toBeGreaterThan(pages);
    await control('resume');
    const duplicates = b.received.filter(type => type === 'UPDATE').length;
    await control('duplicate');
    await expect.poll(() => b.received.filter(type => type === 'UPDATE').length).toBeGreaterThanOrEqual(duplicates + 2);
    await expect(b.editor).toHaveValue('Missed notification repaired');

    await control('block');
    await a.editor.fill('Saved while Redis is unavailable');
    await expect(a.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await expect(b.editor).toHaveValue('Saved while Redis is unavailable', { timeout: 15000 });
    await control('pending');
    // A lost access-change hint cannot leave an old editor writable on B.
    const csrf = await (await alice.request.get('/api/csrf')).json() as { token: string; headerName: string };
    expect((await alice.request.post(`/api/workspaces/${process.env.WORKSPACE_E2E_SHARED_WORKSPACE}/members`, {
      headers: { [csrf.headerName]: csrf.token }, data: { email: process.env.WORKSPACE_E2E_BOB!, role: 'VIEWER' },
    })).status()).toBe(200);
    await expect(b.page.locator('.editor-status')).toContainText('Access was denied or revoked', { timeout: 15000 });
    await expect(b.editor).toHaveAttribute('readonly');
    await control('restore');
  } finally {
    await control('restore'); await alice.close(); await bob.close();
  }
});
