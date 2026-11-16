import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../../lib/api';
import { memberPage, removeMember, setMemberRole, workspaceDetails, type Member, type WorkspaceRole } from './api';

export default function MembersPanel({ workspaceId, userId, onSelfRole, onSelfRemoved, onDenied }: {
  workspaceId: string; userId: string; onSelfRole: (role: WorkspaceRole) => void; onSelfRemoved: () => void; onDenied: (error: ApiError) => void;
}) {
  const [members, setMembers] = useState<Member[]>([]), [cursor, setCursor] = useState<string | null>(null);
  const [email, setEmail] = useState(''), [role, setRole] = useState<WorkspaceRole>('EDITOR');
  const [draftRoles, setDraftRoles] = useState<Record<string, WorkspaceRole>>({});
  const [loading, setLoading] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [reload, setReload] = useState(0);
  const mounted = useRef(false), generation = useRef(0);
  const callbacks = useRef({ onSelfRole, onSelfRemoved, onDenied }); callbacks.current = { onSelfRole, onSelfRemoved, onDenied };
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);
  function failure(cause: unknown) {
    if (cause instanceof ApiError && [401,403,404].includes(cause.status)) { callbacks.current.onDenied(cause); return; }
    setError(cause instanceof Error ? cause.message : 'Member operation failed. Please refresh and retry.');
  }
  async function mutationFailure(cause: unknown, message: string) {
    if (!(cause instanceof ApiError) || cause.status !== 404) { failure(cause); return; }
    // A missing target account/member is different from losing workspace access.
    try {
      const workspace = await workspaceDetails(workspaceId);
      if (!mounted.current) return;
      if (workspace.role !== 'OWNER') callbacks.current.onSelfRole(workspace.role);
      else setError(message);
    } catch (error) { if (mounted.current) failure(error); }
  }
  useEffect(() => {
    const controller = new AbortController(), current = ++generation.current;
    setLoading(true); setMembers([]); setCursor(null); setDraftRoles({}); setError('');
    Promise.all([workspaceDetails(workspaceId, controller.signal), memberPage(workspaceId, undefined, controller.signal)]).then(([workspace, page]) => {
      if (controller.signal.aborted || generation.current !== current) return;
      if (workspace.role !== 'OWNER') { callbacks.current.onSelfRole(workspace.role); return; }
      setMembers(page.items); setCursor(page.nextCursor);
    }).catch(cause => { if (!controller.signal.aborted && generation.current === current) failure(cause); })
      .finally(() => { if (!controller.signal.aborted && generation.current === current) setLoading(false); });
    return () => controller.abort();
  }, [workspaceId, reload]);
  async function more() {
    if (!cursor || loading || busy) return;
    const current = generation.current; setLoading(true); setError('');
    try {
      const page = await memberPage(workspaceId, cursor);
      if (mounted.current && current === generation.current) { setMembers(old => [...old, ...page.items.filter(item => !old.some(existing => existing.userId === item.userId))]); setCursor(page.nextCursor); }
    } catch (cause) { if (mounted.current && current === generation.current) failure(cause); }
    finally { if (mounted.current && current === generation.current) setLoading(false); }
  }
  async function change(target: string, next: WorkspaceRole) {
    if (busy || loading) return; setBusy(true); setError(''); setNotice('');
    try {
      const member = await setMemberRole(workspaceId, target.trim().toLowerCase(), next);
      if (!mounted.current) return;
      setMembers(old => [member, ...old.filter(item => item.userId !== member.userId)]); setEmail('');
      setDraftRoles(old => ({ ...old, [member.userId]: member.role })); setNotice('Member role saved.');
      if (member.userId === userId) callbacks.current.onSelfRole(member.role);
    } catch (cause) { if (mounted.current) await mutationFailure(cause, 'The existing account could not be found. Check the email address.'); }
    finally { if (mounted.current) setBusy(false); }
  }
  async function remove(member: Member) {
    if (busy || loading || !window.confirm(`Remove ${member.email} from this workspace?`)) return;
    setBusy(true); setError(''); setNotice('');
    try {
      await removeMember(workspaceId, member.userId);
      if (!mounted.current) return;
      setMembers(old => old.filter(item => item.userId !== member.userId)); setNotice('Member removed.');
      if (member.userId === userId) callbacks.current.onSelfRemoved();
    } catch (cause) { if (mounted.current) await mutationFailure(cause, 'This member is no longer available. Refresh the member list.'); }
    finally { if (mounted.current) setBusy(false); }
  }
  function add(event: FormEvent<HTMLFormElement>) { event.preventDefault(); if (email.trim()) void change(email, role); }
  return <section className="members-panel" aria-label="Workspace members">
    <h3>Members</h3><p className="muted small">Add an existing account by email. A workspace must keep at least one owner.</p>
    <button type="button" onClick={() => setReload(value => value + 1)} disabled={busy || loading}>Refresh members</button>
    <form className="management-form" onSubmit={add}>
      <label htmlFor="member-email">Existing account email</label><input id="member-email" type="email" maxLength={254} required value={email} onChange={event => setEmail(event.target.value)} disabled={busy || loading} />
      <label htmlFor="new-member-role">New member role</label><select id="new-member-role" value={role} onChange={event => setRole(event.target.value as WorkspaceRole)} disabled={busy || loading}>
        <option value="EDITOR">Editor</option><option value="VIEWER">Viewer</option><option value="OWNER">Owner</option>
      </select><button type="submit" disabled={busy || loading}>Add or update member</button>
    </form>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{loading && <p role="status">Loading members…</p>}
    <ul className="member-list">{members.map(member => <li key={member.userId} aria-label={`Member ${member.email}`}>
      <span>{member.email}{member.userId === userId ? ' (you)' : ''}</span>
      <select aria-label={`Role for ${member.email}`} value={draftRoles[member.userId] ?? member.role} onChange={event => setDraftRoles(old => ({ ...old, [member.userId]: event.target.value as WorkspaceRole }))} disabled={busy || loading}>
        <option value="OWNER">Owner</option><option value="EDITOR">Editor</option><option value="VIEWER">Viewer</option>
      </select>
      <button type="button" onClick={() => void change(member.email, draftRoles[member.userId] ?? member.role)} disabled={busy || loading || (draftRoles[member.userId] ?? member.role) === member.role} aria-label={`Save role for ${member.email}`}>Save role</button>
      <button type="button" onClick={() => void remove(member)} disabled={busy || loading} aria-label={`Remove ${member.email}`}>Remove</button>
    </li>)}</ul>
    {cursor && <button type="button" disabled={loading || busy} onClick={() => void more()}>Load more members</button>}
  </section>;
}
