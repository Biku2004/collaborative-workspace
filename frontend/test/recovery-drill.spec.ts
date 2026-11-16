import { expect, test, type APIRequestContext, type BrowserContext } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const phase = process.env.WORKSPACE_RECOVERY_PHASE;
const manifest = process.env.WORKSPACE_RECOVERY_MANIFEST!;
const password = 'recovery-fixture-password';
type Fixture = { owner: string; viewer: string; outsider: string; workspace: string; document: string; revision: string; checksum: string; sourceCookies: Awaited<ReturnType<BrowserContext['cookies']>> };
test('packaged workspace survives an isolated database restore with content and permissions intact', async ({ browser, request, baseURL }) => {
  test.skip(!phase, 'Run ops/recovery/verify-local.ps1');
  test.setTimeout(90000);
  async function login(api: APIRequestContext, email: string, secret = password) {
    const csrf = await (await api.get('/api/csrf')).json();
    expect((await api.post('/login', { form: { username: email, password: secret }, headers: { [csrf.headerName]: csrf.token }, maxRedirects: 0 })).status()).toBe(302);
  }
  async function post(api: APIRequestContext, url: string, data?: unknown, expected = 201) {
    const csrf = await (await api.get('/api/csrf')).json();
    let response = await api.post(url, { data, headers: { [csrf.headerName]: csrf.token } });
    // Engine work shares a bounded worker pool with indexing; retry only these safe history operations.
    for (let retry = 0; response.status() === 503 && retry < 5 && /\/(checkpoints|revisions)$/.test(url); retry++) {
      await new Promise(resolve => setTimeout(resolve, 250 * (retry + 1)));
      response = await api.post(url, { data, headers: { [csrf.headerName]: csrf.token } });
    }
    expect(response.status()).toBe(expected); return response.json();
  }
  async function open(context: BrowserContext, email: string, fixture: Fixture, openDocument = Boolean(fixture.document)) {
    await login(context.request, email);
    const page = await context.newPage(); await page.goto('/');
    await page.getByRole('button', { name: /Recovery workspace/ }).click();
    if (openDocument) await page.getByRole('button', { name: /Recovery document/ }).click();
    const editor = page.getByLabel('Document text');
    if (openDocument) await expect(editor).toBeVisible();
    return { page, editor };
  }
  expect((await request.get('/')).status()).toBe(200);
  expect((await request.get('/api/me')).status()).toBe(401);
  expect((await request.get('/api/capabilities')).status()).toBe(401);
  expect((await request.get('/actuator/metrics')).status()).toBe(401);
  const owner = await browser.newContext({ baseURL });
  try {
    let fixture: Fixture;
    if (phase === 'seed') {
      await login(request, 'learner', process.env.WORKSPACE_DEV_PASSWORD!);
      const suffix = randomUUID();
      fixture = { owner: `recovery-owner-${suffix}@example.com`, viewer: `recovery-viewer-${suffix}@example.com`, outsider: `recovery-outsider-${suffix}@example.com`, workspace: '', document: '', revision: '', checksum: '', sourceCookies: [] };
      for (const email of [fixture.owner, fixture.viewer, fixture.outsider]) await post(request, '/api/dev/users', { email, password });
      await login(owner.request, fixture.owner);
      const capabilities = await owner.request.get('/api/capabilities');
      expect(capabilities.headers()['cache-control']).toContain('no-store');
      expect(await capabilities.json()).toEqual({ version: 1, documentFormats: ['PLAIN_TEXT_V1'] });
      fixture.workspace = (await post(owner.request, '/api/workspaces', { name: 'Recovery workspace' })).id;
      await post(owner.request, `/api/workspaces/${fixture.workspace}/members`, { email: fixture.viewer, role: 'VIEWER' }, 200);
      // Exercise the supported default packaged UI, including a lost response after server commit.
      const ui = await open(owner, fixture.owner, fixture);
      await expect(ui.page.getByLabel('Document format')).toBeEnabled();
      await expect(ui.page.getByLabel('Document format')).toHaveValue('PLAIN_TEXT_V1');
      await expect(ui.page.locator('option[value="RICH_TEXT_V1"]')).toBeDisabled();
      const keys: string[] = [];
      let loseResponse = true;
      await ui.page.route(`**/api/workspaces/${fixture.workspace}/documents`, async route => {
        if (route.request().method() !== 'POST') { await route.continue(); return; }
        keys.push(route.request().headers()['idempotency-key']!);
        expect(route.request().postDataJSON().format).toBe('PLAIN_TEXT_V1');
        const response = await route.fetch();
        if (loseResponse) { loseResponse = false; fixture.document = (await response.json()).id; await route.fulfill({ status: 503, json: { code: 'TEST_LOST_RESPONSE' } }); }
        else await route.fulfill({ response });
      });
      await ui.page.getByLabel('New document', { exact: true }).fill('Recovery document');
      await ui.page.getByRole('button', { name: 'Create document', exact: true }).click();
      await expect(ui.page.getByRole('alert')).toContainText('could not be completed');
      await ui.page.getByRole('button', { name: 'Create document', exact: true }).click();
      await expect(ui.page.getByRole('button', { name: /Recovery document/ })).toHaveCount(1);
      expect(keys).toHaveLength(2); expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]);
      const documents = await (await owner.request.get(`/api/workspaces/${fixture.workspace}/documents`)).json();
      expect(documents.items).toHaveLength(1); expect(documents.items[0].id).toBe(fixture.document);
      await post(owner.request, `/api/workspaces/${fixture.workspace}/documents`, { title: 'Unavailable rich', format: 'RICH_TEXT_V1' }, 400);
      await ui.page.getByRole('button', { name: /Recovery document/ }).click();
      await expect(ui.editor).not.toHaveAttribute('readonly', { timeout: 15000 });
      await ui.editor.fill('Durable recovery milestone');
      await expect(ui.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
      const prefix = `/api/workspaces/${fixture.workspace}/documents/${fixture.document}`;
      await expect.poll(async () => (await (await owner.request.get(`/api/workspaces/${fixture.workspace}/search?q=Durable`)).json()).items.length, { timeout: 15000 }).toBe(1);
      fixture.checksum = (await post(owner.request, `${prefix}/checkpoints`)).sha256;
      fixture.revision = (await post(owner.request, `${prefix}/revisions`, { name: 'Backup milestone', requestId: randomUUID() })).id;
      fixture.sourceCookies = await owner.cookies();
      writeFileSync(manifest, JSON.stringify(fixture));
    } else {
      fixture = JSON.parse(readFileSync(manifest, 'utf8')) as Fixture;
      if (phase === 'verify') {
        const oldSession = await browser.newContext({ baseURL });
        await oldSession.addCookies(fixture.sourceCookies);
        expect((await oldSession.request.get('/api/me')).status()).toBe(401); await oldSession.close();
      }
      const ui = await open(owner, fixture.owner, fixture);
      await expect(ui.editor).toHaveValue('Durable recovery milestone', { timeout: 15000 });
      await expect(ui.editor).not.toHaveAttribute('readonly');
      if (phase === 'advance') {
        await ui.editor.fill('Post-backup edit intentionally outside snapshot');
        await expect(ui.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
      } else {
        const prefix = `/api/workspaces/${fixture.workspace}/documents/${fixture.document}`;
        const checkpoint = await (await owner.request.get(`${prefix}/checkpoints/latest`)).json(); expect(checkpoint.sha256).toBe(fixture.checksum);
        const revision = await owner.request.get(`${prefix}/revisions/${fixture.revision}`); expect(revision.status()).toBe(200);
        expect((await revision.json()).sha256).toBe(fixture.checksum);
        const viewer = await browser.newContext({ baseURL });
        const readonly = await open(viewer, fixture.viewer, fixture, false);
        await expect(readonly.page.getByRole('button', { name: 'Create document', exact: true })).toHaveCount(0);
        await readonly.page.getByRole('button', { name: /Recovery document/ }).click();
        await expect(readonly.editor).toHaveValue('Durable recovery milestone', { timeout: 15000 });
        await expect(readonly.editor).toHaveAttribute('readonly');
        await expect((await viewer.request.get(`${prefix}/revisions/${fixture.revision}`)).status()).toBe(200);
        await post(viewer.request, `${prefix}/revisions`, { name: 'denied', requestId: randomUUID() }, 403);
        await post(viewer.request, `/api/workspaces/${fixture.workspace}/documents`, { title: 'Denied', format: 'PLAIN_TEXT_V1' }, 403);
        await viewer.close();
        const outsider = await browser.newContext({ baseURL }); await login(outsider.request, fixture.outsider);
        expect((await outsider.request.get(`/api/workspaces/${fixture.workspace}/documents`)).status()).toBe(404);
        expect((await outsider.request.get(`${prefix}/revisions/${fixture.revision}`)).status()).toBe(404);
        expect((await outsider.request.get(`/api/workspaces/${fixture.workspace}/search?q=Durable`)).status()).toBe(404); await outsider.close();
        await ui.editor.fill('Recovered deployment accepts new edits');
        await expect(ui.page.locator('.editor-status')).toHaveText(/Saved|Up to date/, { timeout: 15000 });
        await ui.page.close();
        const reopened = await open(owner, fixture.owner, fixture);
        await expect(reopened.editor).toHaveValue('Recovered deployment accepts new edits', { timeout: 15000 });
      }
    }
  } finally { await owner.close(); }
});
