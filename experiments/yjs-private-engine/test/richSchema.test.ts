import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { getSchema } from '@tiptap/core';
import StarterKit, { type StarterKitOptions } from '@tiptap/starter-kit';
import { prosemirrorJSONToYDoc } from '@tiptap/y-tiptap';
import contract from '@workspace/rich-text-schema-v1/contract.json' with { type: 'json' };
import * as Y from 'yjs';
import { reconstructRichCandidate } from '../src/richSchema.js';

const schema = getSchema([StarterKit.configure(contract.starterKit as Partial<StarterKitOptions>)]);
const example = { type: 'doc', content: [
  { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Title', marks: [{ type: 'bold' }] }] },
  { type: 'paragraph', content: [{ type: 'text', text: 'Body', marks: [{ type: 'italic' }] }] },
] };

test('the versioned initial update encodes exactly one empty paragraph', () => {
  const bytes = Buffer.from(contract.initialUpdate, 'base64');
  const candidate = reconstructRichCandidate([bytes]);
  assert.equal(candidate.initialized, true);
  assert.deepEqual(candidate.json, { type: 'doc', content: [{ type: 'paragraph' }] });
  const source = new Y.Doc();
  try {
    source.clientID = contract.initialClientId;
    source.getXmlFragment(contract.root).insert(0, [new Y.XmlElement('paragraph')]);
    assert.deepEqual(Y.encodeStateAsUpdate(source), new Uint8Array(bytes));
  } finally { source.destroy(); }
  const colliding = new Y.Doc();
  try {
    colliding.clientID = contract.initialClientId;
    Y.applyUpdate(colliding, bytes);
    assert.notEqual(colliding.clientID, contract.initialClientId);
  } finally { colliding.destroy(); }
});

test('a commit cannot remove the last root paragraph', () => {
  const doc = new Y.Doc();
  try {
    const baseline = Buffer.from(contract.initialUpdate, 'base64');
    Y.applyUpdate(doc, baseline);
    const vector = Y.encodeStateVector(doc);
    doc.getXmlFragment(contract.root).delete(0, 1);
    const deletion = Y.encodeStateAsUpdate(doc, vector);
    assert.equal(reconstructRichCandidate([baseline, deletion]).initialized, false);
    assert.throws(() => reconstructRichCandidate([baseline, deletion], true), /UNSUPPORTED_DOCUMENT/);
    assert.equal(reconstructRichCandidate([baseline]).initialized, true);
  } finally { doc.destroy(); }
});

test('shared dependent update requires and extends the canonical bootstrap', () => {
  const fixture = readFileSync(new URL('../../fixtures/rich-dependent.properties', import.meta.url), 'utf8');
  const initial = /^initial=(.+)$/m.exec(fixture)?.[1]?.trim(), update = /^update=(.+)$/m.exec(fixture)?.[1]?.trim();
  assert.equal(initial, contract.initialUpdate); assert.ok(update);
  const bytes = Buffer.from(update, 'base64');
  assert.throws(() => reconstructRichCandidate([bytes]), /MISSING_DEPENDENCY/);
  const candidate = reconstructRichCandidate([Buffer.from(initial!, 'base64'), bytes]);
  assert.deepEqual(candidate.json, { type: 'doc', content: [{ type: 'paragraph', content: [
    { type: 'text', marks: [{ type: 'bold' }], text: 'Rich edit' },
  ] }] });
});

test('real editor heading updates preserve bounded position metadata across checkpoints', () => {
  const fixture = readFileSync(new URL('../../fixtures/rich-heading.properties', import.meta.url), 'utf8');
  let checkpoint = Buffer.from(contract.initialUpdate, 'base64') as Uint8Array;
  for (let i = 1; i <= 4; i++) {
    const encoded = new RegExp(`^update${i}=(.+)$`, 'm').exec(fixture)?.[1]?.trim();
    assert.ok(encoded);
    checkpoint = reconstructRichCandidate([checkpoint, Buffer.from(encoded, 'base64')], true).checkpoint;
  }
  const result = reconstructRichCandidate([checkpoint]);
  // ProseMirror attribute objects can have a null prototype; compare their wire JSON.
  assert.deepEqual(JSON.parse(JSON.stringify(result.json)), { type: 'doc', content: [{ type: 'heading', attrs: { level: 2 }, content: [
    { type: 'text', marks: [{ type: 'bold' }, { type: 'italic' }], text: 'Hello' },
  ] }] });
});

test('commit validation rejects deletion or replacement of existing position history', () => {
  const fixture = readFileSync(new URL('../../fixtures/rich-heading.properties', import.meta.url), 'utf8');
  const original = new Y.Doc();
  try {
    Y.applyUpdate(original, Buffer.from(contract.initialUpdate, 'base64'));
    for (let i = 1; i <= 4; i++) {
      const encoded = new RegExp(`^update${i}=(.+)$`, 'm').exec(fixture)?.[1]?.trim();
      assert.ok(encoded); Y.applyUpdate(original, Buffer.from(encoded, 'base64'));
    }
    const checkpoint = Y.encodeStateAsUpdate(original);
    const key = `__tiptap_position_migrations:root:${contract.root}`;
    assert.ok(original.getMap(key).size > 0);
    for (const operation of ['delete', 'replace'] as const) {
      const attacker = new Y.Doc();
      try {
        Y.applyUpdate(attacker, checkpoint);
        const vector = Y.encodeStateVector(attacker), metadata = attacker.getMap(key);
        const entry = [...metadata.entries()][0]!;
        if (operation === 'delete') metadata.delete(entry[0]);
        else metadata.set(entry[0], { ...(entry[1] as object), seen: [] });
        const update = Y.encodeStateAsUpdate(attacker, vector);
        assert.throws(() => reconstructRichCandidate([checkpoint, update], true), /UNSUPPORTED_DOCUMENT/);
      } finally { attacker.destroy(); }
    }
  } finally { original.destroy(); }
});

test('malformed or excessive position metadata is rejected before clients consume it', () => {
  const create = (value: (client: number, clock: number) => unknown) => {
    const doc = prosemirrorJSONToYDoc(schema, example, contract.root);
    const client = doc.clientID, clock = Y.getState(doc.store, client);
    doc.getMap(`__tiptap_position_migrations:root:${contract.root}`).set(`${client}:${clock}`, value(client, clock));
    return doc;
  };
  const tooLong = create((client, clock) => ({ version: 1, client, clock, seen: [], transfers: [
    { source: { client, clock: 0, length: 5000 }, target: null },
  ] }));
  const malformed = create((client, clock) => ({ version: 1, client, clock, seen: [], transfers: 'not-an-array' }));
  const unknown = create((client, clock) => ({ version: 1, client, clock, seen: [], transfers: [], unexpected: true }));
  const missingId = create((client, clock) => ({ version: 1, client, clock, seen: [], transfers: [
    { source: { client: 4294967295, clock: 0, length: 1 }, target: null },
  ] }));
  try {
    for (const doc of [tooLong, malformed, unknown, missingId])
      assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(doc)]), /UNSUPPORTED_DOCUMENT|TOO_LARGE/);
  } finally { for (const doc of [tooLong, malformed, unknown, missingId]) doc.destroy(); }
});

test('position history accepts 128 bounded records and rejects the next record as a limit', () => {
  const doc = prosemirrorJSONToYDoc(schema, example, contract.root);
  try {
    const metadata = doc.getMap(`__tiptap_position_migrations:root:${contract.root}`);
    const append = () => {
      const client = doc.clientID, clock = Y.getState(doc.store, client);
      metadata.set(`${client}:${clock}`, { version: 1, client, clock, seen: [], transfers: [] });
    };
    for (let i = 0; i < 128; i++) append();
    const baseline = reconstructRichCandidate([Y.encodeStateAsUpdate(doc)]).checkpoint;
    const vector = Y.encodeStateVector(doc);
    append();
    assert.throws(() => reconstructRichCandidate([baseline, Y.encodeStateAsUpdate(doc, vector)], true), /TOO_LARGE/);
    assert.equal(reconstructRichCandidate([baseline]).initialized, true);
  } finally { doc.destroy(); }
});

test('rich candidate reconstructs a supported document and its checkpoint', () => {
  const doc = prosemirrorJSONToYDoc(schema, example, contract.root);
  try {
    const candidate = reconstructRichCandidate([Y.encodeStateAsUpdate(doc)]);
    assert.equal(candidate.initialized, true);
    assert.deepEqual(candidate.json, schema.nodeFromJSON(example).toJSON());
    assert.deepEqual(reconstructRichCandidate([candidate.checkpoint]).json, candidate.json);
  } finally { doc.destroy(); }
});

test('rich candidate rejects trailing bytes and unsupported attributes or marks', () => {
  const doc = prosemirrorJSONToYDoc(schema, example, contract.root);
  try {
    const update = Y.encodeStateAsUpdate(doc);
    assert.throws(() => reconstructRichCandidate([Buffer.concat([update, Buffer.from([255])])]), /INVALID_UPDATE/);
    const heading = doc.getXmlFragment(contract.root).get(0) as Y.XmlElement;
    heading.setAttribute('onclick', 'alert(1)');
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(doc)]), /UNSUPPORTED_DOCUMENT/);
  } finally { doc.destroy(); }
  const linked = prosemirrorJSONToYDoc(schema, example, contract.root);
  try {
    const heading = linked.getXmlFragment(contract.root).get(0) as Y.XmlElement;
    (heading.get(0) as Y.XmlText).format(0, 1, { link: { href: 'javascript:alert(1)' } });
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(linked)]), /UNSUPPORTED_DOCUMENT/);
  } finally { linked.destroy(); }
  const unsupportedHeading = prosemirrorJSONToYDoc(schema, example, contract.root);
  try {
    const heading = unsupportedHeading.getXmlFragment(contract.root).get(0) as Y.XmlElement;
    heading.setAttribute('level', 6 as unknown as string);
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(unsupportedHeading)]), /UNSUPPORTED_DOCUMENT/);
  } finally { unsupportedHeading.destroy(); }
  const malformedMark = prosemirrorJSONToYDoc(schema, example, contract.root);
  try {
    const heading = malformedMark.getXmlFragment(contract.root).get(0) as Y.XmlElement;
    (heading.get(0) as Y.XmlText).format(0, 1, { bold: { unexpected: 'value' } });
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(malformedMark)]), /UNSUPPORTED_DOCUMENT/);
  } finally { malformedMark.destroy(); }
});

test('rich candidate rejects a missing dependency and excessive text', () => {
  const base = prosemirrorJSONToYDoc(schema, example, contract.root);
  try {
    const vector = Y.encodeStateVector(base);
    const heading = base.getXmlFragment(contract.root).get(0) as Y.XmlElement;
    (heading.get(0) as Y.XmlText).insert(5, '!');
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(base, vector)]), /MISSING_DEPENDENCY/);
  } finally { base.destroy(); }
  const large = new Y.Doc();
  try {
    const paragraph = new Y.XmlElement('paragraph'), text = new Y.XmlText();
    large.getXmlFragment(contract.root).insert(0, [paragraph]); paragraph.insert(0, [text]); text.insert(0, '字'.repeat(16385));
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(large)]), /TOO_LARGE/);
  } finally { large.destroy(); }
});

test('rich candidate rejects a checkpoint beyond the bounded internal limit', () => {
  const doc = new Y.Doc();
  try {
    // Varint client-ID width changes encoded size; keep this boundary fixture deterministic.
    doc.clientID = 0xffffffff;
    const paragraph = new Y.XmlElement('paragraph'), text = new Y.XmlText();
    doc.getXmlFragment(contract.root).insert(0, [paragraph]); paragraph.insert(0, [text]); text.insert(0, 'x'.repeat(2000));
    for (let i = 0; i < 2000; i += 2) text.format(i, 1, { bold: {} });
    const update = Y.encodeStateAsUpdate(doc);
    assert.ok(update.byteLength > 65536);
    assert.throws(() => reconstructRichCandidate([update]), /TOO_LARGE/);
  } finally { doc.destroy(); }
});

test('a rich checkpoint larger than a wire update can be reused for the next commit', () => {
  const doc = new Y.Doc();
  try {
    const paragraph = new Y.XmlElement('paragraph'), text = new Y.XmlText();
    doc.getXmlFragment(contract.root).insert(0, [paragraph]); paragraph.insert(0, [text]); text.insert(0, 'x'.repeat(9000));
    const baseline = reconstructRichCandidate([Y.encodeStateAsUpdate(doc)]).checkpoint;
    assert.ok(baseline.byteLength > 8192 && baseline.byteLength <= 65536);
    const vector = Y.encodeStateVector(doc);
    text.insert(text.length, '!');
    const next = reconstructRichCandidate([baseline, Y.encodeStateAsUpdate(doc, vector)], true);
    assert.equal(schema.nodeFromJSON(next.json).textContent.length, 9001);
  } finally { doc.destroy(); }
});

test('concurrent insertion and formatting converge across independent rich replicas', () => {
  const base = prosemirrorJSONToYDoc(schema, { type: 'doc', content: [
    { type: 'paragraph', content: [{ type: 'text', text: 'Hello' }] },
  ] }, contract.root);
  const left = new Y.Doc(), right = new Y.Doc();
  try {
    const snapshot = Y.encodeStateAsUpdate(base), vector = Y.encodeStateVector(base);
    Y.applyUpdate(left, snapshot); Y.applyUpdate(right, snapshot);
    ((left.getXmlFragment(contract.root).get(0) as Y.XmlElement).get(0) as Y.XmlText).insert(5, '!');
    ((right.getXmlFragment(contract.root).get(0) as Y.XmlElement).get(0) as Y.XmlText).format(0, 5, { bold: {} });
    const first = Y.encodeStateAsUpdate(left, vector), second = Y.encodeStateAsUpdate(right, vector);
    Y.applyUpdate(left, second); Y.applyUpdate(right, first);
    const a = reconstructRichCandidate([Y.encodeStateAsUpdate(left)]).json;
    const b = reconstructRichCandidate([Y.encodeStateAsUpdate(right)]).json;
    assert.deepEqual(a, b);
    const parsed = schema.nodeFromJSON(a);
    assert.equal(parsed.textContent, 'Hello!');
    const marks: boolean[] = [];
    parsed.descendants(node => {
      if (node.isText && node.text) for (let i = 0; i < node.text.length; i++) marks.push(node.marks.some(mark => mark.type.name === 'bold'));
    });
    assert.ok(marks.slice(0, 5).every(Boolean));
  } finally { base.destroy(); left.destroy(); right.destroy(); }
});

test('rich candidate rejects unsupported roots and structurally invalid nodes', () => {
  const legacy = new Y.Doc();
  try { legacy.getText('content').insert(0, 'plain'); assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(legacy)]), /UNSUPPORTED_DOCUMENT/); }
  finally { legacy.destroy(); }
  const invalid = new Y.Doc();
  try {
    const list = new Y.XmlElement('bulletList'), heading = new Y.XmlElement('heading');
    invalid.getXmlFragment(contract.root).insert(0, [list]); list.insert(0, [heading]);
    // Yjs supports numeric attributes even though XmlElement's default generic is string-valued.
    heading.setAttribute('level', 2 as unknown as string);
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(invalid)]), /UNSUPPORTED_DOCUMENT/);
  } finally { invalid.destroy(); }
  const unsupported = new Y.Doc();
  try {
    unsupported.getXmlFragment(contract.root).insert(0, [new Y.XmlElement('script')]);
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(unsupported)]), /UNSUPPORTED_DOCUMENT/);
  } finally { unsupported.destroy(); }
  const embed = new Y.Doc();
  try {
    const paragraph = new Y.XmlElement('paragraph'), text = new Y.XmlText();
    embed.getXmlFragment(contract.root).insert(0, [paragraph]); paragraph.insert(0, [text]);
    text.insertEmbed(0, { image: 'https://example.invalid/a' });
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(embed)]), /UNSUPPORTED_DOCUMENT/);
  } finally { embed.destroy(); }
  const large = new Y.Doc();
  try {
    const paragraph = new Y.XmlElement('paragraph'), text = new Y.XmlText();
    large.getXmlFragment(contract.root).insert(0, [paragraph]); paragraph.insert(0, [text]); text.insert(0, 'x'.repeat(16385));
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(large)]), /TOO_LARGE/);
  } finally { large.destroy(); }
  assert.equal(reconstructRichCandidate([new Uint8Array([0, 0])]).initialized, false);
});
