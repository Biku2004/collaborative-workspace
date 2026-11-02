import { useEffect, useRef, useState } from 'react';
import { PlainTextProvider, type EditorState } from './provider';

type Props = { userId: string; workspaceId: string; documentId: string; title: string; readOnly: boolean; onDirty: (dirty: boolean) => void; onBack: () => void };
const initial: EditorState = { phase: 'connecting', text: '', pending: 0, message: 'Connecting…' };

export default function Editor({ userId, workspaceId, documentId, title, readOnly, onDirty, onBack }: Props) {
  const [state, setState] = useState<EditorState>(initial);
  const provider = useRef<PlainTextProvider | null>(null);
  const dirty = state.pending > 0;
  const dirtyCallback = useRef(onDirty);
  dirtyCallback.current = onDirty;

  useEffect(() => {
    setState(initial);
    const instance = new PlainTextProvider(userId, workspaceId, documentId, next => {
      dirtyCallback.current(next.pending > 0);
      setState(next);
    });
    provider.current = instance;
    return () => { provider.current = null; instance.destroy(); dirtyCallback.current(false); };
  }, [userId, workspaceId, documentId]);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  return <section className="editor" aria-labelledby="editor-title">
    <button className="more" type="button" onClick={onBack}>← Back to documents</button>
    <div className="editor-heading"><div><p className="eyebrow">DOCUMENT / PLAIN TEXT</p><h2 id="editor-title">{title}</h2></div>
      <span className={state.phase === 'failed' ? 'editor-status error' : 'editor-status'} role="status">{state.message}</span></div>
    {state.phase === 'failed' && <p className="alert" role="alert">The connection is unavailable or access was denied. Copy your draft if needed; browser storage can be cleared or evicted.</p>}
    {state.phase === 'offline' && <p className="alert" role="status">The service is unavailable or access could not be verified. Local changes remain on this device until the server accepts them. Browser storage can be cleared or evicted.</p>}
    {readOnly && <p className="muted small">You can view this document. An editor or owner can make changes.</p>}
    <label htmlFor="editor-content" className="editor-label">Document text</label>
    <textarea id="editor-content" value={state.text} readOnly={readOnly || (state.phase !== 'ready' && state.phase !== 'offline')}
      onChange={event => provider.current?.setText(event.target.value)} aria-describedby="editor-help" />
    <p id="editor-help" className="muted small">Local changes are not saved on the server until confirmed. Browser storage is not private from other users of this browser profile.</p>
  </section>;
}
