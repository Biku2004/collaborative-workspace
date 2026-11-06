import { lazy, Suspense, useEffect, useState } from 'react';
import * as Y from 'yjs';
import { LocalDocumentStore } from './localDocumentStore';
import type { CollaborationFormat } from '../editor/provider';
import RichEditorBoundary from '../editor/rich/RichEditorBoundary';
const RichTextSurface = lazy(() => import('../editor/rich/RichTextSurface'));

/** Deliberately disconnected from every provider. Recovery requires a deliberate copy and new edit. */
export default function LocalDraftPreview({ userId, workspaceId, documentId, format, epoch }: {
  userId: string; workspaceId: string; documentId: string; format: CollaborationFormat; epoch: number;
}) {
  const [doc, setDoc] = useState<Y.Doc>(), [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    const candidate = new Y.Doc();
    // StrictMode immediately cleans up its first effect. Avoid taking its storage lock.
    void Promise.resolve().then(async () => {
      if (!active) return;
      const store = await LocalDocumentStore.open(userId, workspaceId, documentId, epoch, format);
      try {
        const value = await store.load();
        if (!value) throw new Error('No retained draft');
        if (active) { Y.applyUpdate(candidate, value.state); setDoc(candidate); }
      } finally { await store.close(); }
    }).catch(() => { if (active) setError('The retained draft is unavailable or open in another tab.'); });
    return () => { active = false; candidate.destroy(); };
  }, [userId, workspaceId, documentId, format, epoch]);
  return <section aria-label="Previous local draft">
    <h3>Previous local draft (epoch {epoch})</h3>
    <p className="muted small">Select and copy the text you want to recover, then paste it into the current document as a new edit. This draft is never uploaded automatically.</p>
    {error ? <p role="alert">{error}</p> : !doc ? <p role="status">Loading retained draft…</p> : format === 'PLAIN_TEXT_V1' ? <pre>{doc.getText('content').toString()}</pre> :
      <RichEditorBoundary><Suspense fallback={<p role="status">Loading retained draft…</p>}><RichTextSurface document={doc} readOnly /></Suspense></RichEditorBoundary>}
  </section>;
}
