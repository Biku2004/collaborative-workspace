import { expect, test } from '@playwright/test';

test('presence arriving between native selection movement and selectionchange preserves text', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async () => {
    const host = document.createElement('div'); host.id = 'presence-boundary'; document.body.append(host);
    const path = '/test/rich-presence-harness.tsx';
    const harness = await import(/* @vite-ignore */ path) as typeof import('./rich-presence-harness');
    harness.mount(host);
  });
  const editor = page.getByRole('textbox', { name: 'Document text' });
  await editor.fill('Hello'); await editor.press('Control+A');
  await page.evaluate(async () => {
    const path = '/test/rich-presence-harness.tsx';
    const harness = await import(/* @vite-ignore */ path) as typeof import('./rich-presence-harness');
    harness.insertAtNativeEnd(document.getElementById('presence-boundary')!);
  });
  await expect(editor).toHaveText('Hello!');
});

test('presence redraw waits for a delayed native selectionchange', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async () => {
    const host = document.createElement('div'); host.id = 'delayed-presence-boundary'; document.body.append(host);
    const path = '/test/rich-presence-harness.tsx';
    const harness = await import(/* @vite-ignore */ path) as typeof import('./rich-presence-harness'); harness.mount(host);
  });
  const editor = page.getByRole('textbox', { name: 'Document text' });
  await editor.fill('Hello'); await editor.press('Control+A');
  await page.evaluate(async () => {
    const path = '/test/rich-presence-harness.tsx';
    const harness = await import(/* @vite-ignore */ path) as typeof import('./rich-presence-harness');
    await harness.insertAtDelayedNativeEnd(document.getElementById('delayed-presence-boundary')!);
  });
  await expect(editor).toHaveText('Hello!');
});

test('isolated rich-text surfaces apply supported formatting and viewer mode', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async () => {
    const host = document.createElement('div'); host.id = 'rich-harness'; document.body.append(host);
    const path = '/test/rich-surface-harness.tsx';
    const module = await import(/* @vite-ignore */ path) as typeof import('./rich-surface-harness');
    module.mountRichSurfaceHarness(host);
  });
  const left = page.getByTestId('left'), right = page.getByTestId('right');
  const editor = left.getByRole('textbox', { name: 'Document text' });
  const replica = right.getByRole('textbox', { name: 'Document text' });
  await expect(editor).toBeVisible(); await expect(replica).toBeVisible();
  await editor.fill('Hello'); await editor.press('Control+A');
  await left.getByRole('button', { name: 'Bold', exact: true }).click();
  await expect(replica.locator('strong')).toHaveText('Hello');
  await editor.press('Control+A');
  await left.getByRole('button', { name: 'Italic', exact: true }).click();
  await expect(replica.locator('em')).toHaveText('Hello');
  await left.getByRole('button', { name: 'Heading 2', exact: true }).click();
  await expect(replica.locator('h2')).toHaveText('Hello');
  await editor.press('End'); await editor.press('Enter');
  await left.getByRole('button', { name: 'Bullet list', exact: true }).click();
  await editor.pressSequentially('Item');
  await expect(replica.locator('ul li')).toContainText('Item');
  await left.getByRole('button', { name: 'Numbered list', exact: true }).click();
  await expect(replica.locator('ol li')).toContainText('Item');
  await page.getByRole('button', { name: 'Toggle viewer' }).click();
  await expect(replica).toHaveAttribute('contenteditable', 'false');
  await expect(replica).toHaveAttribute('aria-readonly', 'true');
  await expect(right.getByRole('button', { name: 'Bold', exact: true })).toBeDisabled();
  await expect(editor).toHaveAttribute('contenteditable', 'true');
});
