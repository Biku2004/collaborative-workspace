import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, type Workspace } from '../../lib/api';
import { createWorkspace } from './api';

export default function CreateWorkspaceForm({ onCreated, onUnauthorized }: { onCreated: (workspace: Workspace) => void; onUnauthorized: () => void }) {
  const [name, setName] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const attempt = useRef<{ name: string; key: string } | null>(null), mounted = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const clean = name.trim();
    if (busy) return;
    if (!clean) { setError('Enter a workspace name.'); return; }
    if (!attempt.current || attempt.current.name !== clean) attempt.current = { name: clean, key: crypto.randomUUID() };
    setBusy(true); setError('');
    try {
      const workspace = await createWorkspace(clean, attempt.current.key);
      if (mounted.current) { attempt.current = null; setName(''); onCreated(workspace); }
    } catch (cause) {
      if (!mounted.current) return;
      if (cause instanceof ApiError && cause.status === 401) onUnauthorized();
      else setError(cause instanceof Error ? cause.message : 'Could not create the workspace.');
    } finally { if (mounted.current) setBusy(false); }
  }
  return <form className="workspace-create management-form" onSubmit={submit}>
    <label htmlFor="new-workspace-name">New workspace</label>
    <input id="new-workspace-name" value={name} onChange={event => setName(event.target.value)} maxLength={120} required disabled={busy} />
    <button type="submit" disabled={busy}>{busy ? 'Creating workspace…' : 'Create workspace'}</button>
    {error && <p role="alert">{error}</p>}
  </form>;
}
