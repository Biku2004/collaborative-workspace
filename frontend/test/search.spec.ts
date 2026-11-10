import { expect, test, type BrowserContext, type Page } from '@playwright/test';

async function signIn(context: BrowserContext, email: string) {
  const page = await context.newPage(); await page.goto('/'); await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('browser-test-password'); await page.getByRole('button', { name: /Continue/ }).click(); return page;
}
async function search(page: Page, text: string) {
  const panel = page.getByRole('region', { name: 'Search documents', exact: true });
  await panel.getByLabel('Search committed text').fill(text); await panel.getByRole('button', { name: 'Search', exact: true }).click(); return panel;
}

test('scheduled search indexes committed text, opens results, rebuilds and rejects revoked access', async ({ browser, baseURL }) => {
  test.setTimeout(90000);
  const alice = await browser.newContext({ baseURL }), bob = await browser.newContext({ baseURL });
  try {
    const a = await signIn(alice, process.env.WORKSPACE_E2E_ALICE!);
    await a.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_ALICE_NAME!) }).click();
    await a.getByLabel('New rich-text document').fill('Searchable notes');
    const [response] = await Promise.all([a.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/documents')), a.getByRole('button', { name: 'Create document', exact: true }).click()]);
    const document = await response.json() as { id: string; workspaceId: string };
    await a.getByRole('button', { name: /Searchable notes/ }).click();
    const editor = a.getByRole('textbox', { name: 'Document text' }); await expect(editor).toHaveAttribute('contenteditable', 'true', { timeout: 15000 });
    await editor.fill('Orion contains searchable committed content.'); await expect(a.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
    await a.getByRole('button', { name: /Back to documents/ }).click();
    const panel = await search(a, 'Orion');
    await expect(panel.getByRole('button', { name: 'Searchable notes', exact: true })).toBeVisible({ timeout: 20000 });
    await expect(panel.getByTestId('search-coverage')).toContainText('latest version');
    await expect(panel).not.toContainText('Search indexing is paused');
    await panel.getByRole('button', { name: 'Searchable notes', exact: true }).click(); await expect(editor).toHaveText('Orion contains searchable committed content.');
    await a.getByRole('button', { name: /Back to documents/ }).click(); const again = await search(a, 'Orion');
    await expect(again.getByRole('button', { name: 'Rebuild search index' })).toBeEnabled();
    const [rebuilt] = await Promise.all([a.waitForResponse(r => r.url().endsWith('/search/rebuild')), again.getByRole('button', { name: 'Rebuild search index' }).click()]);
    expect(rebuilt.status()).toBe(202); await expect(again).toContainText('Rebuild requested.');
    await expect(again.getByRole('button', { name: 'Searchable notes', exact: true })).toBeVisible({ timeout: 20000 });
    const token = await (await alice.request.get('/api/csrf')).json() as { headerName: string; token: string };
    const members = `/api/workspaces/${document.workspaceId}/members`;
    expect((await alice.request.post(members, { headers: { [token.headerName]: token.token }, data: { email: process.env.WORKSPACE_E2E_BOB!, role: 'VIEWER' } })).status()).toBe(200);
    const b = await signIn(bob, process.env.WORKSPACE_E2E_BOB!); await b.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_ALICE_NAME!) }).click();
    const viewer = await search(b, 'Orion'); await expect(viewer.getByRole('button', { name: 'Searchable notes', exact: true })).toBeVisible();
    await expect(viewer.getByRole('button', { name: 'Rebuild search index' })).toHaveCount(0);
    expect((await alice.request.delete(`${members}/${process.env.WORKSPACE_E2E_BOB_ID!}`, { headers: { [token.headerName]: token.token } })).status()).toBe(204);
    await expect(viewer.getByRole('button', { name: 'Refresh results' })).toBeEnabled(); await viewer.getByRole('button', { name: 'Refresh results' }).click();
    await expect(viewer.getByRole('alert')).toContainText('unavailable'); await expect(viewer.getByRole('button', { name: 'Searchable notes', exact: true })).toHaveCount(0);
    expect((await bob.request.get(`/api/workspaces/${document.workspaceId}/search?q=Orion`)).status()).toBe(404);
    expect((await bob.request.get(`/api/documents/${document.id}`)).status()).toBe(404);
  } finally { await alice.close(); await bob.close(); }
});

test('search renders stale and paused results as text and clears them on denial', async ({ page }) => {
  const workspace = '20000000-0000-4000-8000-000000000081', user = '10000000-0000-4000-8000-000000000081';
  await page.route('**/api/me', r => r.fulfill({ json: { id: user, username: 'fixture@example.com' } }));
  await page.route('**/api/workspaces', r => r.fulfill({ json: [{ id: workspace, name: 'Search fixture', role: 'OWNER' }] }));
  await page.route('**/documents', r => r.fulfill({ json: { items: [], nextCursor: null } }));
  let denied = false;
  await page.route('**/search?*', r => denied ? r.fulfill({ status: 403, json: {} }) : r.fulfill({ json: {
    indexingEnabled: false, hasMore: true, coverage: { totalDocuments: 2, currentDocuments: 1, pendingDocuments: 1 },
    items: [{ documentId: '30000000-0000-4000-8000-000000000081', title: '<img src=x onerror=alert(1)>', excerpt: '<script>literal text</script>',
      format: 'PLAIN_TEXT_V1', epoch: 0, throughSequence: 1, head: 2, indexedAt: '2026-11-10T00:00:00Z', stale: true }],
  } }));
  await page.goto('/'); await page.getByRole('button', { name: /Search fixture/ }).click(); const panel = await search(page, 'literal');
  await expect(panel).toContainText('1 waiting; results may be incomplete.'); await expect(panel).toContainText('Behind current sequence 2');
  await expect(panel).toContainText('Search indexing is paused.'); await expect(panel).toContainText('Showing the top 20');
  await expect(panel.getByRole('button', { name: 'Rebuild search index' })).toBeDisabled();
  await expect(panel.locator('img,script')).toHaveCount(0); await expect(panel).toContainText('<script>literal text</script>');
  denied = true; await panel.getByRole('button', { name: 'Refresh results' }).click();
  await expect(panel.getByRole('alert')).toContainText('permission'); await expect(panel.locator('.search-results')).toHaveCount(0);
});
