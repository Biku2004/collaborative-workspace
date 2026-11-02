import { expect, request as playwrightRequest, test } from '@playwright/test';

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

test('stored-user login, isolated lists, pagination, revocation and logout', async ({ page, baseURL }) => {
  const alice = required('WORKSPACE_E2E_ALICE'), bob = required('WORKSPACE_E2E_BOB');
  const aliceId = required('WORKSPACE_E2E_ALICE_ID'), bobWorkspace = required('WORKSPACE_E2E_BOB_WORKSPACE');
  const aliceWorkspaceName = required('WORKSPACE_E2E_ALICE_NAME'), bobWorkspaceName = required('WORKSPACE_E2E_BOB_NAME');
  const password = 'browser-test-password';
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => { errors.push('Unexpected dialog'); void dialog.dismiss(); });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
  await page.getByLabel('Email').fill(alice);
  await page.getByLabel('Password').fill('wrong-password');
  await page.getByRole('button', { name: /Continue/ }).click();
  await expect(page.getByRole('alert')).toContainText('Incorrect email or password');
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: /Continue/ }).click();
  await expect(page.getByText(alice, { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText(alice, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: new RegExp(aliceWorkspaceName) })).toBeVisible();
  await page.getByRole('button', { name: new RegExp(aliceWorkspaceName) }).click();
  await expect(page.locator('.document-list li')).toHaveCount(20);
  await page.getByRole('button', { name: /Load more documents/ }).click();
  await expect(page.locator('.document-list li')).toHaveCount(21);
  await expect(page.getByText('<img src=x onerror=alert(1)>', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/workspace-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('heading', { name: 'Documents', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/workspace-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.getByRole('button', { name: new RegExp(bobWorkspaceName) }).click();
  await expect(page.getByText('Private report', { exact: true })).toBeVisible();

  // A separate owner session removes Alice while her browser is still signed in.
  const owner = await playwrightRequest.newContext({ baseURL });
  try {
    const csrf = await (await owner.get('/api/csrf')).json() as { token: string; headerName: string };
    const response = await owner.post('/login', { form: { username: bob, password }, headers: { [csrf.headerName]: csrf.token }, maxRedirects: 0 });
    expect(response.status()).toBe(302);
    const fresh = await (await owner.get('/api/csrf')).json() as { token: string; headerName: string };
    const removed = await owner.delete(`/api/workspaces/${bobWorkspace}/members/${aliceId}`, { headers: { [fresh.headerName]: fresh.token } });
    expect(removed.status()).toBe(204);
  } finally { await owner.dispose(); }
  await page.getByRole('button', { name: new RegExp(aliceWorkspaceName) }).click();
  await expect(page.getByRole('button', { name: /Load more documents/ })).toBeVisible();
  await page.getByRole('button', { name: new RegExp(bobWorkspaceName) }).click();
  await expect(page.getByRole('alert')).toContainText('unavailable or you no longer have access');

  await page.getByRole('button', { name: /Sign out/ }).click();
  await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
  await page.getByLabel('Email').fill(bob);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: /Continue/ }).click();
  await expect(page.getByRole('button', { name: new RegExp(bobWorkspaceName) })).toBeVisible();
  await expect(page.getByRole('button', { name: new RegExp(aliceWorkspaceName) })).toHaveCount(0);
  await page.getByRole('button', { name: new RegExp(bobWorkspaceName) }).click();
  await expect(page.getByText('Private report', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: /Sign out/ }).click();
  await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});
