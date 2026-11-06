import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from 'react';
import * as Y from 'yjs';
import { revisionPage, revisionPreview, saveRevision, type Revision, type RevisionPreview } from './api';
import RichEditorBoundary from './RichEditorBoundary';
const RichTextSurface = lazy(() => import('./RichTextSurface'));

type Props = { workspaceId: string; documentId: string; readOnly: boolean; online: boolean };

function Preview({ snapshot }: { snapshot: RevisionPreview }) {
  const [doc, setDoc] = useState<Y.Doc>();
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    const candidate = new Y.Doc();
    setDoc(undefined); setError('');
    void (async () => {
      if (snapshot.revision.format !== 'PLAIN_TEXT_V1' && snapshot.revision.format !== 'RICH_TEXT_V1') throw new Error('Unsupported revision format');
      const max = snapshot.revision.format === 'PLAIN_TEXT_V1' ? 8192 : 65536;
      if (snapshot.checkpoint.length > Math.ceil(max / 3) * 4 || snapshot.sha256.length !== 44) throw new Error('Invalid revision');
      const binary = atob(snapshot.checkpoint);
      if (!binary.length || binary.length > max || btoa(binary) !== snapshot.checkpoint) throw new Error('Invalid revision');
      const bytes = Uint8Array.from(binary, char => char.charCodeAt(0));
      const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
      let encoded = ''; for (const byte of digest) encoded += String.fromCharCode(byte);
      if (btoa(encoded) !== snapshot.sha256) throw new Error('Revision checksum failed');
      if (!active) return;
      Y.applyUpdate(candidate, bytes); setDoc(candidate);
    })().catch(() => { if (active) setError('This revision could not be verified.'); });
    return () => { active = false; candidate.destroy(); };
  }, [snapshot]);
  return <section aria-label="Revision preview">
    <h3>{snapshot.revision.name}</h3>
    <p className="muted small">Committed sequence {snapshot.revision.throughSequence}, epoch {snapshot.revision.epoch}. This preview is read-only.</p>
    {error ? <p role="alert">{error}</p> : doc ? snapshot.revision.format === 'PLAIN_TEXT_V1' ? <pre>{doc.getText('content').toString()}</pre> :
      <RichEditorBoundary><Suspense fallback={<p role="status">Loading preview…</p>}><RichTextSurface document={doc} readOnly /></Suspense></RichEditorBoundary> : <p role="status">Verifying revision…</p>}
  </section>;
}

export default function RevisionsPanel({ workspaceId, documentId, readOnly, online }: Props) {
  const [name, setName] = useState(''), [items, setItems] = useState<Revision[]>([]), [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState(''), [message, setMessage] = useState(''), [busy, setBusy] = useState(true);
  const [preview, setPreview] = useState<RevisionPreview>();
  const request = useRef<{ name: string; id: string } | undefined>(undefined);
  const active = useRef(true), read = useRef<AbortController | undefined>(undefined);
  useEffect(() => {
    active.current = true;
    const controller = new AbortController(); read.current = controller;
    void revisionPage(workspaceId, documentId, undefined, controller.signal).then(page => {
      if (active.current && !controller.signal.aborted) { setItems(previous => [...previous, ...page.items.filter(item => !previous.some(existing => existing.id === item.id))]); setCursor(page.nextCursor); }
    }).catch(() => { if (active.current && !controller.signal.aborted) setError('Revisions could not be loaded.'); })
      .finally(() => { if (active.current && !controller.signal.aborted) setBusy(false); });
    return () => { active.current = false; read.current?.abort(); };
  }, [workspaceId, documentId]);
  async function save(event: FormEvent) {
    event.preventDefault(); if (busy || readOnly || !online) return;
    const label = name.trim(); if (!label) return;
    if (request.current?.name !== label) request.current = { name: label, id: crypto.randomUUID() };
    setBusy(true); setError(''); setMessage('');
    try {
      const revision = await saveRevision(workspaceId, documentId, label, request.current.id);
      if (!active.current) return;
      setItems(previous => [revision, ...previous.filter(item => item.id !== revision.id)]); setName(''); request.current = undefined;
      setMessage(`Saved revision at committed sequence ${revision.throughSequence}.`);
    } catch { if (active.current) setError('The revision could not be confirmed. Retry the same name to reuse this request.'); }
    finally { if (active.current) setBusy(false); }
  }
  async function show(id: string) {
    read.current?.abort(); const controller = new AbortController(); read.current = controller;
    setError(''); setPreview(undefined);
    try { const result = await revisionPreview(workspaceId, documentId, id, controller.signal); if (active.current && !controller.signal.aborted) setPreview(result); }
    catch { if (active.current && !controller.signal.aborted) setError('The revision is unavailable or access changed.'); }
  }
  async function more() {
    if (!cursor || busy) return;
    setBusy(true); setError('');
    const controller = new AbortController(); read.current?.abort(); read.current = controller;
    try { const result = await revisionPage(workspaceId, documentId, cursor, controller.signal); if (active.current && !controller.signal.aborted) { setItems(previous => [...previous, ...result.items.filter(item => !previous.some(old => old.id === item.id))]); setCursor(result.nextCursor); } }
    catch { if (active.current && !controller.signal.aborted) setError('More revisions could not be loaded.'); }
    finally { if (active.current) setBusy(false); }
  }
  return <section className="revisions" aria-label="Named revisions">
    <h3>Named revisions</h3>
    <p className="muted small">Only committed server edits are included. Pending local edits may be excluded.</p>
    {!readOnly && <form onSubmit={save}><label htmlFor="revision-name">Revision name</label><input id="revision-name" value={name} onChange={event => setName(event.target.value)} maxLength={100} disabled={busy} required />
      <button type="submit" disabled={busy || !online}>Save revision</button></form>}
    {error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    <ul>{items.map(item => <li key={item.id}><button type="button" onClick={() => void show(item.id)}>{item.name} — sequence {item.throughSequence}</button></li>)}</ul>
    {cursor && <button type="button" disabled={busy} onClick={() => void more()}>Load more revisions</button>}
    {preview && <Preview key={preview.revision.id} snapshot={preview} />}
  </section>;
}
