import { expect, test } from '@playwright/test';

test('named rich revisions preserve their preview after later live edits', async ({ page }) => {
  test.skip(process.env.WORKSPACE_E2E_EDITING !== 'true', 'Requires the real local Yjs engine');
  test.setTimeout(60000);
  await page.addInitScript(() => {
    const send = WebSocket.prototype.send;
    let blocked = false;
    const held: { socket: WebSocket; data: string }[] = [];
    const hooks = window as unknown as { holdRevisionUpdates: () => void; releaseRevisionUpdates: () => void };
    hooks.holdRevisionUpdates = () => { blocked = true; };
    hooks.releaseRevisionUpdates = () => { blocked = false; for (const item of held.splice(0)) send.call(item.socket, item.data); };
    WebSocket.prototype.send = function (data) {
      if (blocked && typeof data === 'string' && JSON.parse(data).type === 'SUBMIT_UPDATE') { held.push({ socket: this, data }); return; }
      send.call(this, data);
    };
  });
  await page.goto('/');
  await page.getByLabel('Email').fill(process.env.WORKSPACE_E2E_ALICE!);
  await page.getByLabel('Password').fill('browser-test-password');
  await page.getByRole('button', { name: /Continue/ }).click();
  await page.getByRole('button', { name: new RegExp(process.env.WORKSPACE_E2E_SHARED_NAME!) }).click();
  await page.getByLabel('New rich-text document').fill('Revision notes');
  await page.getByRole('button', { name: 'Create document', exact: true }).click();
  await page.getByRole('button', { name: /Revision notes/ }).click();
  const live = page.getByRole('textbox', { name: 'Document text' });
  await expect(live).toHaveAttribute('contenteditable', 'true', { timeout: 15000 });
  await page.evaluate(() => (window as unknown as { holdRevisionUpdates: () => void }).holdRevisionUpdates());
  await live.fill('Revision original'); await live.press('Control+A');
  await page.getByRole('button', { name: 'Bold', exact: true }).click();
  await expect(page.locator('.editor-status')).toHaveText('Saving…');
  let releaseList!: () => void;
  const holdList = new Promise<void>(resolve => { releaseList = resolve; });
  await page.route('**/revisions', async route => {
    if (route.request().method() !== 'GET') { await route.continue(); return; }
    const response = await route.fetch(); await holdList; await route.fulfill({ response });
  });
  await page.getByRole('button', { name: 'Named revisions', exact: true }).click();
  const revisions = page.getByRole('region', { name: 'Named revisions', exact: true });
  await expect(revisions).toContainText('Pending local edits may be excluded.');
  try { await expect(revisions.getByRole('button', { name: 'Save revision', exact: true })).toBeDisabled(); }
  finally { releaseList(); }
  await expect(revisions.getByLabel('Revision name')).toBeEnabled();
  await page.unroute('**/revisions');
  await revisions.getByLabel('Revision name').fill('Before pending edits');
  await revisions.getByRole('button', { name: 'Save revision', exact: true }).click();
  await expect(revisions.getByRole('status')).toContainText('committed sequence 1.');
  await page.evaluate(() => (window as unknown as { releaseRevisionUpdates: () => void }).releaseRevisionUpdates());
  await expect(page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
  await revisions.getByLabel('Revision name').fill('Milestone');
  await revisions.getByRole('button', { name: 'Save revision', exact: true }).click();
  await expect(revisions.getByRole('status')).toContainText('Saved revision at committed sequence');
  await live.focus(); await live.press('Control+End'); await live.pressSequentially(' newer');
  await expect(live).toHaveText('Revision original newer');
  await expect(page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
  await revisions.getByRole('button', { name: /Milestone — sequence/ }).click();
  const preview = revisions.getByRole('region', { name: 'Revision preview' });
  await expect(preview.getByRole('textbox')).toHaveAttribute('contenteditable', 'false');
  await expect(preview.locator('strong')).toHaveText('Revision original');
  await expect(preview.getByRole('textbox')).toHaveText('Revision original');
  await expect(page.getByRole('textbox', { name: 'Document text' }).first()).toHaveText('Revision original newer');
});
