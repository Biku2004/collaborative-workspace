# Lesson 25 — Persist offline drafts and pending updates

> [Lesson 26](26-reconnect-and-reconcile.md) subsequently adds automatic reconnect; manual reload descriptions below record the Lesson 25 behavior.

## 1. What this lesson adds

The plain-text editor stores a Yjs document snapshot and a separate queue of unacknowledged updates in IndexedDB, scoped to the signed-in stored-user ID, workspace, document and epoch. Each pending entry keeps the same UUID and exact update bytes across reloads. The editor writes a snapshot and its queue together in one IndexedDB transaction **before** it sends the update. A successful local write is not a server commit.

When a previously opened document has a local snapshot, a production-built app can be reopened without a network connection. Choose **Open last local document**, edit, and wait for **Saved locally; pending server confirmation** before closing or reloading. Reconnect and reload, sign in if needed, then reopen the document. The client checks the authenticated account, joins the authorized room, replays committed updates and retries pending IDs in order. This lesson uses manual reload for reconnection; an automatic reconnection state machine and fuller reconciliation are Lesson 26.

This is a bounded learning prototype, not a production offline system. IndexedDB and Cache Storage can be cleared, evicted, unavailable or lost with a device. Local data is not encrypted and can be inspected by another person or script with access to the browser profile. Offline access cannot establish whether the session or membership is still valid. Do not use this prototype for sensitive documents.

## 2. The four different milestones

1. **Visible in the editor:** Yjs updates the in-memory document immediately. A crash here can lose the edit before IndexedDB finishes.
2. **Saved locally:** the IndexedDB transaction completed for the snapshot and pending queue. The edit can usually be restored on this browser profile, but storage may still be evicted or cleared.
3. **Saved on the server:** `SAVED` arrives for the original update ID after the server commits the update. If this reply is lost, the client retains and retries the same ID and bytes.
4. **Seen by another collaborator:** relay and replay reach another client; this is separate from the server commit.

```text
edit -> in-memory Y.Doc -> IndexedDB transaction -> SUBMIT_UPDATE
                                         retry same ID -> committed server update
                                         <- SAVED -> persist queue without that entry
```

If the local transaction fails, the editor freezes and keeps the visible text in the tab for copying. It does not send the newly failed update or claim that it was locally saved. If confirmation is lost after a server commit, the entry remains in the queue; the server recognizes the repeated ID and returns the existing commit rather than appending the operation a second time.

## 3. Follow the code

- `frontend/src/localDocumentStore.ts` uses IndexedDB and the Web Locks API. A document is keyed by immutable user UUID, workspace UUID, document UUID and epoch. One tab holds the lock for that key while it is open, so two tabs cannot overwrite each other's snapshots. Saves are serialized and completed only when their IndexedDB transactions commit.
- `frontend/src/provider.ts` restores a Yjs snapshot and exact pending bytes. It waits for storage before sending. After an acknowledgement it persists the queue without the acknowledged entry; if the browser closes before that transaction, a retry of the same ID is safe. It starts server replay at sequence zero and applies Yjs updates idempotently; this is simple but becomes expensive for long histories.
- `backend/.../CurrentUserController.java` adds the stored account ID to `/api/me`. The temporary `learner` account has no stable stored ID. The socket's `WELCOME` includes the server-authenticated user ID, which the provider checks before joining and transmitting a pending draft. The backend still checks role and document permissions.
- `frontend/src/offlineContext.ts` keeps only enough local metadata to reopen the last document. This metadata is a convenience, not proof of authentication or access. A successful sign-out clears the active local shortcut but does not erase IndexedDB drafts.
- The production build generates `sw.js` to cache the app shell and its versioned static assets. It does not cache `/api`, login or logout responses. A service worker and IndexedDB are browser storage, not protection against another user of the same profile.

If access is removed while offline, the server will reject the subsequent connection or write. The browser can open the last local document for recovery, but the shortcut does not restore membership or grant server access. An expired session requires signing in again; updates are never intentionally sent as a different account.

## 4. Try it locally

Start the local PostgreSQL server, private Yjs engine and Spring backend as in the earlier lessons. Build the frontend, then use Vite's production preview on a separate port and configure the backend with that exact WebSocket origin **before** starting it:

```powershell
# In the frontend directory
npm ci
npm run build
node .\node_modules\vite\bin\vite.js preview --host 127.0.0.1 --port 4173 --strictPort
```

Set `WORKSPACE_WEBSOCKET_ALLOWED_ORIGIN` to `http://127.0.0.1:4173` in the backend's environment. Sign in as a stored user, open an existing authorized document, and wait until it synchronizes. Allow the service worker to install before testing an offline reload. Turn off the network in browser developer tools, edit, wait for local confirmation, and reload. Use the local-document button. Restore the network, reload and reopen the document; the pending edits should eventually show **Saved**.

The Vite development server does not install the production offline shell. The offline shortcut keeps only the last opened document's navigation metadata, although separate document drafts can exist in IndexedDB. The current UI does not provide a complete local-draft manager, encryption, storage export, automatic reconnect or conflict-resolution UI.

## 5. Verification and questions

The browser integration tests cover true network-offline reload of the production shell, a second offline edit, stable retry IDs, a simulated lost acknowledgement followed by exactly one update in server replay, account switching, locally failed writes and recovery after membership removal. Other tests cover cross-tab writer exclusion and real browser collaboration through the backend. These are targeted scenarios, not proof against every browser failure, malicious update or distributed race.

**Why keep a queue if Yjs already stores document state?** The server's commit protocol needs the original update bytes and stable identity to decide whether an ambiguous retry is a duplicate. A snapshot alone does not preserve that retry identity.

**Why not call the local change saved immediately?** The browser may crash before the transaction completes. Even after that transaction, the server may never receive or accept the update.

**What happens if permission is revoked offline?** The old local bytes can remain on the device, because a server cannot erase a disconnected browser's storage. The server rechecks permission when the user reconnects. The user may copy local work, but cannot rely on uploading it after revocation.

**What is the trust boundary for the last-local-document button?** It identifies locally cached data, not a user who has just authenticated. Anyone who controls the browser profile or runs script in the origin can inspect local storage. Only the server's current session and authorization can permit a server write.

**What is still missing?** Automatic reconnect, stronger recovery and reconciliation, complete rich-text compatibility, durable storage guarantees beyond browser promises, multi-instance delivery and production security validation are future work.
