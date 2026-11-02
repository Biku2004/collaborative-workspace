import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, currentUser, documentPage, login, logout, workspacePage, type Document, type Workspace } from './api';
import Editor from './Editor';

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.';
}

export default function App() {
  const [user, setUser] = useState<string | null | undefined>(undefined);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [loginError, setLoginError] = useState('');
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [workspaceCursor, setWorkspaceCursor] = useState<string | null>(null);
  const [workspaceError, setWorkspaceError] = useState('');
  const [workspaceLoading, setWorkspaceLoading] = useState(false);
  const [selected, setSelected] = useState<Workspace | null>(null);
  const [openDocument, setOpenDocument] = useState<Document | null>(null);
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [documentCursor, setDocumentCursor] = useState<string | null>(null);
  const [documentError, setDocumentError] = useState('');
  const [documentLoading, setDocumentLoading] = useState(false);
  const workspaceGeneration = useRef(0);
  const documentGeneration = useRef(0);

  function endSession(message = '') {
    workspaceGeneration.current++; documentGeneration.current++;
    setUser(null); setSelected(null); setOpenDocument(null); setWorkspaces([]); setDocuments([]);
    setWorkspaceCursor(null); setDocumentCursor(null); setLoginError(message);
    setWorkspaceLoading(false); setDocumentLoading(false);
  }
  function handleFailure(error: unknown, display: (message: string) => void) {
    if (error instanceof ApiError && error.status === 401 && !dirtyRef.current) endSession(error.message);
    else display(errorMessage(error));
  }

  useEffect(() => {
    let active = true;
    currentUser().then(name => { if (active) setUser(name); }).catch(error => {
      if (!active) return;
      setUser(null);
      if (!(error instanceof ApiError && error.status === 401)) setLoginError(errorMessage(error));
    });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!user) return;
    const generation = ++workspaceGeneration.current;
    setWorkspaceLoading(true); setWorkspaceError(''); setWorkspaces([]); setWorkspaceCursor(null);
    workspacePage().then(page => {
      if (generation !== workspaceGeneration.current) return;
      setWorkspaces(page.items); setWorkspaceCursor(page.nextCursor);
    }).catch(error => {
      if (generation === workspaceGeneration.current) handleFailure(error, setWorkspaceError);
    }).finally(() => { if (generation === workspaceGeneration.current) setWorkspaceLoading(false); });
    return () => { workspaceGeneration.current++; };
  }, [user]);

  useEffect(() => {
    const generation = ++documentGeneration.current;
    setDocuments([]); setDocumentCursor(null); setDocumentError('');
    if (!user || !selected) { setDocumentLoading(false); return; }
    setDocumentLoading(true);
    documentPage(selected.id).then(page => {
      if (generation !== documentGeneration.current) return;
      setDocuments(page.items); setDocumentCursor(page.nextCursor);
    }).catch(error => {
      if (generation === documentGeneration.current) handleFailure(error, setDocumentError);
    }).finally(() => { if (generation === documentGeneration.current) setDocumentLoading(false); });
    return () => { documentGeneration.current++; };
  }, [user, selected]);

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setLoginError('');
    try { const name = await login(email.trim(), password); setPassword(''); setUser(name); }
    catch (error) { setPassword(''); setLoginError(errorMessage(error)); }
    finally { setBusy(false); }
  }
  async function signOut() {
    if (dirtyRef.current && !window.confirm('You have unsaved changes. Leave and discard the text in this tab?')) return;
    setBusy(true); setWorkspaceError('');
    try { await logout(); endSession(); }
    catch (error) { handleFailure(error, setWorkspaceError); }
    finally { setBusy(false); }
  }
  function setEditorDirty(value: boolean) { dirtyRef.current = value; setDirty(value); }
  function mayLeaveEditor(): boolean { return !dirtyRef.current || window.confirm('You have unsaved changes. Leave and discard the text in this tab?'); }
  function selectWorkspace(workspace: Workspace) {
    if (openDocument && !mayLeaveEditor()) return;
    setOpenDocument(null); setEditorDirty(false);
    if (selected?.id !== workspace.id) { documentGeneration.current++; setSelected(workspace); }
  }
  function closeDocument() { if (mayLeaveEditor()) { setOpenDocument(null); setEditorDirty(false); } }
  async function moreWorkspaces() {
    if (!workspaceCursor || workspaceLoading) return;
    const generation = workspaceGeneration.current;
    setWorkspaceLoading(true); setWorkspaceError('');
    try {
      const page = await workspacePage(workspaceCursor);
      if (generation === workspaceGeneration.current) { setWorkspaces(old => [...old, ...page.items]); setWorkspaceCursor(page.nextCursor); }
    } catch (error) { if (generation === workspaceGeneration.current) handleFailure(error, setWorkspaceError); }
    finally { if (generation === workspaceGeneration.current) setWorkspaceLoading(false); }
  }
  async function moreDocuments() {
    if (!selected || !documentCursor || documentLoading) return;
    const generation = documentGeneration.current;
    setDocumentLoading(true); setDocumentError('');
    try {
      const page = await documentPage(selected.id, documentCursor);
      if (generation === documentGeneration.current) { setDocuments(old => [...old, ...page.items]); setDocumentCursor(page.nextCursor); }
    } catch (error) { if (generation === documentGeneration.current) handleFailure(error, setDocumentError); }
    finally { if (generation === documentGeneration.current) setDocumentLoading(false); }
  }

  if (user === undefined) return <main className="center"><p role="status">Checking your session…</p></main>;
  if (!user) return <main className="login-shell">
    <section className="login-intro" aria-labelledby="login-heading">
      <div className="brand"><span className="brand-mark">W</span> WORKSPACE <span className="brand-note">/ LOCAL EDITION</span></div>
      <div className="login-copy"><p className="eyebrow">A space for shared work</p><h1 id="login-heading">Good work<br /><em>starts here.</em></h1>
        <p>Sign in to see the documents your team has shared with you.</p></div>
      <p className="intro-footer">A COLLABORATIVE WORKSPACE <span>01 / 01</span></p>
    </section>
    <section className="login-panel" aria-labelledby="form-heading"><form className="login-form" onSubmit={signIn}>
      <p className="eyebrow">WELCOME BACK</p><h2 id="form-heading">Sign in</h2><p className="muted">Use your existing workspace account.</p>
      {loginError && <p className="alert" role="alert">{loginError}</p>}
      <label htmlFor="email">Email</label><input id="email" name="email" type="email" autoComplete="username" value={email} onChange={event => setEmail(event.target.value)} required />
      <label htmlFor="password">Password</label><input id="password" name="password" type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required />
      <button className="primary" disabled={busy} type="submit">{busy ? 'Signing in…' : 'Continue'} <span aria-hidden="true">↗</span></button>
      <p className="form-note">Accounts are provisioned through the local development setup.</p>
    </form></section>
  </main>;

  return <div className="app-shell">
    <header className="topbar"><div className="brand"><span className="brand-mark">W</span> WORKSPACE</div>
      <div className="account"><span className="account-name">{user}</span><button className="quiet" type="button" onClick={signOut} disabled={busy}>{busy ? 'Signing out…' : 'Sign out'} <span aria-hidden="true">↗</span></button></div></header>
    <main className="dashboard"><div className="page-heading"><div><p className="eyebrow">YOUR WORKSPACE / DOCUMENTS</p><h1>Make room for<br /><em>good ideas.</em></h1></div><p className="page-summary">Your teams and their shared documents, all in one place.</p></div>
      <div className="workspace-layout"><section className="sidebar" aria-labelledby="workspaces-heading"><div className="section-title"><h2 id="workspaces-heading">Workspaces</h2><span>01</span></div>
        <p className="muted small">Choose a workspace to see its documents.</p>
        {workspaceError && <p className="alert" role="alert">{workspaceError}</p>}
        {workspaceLoading && workspaces.length === 0 && <p className="muted" role="status">Loading workspaces…</p>}
        {!workspaceLoading && !workspaceError && workspaces.length === 0 && <p className="empty-small">No workspaces yet.</p>}
        <ul className="workspace-list">{workspaces.map(workspace => <li key={workspace.id}><button type="button" className={selected?.id === workspace.id ? 'workspace-button selected' : 'workspace-button'} aria-pressed={selected?.id === workspace.id} onClick={() => selectWorkspace(workspace)}><span className="workspace-icon" aria-hidden="true">{workspace.name.slice(0, 1).toUpperCase()}</span><span className="workspace-copy"><strong>{workspace.name}</strong><small>{workspace.role.toLowerCase()}</small></span><span aria-hidden="true">↗</span></button></li>)}</ul>
        {workspaceCursor && <button className="more" disabled={workspaceLoading} type="button" onClick={moreWorkspaces}>{workspaceLoading ? 'Loading…' : 'Load more workspaces'} <span aria-hidden="true">→</span></button>}
      </section>
      {openDocument && selected ? <Editor workspaceId={selected.id} documentId={openDocument.id} title={openDocument.title} readOnly={selected.role === 'VIEWER'} onDirty={setEditorDirty} onBack={closeDocument} /> : <section className="documents" aria-labelledby="documents-heading"><div className="section-title"><div><p className="eyebrow">{selected ? selected.name : 'SELECT A WORKSPACE'}</p><h2 id="documents-heading">Documents</h2></div><span>02</span></div>
        {!selected && <div className="empty-state"><span className="empty-symbol" aria-hidden="true">✳</span><h3>Nothing selected yet.</h3><p>Choose a workspace on the left to browse its documents.</p></div>}
        {selected && <>
          <div className="list-heading"><span>DOCUMENT NAME</span><span>CREATED</span></div>
          {documentError && <p className="alert" role="alert">{documentError}</p>}
          {documentLoading && documents.length === 0 && <p className="muted" role="status">Loading documents…</p>}
          {!documentLoading && !documentError && documents.length === 0 && <div className="empty-state compact"><span className="empty-symbol" aria-hidden="true">◇</span><h3>No documents yet.</h3><p>Documents shared in this workspace will appear here.</p></div>}
          <ul className="document-list">{documents.map(document => <li key={document.id}><button type="button" className="document-button" onClick={() => setOpenDocument(document)}><span className="document-icon" aria-hidden="true">▤</span><span className="document-name"><strong>{document.title}</strong><small>Open document</small></span><time dateTime={document.createdAt}>{new Date(document.createdAt).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })}</time></button></li>)}</ul>
          {documentCursor && <button className="more" disabled={documentLoading} type="button" onClick={moreDocuments}>{documentLoading ? 'Loading…' : 'Load more documents'} <span aria-hidden="true">→</span></button>}
        </>}
      </section>}{dirty && <span className="sr-only">Unsaved changes</span>}</div>
      <footer className="footer"><span>WORKSPACE / LOCAL EDITION</span><span>MADE FOR WORKING TOGETHER</span></footer>
    </main>
  </div>;
}
