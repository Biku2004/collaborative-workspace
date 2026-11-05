import * as Y from 'yjs';
import { InputError } from './protocol.js';

// TipTap's pinned Yjs binding stores structural position migrations in this map.
// Bound both the records and expanded transfer ranges before clients consume them.
const MAX_RECORDS = 64, MAX_SEEN = 32, MAX_TRANSFERS = 512, MAX_EXPANDED = 8192;
const MAX_CLIENT_ID = 0xffffffff;

function record(value: unknown, required: readonly string[], optional: readonly string[] = []): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new InputError('UNSUPPORTED_DOCUMENT');
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) throw new InputError('UNSUPPORTED_DOCUMENT');
  const result = value as Record<string, unknown>, keys = Object.keys(result);
  if (required.some(key => !Object.hasOwn(result, key)) || keys.some(key => !required.includes(key) && !optional.includes(key)))
    throw new InputError('UNSUPPORTED_DOCUMENT');
  return result;
}

function integer(value: unknown, maximum = Number.MAX_SAFE_INTEGER): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0 || value > maximum)
    throw new InputError('UNSUPPORTED_DOCUMENT');
  return value;
}

function boundary(value: unknown): void {
  if (value !== undefined && value !== 'start' && value !== 'end') throw new InputError('UNSUPPORTED_DOCUMENT');
}

export function validatePositionMetadata(doc: Y.Doc, rootName: string): void {
  const key = `__tiptap_position_migrations:root:${rootName}`;
  if (!doc.share.has(key)) return;
  let store: Y.Map<unknown>;
  try { store = doc.getMap(key); } catch { throw new InputError('UNSUPPORTED_DOCUMENT'); }
  if (store._start !== null) throw new InputError('UNSUPPORTED_DOCUMENT');
  if (store.size > MAX_RECORDS) throw new InputError('TOO_LARGE');
  let transferCount = 0, expanded = 0;
  const state = (client: number) => Y.getState(doc.store, client);
  const range = (client: number, clock: number, length: number) => {
    if (length < 1 || clock > Number.MAX_SAFE_INTEGER - length || clock + length > state(client))
      throw new InputError('UNSUPPORTED_DOCUMENT');
  };
  for (const [entryKey, value] of store) {
    const item = record(value, ['version', 'client', 'clock', 'seen', 'transfers']);
    const client = integer(item.client, MAX_CLIENT_ID), clock = integer(item.clock);
    if (item.version !== 1 || entryKey !== `${client}:${clock}` || clock >= state(client)) throw new InputError('UNSUPPORTED_DOCUMENT');
    if (!Array.isArray(item.seen) || item.seen.length > MAX_SEEN) throw new InputError('UNSUPPORTED_DOCUMENT');
    const seenClients = new Set<number>();
    for (const pair of item.seen) {
      if (!Array.isArray(pair) || pair.length !== 2) throw new InputError('UNSUPPORTED_DOCUMENT');
      const seenClient = integer(pair[0], MAX_CLIENT_ID), seenClock = integer(pair[1]);
      if (seenClients.has(seenClient) || seenClock > state(seenClient)) throw new InputError('UNSUPPORTED_DOCUMENT');
      seenClients.add(seenClient);
    }
    if (!Array.isArray(item.transfers)) throw new InputError('UNSUPPORTED_DOCUMENT');
    transferCount += item.transfers.length;
    if (transferCount > MAX_TRANSFERS) throw new InputError('TOO_LARGE');
    for (const transfer of item.transfers) {
      const value = record(transfer, ['source', 'target']);
      const source = record(value.source, ['client', 'clock', 'length'], ['boundary']);
      const sourceClient = integer(source.client, MAX_CLIENT_ID), sourceClock = integer(source.clock), length = integer(source.length, MAX_EXPANDED);
      boundary(source.boundary); range(sourceClient, sourceClock, length);
      expanded += length;
      if (expanded > MAX_EXPANDED) throw new InputError('TOO_LARGE');
      if (value.target === null) continue;
      const target = record(value.target, ['client', 'clock', 'type'], ['tname', 'assoc', 'boundary']);
      const targetClient = integer(target.client, MAX_CLIENT_ID), targetClock = integer(target.clock);
      boundary(target.boundary); range(targetClient, targetClock, length);
      if (target.tname !== undefined && target.tname !== rootName) throw new InputError('UNSUPPORTED_DOCUMENT');
      if (target.assoc !== undefined && target.assoc !== -1 && target.assoc !== 0 && target.assoc !== 1) throw new InputError('UNSUPPORTED_DOCUMENT');
      if (target.type !== null) {
        const type = record(target.type, ['client', 'clock']);
        range(integer(type.client, MAX_CLIENT_ID), integer(type.clock), 1);
      }
    }
  }
}

/** Existing migration records are append-only in the pinned TipTap binding. */
export function snapshotPositionMetadata(doc: Y.Doc, rootName: string): Map<string, string> {
  validatePositionMetadata(doc, rootName);
  const key = `__tiptap_position_migrations:root:${rootName}`;
  if (!doc.share.has(key)) return new Map();
  return new Map([...doc.getMap(key)].map(([name, value]) => [name, JSON.stringify(value)!]));
}

export function requirePositionMetadataPreserved(previous: Map<string, string>, current: Map<string, string>): void {
  for (const [key, value] of previous) {
    if (current.get(key) !== value) throw new InputError('UNSUPPORTED_DOCUMENT');
  }
}
