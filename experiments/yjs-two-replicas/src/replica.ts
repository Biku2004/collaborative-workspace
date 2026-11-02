import * as Y from 'yjs';

// An in-process learning harness. The queue stands in for a disconnected transport.
const REMOTE = Symbol('remote-delivery');

export class Replica {
  readonly doc = new Y.Doc();
  readonly text = this.doc.getText('content');
  private readonly outgoing: Uint8Array[] = [];
  private readonly onUpdate = (update: Uint8Array, origin: unknown): void => {
    if (origin !== REMOTE) this.outgoing.push(update.slice());
  };

  constructor() { this.doc.on('update', this.onUpdate); }

  drain(): Uint8Array[] { return this.outgoing.splice(0); }

  receive(update: Uint8Array): void { Y.applyUpdate(this.doc, update, REMOTE); }

  destroy(): void {
    this.doc.off('update', this.onUpdate);
    this.doc.destroy();
  }
}

export function withReplicas<T>(run: (alice: Replica, bob: Replica) => T): T {
  const alice = new Replica();
  const bob = new Replica();
  try { return run(alice, bob); }
  finally { alice.destroy(); bob.destroy(); }
}

// Compare decoded entries, not encoded byte order. This is NOT a content checksum.
export function vectorEntries(doc: Y.Doc): [number, number][] {
  return [...Y.decodeStateVector(Y.encodeStateVector(doc))].sort(([a], [b]) => a - b);
}

export function deliver(updates: readonly Uint8Array[], target: Replica): void {
  for (const update of updates) target.receive(update);
}
