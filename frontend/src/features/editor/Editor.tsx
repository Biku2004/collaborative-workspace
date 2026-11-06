import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import * as Y from 'yjs';
import { DocumentProvider, type CollaborationFormat, type EditorState, type Presence } from './provider';
import RichEditorBoundary from './rich/RichEditorBoundary';
import { epochHintKey, readEpochHint, saveEpochHint } from '../offline/epochHint';
const RichTextSurface = lazy(() => import('./rich/RichTextSurface'));
const RevisionsPanel = lazy(() => import('../history/RevisionsPanel'));
const LocalDraftPreview = lazy(() => import('../offline/LocalDraftPreview'));

type Props = { userId: string; workspaceId: string; documentId: string; title: string; format: CollaborationFormat; readOnly: boolean; onDirty: (dirty: boolean) => void; onBack: () => void };
const initial: EditorState = { phase: 'connecting', text: '', pending: 0, message: 'Connecting…', hasLocalState: false, editable: false };

export default function Editor({ userId, workspaceId, documentId, title, format, readOnly, onDirty, onBack }: Props) {
  const [state, setState] = useState<EditorState>(initial);
  const [canRenderRich, setCanRenderRich] = useState(false);
  const [presence, setPresence] = useState<Presence[]>([]);
  const [showRevisions, setShowRevisions] = useState(false);
  const hintKey = epochHintKey(userId, workspaceId, documentId, format);
  const [hint, setHint] = useState(() => readEpochHint(hintKey));
  const [showDraft, setShowDraft] = useState(false), [switchError, setSwitchError] = useState('');
  const provider = useRef<DocumentProvider | null>(null);
  const plainEditor = useRef<HTMLTextAreaElement | null>(null);
  const dirty = state.pending > 0;
  const dirtyCallback = useRef(onDirty);
  dirtyCallback.current = onDirty;

  useEffect(() => {
    setState(initial); setCanRenderRich(false); setPresence([]); setShowRevisions(false); setShowDraft(false); setSwitchError('');
    const instance = new DocumentProvider(userId, workspaceId, documentId, next => {
      dirtyCallback.current(next.pending > 0);
      setState(next);
      if (next.phase === 'ready' || next.hasLocalState) setCanRenderRich(true);
    }, format, hint.epoch);
    provider.current = instance;
    const unsubscribe = instance.observePresence(setPresence);
    return () => { unsubscribe(); provider.current = null; instance.destroy(); dirtyCallback.current(false); };
  }, [userId, workspaceId, documentId, format, hint.epoch]);

  async function openRestored() {
    if (state.nextEpoch === undefined) return;
    try {
      await provider.current?.retainDraft();
      saveEpochHint(hintKey, state.nextEpoch, hint.epoch);
      setHint({ epoch: state.nextEpoch, previous: hint.epoch });
    } catch { setSwitchError('Could not retain the old draft safely. Copy it before leaving this editor.'); }
  }

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  useEffect(() => {
    const instance = provider.current, input = plainEditor.current;
    if (format !== 'PLAIN_TEXT_V1' || state.phase !== 'ready' || !instance || !input) return;
    const text = instance.sharedDocument.getText('content');
    instance.setPresence(Y.createRelativePositionFromTypeIndex(text, input.selectionStart), Y.createRelativePositionFromTypeIndex(text, input.selectionEnd));
  }, [format, state.phase]);

  return <section className="editor" aria-labelledby="editor-title">
    <button className="more" type="button" onClick={onBack}>← Back to documents</button>
    <div className="editor-heading"><div><p className="eyebrow">DOCUMENT / {format === 'RICH_TEXT_V1' ? 'RICH TEXT' : 'PLAIN TEXT'}</p><h2 id="editor-title">{title}</h2></div>
      <span className={state.phase === 'failed' ? 'editor-status error' : 'editor-status'} role="status">{state.message}</span></div>
    {state.phase === 'failed' && <p className="alert" role="alert">{state.message} Browser storage can be cleared or evicted.</p>}
    {state.nextEpoch !== undefined && <p><button type="button" onClick={() => void openRestored()}>Keep old draft and open restored version</button></p>}
    {switchError && <p role="alert">{switchError}</p>}
    {state.phase === 'offline' && <p className="alert" role="status">The service is unavailable or access could not be verified. Local changes remain on this device until the server accepts them. Browser storage can be cleared or evicted.</p>}
    {state.phase === 'waiting' && <p className="alert" role="status">A complete local copy is not available yet. The editor will retry the connection before enabling editing.</p>}
    {readOnly && <p className="muted small">You can view this document. An editor or owner can make changes.</p>}
    {presence.length > 0 && <div className="collaborators" aria-label="Collaborators"><span>Collaborators</span><ul>{presence.map(peer => {
      let location = '';
      if (format === 'PLAIN_TEXT_V1' && provider.current) {
        const doc = provider.current.sharedDocument;
        try {
          const anchor = Y.createAbsolutePositionFromRelativePosition(peer.anchor, doc), head = Y.createAbsolutePositionFromRelativePosition(peer.head, doc);
          if (anchor?.type === doc.getText('content') && head?.type === anchor.type)
            location = anchor.index === head.index ? `, cursor at ${head.index}` : `, selection ${anchor.index}–${head.index}`;
        } catch { /* A position may refer to a document update not received yet. */ }
      }
      return <li key={peer.presenceId}>{peer.email} ({peer.userId}){location}</li>;
    })}</ul></div>}
    {format === 'RICH_TEXT_V1' ? (canRenderRich && provider.current && <RichEditorBoundary key={hint.epoch}><Suspense fallback={<p role="status">Loading editor…</p>}><RichTextSurface document={provider.current.sharedDocument} provider={provider.current}
      readOnly={readOnly || !state.editable} /></Suspense></RichEditorBoundary>) : <>
      <label htmlFor="editor-content" className="editor-label">Document text</label>
      <textarea ref={plainEditor} id="editor-content" value={state.text} readOnly={readOnly || !state.editable}
        onChange={event => provider.current?.setText(event.target.value)} onSelect={event => {
          const instance = provider.current; if (!instance) return;
          const text = instance.sharedDocument.getText('content');
          instance.setPresence(Y.createRelativePositionFromTypeIndex(text, event.currentTarget.selectionStart), Y.createRelativePositionFromTypeIndex(text, event.currentTarget.selectionEnd));
        }} aria-describedby="editor-help" /></>}
    <p id="editor-help" className="muted small">Local changes are not saved on the server until confirmed. Browser storage is not private from other users of this browser profile.</p>
    <button type="button" aria-expanded={showRevisions} onClick={() => setShowRevisions(value => !value)}>Named revisions</button>
    {showRevisions && <Suspense fallback={<p role="status">Loading revisions…</p>}><RevisionsPanel key={`${documentId}:${hint.epoch}`} workspaceId={workspaceId} documentId={documentId} readOnly={readOnly} online={state.phase === 'ready'} onRestored={epoch => provider.current?.noticeRestored(epoch)} /></Suspense>}
    {hint.previous !== undefined && <><button type="button" aria-expanded={showDraft} onClick={() => setShowDraft(value => !value)}>View previous local draft</button>
      {showDraft && <Suspense fallback={<p role="status">Loading retained draft…</p>}><LocalDraftPreview userId={userId} workspaceId={workspaceId} documentId={documentId} format={format} epoch={hint.previous} /></Suspense>}</>}
  </section>;
}
