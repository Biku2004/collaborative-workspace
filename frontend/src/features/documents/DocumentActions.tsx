import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, getDocument, type Document } from '../../lib/api';
import { archiveDocument, renameDocument } from './api';

export default function DocumentActions({ document, onUpdated, onDenied }: { document: Document; onUpdated: (document: Document) => void; onDenied: (error: ApiError) => void }) {
  const [current, setCurrent] = useState(document), [title, setTitle] = useState(document.title), [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false), [conflict, setConflict] = useState(false), [error, setError] = useState('');
  const mounted = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  function failure(cause: unknown) {
    if (cause instanceof ApiError && [401,403,404].includes(cause.status)) { onDenied(cause); return; }
    if (cause instanceof ApiError && cause.code === 'DOCUMENT_CONFLICT') setConflict(true);
    setError(cause instanceof Error ? cause.message : 'Document operation failed.');
  }
  async function rename(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || conflict) return;
    if (!title.trim()) { setError('Enter a document title.'); return; }
    setBusy(true); setError('');
    try { const updated = await renameDocument(current, title.trim()); if (mounted.current) { setCurrent(updated); onUpdated(updated); setOpen(false); } }
    catch (cause) { if (mounted.current) failure(cause); }
    finally { if (mounted.current) setBusy(false); }
  }
  async function archive() {
    if (busy || conflict || !window.confirm(`Archive ${current.title}? It will disappear from ordinary document lists.`)) return;
    setBusy(true); setError('');
    try { const updated = await archiveDocument(current); if (mounted.current) onUpdated(updated); }
    catch (cause) { if (mounted.current) failure(cause); }
    finally { if (mounted.current) setBusy(false); }
  }
  async function reload() {
    if (busy || !window.confirm('Load the latest document metadata and discard the proposed title?')) return;
    setBusy(true);
    try {
      const updated = await getDocument(current.id);
      if (!mounted.current) return;
      setCurrent(updated); setTitle(updated.title); setConflict(false); setError(''); onUpdated(updated);
    } catch (cause) { if (mounted.current) failure(cause); }
    finally { if (mounted.current) setBusy(false); }
  }
  return <div className="document-actions">
    <button type="button" aria-expanded={open} onClick={() => { if (!open && !conflict) { setCurrent(document); setTitle(document.title); setError(''); } setOpen(!open); }} disabled={busy}>Document actions</button>
    {open && <div className="management-form" role="region" aria-label={`Manage document ${document.title}`}>
      <form onSubmit={rename}><label htmlFor={`rename-${document.id}`}>Rename document</label><input id={`rename-${document.id}`} maxLength={200} required value={title} onChange={event => setTitle(event.target.value)} disabled={busy} />
        <button type="submit" disabled={busy || conflict}>Save title</button></form>
      <button type="button" disabled={busy || conflict} onClick={() => void archive()}>Archive document</button>
      {error && <p role="alert">{error}</p>}
      {conflict && <button type="button" disabled={busy} onClick={() => void reload()}>Load latest version</button>}
    </div>}
  </div>;
}
