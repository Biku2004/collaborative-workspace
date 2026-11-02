import * as Y from 'yjs';
import * as decoding from 'lib0/decoding';
import { InputError, LIMITS } from './protocol.js';

// Prototype only: store.pendingStructs/pendingDs are version-specific Yjs internals.
export function reconstruct(updates: readonly Uint8Array[]) {
  const doc = new Y.Doc();
  try {
    try {
      for (const update of updates) {
        const decoder = decoding.createDecoder(update);
        Y.readUpdate(decoder, doc, null);
        if (decoding.hasContent(decoder)) throw new InputError('INVALID_UPDATE');
      }
    }
    catch { throw new InputError('INVALID_UPDATE'); }
    if (doc.store.pendingStructs != null || doc.store.pendingDs != null) throw new InputError('MISSING_DEPENDENCY');
    if ([...doc.share.keys()].some(key => key !== 'content')) throw new InputError('UNSUPPORTED_DOCUMENT');
    let text: Y.Text;
    try { text = doc.getText('content'); }
    catch { throw new InputError('UNSUPPORTED_DOCUMENT'); }
    // Root Yjs type information is not fully encoded in an update; map entries must not be hidden by a text view.
    if (text._map.size !== 0) throw new InputError('UNSUPPORTED_DOCUMENT');
    if (text.length > LIMITS.textUnits) throw new InputError('TOO_LARGE');
    if (text.toDelta().some((item: { insert?: unknown; attributes?: unknown }) => typeof item.insert !== 'string' || item.attributes != null)) throw new InputError('UNSUPPORTED_DOCUMENT');
    const checkpoint = Y.encodeStateAsUpdate(doc);
    // A returned checkpoint must itself fit the per-update input limit for a later round trip.
    if (checkpoint.byteLength > LIMITS.updateBytes) throw new InputError('TOO_LARGE');
    return { version: 1, status: 'candidate', text: text.toString(), checkpoint: Buffer.from(checkpoint).toString('base64') };
  } finally { doc.destroy(); }
}
