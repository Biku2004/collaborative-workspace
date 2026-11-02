import * as Y from 'yjs';

type Phase = 'connecting' | 'syncing' | 'ready' | 'failed';
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
  private readonly socket: WebSocket;
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

  constructor(private readonly workspaceId: string, private readonly documentId: string,
              private readonly changed: (state: EditorState) => void) {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.socket = new WebSocket(`${protocol}//${location.host}/api/workspaces/${encodeURIComponent(workspaceId)}/socket`);
    this.socket.onopen = () => this.send({ type: 'HELLO', version: 1 });
    this.socket.onmessage = event => this.onMessage(event.data);
    this.socket.onerror = () => this.fail('Connection failed. Your local text remains in this tab.');
    this.socket.onclose = () => { if (!this.disposed) this.fail('Connection closed. Copy any unsaved text before leaving.'); };
    this.text.observe(this.onText);
    this.doc.on('update', this.onUpdate);
    this.emit();
  }

  private readonly onText = () => this.emit();
  private readonly onUpdate = (update: Uint8Array, origin: unknown) => {
    if (origin === REMOTE || this.disposed) return;
    // Keep the visible text if an update cannot be submitted; never claim it was saved.
    this.pending.push({ id: crypto.randomUUID(), payload: encode(update) });
    if (update.byteLength > 8192 || this.pending.length > MAX_PENDING) {
      this.fail('This edit is too large or the pending queue is full. Copy your text before leaving.'); return;
    }
    this.emit(); this.sendNext();
  };

  setText(next: string): void {
    if (this.phase !== 'ready') return;
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
    if (this.phase === 'ready') { this.message = this.pending.length ? 'Saving…' : 'Up to date'; this.emit(); }
  }

  private send(value: Record<string, unknown>): void {
    if (this.socket.readyState !== WebSocket.OPEN || this.socket.bufferedAmount > 16384) {
      this.fail('Connection is unavailable or congested. Copy any unsaved text before leaving.'); return;
    }
    try { this.socket.send(JSON.stringify(value)); }
    catch { this.fail('Connection closed before the update could be sent. Copy any unsaved text before leaving.'); }
  }
  private sendNext(): void {
    if (this.phase !== 'ready' || this.inFlight || !this.pending.length) return;
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
        if (this.phase !== 'connecting' || event.workspaceId !== this.workspaceId) throw new Error('Unexpected workspace');
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
        else { this.phase = 'ready'; this.message = this.pending.length ? 'Saving…' : 'Up to date'; this.emit(); this.sendNext(); }
      } else if (event.type === 'SAVED') {
        if (!this.inFlight || !this.pending.length || event.updateId !== this.pending[0]!.id || event.documentId !== this.documentId || integer(event.epoch) !== 0) throw new Error('Unexpected acknowledgement');
        integer(event.sequence); clearTimeout(this.saveTimer); this.pending.shift(); this.inFlight = false;
        this.message = this.pending.length ? 'Saving…' : 'Saved'; this.emit(); this.sendNext();
      } else if (event.type === 'TEST_MESSAGE' && event.documentId === this.documentId) {
        // Lesson 20's transient messages are not document changes.
      } else if (event.type === 'REJECTED') {
        this.fail('The server could not save or synchronize this document. Copy any unsaved text before leaving.');
      } else {
        throw new Error('Unsupported message');
      }
    } catch { this.fail('The document could not be synchronized safely. Copy any unsaved text before leaving.'); }
  }
  private fail(message: string): void {
    if (this.disposed || this.phase === 'failed') return;
    clearTimeout(this.syncTimer); clearTimeout(this.saveTimer);
    this.phase = 'failed'; this.message = message; this.emit();
    if (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING) {
      try { this.socket.close(); } catch { /* Connection already closed. */ }
    }
  }
  private emit(): void { if (!this.disposed) this.changed({ phase: this.phase, text: this.text.toString(), pending: this.pending.length, message: this.message }); }
  destroy(): void {
    this.disposed = true; clearTimeout(this.syncTimer); clearTimeout(this.saveTimer); this.text.unobserve(this.onText); this.doc.off('update', this.onUpdate);
    this.doc.destroy(); this.socket.close();
  }
}
