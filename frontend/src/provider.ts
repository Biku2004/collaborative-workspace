import * as Y from 'yjs';
import { LocalDocumentStore } from './localDocumentStore';
import { ApiError, currentUser, documentBaseline } from './api';
import { verifiedState } from './verifiedState';

type Phase = 'connecting' | 'syncing' | 'ready' | 'offline' | 'waiting' | 'failed';
export type EditorState = { phase: Phase; text: string; pending: number; message: string; hasLocalState: boolean; editable: boolean; nextEpoch?: number };
type Pending = { id: string; payload: string };
type UpdateEvent = { sequence: number; payload: string };
export type CollaborationFormat = 'PLAIN_TEXT_V1' | 'RICH_TEXT_V1';
export type Presence = { presenceId: string; userId: string; email: string; anchor: Y.RelativePosition; head: Y.RelativePosition; seen: number };
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

/** A deliberately small custom plain-text provider. It is not y-websocket. */
export class DocumentProvider {
  private readonly doc = new Y.Doc();
  private readonly text?: Y.Text;
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
  private connectionTimer: ReturnType<typeof setTimeout> | undefined;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private retryAttempt = 0;
  private checkingIdentity = false;
  private nextEpoch?: number;
  private identityAbort?: AbortController;
  private readonly presence = new Map<string, Presence>();
  private readonly presenceListeners = new Set<(presence: Presence[]) => void>();
  private localPresence?: { anchor: string; head: string };
  private lastPresenceSent = 0;
  private presenceTimer: ReturnType<typeof setInterval>;
  private presenceSendTimer?: ReturnType<typeof setTimeout>;

  constructor(private readonly userId: string, private readonly workspaceId: string, private readonly documentId: string,
              private readonly changed: (state: EditorState) => void, private readonly format: CollaborationFormat = 'PLAIN_TEXT_V1', private readonly epoch = 0) {
    if (format === 'PLAIN_TEXT_V1') this.text = this.doc.getText('content');
    this.text?.observe(this.onText);
    this.doc.on('update', this.onUpdate);
    window.addEventListener('online', this.onOnline);
    window.addEventListener('offline', this.onOffline);
    this.presenceTimer = setInterval(() => { this.expirePresence(); this.sendPresence(); }, 5000);
    this.emit();
    // React can mount and immediately clean up an editor in development StrictMode.
    // Defer acquiring the cross-tab lock until that cleanup has had a chance to run.
    void Promise.resolve().then(() => { if (!this.disposed) return this.initialize(); });
  }

  private async initialize(): Promise<void> {
    try {
      const store = await LocalDocumentStore.open(this.userId, this.workspaceId, this.documentId, this.epoch, this.format);
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
      await this.attemptConnection();
    } catch { this.fail('Local storage is unavailable or the document is open in another tab.'); }
  }

  private readonly onOnline = () => {
    if (this.phase !== 'offline' && this.phase !== 'waiting') return;
    clearTimeout(this.retryTimer); this.retryTimer = undefined;
    void this.attemptConnection();
  };

  private readonly onOffline = () => {
    if (!this.store || this.disposed || this.phase === 'failed') return;
    this.identityAbort?.abort();
    this.goOffline();
  };

  private async attemptConnection(): Promise<void> {
    if (this.disposed || this.phase === 'failed' || this.checkingIdentity) return;
    this.checkingIdentity = true;
    const controller = new AbortController(); this.identityAbort = controller;
    const deadline = setTimeout(() => controller.abort(), 8000);
    try {
      const current = await currentUser(controller.signal);
      if (this.disposed || (this.phase as Phase) === 'failed') return;
      if (current.id !== this.userId) { this.fail('The active account changed. Your draft remains on this device.'); return; }
      const baseline = await documentBaseline(this.workspaceId, this.documentId, controller.signal);
      if (this.disposed || (this.phase as Phase) === 'failed') return;
      if (baseline.format !== this.format) throw new Error('Unsupported format');
      const epoch = integer(baseline.epoch); integer(baseline.head);
      if (epoch !== this.epoch) { this.epochChanged(epoch); return; }
      const bytes = await verifiedState(baseline.checkpoint, baseline.sha256, baseline.format);
      if (this.disposed || (this.phase as Phase) === 'failed') return;
      Y.applyUpdate(this.doc, bytes, REMOTE);
      this.phase = 'connecting'; this.message = 'Reconnecting…'; this.emit();
      this.connect();
    } catch (error) {
      if (error instanceof ApiError && (error.status === 0 || error.status >= 500)) this.goOffline();
      else if (error instanceof ApiError && (error.status === 403 || error.status === 404)) this.fail('Access was denied or revoked. Your local draft remains on this device.');
      else this.fail('Your session could not be verified. Your draft remains on this device.');
    } finally { clearTimeout(deadline); this.identityAbort = undefined; this.checkingIdentity = false; }
  }

  private scheduleRetry(): void {
    if (this.disposed || this.phase === 'failed' || this.retryTimer) return;
    const delay = Math.min(30000, 1000 * 2 ** Math.min(this.retryAttempt++, 5));
    this.retryTimer = setTimeout(() => { this.retryTimer = undefined; void this.attemptConnection(); }, delay + Math.floor(Math.random() * 250));
  }

  private connect(): void {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(`${protocol}//${location.host}/api/workspaces/${encodeURIComponent(this.workspaceId)}/socket`);
    this.socket = socket;
    this.connectionTimer = setTimeout(() => { if (this.socket === socket) this.goOffline(); }, 10000);
    socket.onopen = () => { if (this.socket === socket) this.send({ type: 'HELLO', version: 1 }); };
    socket.onmessage = event => { if (this.socket === socket) this.onMessage(event.data); };
    // Wait for close: a policy close can indicate revoked access, unlike a network drop.
    socket.onerror = () => {};
    socket.onclose = event => {
      if (this.disposed || this.socket !== socket) return;
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
    if (this.canEdit()) this.message = this.phase === 'ready' ? 'Saving…' : 'Saving locally…';
    this.emit(); this.persist();
  };

  setText(next: string): void {
    const text = this.text;
    if (!text || !this.canEdit()) return;
    if (new TextEncoder().encode(next).byteLength > MAX_TEXT_BYTES) {
      this.message = 'The learning editor is limited to 4,000 UTF-8 bytes.'; this.emit(); return;
    }
    const current = text.toString();
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
      if (removed) text.delete(start, removed);
      if (inserted) text.insert(start, inserted);
    });
    if (this.canEdit()) { this.message = this.phase === 'ready' ? 'Saving…' : 'Saving locally…'; this.emit(); }
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
      this.goOffline(); return;
    }
    try { this.socket.send(JSON.stringify(value)); }
    catch { this.goOffline(); }
  }
  private sendNext(): void {
    if (this.phase !== 'ready' || this.inFlight || !this.pending.length || this.revision !== this.durableRevision) return;
    const next = this.pending[0]!; this.inFlight = true;
    this.saveTimer = setTimeout(() => this.goOffline(), 10000);
    this.send({ type: 'SUBMIT_UPDATE', version: 1, epoch: this.epoch, updateId: next.id, payload: next.payload });
  }
  private requestSync(): void {
    if (this.requestedAfter !== null || this.phase === 'failed') return;
    this.phase = 'syncing'; this.message = 'Synchronizing…'; this.requestedAfter = this.lastApplied; this.emit();
    this.syncTimer = setTimeout(() => this.goOffline(), 10000);
    this.send({ type: 'SYNC', version: 1, epoch: this.epoch, after: this.requestedAfter });
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
      if (['HEAD', 'UPDATE', 'SYNC_PAGE', 'SAVED'].includes(event.type) && integer(event.epoch) !== this.epoch) {
        if (integer(event.epoch) > this.epoch) this.epochChanged(integer(event.epoch));
        return; // Delayed prior-epoch outbox messages cannot enter the new document.
      }
      if (event.type === 'WELCOME') {
        if (this.phase !== 'connecting' || event.workspaceId !== this.workspaceId) throw new Error('Unexpected workspace');
        if (event.userId !== this.userId) { this.fail('The active account changed. Copy your local draft before leaving.'); return; }
        this.send({ type: 'JOIN', version: 1, documentId: this.documentId, format: this.format, presence: true });
      } else if (event.type === 'JOINED') {
        if (this.phase !== 'connecting' || event.documentId !== this.documentId || event.format !== this.format) throw new Error('Unexpected document or format');
        clearTimeout(this.connectionTimer);
        this.requestSync();
      } else if (event.type === 'HEAD') {
        if (event.documentId !== this.documentId) throw new Error('Unexpected document');
        this.highestSeen = Math.max(this.highestSeen, integer(event.sequence));
        if (this.highestSeen > this.lastApplied) this.requestSync();
      } else if (event.type === 'UPDATE') {
        if (event.documentId !== this.documentId) throw new Error('Unexpected document');
        this.apply(this.updateEvent(event));
      } else if (event.type === 'SYNC_PAGE') {
        if (this.requestedAfter === null || event.documentId !== this.documentId ||
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
        else { if (!this.pending.length) this.retryAttempt = 0; this.initialReplayComplete = true; this.phase = 'ready'; this.message = this.pending.length ? 'Saving…' : 'Up to date'; this.emit(); this.persist(); }
      } else if (event.type === 'SAVED') {
        if (!this.inFlight || !this.pending.length || event.updateId !== this.pending[0]!.id || event.documentId !== this.documentId) throw new Error('Unexpected acknowledgement');
        integer(event.sequence); clearTimeout(this.saveTimer); this.retryAttempt = 0; this.pending.shift();
        this.message = this.pending.length ? 'Saving…' : 'Confirming local state…'; this.emit();
        const acknowledgedSocket = this.socket;
        this.persist(() => { if (this.socket === acknowledgedSocket) { this.inFlight = false; this.message = this.pending.length ? 'Saving…' : 'Saved'; this.emit(); } });
      } else if ((event.type === 'PRESENCE' || event.type === 'PRESENCE_LEFT') && event.documentId === this.documentId) {
        this.receivePresence(event);
      } else if (event.type === 'TEST_MESSAGE' && event.documentId === this.documentId) {
        // Lesson 20's transient messages are not document changes.
      } else if (event.type === 'REJECTED') {
        if (event.code === 'ENGINE_UNAVAILABLE' || event.code === 'RETRY_HEAD_CHANGED') { this.goOffline(); return; }
        if (event.code === 'DOCUMENT_LIMIT') this.fail('This document has reached a server limit. Your local draft was not saved to the server; copy it before leaving.');
        else if (event.code === 'EPOCH_CHANGED') {
          this.epochChanged();
          void documentBaseline(this.workspaceId, this.documentId).then(baseline => {
            if (!this.disposed && baseline.format === this.format && integer(baseline.epoch) > this.epoch) { this.nextEpoch = baseline.epoch; this.emit(); }
          }).catch(() => {});
        }
        else if (event.code === 'SCHEMA_UNSUPPORTED') this.fail('The document format is unsupported. Your local draft remains on this device; copy it before leaving.');
        else if (event.code === 'ACCESS_DENIED' || event.code === 'NOT_FOUND' || event.code === 'DOCUMENT_ARCHIVED')
          this.fail('The server rejected access or the document is unavailable. Your local draft remains on this device.');
        else this.fail('The server rejected the update. Your local draft remains on this device; copy it before leaving.');
      } else {
        throw new Error('Unsupported message');
      }
    } catch { this.fail('The document could not be synchronized safely. Copy any unsaved text before leaving.'); }
  }
  private goOffline(): void {
    if (this.disposed || this.phase === 'failed') return;
    clearTimeout(this.syncTimer); clearTimeout(this.saveTimer); clearTimeout(this.connectionTimer);
    this.inFlight = false; this.requestedAfter = null;
    this.clearPresence();
    const previous = this.socket; this.socket = undefined;
    this.phase = this.store && this.hasLocalState ? 'offline' : 'waiting';
    this.message = this.phase === 'offline' ? 'Offline; changes are pending server confirmation' : 'Waiting for the first complete synchronization…';
    this.emit();
    if (previous && (previous.readyState === WebSocket.OPEN || previous.readyState === WebSocket.CONNECTING)) previous.close();
    this.scheduleRetry();
  }
  private epochChanged(epoch?: number): void {
    this.nextEpoch = epoch;
    this.fail('A revision was restored. This older local draft is quarantined and will not be uploaded.');
  }
  async retainDraft(): Promise<void> {
    if (this.store && this.hasLocalState) await this.store.save({ version: 1, state: Y.encodeStateAsUpdate(this.doc), pending: this.pending });
  }
  noticeRestored(epoch: number): void { this.epochChanged(integer(epoch)); }
  private fail(message: string): void {
    if (this.disposed || this.phase === 'failed') return;
    clearTimeout(this.syncTimer); clearTimeout(this.saveTimer); clearTimeout(this.connectionTimer); clearTimeout(this.retryTimer);
    this.phase = 'failed'; this.message = message; this.emit();
    this.clearPresence();
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      try { this.socket.close(); } catch { /* Connection already closed. */ }
    }
  }
  /** Bind an editor only after this provider has restored and synchronized the document. */
  get sharedDocument(): Y.Doc { return this.doc; }
  observePresence(listener: (presence: Presence[]) => void): () => void {
    this.presenceListeners.add(listener); listener([...this.presence.values()]);
    return () => { this.presenceListeners.delete(listener); };
  }
  getPresence(): Presence[] { return [...this.presence.values()]; }
  setPresence(anchor: Y.RelativePosition, head: Y.RelativePosition): void {
    const next = { anchor: encode(Y.encodeRelativePosition(anchor)), head: encode(Y.encodeRelativePosition(head)) };
    if (this.localPresence?.anchor === next.anchor && this.localPresence.head === next.head) return;
    this.localPresence = next;
    this.sendPresence();
    clearTimeout(this.presenceSendTimer);
    this.presenceSendTimer = setTimeout(() => this.sendPresence(), 160);
  }
  private sendPresence(): void {
    const now = performance.now();
    if (this.phase !== 'ready' || !this.localPresence || now - this.lastPresenceSent < 150 || !this.socket ||
        this.socket.readyState !== WebSocket.OPEN || this.socket.bufferedAmount > 16384) return;
    try { this.socket.send(JSON.stringify({ type: 'PRESENCE', version: 1, ...this.localPresence })); this.lastPresenceSent = now; }
    catch { /* Presence is disposable; the normal connection lifecycle handles failures. */ }
  }
  private receivePresence(event: Record<string, unknown>): void {
    // Presence is untrusted ephemeral input. A bad cursor must never break document synchronization.
    try {
      const id = event.presenceId, user = event.userId, email = event.email;
      const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
      if (typeof id !== 'string' || !uuid.test(id)) return;
      if (event.type === 'PRESENCE_LEFT') { if (this.presence.delete(id)) this.emitPresence(); return; }
      if (typeof user !== 'string' || !uuid.test(user) || typeof email !== 'string' || email.length < 1 || email.length > 254 || (!this.presence.has(id) && this.presence.size >= 256)) return;
      const position = (value: unknown) => {
        if (typeof value !== 'string' || value.length > 172) throw new Error('Invalid position');
        const raw = atob(value);
        if (!raw.length || raw.length > 128 || btoa(raw) !== value) throw new Error('Invalid position');
        return Y.decodeRelativePosition(Uint8Array.from(raw, char => char.charCodeAt(0)));
      };
      this.presence.set(id, { presenceId: id, userId: user, email, anchor: position(event.anchor), head: position(event.head), seen: performance.now() });
      this.emitPresence();
    } catch { /* Ignore invalid or unresolvable positions from another client. */ }
  }
  private expirePresence(): void {
    const cutoff = performance.now() - 15000;
    let changed = false;
    for (const [id, entry] of this.presence) if (entry.seen <= cutoff) { this.presence.delete(id); changed = true; }
    if (changed) this.emitPresence();
  }
  private clearPresence(): void { if (this.presence.size) { this.presence.clear(); this.emitPresence(); } }
  private emitPresence(): void { const snapshot = [...this.presence.values()]; for (const listener of this.presenceListeners) listener(snapshot); }
  private canEdit(): boolean {
    return this.phase === 'ready' || (this.hasLocalState && ['connecting', 'syncing', 'offline'].includes(this.phase));
  }
  private emit(): void { if (!this.disposed) this.changed({ phase: this.phase, text: this.text?.toString() ?? '', pending: this.pending.length, message: this.message, hasLocalState: this.hasLocalState, editable: this.canEdit(), nextEpoch: this.nextEpoch }); }
  destroy(): void {
    this.disposed = true; this.identityAbort?.abort(); clearInterval(this.presenceTimer); clearTimeout(this.presenceSendTimer); clearTimeout(this.syncTimer); clearTimeout(this.saveTimer); clearTimeout(this.connectionTimer); clearTimeout(this.retryTimer); window.removeEventListener('online', this.onOnline); window.removeEventListener('offline', this.onOffline); this.text?.unobserve(this.onText); this.doc.off('update', this.onUpdate);
    this.doc.destroy(); this.socket?.close();
    if (this.store) void this.store.close().catch(() => {});
  }
}
