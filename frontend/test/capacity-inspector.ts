import * as Y from 'yjs';

/** Aggregate diagnostics only: never return document text or metadata payloads. */
export function inspectCapacity(state: Uint8Array) {
  const doc = new Y.Doc();
  try {
    Y.applyUpdate(doc, state);
    const metadata = doc.getMap<{ transfers: { source: { length: number } }[] }>('__tiptap_position_migrations:root:body');
    let transfers = 0, expanded = 0;
    for (const entry of metadata.values()) {
      transfers += entry.transfers.length;
      for (const transfer of entry.transfers) expanded += transfer.source.length;
    }
    return { checkpointBytes: state.byteLength, records: metadata.size, transfers, expanded };
  } finally { doc.destroy(); }
}
