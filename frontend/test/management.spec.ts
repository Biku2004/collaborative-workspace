import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

const env = (name: string) => { const value = process.env[`WORKSPACE_E2E_${name}`]; if (!value) throw new Error(name); return value; };
async function login(page: Page, email: string) {
  await page.goto('/'); await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password').fill('browser-test-password');
  await page.getByRole('button', { name: /Continue/ }).click();
  await expect(page.getByRole('button', { name: /Sign out/ })).toBeVisible();
}
async function headers(request: APIRequestContext) {
  const token = await (await request.get('/api/csrf')).json(); return { [token.headerName]: token.token };
}

test('management: empty account, membership pagination, conflicts and live revocation', async ({ page, browser, baseURL }) => {
  test.setTimeout(90000);
  test.skip(process.env.WORKSPACE_E2E_EDITING !== 'true', 'Requires the private Yjs engine');
  const editorContext = await browser.newContext({ baseURL }), viewerContext = await browser.newContext({ baseURL });
  const editor = await editorContext.newPage(), viewer = await viewerContext.newPage();
  page.on('dialog', dialog => void dialog.accept());
  editor.on('dialog', dialog => void dialog.accept());
  try {
    await login(page, env('MANAGER'));
    await expect(page.getByText('No workspaces yet.', { exact: true })).toBeVisible();
    await page.getByLabel('New workspace', { exact: true }).fill('   ');
    await page.getByRole('button', { name: 'Create workspace', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Enter a workspace name');
    const name = `Management-${Date.now()}`;
    await page.getByLabel('New workspace', { exact: true }).fill(name);
    // Lose a committed response; the retry must select the same workspace.
    let loseResponse = true;
    await page.route('**/api/workspaces', async route => {
      if (route.request().method() !== 'POST' || !loseResponse) { await route.continue(); return; }
      loseResponse = false; await route.fetch(); await route.abort('failed');
    });
    await page.getByRole('button', { name: 'Create workspace', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Cannot reach');
    await page.getByRole('button', { name: 'Create workspace', exact: true }).click();
    await expect(page.getByRole('button', { name: new RegExp(name) })).toHaveCount(1);
    const workspaces = await (await page.request.get('/api/workspaces')).json();
    expect(workspaces).toHaveLength(1);
    const id = workspaces[0].id as string, path = `/api/workspaces/${id}`;
    await page.getByRole('button', { name: 'Manage members', exact: true }).click();
    const panel = page.getByRole('region', { name: 'Workspace members', exact: true });
    await expect(panel.getByRole('listitem')).toHaveCount(1);
    await panel.getByLabel('Existing account email').fill('missing-account@example.invalid');
    await panel.getByRole('button', { name: 'Add or update member' }).click();
    await expect(panel.getByRole('alert')).toContainText('existing account could not be found');
    await panel.getByLabel(`Role for ${env('MANAGER')}`, { exact: true }).selectOption('VIEWER');
    await panel.getByRole('button', { name: `Save role for ${env('MANAGER')}`, exact: true }).click();
    await expect(panel.getByRole('alert')).toContainText('at least one owner');
    await panel.getByRole('button', { name: `Remove ${env('MANAGER')}`, exact: true }).click();
    await expect(panel.getByRole('alert')).toContainText('at least one owner');
    for (const [account, role] of [[env('ALICE'), 'EDITOR'], [env('BOB'), 'VIEWER']]) {
      await panel.getByLabel('Existing account email').fill(account!);
      await panel.getByLabel('New member role').selectOption(role!);
      await panel.getByRole('button', { name: 'Add or update member' }).click();
      await expect(panel.getByLabel(`Role for ${account}`, { exact: true })).toHaveValue(role!);
    }
    const csrf = await headers(page.request);
    for (const email of env('EXTRA_MEMBERS').split(',')) {
      expect((await page.request.post(`${path}/members`, { headers: csrf, data: { email, role: 'VIEWER' } })).status()).toBe(200);
    }
    await panel.getByRole('button', { name: 'Refresh members' }).click();
    await expect(panel.getByRole('listitem')).toHaveCount(20);
    await panel.getByRole('button', { name: 'Load more members' }).click();
    await expect(panel.getByRole('listitem')).toHaveCount(22);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: 'test-results/management-mobile.png', fullPage: true });
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.getByLabel('New document', { exact: true }).fill('Managed document');
    await page.getByRole('button', { name: 'Create document', exact: true }).click();
    await expect(page.locator('.document-list li')).toHaveCount(1);
    const document = (await (await page.request.get(`${path}/documents`)).json()).items[0];
    const documentPath = `${path}/documents/${document.id}`;
    await login(editor, env('ALICE')); await login(viewer, env('BOB'));
    for (const tab of [editor, viewer]) await tab.getByRole('button', { name: new RegExp(name) }).click();
    await expect(viewer.getByRole('button', { name: 'Document actions', exact: true })).toHaveCount(0);
    await expect(editor.getByRole('button', { name: 'Manage members', exact: true })).toHaveCount(0);
    expect((await viewer.request.patch(documentPath, { headers: await headers(viewer.request), data: { title: 'forbidden', version: document.version } })).status()).toBe(403);
    expect((await viewer.request.post(`${documentPath}/archive`, { headers: await headers(viewer.request), data: { version: document.version } })).status()).toBe(403);
    expect((await editor.request.post(`${path}/members`, { headers: await headers(editor.request), data: { email: env('BOB'), role: 'OWNER' } })).status()).toBe(403);
    await page.getByRole('button', { name: 'Document actions', exact: true }).click();
    await page.getByLabel('Rename document', { exact: true }).fill('My retained proposal');
    await editor.getByRole('button', { name: 'Document actions', exact: true }).click();
    await editor.getByLabel('Rename document', { exact: true }).fill('Editor renamed');
    await editor.getByRole('button', { name: 'Save title', exact: true }).click();
    await expect(editor.locator('.document-name')).toContainText('Editor renamed');
    await page.getByRole('button', { name: 'Save title', exact: true }).click();
    await expect(page.getByLabel('Rename document', { exact: true })).toHaveValue('My retained proposal');
    await expect(page.getByRole('button', { name: 'Save title', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: 'Load latest version', exact: true }).click();
    await expect(page.getByLabel('Rename document', { exact: true })).toHaveValue('Editor renamed');
    await page.getByLabel('Rename document', { exact: true }).fill('Final title');
    await page.getByRole('button', { name: 'Save title', exact: true }).click();
    await expect(page.locator('.document-name')).toContainText('Final title');
    await editor.getByLabel('New document', { exact: true }).fill('Editor archive example');
    await editor.getByRole('button', { name: 'Create document', exact: true }).click();
    const archiveRow = editor.locator('.document-list li').filter({ hasText: 'Editor archive example' });
    await archiveRow.getByRole('button', { name: 'Document actions', exact: true }).click();
    await archiveRow.getByRole('button', { name: 'Archive document', exact: true }).click();
    await expect(archiveRow).toHaveCount(0);
    await editor.locator('.document-button').click();
    await page.locator('.document-button').click();
    await editor.getByLabel('Document text', { exact: true }).fill('Collaborative content');
    await expect(page.getByLabel('Document text', { exact: true })).toHaveValue('Collaborative content');
    await expect(editor.locator('.editor-status')).toHaveText(/Saved|Up to date/);
    await page.getByRole('button', { name: /Back to documents/ }).click();
    await expect(panel.getByRole('listitem')).toHaveCount(20);
    // Use the add/update form so the target need not be on the first UUID page.
    await panel.getByLabel('Existing account email').fill(env('ALICE'));
    await panel.getByLabel('New member role').selectOption('VIEWER');
    await panel.getByRole('button', { name: 'Add or update member' }).click();
    await expect(editor.getByLabel('Document text', { exact: true })).toHaveAttribute('readonly');
    await panel.getByRole('button', { name: `Remove ${env('ALICE')}`, exact: true }).click();
    await expect(panel.getByRole('button', { name: `Remove ${env('ALICE')}`, exact: true })).toHaveCount(0);
    expect((await editor.request.get(`/api/documents/${document.id}`)).status()).toBe(404);
    expect((await editor.request.get(path)).status()).toBe(404);
    expect((await editor.request.get(`${path}/members`)).status()).toBe(404);
    await expect(editor.getByLabel('Document text', { exact: true })).toHaveValue('Collaborative content');
    await page.getByRole('button', { name: 'Document actions', exact: true }).click();
    await page.getByRole('button', { name: 'Archive document', exact: true }).click();
    await expect(page.locator('.document-list li')).toHaveCount(0);
    expect((await (await viewer.request.get(`/api/documents/${document.id}`)).json()).archived).toBe(true);
    await panel.getByLabel('Existing account email').fill(env('BOB'));
    await panel.getByLabel('New member role').selectOption('OWNER');
    await panel.getByRole('button', { name: 'Add or update member' }).click();
    await expect(panel.getByLabel(`Role for ${env('BOB')}`, { exact: true })).toHaveValue('OWNER');
    await panel.getByLabel('Existing account email').fill(env('MANAGER'));
    await panel.getByLabel('New member role').selectOption('VIEWER');
    await panel.getByRole('button', { name: 'Add or update member' }).click();
    await expect(page.getByRole('button', { name: 'Manage members', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Create document', exact: true })).toHaveCount(0);
    expect((await page.request.post(`${path}/members`, { headers: csrf, data: { email: env('ALICE'), role: 'OWNER' } })).status()).toBe(403);
    const ownRole = await page.request.get(path);
    expect(ownRole.headers()['cache-control']).toContain('no-store');
    expect((await ownRole.json()).role).toBe('VIEWER');
  } finally { await editorContext.close(); await viewerContext.close(); }
});
