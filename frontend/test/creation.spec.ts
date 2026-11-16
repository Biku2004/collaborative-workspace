import { expect, test } from '@playwright/test';

test('document creation fails closed on invalid capabilities and recovers after retry', async ({ page }) => {
  let invalid = true;
  await page.route('**/api/capabilities', async route => {
    if (invalid) await route.fulfill({ json: { version: 1, documentFormats: ['FUTURE_FORMAT'] } });
    else await route.continue();
  });
  await page.goto('/');
  await page.getByLabel('Email').fill(process.env.WORKSPACE_E2E_ALICE!);
  await page.getByLabel('Password').fill('browser-test-password');
  await page.getByRole('button', { name: /Continue/ }).click();
  await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_ALICE_NAME!) }).click();
  await expect(page.getByRole('button', { name: 'Create document', exact: true })).toBeDisabled();
  await expect(page.getByRole('alert')).toContainText('supported formats can be checked');
  invalid = false;
  await page.getByRole('button', { name: 'Retry available formats' }).click();
  await expect(page.getByRole('button', { name: 'Create document', exact: true })).toBeEnabled();
  await expect(page.getByLabel('Document format')).toHaveValue('PLAIN_TEXT_V1');
  await page.getByLabel('New document', { exact: true }).fill('   ');
  await page.getByRole('button', { name: 'Create document', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Enter a document title.');
});

test('creation retry IDs survive a failed attempt and change when the document format changes', async ({ page }) => {
  test.skip(process.env.WORKSPACE_E2E_EDITING !== 'true', 'Requires the rich-enabled browser fixture');
  const requests: { key: string; format: string }[] = [];
  await page.route('**/api/workspaces/*/documents', async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return; }
    requests.push({ key: route.request().headers()['idempotency-key']!, format: route.request().postDataJSON().format });
    await route.fulfill({ status: 503, json: { code: 'TEST_UNAVAILABLE' } });
  });
  await page.goto('/'); await page.getByLabel('Email').fill(process.env.WORKSPACE_E2E_ALICE!);
  await page.getByLabel('Password').fill('browser-test-password');
  await page.getByRole('button', { name: /Continue/ }).click();
  await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_ALICE_NAME!) }).click();
  await page.getByLabel('New document', { exact: true }).fill('Same title');
  const create = page.getByRole('button', { name: 'Create document', exact: true });
  for (let i = 0; i < 2; i++) {
    await create.click(); await expect.poll(() => requests.length).toBe(i + 1); await expect(create).toBeEnabled();
  }
  await page.getByLabel('Document format').selectOption('RICH_TEXT_V1');
  await create.click(); await expect.poll(() => requests.length).toBe(3); await expect(create).toBeEnabled();
  expect(requests[0]!.key).toBeTruthy(); expect(requests[1]!.key).toBe(requests[0]!.key);
  expect(requests[2]!.key).not.toBe(requests[0]!.key);
  expect(requests.map(value => value.format)).toEqual(['PLAIN_TEXT_V1', 'PLAIN_TEXT_V1', 'RICH_TEXT_V1']);
});
