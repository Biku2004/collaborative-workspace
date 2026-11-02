/** Local data is not authorization or proof of a server commit. */
export type LocalUpdate = { id: string; payload: string };
export type LocalDocument = { version: 1; state: Uint8Array; pending: LocalUpdate[] };
const DATABASE = 'workspace-offline-v1';
const STORE = 'documents';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A single tab owns each user/workspace/document/epoch key while an editor is open. */
export class LocalDocumentStore {
  private closed = false;
  private tail: Promise<void> = Promise.resolve();
  private constructor(private readonly db: IDBDatabase, private readonly key: string, private readonly releaseLock: () => Promise<void>) {}

  static async open(userId: string, workspaceId: string, documentId: string, epoch: number): Promise<LocalDocumentStore> {
    if (![userId, workspaceId, documentId].every(value => UUID.test(value)) || !Number.isSafeInteger(epoch) || epoch < 0)
      throw new Error('Invalid local document identity');
    if (!navigator.locks || !globalThis.indexedDB) throw new Error('Safe local storage is unavailable');
    const key = JSON.stringify([userId.toLowerCase(), workspaceId.toLowerCase(), documentId.toLowerCase(), epoch]);
    let release!: () => void;
    let acquired!: (value: boolean) => void;
    const acquiredPromise = new Promise<boolean>(resolve => { acquired = resolve; });
    const lifetime = new Promise<void>(resolve => { release = resolve; });
    const request = navigator.locks.request(`workspace-document:${key}`, { ifAvailable: true }, async lock => {
      acquired(Boolean(lock));
      if (lock) await lifetime;
    });
    void request.catch(() => acquired(false));
    if (!await acquiredPromise) throw new Error('This document is already open in another tab or local storage is unavailable');
    try {
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const open = indexedDB.open(DATABASE, 1);
        open.onupgradeneeded = () => { open.result.createObjectStore(STORE); };
        open.onerror = () => reject(open.error ?? new Error('Could not open local storage'));
        let blocked = false;
        open.onblocked = () => { blocked = true; reject(new Error('Local storage upgrade is blocked by another tab')); };
        open.onsuccess = () => { if (blocked) open.result.close(); else resolve(open.result); };
      });
      db.onversionchange = () => db.close();
      return new LocalDocumentStore(db, key, async () => { release(); await request; });
    } catch (error) { release(); throw error; }
  }

  async load(): Promise<LocalDocument | undefined> {
    if (this.closed) throw new Error('Local storage is closed');
    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction(STORE, 'readonly');
      const request = transaction.objectStore(STORE).get(this.key);
      let value: unknown;
      request.onsuccess = () => { value = request.result; };
      transaction.oncomplete = () => {
        if (value === undefined) resolve(undefined);
        else if (!validDocument(value)) reject(new Error('Local document data is invalid or incompatible'));
        else resolve(value);
      };
      transaction.onabort = () => reject(transaction.error ?? new Error('Could not read local document'));
      transaction.onerror = () => { /* onabort reports the failure */ };
    });
  }

  /** Serialize immutable snapshots and resolve only after the transaction commits. */
  save(value: LocalDocument): Promise<void> {
    if (this.closed) return Promise.reject(new Error('Local storage is closed'));
    if (!validDocument(value)) return Promise.reject(new Error('Invalid local document'));
    const snapshot: LocalDocument = { version: 1, state: value.state.slice(), pending: value.pending.map(item => ({ ...item })) };
    const operation = this.tail.then(() => new Promise<void>((resolve, reject) => {
      const transaction = this.db.transaction(STORE, 'readwrite');
      transaction.objectStore(STORE).put(snapshot, this.key);
      transaction.oncomplete = () => resolve();
      transaction.onabort = () => reject(transaction.error ?? new Error('Could not save local document'));
      transaction.onerror = () => { /* onabort reports the failure */ };
    }));
    // A failed write poisons the queue so later writes cannot conceal the failure.
    this.tail = operation;
    return operation;
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    try { await this.tail; } finally { this.db.close(); await this.releaseLock(); }
  }
}

function validDocument(value: unknown): value is LocalDocument {
  if (!value || typeof value !== 'object') return false;
  const doc = value as Partial<LocalDocument>;
  if (doc.version !== 1 || !(doc.state instanceof Uint8Array) || doc.state.byteLength > 262144 ||
      !Array.isArray(doc.pending) || doc.pending.length > 32) return false;
  const ids = new Set<string>();
  return doc.pending.every(item => {
    if (!item || typeof item.id !== 'string' || !UUID.test(item.id) || ids.has(item.id) ||
        typeof item.payload !== 'string' || item.payload.length === 0 || item.payload.length > 10924) return false;
    ids.add(item.id);
    return true;
  });
}
