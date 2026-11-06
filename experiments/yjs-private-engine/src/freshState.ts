import { getSchema } from '@tiptap/core';
import StarterKit, { type StarterKitOptions } from '@tiptap/starter-kit';
import { prosemirrorJSONToYXmlFragment } from '@tiptap/y-tiptap';
import contract from '@workspace/rich-text-schema-v1/contract.json' with { type: 'json' };
import * as Y from 'yjs';
import { InputError, LIMITS } from './protocol.js';
import { reconstruct } from './reconstruct.js';
import { reconstructRichCandidate } from './richSchema.js';

const schema = getSchema([StarterKit.configure(contract.starterKit as Partial<StarterKitOptions>)]);

/** Copy validated materialized content into a new CRDT history. Epoch enforcement belongs to Java. */
export function createFreshState(format: string, source: Uint8Array): Uint8Array {
  const rich = format === contract.format;
  if (!rich && format !== 'PLAIN_TEXT_V1') throw new InputError('INVALID_REQUEST');
  if (!source.length || source.byteLength > (rich ? LIMITS.richCheckpointBytes : LIMITS.updateBytes)) throw new InputError('TOO_LARGE');
  // Validate the complete source before copying any supported materialized content.
  const content = rich ? reconstructRichCandidate([source]) : reconstruct([source]);
  if ('initialized' in content && !content.initialized) throw new InputError('UNSUPPORTED_DOCUMENT');
  const sourceClients = new Set(Y.decodeStateVector(Y.encodeStateVectorFromUpdate(source)).keys());
  const fresh = new Y.Doc();
  try {
    // Avoid even a random collision with an identity encoded in the source history.
    while (sourceClients.has(fresh.clientID)) fresh.clientID = (fresh.clientID + 1) % 0x100000000;
    if ('initialized' in content) prosemirrorJSONToYXmlFragment(schema, content.json, fresh.getXmlFragment(contract.root));
    else if (content.text.length) fresh.getText('content').insert(0, content.text);
    const checkpoint = Y.encodeStateAsUpdate(fresh);
    // Reapply the same output limits and schema validation to the new history.
    if (rich) reconstructRichCandidate([checkpoint]);
    else reconstruct([checkpoint]);
    return checkpoint;
  } finally { fresh.destroy(); }
}
