import assert from 'node:assert/strict';
import test from 'node:test';
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

test('rich candidate rejects a missing dependency and an oversized checkpoint', () => {
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
    large.getXmlFragment(contract.root).insert(0, [paragraph]); paragraph.insert(0, [text]); text.insert(0, 'x'.repeat(9000));
    assert.throws(() => reconstructRichCandidate([Y.encodeStateAsUpdate(large)]), /TOO_LARGE/);
  } finally { large.destroy(); }
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
