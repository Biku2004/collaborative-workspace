import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, createDocument, documentFormats, type Document, type DocumentFormat } from '../../lib/api';

export default function CreateDocumentForm({ workspaceId, onCreated, onUnauthorized }: {
  workspaceId: string; onCreated: (document: Document) => void; onUnauthorized: () => void;
}) {
  const [title, setTitle] = useState('');
  const [format, setFormat] = useState<DocumentFormat>('PLAIN_TEXT_V1');
  const [formats, setFormats] = useState<DocumentFormat[] | null>(null);
  const [capabilityError, setCapabilityError] = useState('');
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [reload, setReload] = useState(0);
  const attempt = useRef<{ title: string; format: DocumentFormat; key: string } | null>(null);
  const mounted = useRef(false);
  const unauthorized = useRef(onUnauthorized);
  unauthorized.current = onUnauthorized;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    setFormats(null); setCapabilityError('');
    documentFormats(controller.signal).then(available => {
      if (controller.signal.aborted) return;
      setFormats(available); setFormat(current => available.includes(current) ? current : 'PLAIN_TEXT_V1');
    }).catch(cause => {
      if (controller.signal.aborted) return;
      if (cause instanceof ApiError && cause.status === 401) unauthorized.current();
      else setCapabilityError('Document creation is unavailable until supported formats can be checked.');
    });
    return () => controller.abort();
  }, [reload]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanTitle = title.trim();
    if (creating || !formats?.includes(format)) return;
    if (!cleanTitle) { setError('Enter a document title.'); return; }
    if (!attempt.current || attempt.current.title !== cleanTitle || attempt.current.format !== format)
      attempt.current = { title: cleanTitle, format, key: crypto.randomUUID() };
    setCreating(true); setError('');
    try {
      const document = await createDocument(workspaceId, cleanTitle, attempt.current.key, format);
      if (!mounted.current) return;
      attempt.current = null; setTitle(''); onCreated(document);
    } catch (cause) {
      if (!mounted.current) return;
      if (cause instanceof ApiError && cause.status === 401) unauthorized.current();
      else if (cause instanceof ApiError && cause.status === 400) {
        setError('Check the title and available document formats, then retry.'); setReload(current => current + 1);
      } else setError(cause instanceof Error ? cause.message : 'Could not create the document. Please retry.');
    } finally { if (mounted.current) setCreating(false); }
  }
  const richEnabled = formats?.includes('RICH_TEXT_V1') === true;
  return <form className="create-document" onSubmit={submit}>
    <label htmlFor="new-document-title">New document</label>
    <div><input id="new-document-title" value={title} onChange={event => setTitle(event.target.value)} maxLength={200} required disabled={creating} />
      <button type="submit" disabled={creating || !formats}>{creating ? 'Creating…' : 'Create document'}</button></div>
    <label htmlFor="new-document-format">Document format</label>
    <select id="new-document-format" value={format} onChange={event => setFormat(event.target.value as DocumentFormat)} disabled={creating || !formats} aria-describedby="document-format-help">
      <option value="PLAIN_TEXT_V1">Plain text</option>
      <option value="RICH_TEXT_V1" disabled={!richEnabled}>Rich text (experimental)</option>
    </select>
    <p id="document-format-help" className="muted small">{!formats ? capabilityError ? 'Retry to check available formats.' : 'Checking available formats…' : richEnabled ? 'Rich text is experimental. Plain text is recommended.' : 'Rich text is unavailable in this deployment.'}</p>
    {capabilityError && <><p role="alert">{capabilityError}</p><button type="button" onClick={() => setReload(current => current + 1)}>Retry available formats</button></>}
    {error && <p className="alert" role="alert">{error}</p>}
  </form>;
}
