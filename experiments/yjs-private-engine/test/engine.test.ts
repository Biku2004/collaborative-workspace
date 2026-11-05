import assert from 'node:assert/strict';
import test from 'node:test';
import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import * as Y from 'yjs';
import { createEngineServer } from '../src/server.js';
import { LIMITS } from '../src/protocol.js';
import contract from '@workspace/rich-text-schema-v1/contract.json' with { type: 'json' };

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

test('private version 2 validates a bounded rich-text candidate and rejects unsupported input', async () => {
  const doc = new Y.Doc();
  try {
    const paragraph = new Y.XmlElement('paragraph'), text = new Y.XmlText();
    doc.getXmlFragment('body').insert(0, [paragraph]); paragraph.insert(0, [text]);
    text.insert(0, 'Hello'); text.format(0, 5, { bold: {} });
    const encoded = b64(Y.encodeStateAsUpdate(doc));
    await withServer(async legacyUrl => {
      const url = legacyUrl.replace('/v1/', '/v2/');
      const valid = await post(url, { version: 2, format: 'RICH_TEXT_V1', updates: [encoded] });
      assert.equal(valid.status, 200);
      assert.equal(valid.body.version, 2);
      assert.equal(valid.body.format, 'RICH_TEXT_V1');
      assert.equal(valid.body.initialized, true);
      assert.deepEqual(valid.body.document, { type: 'doc', content: [{ type: 'paragraph', content: [
        { type: 'text', marks: [{ type: 'bold' }], text: 'Hello' },
      ] }] });
      const checkpoint = valid.body.checkpoint;
      assert.equal(typeof checkpoint, 'string');
      assert.equal((await post(url, { version: 2, format: 'RICH_TEXT_V1', updates: [checkpoint] })).status, 200);
      assert.equal((await post(url, { version: 2, format: 'UNKNOWN', updates: [encoded] })).status, 400);
      assert.equal((await post(url, { version: 2, format: 'RICH_TEXT_V1', mode: 'commit', updates: [encoded] })).status, 400);
      assert.equal((await post(url, { version: 2, format: 'RICH_TEXT_V1', mode: 'other', updates: [encoded] })).status, 400);
      assert.equal((await post(url, { version: 1, updates: [encoded] })).status, 400);
      const plain = new Y.Doc(); plain.getText('content').insert(0, 'plain');
      const wrongRoot = await post(url, { version: 2, format: 'RICH_TEXT_V1', updates: [b64(Y.encodeStateAsUpdate(plain))] });
      plain.destroy();
      assert.equal(wrongRoot.status, 422);
      assert.equal(wrongRoot.body.code, 'UNSUPPORTED_DOCUMENT');
      paragraph.setAttribute('onclick', 'alert(1)');
      const invalid = await post(url, { version: 2, format: 'RICH_TEXT_V1', updates: [b64(Y.encodeStateAsUpdate(doc))] });
      assert.equal(invalid.status, 422);
      assert.equal(invalid.body.code, 'UNSUPPORTED_DOCUMENT');
      assert.equal(invalid.body.checkpoint, undefined);
    });
  } finally { doc.destroy(); }
});

test('version 2 commit mode protects prior position history at the HTTP boundary', async () => {
  const fixture = readFileSync(new URL('../../fixtures/rich-heading.properties', import.meta.url), 'utf8');
  const doc = new Y.Doc();
  try {
    Y.applyUpdate(doc, Buffer.from(contract.initialUpdate, 'base64'));
    for (let i = 1; i <= 4; i++) {
      const encoded = new RegExp(`^update${i}=(.+)$`, 'm').exec(fixture)?.[1]?.trim();
      assert.ok(encoded); Y.applyUpdate(doc, Buffer.from(encoded, 'base64'));
    }
    const checkpoint = Y.encodeStateAsUpdate(doc), vector = Y.encodeStateVector(doc);
    const metadata = doc.getMap('__tiptap_position_migrations:root:body');
    const key = [...metadata.keys()][0]; assert.ok(key);
    metadata.delete(key);
    const deletion = Y.encodeStateAsUpdate(doc, vector);
    await withServer(async legacyUrl => {
      const url = legacyUrl.replace('/v1/', '/v2/');
      const result = await post(url, { version: 2, format: 'RICH_TEXT_V1', mode: 'commit', updates: [b64(checkpoint), b64(deletion)] });
      assert.equal(result.status, 422);
      assert.equal(result.body.code, 'UNSUPPORTED_DOCUMENT');
      assert.equal(result.body.checkpoint, undefined);
    });
  } finally { doc.destroy(); }
});

test('rich commit accepts a larger checkpoint but still bounds the incoming update', async () => {
  const doc = new Y.Doc();
  try {
    const paragraph = new Y.XmlElement('paragraph'), text = new Y.XmlText();
    doc.getXmlFragment('body').insert(0, [paragraph]); paragraph.insert(0, [text]); text.insert(0, 'x'.repeat(9000));
    const checkpoint = Y.encodeStateAsUpdate(doc), vector = Y.encodeStateVector(doc);
    assert.ok(checkpoint.byteLength > 8192);
    text.insert(text.length, '!');
    const update = Y.encodeStateAsUpdate(doc, vector);
    await withServer(async legacyUrl => {
      const url = legacyUrl.replace('/v1/', '/v2/');
      const result = await post(url, { version: 2, format: 'RICH_TEXT_V1', mode: 'commit', updates: [b64(checkpoint), b64(update)] });
      assert.equal(result.status, 200);
      assert.ok(Buffer.from(result.body.checkpoint as string, 'base64').length > 8192);
      const tooBig = await post(url, { version: 2, format: 'RICH_TEXT_V1', mode: 'commit', updates: [b64(checkpoint), b64(new Uint8Array(8193))] });
      assert.equal(tooBig.status, 400);
    });
  } finally { doc.destroy(); }
});

test('a valid bounded rich document fits the response even when JSON expands its text', async () => {
  const doc = new Y.Doc(), updates: Uint8Array[] = [];
  try {
    doc.on('update', (update: Uint8Array) => updates.push(update.slice()));
    const paragraph = new Y.XmlElement('paragraph'), text = new Y.XmlText();
    doc.transact(() => { doc.getXmlFragment('body').insert(0, [paragraph]); paragraph.insert(0, [text]); text.insert(0, '\u0000'.repeat(6000)); });
    text.insert(6000, '\u0000'.repeat(6000));
    assert.ok(updates.every(update => update.length <= 8192));
    await withServer(async legacyUrl => {
      const result = await post(legacyUrl.replace('/v1/', '/v2/'), { version: 2, format: 'RICH_TEXT_V1', updates: updates.map(b64) });
      assert.equal(result.status, 200);
      assert.equal(result.body.initialized, true);
      assert.ok(Buffer.from(result.body.checkpoint as string, 'base64').length <= 65536);
    });
  } finally { doc.destroy(); }
});

test('rich commit refuses deletion of the last paragraph through HTTP', async () => {
  const doc = new Y.Doc();
  try {
    const checkpoint = Buffer.from(contract.initialUpdate, 'base64');
    Y.applyUpdate(doc, checkpoint);
    const vector = Y.encodeStateVector(doc);
    doc.getXmlFragment(contract.root).delete(0, 1);
    await withServer(async legacyUrl => {
      const result = await post(legacyUrl.replace('/v1/', '/v2/'), {
        version: 2, format: 'RICH_TEXT_V1', mode: 'commit', updates: [b64(checkpoint), b64(Y.encodeStateAsUpdate(doc, vector))],
      });
      assert.equal(result.status, 422);
      assert.equal(result.body.code, 'UNSUPPORTED_DOCUMENT');
      assert.equal(result.body.checkpoint, undefined);
    });
  } finally { doc.destroy(); }
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
    const richUrl = url.replace('/v1/reconstruct', '/v2/reconstruct');
    const withinRichBody = await fetch(richUrl, { method: 'POST', body: 'x'.repeat(LIMITS.bodyBytes + 1) });
    assert.equal(withinRichBody.status, 400); // The body fits v2, but is not JSON.
    const hugeRich = await fetch(richUrl, { method: 'POST', body: 'x'.repeat(LIMITS.richBodyBytes + 1) });
    assert.equal(hugeRich.status, 413);
    const doc = new Y.Doc(); doc.getText('other').insert(0, 'wrong root');
    const unsupported = await post(url, { version: 1, updates: [b64(Y.encodeStateAsUpdate(doc))] });
    doc.destroy();
    assert.equal(unsupported.status, 422);
    assert.equal(unsupported.body.code, 'UNSUPPORTED_DOCUMENT');
    const rich = new Y.Doc();
    const paragraph = new Y.XmlElement('paragraph'), xmlText = new Y.XmlText();
    rich.getXmlFragment('body').insert(0, [paragraph]); paragraph.insert(0, [xmlText]); xmlText.insert(0, 'rich');
    const legacyProtocol = await post(url, { version: 1, updates: [b64(Y.encodeStateAsUpdate(rich))] });
    rich.destroy();
    assert.equal(legacyProtocol.status, 422);
    assert.equal(legacyProtocol.body.code, 'UNSUPPORTED_DOCUMENT');
    const valid = Buffer.from(fixtures()[0]!, 'base64');
    const trailing = await post(url, { version: 1, updates: [b64(Buffer.concat([valid, Buffer.from([255])]))] });
    assert.equal(trailing.status, 422);
    assert.equal(trailing.body.code, 'INVALID_UPDATE');
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
