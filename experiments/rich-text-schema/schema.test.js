import test from 'node:test';
import assert from 'node:assert/strict';
import { Y, prosemirrorJSONToYDoc, schema, validateRichDocument } from './schema.js';

const sample = { type: 'doc', content: [
  { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Title', marks: [{ type: 'bold' }] }] },
  { type: 'paragraph', content: [{ type: 'text', text: 'plain and ', marks: [{ type: 'italic' }] }, { type: 'text', text: 'safe' }] },
  { type: 'bulletList', content: [{ type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'one' }] }] }] },
  { type: 'orderedList', attrs: { start: 2 }, content: [{ type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'two' }] }] }] },
] };

function copy(doc) { const result = new Y.Doc(); Y.applyUpdate(result, Y.encodeStateAsUpdate(doc)); return result; }

test('supported TipTap structures survive Yjs reconstruction and a checkpoint round trip', () => {
  const original = prosemirrorJSONToYDoc(schema, sample, 'body');
  const candidate = copy(original);
  const result = validateRichDocument(candidate);
  assert.equal(result.initialized, true);
  assert.deepEqual(result.json, schema.nodeFromJSON(sample).toJSON());
  const restored = new Y.Doc(); Y.applyUpdate(restored, result.checkpoint);
  assert.deepEqual(validateRichDocument(restored).json, result.json);
  original.destroy(); candidate.destroy(); restored.destroy();
});

test('empty documents are distinguished from initialized documents', () => {
  const doc = new Y.Doc();
  assert.equal(validateRichDocument(doc).initialized, false);
  doc.destroy();
});

test('independent replicas merge a concurrent insertion and formatting change', () => {
  const initial = prosemirrorJSONToYDoc(schema, { type: 'doc', content: [
    { type: 'paragraph', content: [{ type: 'text', text: 'Hello' }] },
  ] }, 'body');
  const left = copy(initial), right = copy(initial);
  const baseVector = Y.encodeStateVector(initial);
  left.getXmlFragment('body').get(0).get(0).insert(5, '!');
  right.getXmlFragment('body').get(0).get(0).format(0, 5, { bold: {} });
  const leftUpdate = Y.encodeStateAsUpdate(left, baseVector), rightUpdate = Y.encodeStateAsUpdate(right, baseVector);
  Y.applyUpdate(left, rightUpdate); Y.applyUpdate(right, leftUpdate);
  const a = validateRichDocument(left).json, b = validateRichDocument(right).json;
  assert.deepEqual(a, b);
  const pieces = a.content[0].content;
  assert.equal(pieces.map(piece => piece.text).join(''), 'Hello!');
  const boldCharacters = pieces.flatMap(piece => Array.from(piece.text, () => piece.marks?.some(mark => mark.type === 'bold') ?? false));
  assert.ok(boldCharacters.slice(0, 5).every(Boolean));
  initial.destroy(); left.destroy(); right.destroy();
});

test('unknown roots, nodes, attributes, marks and embedded objects are rejected', () => {
  const differentRoot = new Y.Doc(); differentRoot.getText('content').insert(0, 'legacy');
  assert.throws(() => validateRichDocument(differentRoot), /UNSUPPORTED_DOCUMENT/);

  const badNode = new Y.Doc(); badNode.getXmlFragment('body').insert(0, [new Y.XmlElement('script')]);
  assert.throws(() => validateRichDocument(badNode), /UNSUPPORTED_DOCUMENT/);

  const badAttribute = prosemirrorJSONToYDoc(schema, sample, 'body');
  badAttribute.getXmlFragment('body').get(0).setAttribute('onclick', 'alert(1)');
  assert.throws(() => validateRichDocument(badAttribute), /UNSUPPORTED_DOCUMENT/);

  const badHeading = prosemirrorJSONToYDoc(schema, sample, 'body');
  badHeading.getXmlFragment('body').get(0).setAttribute('level', 6);
  assert.throws(() => validateRichDocument(badHeading), /UNSUPPORTED_DOCUMENT/);

  const badMark = prosemirrorJSONToYDoc(schema, sample, 'body');
  badMark.getXmlFragment('body').get(0).get(0).format(0, 1, { link: { href: 'javascript:alert(1)' } });
  assert.throws(() => validateRichDocument(badMark), /UNSUPPORTED_DOCUMENT/);

  const badEmbed = new Y.Doc(); const p = new Y.XmlElement('paragraph'); const text = new Y.XmlText();
  badEmbed.getXmlFragment('body').insert(0, [p]); p.insert(0, [text]); text.insertEmbed(0, { image: 'https://example.invalid/a' });
  assert.throws(() => validateRichDocument(badEmbed), /UNSUPPORTED_DOCUMENT/);
  for (const doc of [differentRoot, badNode, badAttribute, badHeading, badMark, badEmbed]) doc.destroy();
});

test('valid Yjs with an invalid ProseMirror hierarchy is rejected', () => {
  const doc = new Y.Doc(); const list = new Y.XmlElement('bulletList');
  doc.getXmlFragment('body').insert(0, [list]); list.insert(0, [new Y.XmlElement('heading')]);
  list.get(0).setAttribute('level', 2);
  assert.throws(() => validateRichDocument(doc), /UNSUPPORTED_DOCUMENT/);
  doc.destroy();
});

test('missing Yjs dependencies and oversized documents are rejected', () => {
  const base = prosemirrorJSONToYDoc(schema, { type: 'doc', content: [
    { type: 'paragraph', content: [{ type: 'text', text: 'base' }] },
  ] }, 'body');
  const vector = Y.encodeStateVector(base);
  base.getXmlFragment('body').get(0).get(0).insert(4, ' later');
  const orphan = new Y.Doc(); Y.applyUpdate(orphan, Y.encodeStateAsUpdate(base, vector));
  assert.throws(() => validateRichDocument(orphan), /MISSING_DEPENDENCY/);

  const long = new Y.Doc(); const p = new Y.XmlElement('paragraph'); const t = new Y.XmlText();
  long.getXmlFragment('body').insert(0, [p]); p.insert(0, [t]); t.insert(0, 'x'.repeat(9000));
  assert.throws(() => validateRichDocument(long), /TOO_LARGE/);

  const excessive = new Y.Doc(); const paragraph = new Y.XmlElement('paragraph'); const text = new Y.XmlText();
  excessive.getXmlFragment('body').insert(0, [paragraph]); paragraph.insert(0, [text]); text.insert(0, 'x'.repeat(16385));
  assert.throws(() => validateRichDocument(excessive), /TOO_LARGE/);
  for (const doc of [base, orphan, long, excessive]) doc.destroy();
});
