import assert from 'node:assert/strict';
import test from 'node:test';
import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import * as Y from 'yjs';
import { createEngineServer } from '../src/server.js';
import { LIMITS } from '../src/protocol.js';

async function withServer(run: (url: string) => Promise<void>, deadline: number = LIMITS.deadlineMs, worker?: URL) {
  const server = createEngineServer(deadline, worker);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('No listening address');
  try { await run(`http://127.0.0.1:${address.port}/v1/reconstruct`); }
  finally { server.closeAllConnections(); await new Promise<void>((resolve, reject) => server.close(err => err ? reject(err) : resolve())); }
}
const b64 = (bytes: Uint8Array) => Buffer.from(bytes).toString('base64');

test('shared Java transport fixture reconstructs to the expected Yjs text', async () => {
  const fixture = readFileSync(new URL('../../fixtures/plain-text.properties', import.meta.url), 'utf8');
  const update = /^update=(.+)$/m.exec(fixture)?.[1]?.trim();
  assert.ok(update);
  await withServer(async url => {
    const result = await post(url, { version: 1, updates: [update] });
    assert.equal(result.status, 200);
    assert.equal(result.body.text, 'Hello Java');
  });
});
async function post(url: string, value: unknown) {
  const response = await fetch(url, { method: 'POST', body: JSON.stringify(value), headers: { 'Content-Type': 'application/json' } });
  return { status: response.status, body: await response.json() as Record<string, unknown> };
}
function fixtures() {
  const doc = new Y.Doc();
  const updates: Uint8Array[] = [];
  doc.on('update', (u: Uint8Array) => updates.push(u.slice()));
  const text = doc.getText('content');
  text.insert(0, 'A😀BC');
  text.delete(3, 1);
  doc.destroy();
  return updates.map(b64);
}

test('round trip, out-of-order deletion, duplicate update and fresh server restart', async () => {
  const updates = fixtures();
  let checkpoint = '';
  await withServer(async url => {
    const result = await post(url, { version: 1, updates: [updates[1], updates[0], updates[1]] });
    assert.equal(result.status, 200);
    assert.equal(result.body.status, 'candidate');
    assert.equal(result.body.text, 'A😀C');
    checkpoint = result.body.checkpoint as string;
    const restored = new Y.Doc();
    try { Y.applyUpdate(restored, Buffer.from(checkpoint, 'base64')); assert.equal(restored.getText('content').toString(), 'A😀C'); }
    finally { restored.destroy(); }
  });
  await withServer(async url => {
    const result = await post(url, { version: 1, updates: [checkpoint] });
    assert.equal(result.status, 200);
    assert.equal(result.body.text, 'A😀C');
  });
});

test('missing insertion and deletion dependencies are rejected, not silently checkpointed', async () => {
  const doc = new Y.Doc(), updates: Uint8Array[] = [];
  doc.on('update', (u: Uint8Array) => updates.push(u.slice()));
  const text = doc.getText('content'); text.insert(0, 'A'); text.insert(1, 'B'); text.delete(0, 1);
  doc.destroy();
  await withServer(async url => {
    for (const update of [updates[1]!, updates[2]!]) {
      const result = await post(url, { version: 1, updates: [b64(update)] });
      assert.equal(result.status, 422);
      assert.equal(result.body.code, 'MISSING_DEPENDENCY');
      assert.equal(result.body.checkpoint, undefined);
    }
  });
});

test('malformed, oversized, unsupported and invalid requests are rejected', async () => {
  await withServer(async url => {
    for (const request of [{ version: 1, updates: ['%%%'] }, { version: 1, updates: [b64(Uint8Array.of(255))] },
      { version: 2, updates: [] }, { version: 1, updates: Array(17).fill('AA==') },
      { version: 1, updates: [b64(new Uint8Array(LIMITS.updateBytes + 1))] }]) {
      const result = await post(url, request);
      assert.ok(result.status >= 400 && result.status < 500);
      assert.equal(result.body.checkpoint, undefined);
    }
    const huge = await fetch(url, { method: 'POST', body: 'x'.repeat(LIMITS.bodyBytes + 1) });
    assert.equal(huge.status, 413);
    const doc = new Y.Doc(); doc.getText('other').insert(0, 'wrong root');
    const unsupported = await post(url, { version: 1, updates: [b64(Y.encodeStateAsUpdate(doc))] });
    doc.destroy();
    assert.equal(unsupported.status, 422);
    assert.equal(unsupported.body.code, 'UNSUPPORTED_DOCUMENT');
    const map = new Y.Doc(); map.getMap('content').set('hidden', 'value');
    const hiddenMap = await post(url, { version: 1, updates: [b64(Y.encodeStateAsUpdate(map))] });
    map.destroy();
    assert.equal(hiddenMap.status, 422);
    assert.equal(hiddenMap.body.code, 'UNSUPPORTED_DOCUMENT');
  });
});

test('worker deadline terminates a stuck operation and returns no candidate', async () => {
  await withServer(async url => {
    const result = await post(url, { version: 1, updates: [] });
    assert.equal(result.status, 504);
    assert.equal(result.body.code, 'TIMEOUT');
    assert.equal(result.body.checkpoint, undefined);
  }, 150, new URL('./hanging-worker.js', import.meta.url));
});

test('a checkpoint that cannot be supplied as a later input is rejected', async () => {
  const doc = new Y.Doc(), updates: Uint8Array[] = [];
  doc.on('update', (u: Uint8Array) => updates.push(u.slice()));
  const text = doc.getText('content');
  text.insert(0, randomBytes(3000).toString('hex'));
  text.insert(text.length, randomBytes(3000).toString('hex'));
  doc.destroy();
  assert.ok(updates.every(update => update.byteLength <= LIMITS.updateBytes));
  await withServer(async url => {
    const result = await post(url, { version: 1, updates: updates.map(b64) });
    assert.equal(result.status, 422);
    assert.equal(result.body.code, 'TOO_LARGE');
    assert.equal(result.body.checkpoint, undefined);
  });
});

test('concurrent worker limit rejects excess requests and releases slots after deadlines', async () => {
  await withServer(async url => {
    const first = post(url, { version: 1, updates: [] });
    const second = post(url, { version: 1, updates: [] });
    await new Promise(resolve => setTimeout(resolve, 250));
    const excess = await post(url, { version: 1, updates: [] });
    assert.equal(excess.status, 503);
    assert.equal(excess.body.code, 'BUSY');
    assert.equal((await first).status, 504);
    assert.equal((await second).status, 504);
    await new Promise(resolve => setTimeout(resolve, 100));
    const after = await post(url, { version: 1, updates: [] });
    assert.equal(after.status, 504);
  }, 1000, new URL('./hanging-worker.js', import.meta.url));
});
