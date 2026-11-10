import { useEffect, useRef, useState, type FormEvent } from 'react';
import { getDocument, rebuildSearch, searchDocuments, type Document, type SearchResults } from '../../lib/api';

export default function SearchPanel({ workspaceId, owner, onOpen }: { workspaceId: string; owner: boolean; onOpen: (document: Document) => void }) {
  const [query, setQuery] = useState(''), [submitted, setSubmitted] = useState(''), [refresh, setRefresh] = useState(0);
  const [results, setResults] = useState<SearchResults>(), [busy, setBusy] = useState(false), [rebuilding, setRebuilding] = useState(false);
  const [error, setError] = useState(''), [message, setMessage] = useState('');
  const active = useRef(false), opening = useRef<AbortController | undefined>(undefined);
  useEffect(() => { active.current = true; return () => { active.current = false; opening.current?.abort(); }; }, []);
  useEffect(() => {
    setResults(undefined); setError('');
    if (!submitted || rebuilding) { setBusy(false); return; }
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout> | undefined;
    async function load() {
      setBusy(true);
      try {
        const value = await searchDocuments(workspaceId, submitted, controller.signal);
        if (controller.signal.aborted) return;
        setResults(value); setError('');
        timer = setTimeout(() => void load(), value.coverage.pendingDocuments > 0 && value.indexingEnabled ? 2000 : 10000);
      } catch (failure) {
        if (!controller.signal.aborted) { setResults(undefined); setError(failure instanceof Error ? failure.message : 'Search is unavailable.'); }
      } finally { if (!controller.signal.aborted) setBusy(false); }
    }
    void load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [workspaceId, submitted, refresh, rebuilding]);
  function submit(event: FormEvent) {
    event.preventDefault(); const text = query.trim(); if (!text) return;
    setSubmitted(text); setRefresh(value => value + 1); setMessage('');
  }
  async function open(id: string) {
    opening.current?.abort(); const controller = new AbortController(); opening.current = controller; setError('');
    try {
      const document = await getDocument(id, controller.signal);
      if (active.current && !controller.signal.aborted) {
        if (document.workspaceId !== workspaceId || document.archived || !['PLAIN_TEXT_V1', 'RICH_TEXT_V1'].includes(document.collaborationFormat)) throw new Error('This document is unavailable.');
        onOpen(document);
      }
    } catch (failure) { if (active.current && !controller.signal.aborted) { setResults(undefined); setError(failure instanceof Error ? failure.message : 'Document unavailable.'); } }
  }
  async function rebuild() {
    if (rebuilding) return;
    setRebuilding(true); setError(''); setResults(undefined);
    try { await rebuildSearch(workspaceId); if (active.current) { setMessage('Rebuild requested. Results will return as indexing progresses.'); setRefresh(value => value + 1); } }
    catch (failure) { if (active.current) setError(failure instanceof Error ? failure.message : 'Rebuild could not be confirmed.'); }
    finally { if (active.current) setRebuilding(false); }
  }
  return <section className="search-panel" aria-label="Search documents">
    <form onSubmit={submit}><label htmlFor="document-search">Search committed text</label><div className="search-controls">
      <input id="document-search" type="search" value={query} maxLength={200} onChange={event => setQuery(event.target.value)} />
      <button type="submit" disabled={!query.trim() || rebuilding}>Search</button>
      {submitted && <button type="button" disabled={busy || rebuilding} onClick={() => setRefresh(value => value + 1)}>Refresh results</button>}
    </div></form>
    <p className="muted small">Only server-confirmed content is searched. Pending local edits are excluded.</p>
    {busy && <p role="status">Checking search results…</p>}{error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    {results && <>
      <p className="muted small" data-testid="search-coverage">{results.coverage.currentDocuments} of {results.coverage.totalDocuments} committed documents indexed at their latest version. {results.coverage.pendingDocuments > 0 ? `${results.coverage.pendingDocuments} waiting; results may be incomplete.` : 'Results reflect the last check.'}</p>
      {!results.indexingEnabled && <p role="status">Search indexing is paused. Results may be incomplete.</p>}
      {!results.items.length && <p>No matching indexed content.</p>}
      <ul className="search-results">{results.items.map(item => <li key={item.documentId}>
        <button type="button" onClick={() => void open(item.documentId)}>{item.title}</button><p className="search-excerpt">{item.excerpt}</p>
        <small>Indexed {new Date(item.indexedAt).toLocaleString()} · epoch {item.epoch}, sequence {item.throughSequence}{item.stale ? ` · Behind current sequence ${item.head}` : ''}</small>
      </li>)}</ul>
      {results.hasMore && <p className="muted small">Showing the top 20 matches. Refine your search to find more.</p>}
      {owner && <button type="button" disabled={rebuilding || !results.indexingEnabled} onClick={() => void rebuild()}>Rebuild search index</button>}
    </>}
  </section>;
}
