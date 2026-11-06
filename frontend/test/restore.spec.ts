import { expect, test, type BrowserContext } from '@playwright/test';

test('restored rich epochs quarantine offline drafts and allow deliberate manual recovery', async ({ browser, baseURL }) => {
  test.skip(process.env.WORKSPACE_E2E_EDITING !== 'true', 'Requires real engine and database');
  test.setTimeout(90000);
  const alice = await browser.newContext({ baseURL }), bob = await browser.newContext({ baseURL });
  const errors: string[] = [];
  async function login(context: BrowserContext, email: string) {
    const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
    await page.goto('/'); await page.getByLabel('Email').fill(email); await page.getByLabel('Password').fill('browser-test-password');
    await page.getByRole('button', { name: /Continue/ }).click();
    await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click(); return page;
  }
  try {
    const a = await login(alice, process.env.WORKSPACE_E2E_ALICE!);
    await a.getByLabel('New rich-text document').fill('Restore notes');
    await a.getByRole('button', { name: 'Create document', exact: true }).click();
    await a.getByRole('button', { name: /Restore notes/ }).click();
    const liveA = a.locator('.editor > .rich-editor [role="textbox"]');
    await expect(liveA).toHaveAttribute('contenteditable', 'true', { timeout: 15000 });
    await liveA.fill('Original'); await liveA.press('Control+A'); await a.getByRole('button', { name: 'Bold', exact: true }).click();
    await expect(a.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await a.getByRole('button', { name: 'Named revisions', exact: true }).click();
    const revisions = a.getByRole('region', { name: 'Named revisions', exact: true });
    await expect(revisions.getByLabel('Revision name')).toBeEnabled(); await revisions.getByLabel('Revision name').fill('Original revision');
    await revisions.getByRole('button', { name: 'Save revision', exact: true }).click();
    await expect(revisions.getByRole('status')).toContainText('Saved revision');
    await liveA.fill('Later committed content');
    await expect(a.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    // Earlier revocation scenarios intentionally remove Bob from this shared fixture.
    const csrf = await (await alice.request.get('/api/csrf')).json() as { token: string; headerName: string };
    expect((await alice.request.post(`/api/workspaces/${process.env.WORKSPACE_E2E_SHARED_WORKSPACE!}/members`, {
      headers: { [csrf.headerName]: csrf.token }, data: { email: process.env.WORKSPACE_E2E_BOB!, role: 'EDITOR' },
    })).status()).toBe(200);
    const b = await login(bob, process.env.WORKSPACE_E2E_BOB!); await b.getByRole('button', { name: /Restore notes/ }).click();
    const liveB = b.locator('.editor > .rich-editor [role="textbox"]');
    await expect(liveB).toHaveText('Later committed content', { timeout: 15000 });
    await bob.setOffline(true); await expect(b.locator('.editor-status')).toContainText('Offline');
    await liveB.focus(); await liveB.press('Control+End'); await liveB.pressSequentially(' offline-only');
    await expect(b.locator('.editor-status')).toContainText('Saved locally');
    await revisions.getByRole('button', { name: /Original revision — sequence/ }).click();
    await expect(revisions.getByRole('region', { name: 'Revision preview' }).locator('strong')).toHaveText('Original');
    await revisions.getByRole('button', { name: 'Review restore', exact: true }).click();
    await expect(revisions.getByRole('region', { name: 'Confirm revision restore' })).toContainText('epoch 0');
    await revisions.getByRole('button', { name: 'Restore this revision', exact: true }).click();
    await expect(a.locator('.editor-status')).toContainText('quarantined');
    await a.getByRole('button', { name: 'Keep old draft and open restored version' }).click();
    await expect(liveA).toHaveText('Original', { timeout: 15000 }); await expect(liveA.locator('strong')).toHaveText('Original');
    await expect(liveA).toHaveAttribute('contenteditable', 'true');
    await bob.setOffline(false);
    await expect(b.locator('.editor-status')).toContainText('quarantined', { timeout: 15000 });
    await expect(liveB).toHaveText('Later committed content offline-only'); await expect(liveB).toHaveAttribute('contenteditable', 'false');
    await b.getByRole('button', { name: 'Keep old draft and open restored version' }).click();
    await expect(liveB).toHaveText('Original', { timeout: 15000 }); await expect(liveB).toHaveAttribute('contenteditable', 'true');
    await expect(b.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await b.reload();
    await b.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
    await b.getByRole('button', { name: /Restore notes/ }).click();
    await expect(liveB).toHaveText('Original', { timeout: 15000 });
    await b.getByRole('button', { name: 'View previous local draft' }).click();
    const draft = b.getByRole('region', { name: 'Previous local draft', exact: true });
    await expect(draft.getByRole('textbox')).toHaveText('Later committed content offline-only');
    await expect(draft.getByRole('textbox')).toHaveAttribute('contenteditable', 'false');
    // A user deliberately selects just the desired fragment from the separate read-only draft.
    const recovered = (await draft.getByRole('textbox').innerText()).match(/offline-only/)![0];
    await liveB.focus(); await liveB.press('Control+End'); await liveB.pressSequentially(` ${recovered}`);
    await expect(liveA).toHaveText('Original offline-only', { timeout: 15000 });
    await expect(b.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await expect(liveA).not.toContainText('Later committed content');
    expect(errors).toEqual([]);
  } finally { await alice.close(); await bob.close(); }
});
