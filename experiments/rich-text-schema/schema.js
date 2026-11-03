import { getSchema } from '../yjs-private-engine/node_modules/@tiptap/core/dist/index.js';
import StarterKit from '../yjs-private-engine/node_modules/@tiptap/starter-kit/dist/index.js';
import * as Y from '../yjs-private-engine/node_modules/yjs/dist/yjs.mjs';
import { prosemirrorJSONToYDoc } from '../yjs-private-engine/node_modules/@tiptap/y-tiptap/dist/index.js';
import contract from '../../packages/rich-text-schema-v1/contract.json' with { type: 'json' };
import { reconstructRichCandidate } from '../yjs-private-engine/dist/src/richSchema.js';

// Use the engine's exact dependency instances; multiple Yjs copies break type checks.
export { Y, prosemirrorJSONToYDoc };
export const schema = getSchema([StarterKit.configure(contract.starterKit)]);
export function validateRichDocument(doc) {
  if (doc.store.pendingStructs != null || doc.store.pendingDs != null) throw new Error('MISSING_DEPENDENCY');
  return reconstructRichCandidate([Y.encodeStateAsUpdate(doc)]);
}
