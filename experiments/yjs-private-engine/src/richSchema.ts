import { getSchema } from '@tiptap/core';
import StarterKit, { type StarterKitOptions } from '@tiptap/starter-kit';
import { yDocToProsemirrorJSON } from '@tiptap/y-tiptap';
import contract from '@workspace/rich-text-schema-v1/contract.json' with { type: 'json' };
import * as Y from 'yjs';
import * as decoding from 'lib0/decoding';
import { InputError, LIMITS } from './protocol.js';

// Experimental v2 candidate path; the Java application does not use it yet.
const schema = getSchema([StarterKit.configure(contract.starterKit as Partial<StarterKitOptions>)]);
const allowedNodes = new Set(['paragraph', 'heading', 'bulletList', 'orderedList', 'listItem']);
const allowedMarks = new Set(['bold', 'italic']);
const MAX_NODES = 512, MAX_DEPTH = 16;

function emptyObject(value: unknown): boolean {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return (prototype === Object.prototype || prototype === null) && Object.keys(value).length === 0;
}

export function reconstructRichCandidate(updates: readonly Uint8Array[]) {
  const doc = new Y.Doc();
  try {
    try {
      for (const update of updates) {
        const decoder = decoding.createDecoder(update);
        Y.readUpdate(decoder, doc, null);
        if (decoding.hasContent(decoder)) throw new InputError('INVALID_UPDATE');
      }
    } catch { throw new InputError('INVALID_UPDATE'); }
    if (doc.store.pendingStructs != null || doc.store.pendingDs != null) throw new InputError('MISSING_DEPENDENCY');
    if ([...doc.share.keys()].some(key => key !== contract.root)) throw new InputError('UNSUPPORTED_DOCUMENT');
    const root = doc.getXmlFragment(contract.root);
    if (root._map.size !== 0) throw new InputError('UNSUPPORTED_DOCUMENT');
    let count = 0, textUnits = 0;
    function visit(node: unknown, depth: number): void {
      if (++count > MAX_NODES || depth > MAX_DEPTH) throw new InputError('TOO_LARGE');
      if (node instanceof Y.XmlText) {
        if (Object.keys(node.getAttributes()).length) throw new InputError('UNSUPPORTED_DOCUMENT');
        for (const part of node.toDelta()) {
          if (typeof part.insert !== 'string') throw new InputError('UNSUPPORTED_DOCUMENT');
          textUnits += part.insert.length;
          if (textUnits > LIMITS.textUnits) throw new InputError('TOO_LARGE');
          for (const [name, value] of Object.entries(part.attributes ?? {}))
            if (!allowedMarks.has(name) || !emptyObject(value)) throw new InputError('UNSUPPORTED_DOCUMENT');
        }
        return;
      }
      if (!(node instanceof Y.XmlElement) || !allowedNodes.has(node.nodeName)) throw new InputError('UNSUPPORTED_DOCUMENT');
      const attrs = node.getAttributes(), keys = Object.keys(attrs);
      if (node.nodeName === 'heading') {
        if (keys.some(key => key !== 'level') || ![1, 2, 3].includes(attrs.level as number)) throw new InputError('UNSUPPORTED_DOCUMENT');
      } else if (node.nodeName === 'orderedList') {
        if (keys.some(key => key !== 'start') || (attrs.start !== undefined &&
            (!Number.isSafeInteger(attrs.start) || (attrs.start as number) < 1 || (attrs.start as number) > 1000))) throw new InputError('UNSUPPORTED_DOCUMENT');
      } else if (keys.length) throw new InputError('UNSUPPORTED_DOCUMENT');
      for (const child of node.toArray()) visit(child, depth + 1);
    }
    for (const child of root.toArray()) visit(child, 1);
    const checkpoint = Y.encodeStateAsUpdate(doc);
    if (checkpoint.byteLength > LIMITS.updateBytes) throw new InputError('TOO_LARGE');
    if (root.length === 0) return { initialized: false, checkpoint };
    try {
      const parsed = schema.nodeFromJSON(yDocToProsemirrorJSON(doc, contract.root));
      parsed.check();
      return { initialized: true, json: parsed.toJSON(), checkpoint };
    } catch { throw new InputError('UNSUPPORTED_DOCUMENT'); }
  } finally { doc.destroy(); }
}
