import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import * as Y from 'yjs';
import { getSchema } from '@tiptap/core';
import StarterKit, { type StarterKitOptions } from '@tiptap/starter-kit';
import { prosemirrorJSONToYDoc } from '@tiptap/y-tiptap';
import contract from '@workspace/rich-text-schema-v1/contract.json' with { type: 'json' };
import { createFreshState } from '../src/freshState.js';
import { reconstruct } from '../src/reconstruct.js';
import { reconstructRichCandidate } from '../src/richSchema.js';
import { LIMITS } from '../src/protocol.js';

function clients(bytes: Uint8Array) { return [...Y.decodeStateVector(Y.encodeStateVectorFromUpdate(bytes)).keys()]; }

test('fresh rich state preserves both list types, heading levels and marks', () => {
  const schema = getSchema([StarterKit.configure(contract.starterKit as Partial<StarterKitOptions>)]);
  const paragraph = { type: 'paragraph', content: [{ type: 'text', text: 'Item', marks: [{ type: 'bold' }, { type: 'italic' }] }] };
  const content = { type: 'doc', content: [
    ...[1, 2, 3].map(level => ({ type: 'heading', attrs: { level }, content: [{ type: 'text', text: `Heading ${level}` }] })),
    { type: 'bulletList', content: [{ type: 'listItem', content: [paragraph] }] },
    { type: 'orderedList', attrs: { start: 3 }, content: [{ type: 'listItem', content: [paragraph] }] },
  ] };
  const doc = prosemirrorJSONToYDoc(schema, content, contract.root);
  try {
    const source = reconstructRichCandidate([Y.encodeStateAsUpdate(doc)]);
    const restored = createFreshState('RICH_TEXT_V1', source.checkpoint);
    assert.deepEqual(reconstructRichCandidate([restored]).json, source.json);
  } finally { doc.destroy(); }
});

test('fresh plain state preserves deleted materialized content with new CRDT identities', () => {
  const source = new Y.Doc();
  try {
    source.getText('content').insert(0, 'ABCDE'); source.getText('content').delete(1, 2);
    const checkpoint = Y.encodeStateAsUpdate(source), fresh = createFreshState('PLAIN_TEXT_V1', checkpoint);
    assert.equal(reconstruct([fresh]).text, 'ADE');
    assert.ok(clients(fresh).every(id => !clients(checkpoint).includes(id)));
    assert.deepEqual([...createFreshState('PLAIN_TEXT_V1', Uint8Array.of(0, 0))], [0, 0]);
  } finally { source.destroy(); }
});

test('fresh rich state preserves supported formatting and replaces structural history', () => {
  const fixture = readFileSync(new URL('../../fixtures/rich-heading.properties', import.meta.url), 'utf8');
  const values = new Map(fixture.split(/\r?\n/).filter(line => line.includes('=')).map(line => {
    const separator = line.indexOf('='); return [line.slice(0, separator), line.slice(separator + 1).trim()];
  }));
  const updates = [...values.values()].map(value => Buffer.from(value, 'base64'));
  const source = reconstructRichCandidate([Buffer.from(contract.initialUpdate, 'base64'), ...updates]);
  assert.equal(source.initialized, true);
  const restored = createFreshState('RICH_TEXT_V1', source.checkpoint);
  assert.deepEqual(reconstructRichCandidate([restored]).json, source.json);
  assert.ok(clients(restored).every(id => !clients(source.checkpoint).includes(id)));
  const doc = new Y.Doc();
  try {
    Y.applyUpdate(doc, restored);
    assert.deepEqual([...doc.share.keys()], ['body']);
  } finally { doc.destroy(); }
});

test('fresh state refuses unknown formats, uninitialized rich roots and invalid or oversized sources', () => {
  assert.throws(() => createFreshState('RICH_TEXT_V2', Uint8Array.of(0, 0)), /INVALID_REQUEST/);
  assert.throws(() => createFreshState('RICH_TEXT_V1', Uint8Array.of(0, 0)), /UNSUPPORTED_DOCUMENT/);
  assert.throws(() => createFreshState('PLAIN_TEXT_V1', Uint8Array.of(255)), /INVALID_UPDATE/);
  assert.throws(() => createFreshState('PLAIN_TEXT_V1', new Uint8Array(LIMITS.updateBytes + 1)), /TOO_LARGE/);
  assert.throws(() => createFreshState('RICH_TEXT_V1', new Uint8Array(LIMITS.richCheckpointBytes + 1)), /TOO_LARGE/);
});
