import * as Y from 'yjs';
import { LocalDocumentStore } from './localDocumentStore';
import { ApiError, currentUser } from './api';

type Phase = 'connecting' | 'syncing' | 'ready' | 'offline' | 'failed';
export type EditorState = { phase: Phase; text: string; pending: number; message: string };
type Pending = { id: string; payload: string };
type UpdateEvent = { sequence: number; payload: string };
const REMOTE = Symbol('remote-update');
const MAX_PENDING = 32;
const MAX_TEXT_BYTES = 4000;

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid server message');
  return value as Record<string, unknown>;
}
function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('Invalid sequence');
  return value;
}
function decode(value: unknown): Uint8Array {
  if (typeof value !== 'string' || value.length > 10924) throw new Error('Invalid update');
  const binary = atob(value);
  if (!binary.length || binary.length > 8192 || btoa(binary) !== value) throw new Error('Invalid update');
  return Uint8Array.from(binary, char => char.charCodeAt(0));
}
function encode(bytes: Uint8Array): string {
  let value = '';
  for (const byte of bytes) value += String.fromCharCode(byte);
  return btoa(value);
}

/** A deliberately small, online-only custom provider. It is not y-websocket. */
export class PlainTextProvider {
  private readonly doc = new Y.Doc();
  private readonly text = this.doc.getText('content');
  private socket?: WebSocket;
  private store?: LocalDocumentStore;
  private revision = 0;
  private durableRevision = 0;
  private hasLocalState = false;
  private initialReplayComplete = false;
  private readonly pending: Pending[] = [];
  private phase: Phase = 'connecting';
  private message = 'Connecting…';
  private lastApplied = 0;
  private highestSeen = 0;
  private requestedAfter: number | null = null;
  private inFlight = false;
  private disposed = false;
  private syncTimer: ReturnType<typeof setTimeout> | undefined;
  private saveTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(private readonly userId: string, private readonly workspaceId: string, private readonly documentId: string,
              private readonly changed: (state: EditorState) => void) {
    this.text.observe(this.onText);
    this.doc.on('update', this.onUpdate);
    this.emit();
    // React can mount and immediately clean up an editor in development StrictMode.
    // Defer acquiring the cross-tab lock until that cleanup has had a chance to run.
    void Promise.resolve().then(() => { if (!this.disposed) return this.initialize(); });
  }

  private async initialize(): Promise<void> {
    try {
      const store = await LocalDocumentStore.open(this.userId, this.workspaceId, this.documentId, 0);
      if (this.disposed) { await store.close(); return; }
      this.store = store;
      const cached = await store.load();
      if (this.disposed) return;
      if (cached) {
        Y.applyUpdate(this.doc, cached.state, REMOTE);
        this.pending.push(...cached.pending);
        this.hasLocalState = true;
        this.emit();
      }
      try {
        const current = await currentUser();
        if (this.disposed) return;
        if (current.id !== this.userId) { this.fail('The active account changed. Your draft remains on this device.'); return; }
        this.connect();
      } catch (error) {
        if (error instanceof ApiError && error.status === 0) this.goOffline();
        else this.fail('Your session could not be verified. Your draft remains on this device.');
      }
    } catch { this.fail('Local storage is unavailable or the document is open in another tab.'); }
  }

  private connect(): void {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.socket = new WebSocket(`${protocol}//${location.host}/api/workspaces/${encodeURIComponent(this.workspaceId)}/socket`);
    this.socket.onopen = () => this.send({ type: 'HELLO', version: 1 });
    this.socket.onmessage = event => this.onMessage(event.data);
    // Wait for close: a policy close can indicate revoked access, unlike a network drop.
    this.socket.onerror = () => {};
    this.socket.onclose = event => {
      if (this.disposed) return;
      if (event.code === 1008) this.fail('Access was denied or revoked. Your local draft remains on this device.');
      else this.goOffline();
    };
  }

  private readonly onText = () => this.emit();
  private readonly onUpdate = (update: Uint8Array, origin: unknown) => {
    if (origin === REMOTE || this.disposed) return;
    // Keep the visible text if an update cannot be submitted; never claim it was saved.
    this.pending.push({ id: crypto.randomUUID(), payload: encode(update) });
    if (update.byteLength > 8192 || this.pending.length > MAX_PENDING) {
      this.fail('This edit is too large or the pending queue is full. Copy your text before leaving.'); return;
    }
    this.emit(); this.persist();
  };

  setText(next: string): void {
    if (this.phase !== 'ready' && this.phase !== 'offline') return;
    if (new TextEncoder().encode(next).byteLength > MAX_TEXT_BYTES) {
      this.message = 'The learning editor is limited to 4,000 UTF-8 bytes.'; this.emit(); return;
    }
    const current = this.text.toString();
    if (current === next) return;
    // Compare code points so an emoji surrogate pair is never split by a diff boundary.
    const oldPoints = Array.from(current), newPoints = Array.from(next);
    let prefix = 0, suffix = 0;
    while (prefix < oldPoints.length && prefix < newPoints.length && oldPoints[prefix] === newPoints[prefix]) prefix++;
    while (suffix < oldPoints.length - prefix && suffix < newPoints.length - prefix &&
           oldPoints[oldPoints.length - 1 - suffix] === newPoints[newPoints.length - 1 - suffix]) suffix++;
    const start = oldPoints.slice(0, prefix).join('').length;
    const removed = oldPoints.slice(prefix, oldPoints.length - suffix).join('').length;
    const inserted = newPoints.slice(prefix, newPoints.length - suffix).join('');
    this.doc.transact(() => {
      if (removed) this.text.delete(start, removed);
      if (inserted) this.text.insert(start, inserted);
    });
    if (this.phase === 'ready' || this.phase === 'offline') { this.message = this.phase === 'offline' ? 'Saving locally…' : 'Saving…'; this.emit(); }
  }

  private persist(after?: () => void): void {
    if (!this.store) { this.fail('Local storage is unavailable. Copy your text before leaving.'); return; }
    const revision = ++this.revision;
    void this.store.save({ version: 1, state: Y.encodeStateAsUpdate(this.doc), pending: this.pending }).then(() => {
      if (this.disposed || this.phase === 'failed') return;
      this.durableRevision = revision;
      this.hasLocalState = true;
      after?.();
      if (this.phase === 'offline' && this.durableRevision === this.revision) { this.message = 'Saved locally; pending server confirmation'; this.emit(); }
      this.sendNext();
    }).catch(() => this.fail('Could not save locally. Copy your text before leaving.'));
  }

  private send(value: Record<string, unknown>): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN || this.socket.bufferedAmount > 16384) {
      this.fail('Connection is unavailable or congested. Copy any unsaved text before leaving.'); return;
    }
    try { this.socket.send(JSON.stringify(value)); }
    catch { this.fail('Connection closed before the update could be sent. Copy any unsaved text before leaving.'); }
  }
  private sendNext(): void {
    if (this.phase !== 'ready' || this.inFlight || !this.pending.length || this.revision !== this.durableRevision) return;
    const next = this.pending[0]!; this.inFlight = true;
    this.saveTimer = setTimeout(() => this.fail('Save confirmation timed out. Copy your unsaved text before leaving.'), 10000);
    this.send({ type: 'SUBMIT_UPDATE', version: 1, epoch: 0, updateId: next.id, payload: next.payload });
  }
  private requestSync(): void {
    if (this.requestedAfter !== null || this.phase === 'failed') return;
    this.phase = 'syncing'; this.message = 'Synchronizing…'; this.requestedAfter = this.lastApplied; this.emit();
    this.syncTimer = setTimeout(() => this.fail('Synchronization timed out. Copy any unsaved text before leaving.'), 10000);
    this.send({ type: 'SYNC', version: 1, epoch: 0, after: this.requestedAfter });
  }
  private apply(event: UpdateEvent): void {
    if (event.sequence <= this.lastApplied) return;
    this.highestSeen = Math.max(this.highestSeen, event.sequence);
    if (event.sequence !== this.lastApplied + 1) { this.requestSync(); return; }
    Y.applyUpdate(this.doc, decode(event.payload), REMOTE);
    this.lastApplied = event.sequence;
    // A first-time replica is not a complete offline baseline until initial replay finishes.
    if (this.hasLocalState || this.initialReplayComplete) this.persist();
  }
  private updateEvent(value: unknown): UpdateEvent {
    const item = record(value);
    return { sequence: integer(item.sequence), payload: typeof item.payload === 'string' ? item.payload : '' };
  }
  private onMessage(value: unknown): void {
    if (this.disposed || this.phase === 'failed') return;
    try {
      if (typeof value !== 'string' || new TextEncoder().encode(value).byteLength > 16384) throw new Error('Invalid frame');
      const event = record(JSON.parse(value));
      if (event.version !== 1 || typeof event.type !== 'string') throw new Error('Unsupported protocol');
      if (event.type === 'WELCOME') {
        if (this.phase !== 'connecting' || event.workspaceId !== this.workspaceId || event.userId !== this.userId) throw new Error('Unexpected workspace or account');
        this.send({ type: 'JOIN', version: 1, documentId: this.documentId });
      } else if (event.type === 'JOINED') {
        if (this.phase !== 'connecting' || event.documentId !== this.documentId) throw new Error('Unexpected document');
        this.requestSync();
      } else if (event.type === 'HEAD') {
        if (event.documentId !== this.documentId || integer(event.epoch) !== 0) throw new Error('Document epoch changed');
        this.highestSeen = Math.max(this.highestSeen, integer(event.sequence));
        if (this.highestSeen > this.lastApplied) this.requestSync();
      } else if (event.type === 'UPDATE') {
        if (event.documentId !== this.documentId || integer(event.epoch) !== 0) throw new Error('Document epoch changed');
        this.apply(this.updateEvent(event));
      } else if (event.type === 'SYNC_PAGE') {
        if (this.requestedAfter === null || event.documentId !== this.documentId || integer(event.epoch) !== 0 ||
            !Array.isArray(event.updates) || event.updates.length > 8 || typeof event.hasMore !== 'boolean') throw new Error('Invalid replay page');
        const after = integer(event.after), head = integer(event.head);
        let expected = this.requestedAfter;
        for (const item of event.updates) {
          const update = this.updateEvent(item);
          if (update.sequence !== ++expected) throw new Error('Non-contiguous replay page');
          this.apply(update);
        }
        if (after !== expected || head < after || event.hasMore !== (after < head)) throw new Error('Invalid replay cursor');
        this.highestSeen = Math.max(this.highestSeen, head);
        clearTimeout(this.syncTimer); this.requestedAfter = null;
        if (this.lastApplied < this.highestSeen) this.requestSync();
        else { this.initialReplayComplete = true; this.phase = 'ready'; this.message = this.pending.length ? 'Saving…' : 'Up to date'; this.emit(); this.persist(); }
      } else if (event.type === 'SAVED') {
        if (!this.inFlight || !this.pending.length || event.updateId !== this.pending[0]!.id || event.documentId !== this.documentId || integer(event.epoch) !== 0) throw new Error('Unexpected acknowledgement');
        integer(event.sequence); clearTimeout(this.saveTimer); this.pending.shift();
        this.message = this.pending.length ? 'Saving…' : 'Confirming local state…'; this.emit();
        this.persist(() => { this.inFlight = false; this.message = this.pending.length ? 'Saving…' : 'Saved'; this.emit(); });
      } else if (event.type === 'TEST_MESSAGE' && event.documentId === this.documentId) {
        // Lesson 20's transient messages are not document changes.
      } else if (event.type === 'REJECTED') {
        this.fail('The server could not save or synchronize this document. Copy any unsaved text before leaving.');
      } else {
        throw new Error('Unsupported message');
      }
    } catch { this.fail('The document could not be synchronized safely. Copy any unsaved text before leaving.'); }
  }
  private goOffline(): void {
    if (this.disposed || this.phase === 'failed' || this.phase === 'offline') return;
    clearTimeout(this.syncTimer); clearTimeout(this.saveTimer);
    this.inFlight = false; this.requestedAfter = null;
    // Editing an empty, never-synchronized document offline would be misleading.
    if (!this.store || !this.hasLocalState) {
      this.fail('The document has not been saved locally yet. Connect before editing.'); return;
    }
    this.phase = 'offline'; this.message = 'Offline; changes are pending server confirmation'; this.emit();
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) this.socket.close();
  }
  private fail(message: string): void {
    if (this.disposed || this.phase === 'failed') return;
    clearTimeout(this.syncTimer); clearTimeout(this.saveTimer);
    this.phase = 'failed'; this.message = message; this.emit();
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      try { this.socket.close(); } catch { /* Connection already closed. */ }
    }
  }
  private emit(): void { if (!this.disposed) this.changed({ phase: this.phase, text: this.text.toString(), pending: this.pending.length, message: this.message }); }
  destroy(): void {
    this.disposed = true; clearTimeout(this.syncTimer); clearTimeout(this.saveTimer); this.text.unobserve(this.onText); this.doc.off('update', this.onUpdate);
    this.doc.destroy(); this.socket?.close();
    if (this.store) void this.store.close().catch(() => {});
  }
}
